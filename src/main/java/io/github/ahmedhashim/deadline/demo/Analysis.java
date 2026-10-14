package io.github.ahmedhashim.deadline.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns what the user saw and what the services did into the numbers the README quotes. */
public final class Analysis {
    private Analysis() {
    }

    /**
     * One request.
     *
     * @param busyUntilMs   when the last service stopped working on it, from when the user sent it
     * @param workMs        worker time spent on it, all services
     * @param workAfterMs   of that, time after the user stopped waiting
     * @param calls         calls between services actually sent
     * @param callsAfter    of those, calls sent after the user stopped waiting
     */
    public record Request(Cluster.UserResult user, long busyUntilMs, long workMs, long workAfterMs, int calls,
                          int callsAfter) {
    }

    public static Request request(Cluster.UserResult u, Collection<Event> evs) {
        long last = u.sentEpochMs();
        long work = 0;
        long workAfter = 0;
        int calls = 0;
        int callsAfter = 0;
        for (Event e : evs) {
            if (!e.req().equals(u.req())) {
                continue;
            }
            last = Math.max(last, e.t1());
            switch (e.kind()) {
                case "work" -> {
                    work += e.ms();
                    workAfter += Math.max(0, e.t1() - Math.max(e.t0(), u.stoppedEpochMs()));
                }
                case "call" -> {
                    if (!e.out().equals("not_started")) {
                        calls++;
                        if (e.t0() >= u.stoppedEpochMs()) {
                            callsAfter++;
                        }
                    }
                }
                default -> {
                }
            }
        }
        return new Request(u, last - u.sentEpochMs(), work, workAfter, calls, callsAfter);
    }

    /** Many requests. Times in ms. */
    public record Load(int requests, int okInTime, long p50OkMs, long p99OkMs, long workMs, long workAfterMs,
                       long workForNothingMs, int calls, int callsAfter, long drainMs) {
        public double goodput() {
            return requests == 0 ? 0 : (double) okInTime / requests;
        }
    }

    public static Load load(List<Cluster.UserResult> users, List<Event> evs) {
        Map<String, List<Event>> byReq = new HashMap<>();
        long lastEvent = 0;
        for (Event e : evs) {
            byReq.computeIfAbsent(e.req(), k -> new ArrayList<>()).add(e);
            lastEvent = Math.max(lastEvent, e.t1());
        }
        int ok = 0;
        long work = 0;
        long after = 0;
        long nothing = 0;
        int calls = 0;
        int callsAfter = 0;
        long lastStop = 0;
        List<Long> lat = new ArrayList<>();
        for (Cluster.UserResult u : users) {
            Request r = request(u, byReq.getOrDefault(u.req(), List.of()));
            if (u.ok()) {
                ok++;
                lat.add(u.waitedMs());
            } else {
                nothing += r.workMs();
            }
            work += r.workMs();
            after += r.workAfterMs();
            calls += r.calls();
            callsAfter += r.callsAfter();
            lastStop = Math.max(lastStop, u.stoppedEpochMs());
        }
        lat.sort(null);
        return new Load(users.size(), ok, pct(lat, 0.50), pct(lat, 0.99), work, after, nothing, calls, callsAfter,
                Math.max(0, lastEvent - lastStop));
    }

    private static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int i = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
