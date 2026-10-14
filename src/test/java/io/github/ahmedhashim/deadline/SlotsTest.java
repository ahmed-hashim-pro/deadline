package io.github.ahmedhashim.deadline;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotsTest {

    @Test
    void aQueuedRequestGivesUpWhenItsBudgetRunsOut() throws Exception {
        Slots s = new Slots(1);
        assertTrue(s.acquire(null) >= 0);
        long t0 = System.nanoTime();
        long r = s.acquire(Deadline.afterMillis(150, Clock.system()));
        long waited = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(-1, r);
        assertTrue(waited >= 140 && waited < 1000, "waited " + waited + " ms");
        assertEquals(1, s.inUse());
    }

    @Test
    void anExpiredRequestDoesNotEvenQueue() throws Exception {
        Slots s = new Slots(1);
        assertEquals(-1, s.acquire(Deadline.afterMillis(-1, Clock.system())));
        assertEquals(0, s.inUse());
    }

    @Test
    void withoutADeadlineItWaitsAsLongAsItTakes() throws Exception {
        Slots s = new Slots(1);
        s.acquire(null);
        CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
            try {
                return s.acquire(null);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertTrue(!f.isDone(), "still waiting");
        s.release();
        assertTrue(f.get(5, TimeUnit.SECONDS) >= 150);
    }

    @Test
    void needsAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new Slots(0));
    }
}
