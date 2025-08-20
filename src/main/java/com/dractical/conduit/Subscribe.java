package com.dractical.conduit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
    Priority priority() default Priority.NORMAL;

    /**
     * If true and the event implements Cancellable and is already cancelled,
     * the handler will be skipped. Set false to always receive cancelled events.
     */
    boolean ignoreCancelled() default true;

    /**
     * If true, this handler for type T also receives subtypes of T.
     * If false, it only receives exactly T.
     */
    boolean receiveSubtypes() default true;

    /**
     * If true, unregister this handler after its first successful invocation.
     */
    boolean once() default false;
}
