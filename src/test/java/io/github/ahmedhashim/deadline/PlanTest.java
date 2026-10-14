package io.github.ahmedhashim.deadline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {
    private final VirtualClock clock = new VirtualClock();

    @Test
    void withoutPropagationEveryAttemptGetsTheConfiguredTimeoutAndNoBudget() {
        CallPolicy p = CallPolicy.of(1000, 3).withPropagate(false);
        Deadline d = Deadline.afterMillis(10, clock);
        assertEquals(new Plan.Send(1000, -1), Plan.next(p, d));
        clock.advanceMillis(500);
        assertEquals(new Plan.Send(1000, -1), Plan.next(p, d), "even after the deadline passed");
    }

    @Test
    void withNoIncomingDeadlineTheTimeoutIsAllThereIs() {
        assertEquals(new Plan.Send(1000, -1), Plan.next(CallPolicy.of(1000, 3), null));
    }

    @Test
    void theCalleeIsToldHowLongWeWillWaitMinusTheMargin() {
        CallPolicy p = CallPolicy.of(1000, 3); // margin 20, minUseful 50
        Deadline d = Deadline.afterMillis(1930, clock);
        assertEquals(new Plan.Send(1000, 980), Plan.next(p, d), "timeout is the limit");
        clock.advanceMillis(1000);
        assertEquals(new Plan.Send(930, 910), Plan.next(p, d), "deadline is the limit");
        clock.advanceMillis(870);
        assertEquals(new Plan.Skip(60, 70), Plan.next(p, d), "70 needed, 60 left");
    }

    @Test
    void anAttemptThatCannotFinishIsNotStarted() {
        CallPolicy p = CallPolicy.of(1000, 3);
        Deadline d = Deadline.afterMillis(69, clock);
        assertEquals(new Plan.Skip(69, 70), Plan.next(p, d));
        clock.advanceMillis(100);
        assertEquals(new Plan.Skip(-31, 70), Plan.next(p, d));
    }

    /** The orders hop of the README demo, decision by decision, with no real time passing. */
    @Test
    void theDemoScenarioOnAVirtualClock() {
        CallPolicy p = CallPolicy.of(1000, 3).withMargin(Duration.ofMillis(50));
        Deadline d = Deadline.afterMillis(1890, clock);
        List<Plan> plans = new ArrayList<>();
        for (int attempt = 1; attempt <= p.maxAttempts(); attempt++) {
            Plan plan = Plan.next(p, d);
            plans.add(plan);
            if (plan instanceof Plan.Send s) {
                clock.advanceMillis(s.budgetMs()); // the callee works until its budget runs out
            } else {
                break;
            }
        }
        assertEquals(List.of(new Plan.Send(1000, 950), new Plan.Send(940, 890), new Plan.Skip(50, 100)), plans);
        assertTrue(clock.nanos() < Duration.ofMillis(1890).toNanos(), "done before the deadline");
    }

    @Test
    void invariantsOverManyRandomPolicies() {
        Random r = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            VirtualClock c = new VirtualClock();
            long margin = r.nextInt(200);
            long timeout = margin + 1 + r.nextInt(5000);
            long minUseful = r.nextInt(200);
            CallPolicy p = new CallPolicy(Duration.ofMillis(timeout), 1 + r.nextInt(5), Duration.ofMillis(minUseful),
                    Duration.ofMillis(margin), true);
            long left = r.nextInt(8000) - 1000;
            Deadline d = Deadline.afterMillis(left, c);
            Plan plan = Plan.next(p, d);
            if (left < minUseful + margin) {
                assertInstanceOf(Plan.Skip.class, plan, "case " + i);
                continue;
            }
            Plan.Send s = assertInstanceOf(Plan.Send.class, plan, "case " + i);
            assertTrue(s.budgetMs() <= timeout - margin, "budget within the timeout, less the margin");
            assertTrue(s.budgetMs() <= left - margin, "budget leaves the margin");
            assertTrue(s.budgetMs() >= Math.min(minUseful, timeout - margin), "budget is worth sending");
            assertTrue(s.budgetMs() > 0, "a budget we send is never already spent");
            assertTrue(s.waitMs() <= left, "we never wait past our own deadline");
            assertEquals(s.budgetMs() + margin, s.waitMs(), "the callee answers inside our wait, by the margin");
        }
    }

    @Test
    void policyRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> CallPolicy.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> CallPolicy.of(100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CallPolicy.of(100, 1).withMargin(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> CallPolicy.of(100, 1).withMargin(Duration.ofMillis(100)));
    }
}
