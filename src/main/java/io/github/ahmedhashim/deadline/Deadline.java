package io.github.ahmedhashim.deadline;

import java.time.Duration;

/**
 * The instant after which nobody is waiting for the answer.
 *
 * <p>It travels between services as a relative budget ("1980 ms left"), not as a
 * wall-clock time, so hosts with skewed clocks still agree on it. Each hop turns
 * the budget back into an instant on its own monotonic clock when the request
 * arrives.
 */
public final class Deadline {
    private final Clock clock;
    private final long expiresAt;

    private Deadline(Clock clock, long expiresAt) {
        this.clock = clock;
        this.expiresAt = expiresAt;
    }

    public static Deadline after(Duration budget, Clock clock) {
        return new Deadline(clock, clock.nanos() + budget.toNanos());
    }

    public static Deadline afterMillis(long ms, Clock clock) {
        return after(Duration.ofMillis(ms), clock);
    }

    /** Milliseconds left, rounded down; negative once expired. */
    public long remainingMillis() {
        return Math.floorDiv(expiresAt - clock.nanos(), 1_000_000L);
    }

    public Duration remaining() {
        return Duration.ofNanos(expiresAt - clock.nanos());
    }

    public boolean expired() {
        return clock.nanos() >= expiresAt;
    }

    /** Throws if the deadline has passed. Call it between units of work. */
    public void check(String what) {
        if (expired()) {
            throw new DeadlineExceededException(what + ": deadline passed " + (-remainingMillis()) + " ms ago");
        }
    }

    /** The earlier of this deadline and {@code now + d}. */
    public Deadline atMost(Duration d) {
        long other = clock.nanos() + d.toNanos();
        return other < expiresAt ? new Deadline(clock, other) : this;
    }

    public Clock clock() {
        return clock;
    }

    @Override
    public String toString() {
        return "Deadline[" + remainingMillis() + " ms left]";
    }
}
