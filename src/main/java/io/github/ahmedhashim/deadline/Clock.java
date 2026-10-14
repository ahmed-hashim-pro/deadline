package io.github.ahmedhashim.deadline;

/**
 * A monotonic clock in nanoseconds. Production code uses {@link #system()};
 * tests use {@link VirtualClock} so that time only moves when they say so.
 */
@FunctionalInterface
public interface Clock {
    long nanos();

    static Clock system() {
        return System::nanoTime;
    }
}
