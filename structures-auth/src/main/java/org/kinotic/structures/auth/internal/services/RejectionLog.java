package org.kinotic.structures.auth.internal.services;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

/**
 * Logs rejected tokens at warn at most once per {@link #INTERVAL}, and at debug otherwise. The tokens being
 * rejected are unverified and anyone can send them, so logging each at warn would let anyone flood the logs.
 */
final class RejectionLog {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final Logger log;
    private final AtomicLong nextWarnNanos = new AtomicLong(System.nanoTime());

    RejectionLog(Logger log) {
        this.log = log;
    }

    void log(String format, Object... args) {
        long now = System.nanoTime();
        long next = nextWarnNanos.get();
        if (now - next >= 0 && nextWarnNanos.compareAndSet(next, now + INTERVAL.toNanos())) {
            log.warn(format + " (further rejections within " + INTERVAL + " are logged at debug)", args);
        } else {
            log.debug(format, args);
        }
    }
}
