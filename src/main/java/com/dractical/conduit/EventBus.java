package com.dractical.conduit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conduit EventBus
 */
public final class EventBus {

    /**
     * Receives handler exceptions; default logs to stderr.
     */
    public interface ErrorHandler {
        void onHandlerException(Event event, Object listener, Method method, Throwable error);
    }

    private static final class Handler {
        final WeakReference<Object> listener;
        final Method method;
        final MethodHandle handle;
        final Class<?> paramType;
        final Priority priority;
        final boolean ignoreCancelled;
        final boolean receiveSubtypes;
        final boolean once;
        final long seq;

        Handler(Object listener, Method method, MethodHandle handle, Class<?> paramType, Subscribe meta, long seq) {
            this.listener = new WeakReference<>(listener);
            this.method = method;
            this.handle = handle;
            this.paramType = paramType;
            this.priority = meta.priority();
            this.ignoreCancelled = meta.ignoreCancelled();
            this.receiveSubtypes = meta.receiveSubtypes();
            this.once = meta.once();
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Handler other)) return false;
            return listener.get() == other.listener.get() && method.equals(other.method);
        }

        @Override
        public int hashCode() {
            Object l = listener.get();
            return System.identityHashCode(l) * 31 + method.hashCode();
        }
    }

    private final ConcurrentMap<Class<?>, CopyOnWriteArraySet<Handler>> handlersByType = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<Handler>> dispatchCache = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    private final Executor executor;
    private final ErrorHandler errorHandler;

    public EventBus() {
        this(null, defaultErrorHandler());
    }

    public EventBus(Executor executor) {
        this(executor, defaultErrorHandler());
    }

    public EventBus(Executor executor, ErrorHandler errorHandler) {
        this.executor = executor;
        this.errorHandler = (errorHandler != null) ? errorHandler : defaultErrorHandler();
    }

    private static ErrorHandler defaultErrorHandler() {
        return (event, listener, method, error) -> {
            System.err.println("[Conduit] Handler exception in " + listener.getClass().getName()
                    + "#" + method.getName() + " for " + event.getClass().getName());
            error.printStackTrace(System.err);
        };
    }

    /**
     * Registers all @Subscribe methods on the given listener.
     */
    public void register(Object listener) {
        Objects.requireNonNull(listener, "listener");
        int found = 0;
        boolean added = false;
        for (Class<?> cls = listener.getClass(); cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                Subscribe ann = m.getAnnotation(Subscribe.class);
                if (ann == null) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
                    throw new IllegalArgumentException("@Subscribe method must take exactly one parameter of type Event: "
                            + cls.getName() + "#" + m.getName());
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    throw new IllegalArgumentException("@Subscribe method must be an instance method: "
                            + cls.getName() + "#" + m.getName());
                }
                m.setAccessible(true);
                Class<?> paramType = params[0];
                MethodHandle handle;
                try {
                    handle = MethodHandles.privateLookupIn(cls, MethodHandles.lookup())
                            .unreflect(m);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("Failed to access @Subscribe method: "
                            + cls.getName() + "#" + m.getName(), e);
                }
                Handler h = new Handler(listener, m, handle, paramType, ann, sequence.getAndIncrement());
                CopyOnWriteArraySet<Handler> set = handlersByType.computeIfAbsent(paramType, k -> new CopyOnWriteArraySet<>());
                if (set.add(h)) {
                    added = true;
                }
                found++;
            }
        }
        if (found == 0) {
            throw new IllegalArgumentException("No @Subscribe methods found on " + listener.getClass().getName());
        }
        if (added) invalidateCache();
    }

    /**
     * Unregisters all handlers belonging to the given listener.
     */
    public void unregister(Object listener) {
        Objects.requireNonNull(listener, "listener");
        boolean changed = false;
        for (Map.Entry<Class<?>, CopyOnWriteArraySet<Handler>> e : handlersByType.entrySet()) {
            CopyOnWriteArraySet<Handler> set = e.getValue();
            if (set.removeIf(h -> h.listener.get() == listener)) {
                changed = true;
                if (set.isEmpty()) {
                    handlersByType.remove(e.getKey(), set);
                }
            }
        }
        if (changed) invalidateCache();
    }

    /**
     * Posts the event synchronously on the calling thread.
     */
    public <E extends Event> E post(E event) {
        Objects.requireNonNull(event, "event");
        List<Handler> toCall = resolveHandlers(event.getClass());
        if (toCall.isEmpty()) {
            if (!(event instanceof DeadEvent)) {
                List<Handler> deadHandlers = resolveHandlers(DeadEvent.class);
                if (!deadHandlers.isEmpty()) {
                    post(new DeadEvent(this, event));
                }
            }
            return event;
        }

        boolean mutated = false;
        for (Handler h : toCall) {
            Object target = h.listener.get();
            if (target == null) {
                CopyOnWriteArraySet<Handler> set = handlersByType.get(h.paramType);
                if (set != null) {
                    if (set.remove(h) && set.isEmpty()) {
                        handlersByType.remove(h.paramType, set);
                    }
                }
                mutated = true;
                continue;
            }
            if (event instanceof Cancellable) {
                if (((Cancellable) event).isCancelled() && h.ignoreCancelled) {
                    continue;
                }
            }
            try {
                h.handle.invoke(target, event);
            } catch (Throwable t) {
                errorHandler.onHandlerException(event, target, h.method, t);
            }
            if (h.once) {
                CopyOnWriteArraySet<Handler> set = handlersByType.get(h.paramType);
                if (set != null) {
                    if (set.remove(h)) {
                        if (set.isEmpty()) handlersByType.remove(h.paramType, set);
                        mutated = true;
                    }
                }
            }
        }
        if (mutated) invalidateCache();
        return event;
    }

    /**
     * Posts the event asynchronously using the bus' Executor.
     */
    public <E extends Event> CompletableFuture<E> postAsync(E event) {
        Objects.requireNonNull(event, "event");
        if (executor == null) {
            throw new IllegalStateException("No Executor configured. Use new EventBus(Executor) or post(...) instead.");
        }

        List<Handler> toCall = resolveHandlers(event.getClass());
        if (toCall.isEmpty()) {
            if (!(event instanceof DeadEvent)) {
                List<Handler> deadHandlers = resolveHandlers(DeadEvent.class);
                if (!deadHandlers.isEmpty()) {
                    return this.postAsync(new DeadEvent(this, event))
                            .thenApply(de -> event);
                }
            }
            return CompletableFuture.completedFuture(event);
        }

        ArrayList<CompletableFuture<?>> futures = new ArrayList<>(toCall.size());
        AtomicBoolean mutated = new AtomicBoolean(false);

        for (Handler h : toCall) {
            CompletableFuture<?> cf = CompletableFuture.runAsync(() -> {
                Object target = h.listener.get();
                if (target == null) {
                    CopyOnWriteArraySet<Handler> set = handlersByType.get(h.paramType);
                    if (set != null && set.remove(h) && set.isEmpty()) {
                        handlersByType.remove(h.paramType, set);
                    }
                    mutated.set(true);
                    return;
                }
                if (event instanceof Cancellable) {
                    if (((Cancellable) event).isCancelled() && h.ignoreCancelled) {
                        return;
                    }
                }
                try {
                    h.handle.invoke(target, event);
                } catch (Throwable t) {
                    errorHandler.onHandlerException(event, target, h.method, t);
                }
                if (h.once) {
                    CopyOnWriteArraySet<Handler> set = handlersByType.get(h.paramType);
                    if (set != null && set.remove(h) && set.isEmpty()) {
                        handlersByType.remove(h.paramType, set);
                    }
                    mutated.set(true);
                }
            }, executor);
            futures.add(cf);
        }

        return CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    if (mutated.get()) invalidateCache();
                    return event;
                });
    }

    private void invalidateCache() {
        dispatchCache.clear();
    }

    private List<Handler> resolveHandlers(Class<?> eventClass) {
        return dispatchCache.computeIfAbsent(eventClass, this::computeHandlersFor);
    }

    private List<Handler> computeHandlersFor(Class<?> eventClass) {
        if (handlersByType.isEmpty()) return Collections.emptyList();
        ArrayList<Handler> flat = new ArrayList<>();
        for (Map.Entry<Class<?>, CopyOnWriteArraySet<Handler>> e : handlersByType.entrySet()) {
            Class<?> keyType = e.getKey();
            if (!keyType.isAssignableFrom(eventClass)) continue;
            for (Handler h : e.getValue()) {
                if (!h.receiveSubtypes && keyType != eventClass) continue;
                flat.add(h);
            }
        }
        if (flat.isEmpty()) return Collections.emptyList();
        flat.sort(Comparator
                .comparingInt((Handler h) -> h.priority.weight())
                .thenComparingLong(h -> h.seq));
        return Collections.unmodifiableList(flat);
    }
}
