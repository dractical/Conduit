package com.dractical.conduit;

public interface Cancellable extends Event {
    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
