package io.github.ahmedhashim.deadline;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that stands still until {@link #advance} is called. */
public final class VirtualClock implements Clock {
    private final AtomicLong now = new AtomicLong();

    @Override
    public long nanos() {
        return now.get();
    }

    public void advance(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("time does not go backwards: " + d);
        }
        now.addAndGet(d.toNanos());
    }

    public void advanceMillis(long ms) {
        advance(Duration.ofMillis(ms));
    }
}
