package io.github.ahmedhashim.deadline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceTest {

    @Test
    void roundTripsAwkwardText() {
        Trace t = new Trace();
        t.add(new Span("gate way", 0, 0, 10, 0, "ok", "a;b,c%d>e ✓"));
        t.add(new Span("gate way>orders", 2, 3, 9, 1, "error_503", ""));
        Trace back = Trace.decode(t.encode());
        assertEquals(t.spans(), back.spans());
    }

    @Test
    void malformedHeadersGiveAnEmptyTrace() {
        for (String h : List.of("x", "a;1;2", "a;b;c;d;e;f;g", ";;;;;;", "+x", "a;1;2;3;4;ok;%zz")) {
            assertTrue(Trace.decode(h).spans().isEmpty(), h);
        }
        assertTrue(Trace.decode(null).spans().isEmpty());
        assertTrue(Trace.decode("").spans().isEmpty());
    }

    @Test
    void mergeShiftsTheCalleeOntoTheCallersTimelineAndNumbersTheAttempt() {
        Trace callee = new Trace();
        callee.add(new Span("orders", 0, 0, 900, 0, "ok", ""));
        callee.add(new Span("orders>inventory", 1, 20, 880, 5, "ok", ""));
        Trace root = new Trace();
        root.merge(callee, 1000, "gateway", 2);
        assertEquals(List.of(new Span("gateway>orders", 2, 1000, 1900, 0, "ok", ""),
                new Span("gateway>orders>inventory", 1, 1020, 1880, 5, "ok", "")), root.spans());
    }

    @Test
    void stopsKeepingSpansButCountsThem() {
        Trace t = new Trace();
        for (int i = 0; i < Trace.MAX_SPANS + 5; i++) {
            t.add(new Span("a", i, i, i, 0, "ok", ""));
        }
        assertEquals(Trace.MAX_SPANS, t.spans().size());
        assertEquals(5, t.dropped());
        Trace back = Trace.decode(t.encode());
        assertEquals(Trace.MAX_SPANS, back.spans().size());
        assertEquals(5, back.dropped());
        assertTrue(back.render(-1).contains("5 more spans not kept"));
    }

    @Test
    void rendersChildrenUnderTheAttemptTheyBelongTo() {
        Trace t = new Trace();
        t.add(new Span("gateway", 0, 0, 1900, 0, "deadline_exceeded", ""));
        t.add(new Span("gateway>orders", 2, 1000, 1880, 0, "deadline_exceeded", ""));
        t.add(new Span("gateway>orders", 1, 0, 990, 0, "error_503", ""));
        t.add(new Span("gateway>orders>inventory", 1, 1010, 1870, 0, "deadline_exceeded", ""));
        t.add(new Span("gateway>orders>inventory", 1, 10, 980, 0, "error_503", ""));
        t.add(new Span("gateway>orders", 3, 1890, 1890, 0, "not_started", "10 ms left, needs 70"));
        String[] lines = t.render(-1).split("\n");
        assertTrue(lines[1].startsWith("gateway "), lines[1]);
        assertTrue(lines[2].matches("  orders\\s+1\\s+0\\s.*"), lines[2]);
        assertTrue(lines[3].matches("    inventory\\s+1\\s+10\\s.*"), lines[3]);
        assertTrue(lines[4].matches("  orders\\s+2\\s+1000\\s.*"), lines[4]);
        assertTrue(lines[5].matches("    inventory\\s+1\\s+1010\\s.*"), lines[5]);
        assertTrue(lines[6].matches("  orders\\s+3\\s+1890\\s+-\\s+-\\s+not_started \\(10 ms left, needs 70\\)"), lines[6]);
    }
}
