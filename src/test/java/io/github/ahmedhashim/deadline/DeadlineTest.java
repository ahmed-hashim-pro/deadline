package io.github.ahmedhashim.deadline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineTest {

    @Test
    void countsDownOnTheClockItWasGiven() {
        VirtualClock clock = new VirtualClock();
        Deadline d = Deadline.afterMillis(2000, clock);
        assertEquals(2000, d.remainingMillis());
        clock.advanceMillis(1999);
        assertEquals(1, d.remainingMillis());
        assertFalse(d.expired());
        clock.advanceMillis(1);
        assertTrue(d.expired());
        clock.advanceMillis(250);
        assertEquals(-250, d.remainingMillis());
    }

    @Test
    void checkThrowsOnlyOnceExpired() {
        VirtualClock clock = new VirtualClock();
        Deadline d = Deadline.afterMillis(10, clock);
        d.check("before");
        clock.advanceMillis(15);
        DeadlineExceededException e = assertThrows(DeadlineExceededException.class, () -> d.check("loading"));
        assertTrue(e.getMessage().contains("loading"), e.getMessage());
        assertTrue(e.getMessage().contains("5 ms ago"), e.getMessage());
    }

    @Test
    void remainingRoundsDownSoAPartialMillisecondIsNotPromised() {
        VirtualClock clock = new VirtualClock();
        Deadline d = Deadline.after(Duration.ofNanos(1_999_999), clock);
        assertEquals(1, d.remainingMillis());
        clock.advance(Duration.ofNanos(1_000_000));
        assertEquals(0, d.remainingMillis());
        clock.advance(Duration.ofNanos(1_000_000));
        assertEquals(-1, d.remainingMillis());
    }

    @Test
    void atMostKeepsTheEarlierDeadline() {
        VirtualClock clock = new VirtualClock();
        Deadline d = Deadline.afterMillis(1000, clock);
        assertEquals(300, d.atMost(Duration.ofMillis(300)).remainingMillis());
        assertSame(d, d.atMost(Duration.ofMillis(5000)));
    }

    @Test
    void virtualClockRefusesToGoBackwards() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualClock().advance(Duration.ofMillis(-1)));
    }
}
