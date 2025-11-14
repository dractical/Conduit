package com.dractical.conduit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Conduit EventBus
 */
public final class EventBus {

    /**
     * Receives handler exceptions; default logs to stderr.
     */
    @FunctionalInterface
    public interface ErrorHandler {
        void onHandlerException(Event event, Object listener, Method method, Throwable error);
    }

    private static final class Handler {
        final WeakReference<Object> listenerRef;
        final Method method;
        final MethodHandle handle;
        final Class<?> paramType;
        final Priority priority;
        final boolean ignoreCancelled;
        final boolean receiveSubtypes;
        final boolean once;
        final long seq;

        Handler(
                Object listener,
                ReferenceQueue<Object> queue,
                Method method,
                MethodHandle handle,
                Class<?> paramType,
                Subscribe meta,
                long seq
        ) {
            this.listenerRef = new WeakReference<>(listener, queue);
            this.method = method;
            this.handle = handle;
            this.paramType = paramType;
            this.priority = meta.priority();
            this.ignoreCancelled = meta.ignoreCancelled();
            this.receiveSubtypes = meta.receiveSubtypes();
            this.once = meta.once();
            this.seq = seq;
        }

        Object listener() {
            return listenerRef.get();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Handler other)) return false;
            return listener() == other.listener() && method.equals(other.method);
        }

        @Override
        public int hashCode() {
            Object l = listener();
            return System.identityHashCode(l) * 31 + method.hashCode();
        }
    }

    private record HandlerError(Object listener, Method method, Throwable error) {
    }

    private static final Comparator<Handler> HANDLER_ORDER =
            Comparator.comparingInt((Handler h) -> h.priority.weight())
                    .thenComparingLong(h -> h.seq);

    private final ConcurrentMap<Class<?>, CopyOnWriteArraySet<Handler>> handlersByType = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<Handler>> handlerLookup = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<Handler>> dispatchCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, Set<Handler>> handlersByListener = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<>();

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
        drainQueue();

        int found = 0;
        Set<Class<?>> addedTypes = new HashSet<>();

        Set<Class<?>> visited = new HashSet<>();
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        queue.add(listener.getClass());

        while (!queue.isEmpty()) {
            Class<?> cls = queue.removeFirst();
            if (cls == Object.class || !visited.add(cls)) {
                continue;
            }

            for (Method m : cls.getDeclaredMethods()) {
                Subscribe ann = m.getAnnotation(Subscribe.class);
                if (ann == null) continue;

                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1 || !Event.class.isAssignableFrom(params[0])) {
                    throw new IllegalArgumentException("@Subscribe method must take exactly one parameter of type Event: " + cls.getName() + "#" + m.getName());
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    throw new IllegalArgumentException("@Subscribe method must be an instance method: " + cls.getName() + "#" + m.getName());
                }

                m.setAccessible(true);
                Class<?> paramType = params[0];

                MethodHandle handle;
                try {
                    handle = MethodHandles.privateLookupIn(cls, MethodHandles.lookup()).unreflect(m);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("Failed to access @Subscribe method: " + cls.getName() + "#" + m.getName(), e);
                }

                Handler h = new Handler(
                        listener,
                        refQueue,
                        m,
                        handle,
                        paramType,
                        ann,
                        sequence.getAndIncrement()
                );

                CopyOnWriteArraySet<Handler> set = handlersByType.computeIfAbsent(paramType, k -> new CopyOnWriteArraySet<>());
                if (set.add(h)) {
                    addedTypes.add(paramType);
                }
                handlersByListener.computeIfAbsent(listener, k -> ConcurrentHashMap.newKeySet()).add(h);
                found++;
            }

            Class<?> superCls = cls.getSuperclass();
            if (superCls != null) queue.add(superCls);
            Collections.addAll(queue, cls.getInterfaces());
        }

        if (found == 0) {
            throw new IllegalArgumentException("No @Subscribe methods found on " + listener.getClass().getName());
        }

        for (Class<?> type : addedTypes) {
            rebuildHandlerLookup(type);
        }
    }

    /**
     * Unregisters all handlers belonging to the given listener.
     */
    public void unregister(Object listener) {
        Objects.requireNonNull(listener, "listener");
        drainQueue();
        Set<Handler> owned = handlersByListener.remove(listener);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        Set<Class<?>> changedTypes = new HashSet<>();
        for (Handler h : owned) {
            if (removeHandlerFromTypeMap(h)) {
                changedTypes.add(h.paramType);
            }
        }
        for (Class<?> type : changedTypes) {
            rebuildHandlerLookup(type);
        }
    }

    /**
     * Posts the event synchronously on the calling thread.
     */
    public <E extends Event> E post(E event) {
        Objects.requireNonNull(event, "event");
        drainQueue();

        List<Handler> toCall = resolveHandlers(event.getClass());
        if (toCall.isEmpty()) {
            handleDeadEventIfNeeded(event);
            return event;
        }
        Consumer<HandlerError> errorSink = err -> errorHandler.onHandlerException(event, err.listener(), err.method(), err.error());
        Set<Class<?>> mutatedTypes = dispatchToHandlers(event, toCall, errorSink);
        mutatedTypes.forEach(this::rebuildHandlerLookup);
        return event;
    }

    /**
     * Posts the event asynchronously using the bus' Executor.
     */
    public <E extends Event> CompletableFuture<E> postAsync(E event) {
        Objects.requireNonNull(event, "event");
        drainQueue();
        if (executor == null) {
            throw new IllegalStateException("No Executor configured. Use new EventBus(Executor) or post(...) instead.");
        }

        List<Handler> toCall = resolveHandlers(event.getClass());
        if (toCall.isEmpty()) {
            if (!(event instanceof DeadEvent)) {
                List<Handler> deadHandlers = resolveHandlers(DeadEvent.class);
                if (!deadHandlers.isEmpty()) {
                    return this.postAsync(new DeadEvent(this, event)).thenApply(de -> event);
                }
            }
            return CompletableFuture.completedFuture(event);
        }

        return CompletableFuture.supplyAsync(() -> {
            List<HandlerError> errors = new ArrayList<>(4);

            Consumer<HandlerError> errorSink = errors::add;
            Set<Class<?>> mutatedTypes = dispatchToHandlers(event, toCall, errorSink);

            mutatedTypes.forEach(this::rebuildHandlerLookup);

            for (HandlerError err : errors) {
                errorHandler.onHandlerException(event, err.listener(), err.method(), err.error());
            }

            return event;
        }, executor);
    }

    private List<Handler> resolveHandlers(Class<?> eventClass) {
        return dispatchCache.computeIfAbsent(eventClass, this::computeHandlersFor);
    }

    private List<Handler> computeHandlersFor(Class<?> eventClass) {
        if (handlerLookup.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<Handler> flat = new ArrayList<>();
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();

        queue.add(eventClass);

        while (!queue.isEmpty()) {
            Class<?> type = queue.removeFirst();
            if (!visited.add(type)) continue;

            List<Handler> list = handlerLookup.get(type);
            if (list != null) {
                if (type == eventClass) {
                    flat.addAll(list);
                } else {
                    for (Handler h : list) {
                        if (h.receiveSubtypes) {
                            flat.add(h);
                        }
                    }
                }
            }

            Class<?> superType = type.getSuperclass();
            if (superType != null) queue.add(superType);
            Collections.addAll(queue, type.getInterfaces());
        }

        if (flat.isEmpty()) {
            return Collections.emptyList();
        }

        flat.sort(HANDLER_ORDER);
        return Collections.unmodifiableList(flat);
    }

    private <E extends Event> Set<Class<?>> dispatchToHandlers(
            E event,
            List<Handler> toCall,
            Consumer<HandlerError> errorSink
    ) {
        boolean isCancellable = event instanceof Cancellable;
        Cancellable cancellable = isCancellable ? (Cancellable) event : null;

        Set<Class<?>> mutatedTypes = new HashSet<>();

        for (Handler h : toCall) {
            Object target = h.listener();
            if (target == null) {
                if (removeHandlerFromTypeMap(h)) {
                    mutatedTypes.add(h.paramType);
                }
                removeHandlerFromListenerMap(h, null);
                continue;
            }

            if (isCancellable && cancellable.isCancelled() && h.ignoreCancelled) {
                continue;
            }

            try {
                h.handle.invoke(target, event);
            } catch (Throwable t) {
                errorSink.accept(new HandlerError(target, h.method, t));
            }

            if (h.once) {
                if (removeHandlerFromTypeMap(h)) {
                    mutatedTypes.add(h.paramType);
                }
                removeHandlerFromListenerMap(h, target);
            }
        }

        return mutatedTypes;
    }

    private void handleDeadEventIfNeeded(Event event) {
        if (event instanceof DeadEvent) {
            return;
        }
        List<Handler> deadHandlers = resolveHandlers(DeadEvent.class);
        if (!deadHandlers.isEmpty()) {
            post(new DeadEvent(this, event));
        }
    }

    private void rebuildHandlerLookup(Class<?> type) {
        CopyOnWriteArraySet<Handler> set = handlersByType.get(type);
        if (set == null || set.isEmpty()) {
            handlersByType.remove(type, set);
            handlerLookup.remove(type);
        } else {
            ArrayList<Handler> list = new ArrayList<>(set);
            list.sort(HANDLER_ORDER);
            handlerLookup.put(type, Collections.unmodifiableList(list));
        }
        dispatchCache.keySet().removeIf(type::isAssignableFrom);
    }

    private boolean removeHandlerFromTypeMap(Handler h) {
        CopyOnWriteArraySet<Handler> set = handlersByType.get(h.paramType);
        if (set == null) return false;

        boolean removed = set.remove(h);
        if (!removed) return false;

        if (set.isEmpty()) {
            handlersByType.remove(h.paramType, set);
        }
        return true;
    }

    private void removeHandlerFromListenerMap(Handler h, Object listener) {
        if (listener != null) {
            Set<Handler> set = handlersByListener.get(listener);
            if (set != null) {
                set.remove(h);
                if (set.isEmpty()) {
                    handlersByListener.remove(listener, set);
                }
            }
        } else {
            for (Map.Entry<Object, Set<Handler>> e : handlersByListener.entrySet()) {
                Set<Handler> set = e.getValue();
                if (set.remove(h)) {
                    if (set.isEmpty()) {
                        handlersByListener.remove(e.getKey(), set);
                    }
                    break;
                }
            }
        }
    }

    private void drainQueue() {
        Reference<?> ref;
        Set<Class<?>> changedTypes = new HashSet<>();
        while ((ref = refQueue.poll()) != null) {
            Reference<?> deadRef = ref;
            for (Map.Entry<Class<?>, CopyOnWriteArraySet<Handler>> e : handlersByType.entrySet()) {
                CopyOnWriteArraySet<Handler> set = e.getValue();
                if (set.removeIf(h -> {
                    if (h.listenerRef == deadRef) {
                        removeHandlerFromListenerMap(h, null);
                        return true;
                    }
                    return false;
                })) {
                    changedTypes.add(e.getKey());
                    if (set.isEmpty()) {
                        handlersByType.remove(e.getKey(), set);
                    }
                }
            }
        }
        for (Class<?> type : changedTypes) {
            rebuildHandlerLookup(type);
        }
    }
}
