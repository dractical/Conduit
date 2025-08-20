package com.dractical.conduit;

import java.util.Objects;

/**
 * Published when an event has no listeners. Useful for diagnostics.
 */
public final class DeadEvent implements Event {
    private final Object source;
    private final Event event;

    public DeadEvent(Object source, Event event) {
        this.source = Objects.requireNonNull(source, "source");
        this.event = Objects.requireNonNull(event, "event");
    }

    public Object getSource() {
        return source;
    }

    public Event getEvent() {
        return event;
    }

    @Override
    public String toString() {
        return "DeadEvent{source=" + source + ", event=" + event + '}';
    }
}
