package io.github.ahmedhashim.deadline.demo;

import io.github.ahmedhashim.deadline.Span;
import io.github.ahmedhashim.deadline.Trace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README demo at half speed, all three services in this JVM: the same
 * request with deadlines off and on.
 */
class ClusterTest {
    private static final Cluster.Topology T = Cluster.Topology.demoFast();

    private static Main.Run run(boolean deadlines) throws Exception {
        try (Cluster c = Cluster.start(T, deadlines, false)) {
            c.call("warmup");
            c.awaitQuiet(600, 20_000);
            Cluster.UserResult u = c.call("r1");
            c.awaitQuiet(600, 20_000);
            List<Event> evs = c.events().stream().filter(e -> e.req().equals("r1")).toList();
            return new Main.Run(u, evs, Analysis.request(u, evs));
        }
    }

    @Test
    void withoutDeadlinesTheServicesKeepWorkingLongAfterTheUserLeft() throws Exception {
        Main.Run r = run(false);
        assertEquals("gave up", r.user().outcome());
        // 3 gateway attempts x 3 orders attempts, each ~ordersTimeout, plus the last inventory call.
        assertTrue(r.stats().busyUntilMs() > 4 * T.userWaitMs(), "busy until " + r.stats().busyUntilMs());
        assertTrue(r.stats().callsAfter() >= 6, "calls after: " + r.stats().callsAfter());
        assertTrue(r.stats().workAfterMs() > 3 * T.userWaitMs(), "work after: " + r.stats().workAfterMs());
        long inventory = r.events().stream().filter(e -> e.kind().equals("work") && e.svc().equals("inventory"))
                .count();
        assertEquals(9, inventory, "three orders attempts per gateway attempt, all of them worked on");
    }

    @Test
    void withDeadlinesEverythingStopsBeforeTheUserGivesUp() throws Exception {
        Main.Run r = run(true);
        assertEquals(504, r.user().status(), "the user hears why, before giving up");
        assertTrue(r.user().waitedMs() < T.userWaitMs(), "answered at " + r.user().waitedMs());
        assertTrue(r.stats().busyUntilMs() <= T.userWaitMs(), "busy until " + r.stats().busyUntilMs());
        assertEquals(0, r.stats().callsAfter());
        assertEquals(0, r.stats().workAfterMs());
        assertTrue(r.events().stream().anyMatch(e -> e.kind().equals("work") && e.out().equals("cancelled")),
                "inventory's work was cut short");

        List<Span> spans = Trace.decode(r.user().trace()).spans();
        assertEquals("gateway", spans.get(0).path());
        assertTrue(spans.stream().anyMatch(s -> s.path().equals("gateway>orders>inventory")
                && s.outcome().equals("deadline_exceeded")), "inventory's own report reached the user");
        assertTrue(spans.stream().anyMatch(s -> s.outcome().equals("not_started")),
                "a retry that could not finish was skipped");
        for (Span s : spans) {
            assertTrue(s.endMs() <= r.user().waitedMs(), s + " ends after the user's answer");
        }
    }

    @Test
    void theTimelineIsValidSvgWithBothRuns() throws Exception {
        String svg = Timeline.svg(T, run(false), run(true));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(svg.contains("user gave up at"));
        assertTrue(svg.contains("user got 504 at"));
        assertFalse(svg.contains("NaN"));
    }
}
