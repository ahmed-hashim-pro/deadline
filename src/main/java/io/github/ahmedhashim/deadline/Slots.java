package io.github.ahmedhashim.deadline;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A service's real capacity: worker threads, database connections, whatever runs
 * out first. With a deadline, a request gives up its place in the queue when
 * its budget runs out instead of being served after nobody is waiting.
 * Waits are in real time.
 */
public final class Slots {
    private final Semaphore sem;
    private final int size;

    public Slots(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("need at least one slot");
        }
        this.size = size;
        this.sem = new Semaphore(size, true);
    }

    public static Slots unlimited() {
        return new Slots(Integer.MAX_VALUE / 2);
    }

    /**
     * Waits for a slot.
     *
     * @return milliseconds spent waiting, or -1 if the deadline passed first
     */
    public long acquire(Deadline deadline) throws InterruptedException {
        long start = System.nanoTime();
        if (deadline == null) {
            sem.acquire();
        } else {
            long left = deadline.remaining().toNanos();
            if (left <= 0 || !sem.tryAcquire(left, TimeUnit.NANOSECONDS)) {
                return -1;
            }
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }

    public void release() {
        sem.release();
    }

    public int inUse() {
        return size - sem.availablePermits();
    }

    public int waiting() {
        return sem.getQueueLength();
    }
}
