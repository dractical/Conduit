package test;

import com.dractical.conduit.Event;
import com.dractical.conduit.EventBus;
import com.dractical.conduit.Subscribe;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    static class TestEvent implements Event {
    }

    static class Listener {
        int calls = 0;

        @Subscribe
        public void handle(TestEvent e) {
            calls++;
        }
    }

    @Test
    void duplicateRegistrationIgnored() {
        EventBus bus = new EventBus();
        Listener listener = new Listener();
        bus.register(listener);
        bus.register(listener);
        bus.post(new TestEvent());
        assertEquals(1, listener.calls);
    }

    @Test
    void unregisterRemovesHandler() {
        EventBus bus = new EventBus();
        Listener listener = new Listener();
        bus.register(listener);
        bus.unregister(listener);
        bus.post(new TestEvent());
        assertEquals(0, listener.calls);
    }

    @Test
    void postAsyncRequiresExecutor() {
        EventBus bus = new EventBus();
        assertThrows(IllegalStateException.class, () -> bus.postAsync(new TestEvent()));
    }

    @Test
    void postAsyncDispatchesToHandlers() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            EventBus bus = new EventBus(exec);
            Listener l1 = new Listener();
            Listener l2 = new Listener();
            bus.register(l1);
            bus.register(l2);
            TestEvent event = new TestEvent();
            TestEvent result = bus.postAsync(event).get(1, TimeUnit.SECONDS);
            assertSame(event, result);
            assertEquals(1, l1.calls);
            assertEquals(1, l2.calls);
        } finally {
            exec.shutdownNow();
        }
    }
}

