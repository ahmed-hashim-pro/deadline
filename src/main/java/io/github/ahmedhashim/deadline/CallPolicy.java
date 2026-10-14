package io.github.ahmedhashim.deadline;

import java.time.Duration;

/**
 * How one service calls another.
 *
 * @param attemptTimeout the most one attempt may take, as configured today
 * @param maxAttempts    attempts including the first
 * @param minUseful      don't start an attempt with less budget than this left; it
 *                       could not finish, so it would only add load
 * @param margin         budget kept back to send our own reply after the callee answers
 * @param propagate      false gives the conventional behaviour: fixed per-attempt
 *                       timeouts, retries regardless of whether anyone is still waiting
 */
public record CallPolicy(Duration attemptTimeout, int maxAttempts, Duration minUseful, Duration margin,
                         boolean propagate) {

    public CallPolicy {
        if (attemptTimeout.isNegative() || attemptTimeout.isZero()) {
            throw new IllegalArgumentException("attemptTimeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (minUseful.isNegative() || margin.isNegative()) {
            throw new IllegalArgumentException("minUseful and margin can't be negative");
        }
        if (margin.compareTo(attemptTimeout) >= 0) {
            throw new IllegalArgumentException("margin must be shorter than attemptTimeout");
        }
    }

    public static CallPolicy of(long attemptTimeoutMs, int maxAttempts) {
        return new CallPolicy(Duration.ofMillis(attemptTimeoutMs), maxAttempts, Duration.ofMillis(50),
                Duration.ofMillis(20), true);
    }

    public CallPolicy withPropagate(boolean p) {
        return new CallPolicy(attemptTimeout, maxAttempts, minUseful, margin, p);
    }

    public CallPolicy withMinUseful(Duration d) {
        return new CallPolicy(attemptTimeout, maxAttempts, d, margin, propagate);
    }

    public CallPolicy withMargin(Duration d) {
        return new CallPolicy(attemptTimeout, maxAttempts, minUseful, d, propagate);
    }
}
