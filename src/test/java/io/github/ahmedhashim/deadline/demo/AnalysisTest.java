package io.github.ahmedhashim.deadline.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisTest {
    private static Cluster.UserResult user(String req, long sent, long stopped, int status) {
        return new Cluster.UserResult(req, sent, stopped, status, status == 200 ? "ok" : "gave up", "");
    }

    @Test
    void workIsSplitAtTheMomentTheUserStoppedWaiting() {
        Cluster.UserResult u = user("a", 1000, 3000, 0);
        List<Event> evs = List.of(
                new Event("work", "inventory", "", "a", 0, 1100, 2600, "done"),   // all before
                new Event("work", "inventory", "", "a", 0, 2500, 4000, "done"),   // 1000 after
                new Event("work", "inventory", "", "a", 0, 5000, 5500, "done"),   // all after
                new Event("call", "orders", "inventory", "a", 1, 1050, 2050, "timeout"),
                new Event("call", "orders", "inventory", "a", 2, 3000, 4000, "ok"),  // starts as the user leaves
                new Event("call", "orders", "inventory", "a", 3, 4000, 4000, "not_started"),
                new Event("work", "inventory", "", "other", 0, 0, 99_999, "done"));
        Analysis.Request r = Analysis.request(u, evs);
        assertEquals(3500, r.workMs());
        assertEquals(1500, r.workAfterMs());
        assertEquals(2, r.calls(), "skipped attempts are not calls");
        assertEquals(1, r.callsAfter());
        assertEquals(4500, r.busyUntilMs());
    }

    @Test
    void loadCountsGoodputLatencyAndWorkForNothing() {
        List<Cluster.UserResult> users = List.of(user("a", 0, 100, 200), user("b", 0, 300, 200),
                user("c", 0, 2000, 0), user("d", 10, 2010, 0));
        List<Event> evs = List.of(
                new Event("work", "inventory", "", "a", 0, 0, 90, "done"),
                new Event("work", "inventory", "", "b", 0, 0, 290, "done"),
                new Event("work", "inventory", "", "c", 0, 0, 2500, "done"),
                new Event("work", "inventory", "", "d", 0, 3000, 3100, "done"));
        Analysis.Load l = Analysis.load(users, evs);
        assertEquals(4, l.requests());
        assertEquals(2, l.okInTime());
        assertEquals(100, l.p50OkMs());
        assertEquals(300, l.p99OkMs());
        assertEquals(90 + 290 + 2500 + 100, l.workMs());
        assertEquals(500 + 100, l.workAfterMs());
        assertEquals(2500 + 100, l.workForNothingMs());
        assertEquals(3100 - 2010, l.drainMs());
    }
}
