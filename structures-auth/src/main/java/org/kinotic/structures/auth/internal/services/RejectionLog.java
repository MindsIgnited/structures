package org.kinotic.structures.auth.internal.services;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

/**
 * Logs rejected tokens at warn at most once per {@link #INTERVAL} across all instances, and at debug otherwise.
 * The tokens being rejected are unverified and anyone can send them, so logging each at warn would let anyone
 * flood the logs. String arguments are sanitized, since they can come from the token.
 */
final class RejectionLog {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    // shared, so the services that reject tokens together log at warn at most once per interval
    private static final AtomicLong NEXT_WARN_NANOS = new AtomicLong(System.nanoTime());

    private final Logger log;

    RejectionLog(Logger log) {
        this.log = log;
    }

    void log(String format, Object... args) {
        Object[] safeArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            safeArgs[i] = args[i] instanceof String text ? UntrustedText.sanitize(text) : args[i];
        }
        long now = System.nanoTime();
        long next = NEXT_WARN_NANOS.get();
        if (now - next >= 0 && NEXT_WARN_NANOS.compareAndSet(next, now + INTERVAL.toNanos())) {
            log.warn(format + " (further rejections within " + INTERVAL + " are logged at debug)", safeArgs);
        } else {
            log.debug(format, safeArgs);
        }
    }
}
