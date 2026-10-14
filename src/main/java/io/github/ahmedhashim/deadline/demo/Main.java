package io.github.ahmedhashim.deadline.demo;

import io.github.ahmedhashim.deadline.Trace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** {@code deadline service|demo|load}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage(System.out);
            return;
        }
        Map<String, String> o = options(args);
        int code = switch (args[0]) {
            case "service" -> service(o);
            case "demo" -> demo(o);
            case "load" -> load(o);
            default -> {
                System.err.println("unknown command: " + args[0]);
                usage(System.err);
                yield 2;
            }
        };
        System.exit(code);
    }

    private static void usage(PrintStream out) {
        out.println("""
                usage:
                  deadline demo [--svg FILE]               one slow request, with and without deadlines
                  deadline load [--rate N] [--seconds S] [--check]
                                                           steady traffic, with and without deadlines
                  deadline service --name N --port P ...   one hop (the demo starts these itself)""");
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + args[i]);
            }
            String k = args[i].substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                o.put(k, "true");
            } else {
                o.put(k, args[++i]);
            }
        }
        return o;
    }

    private static int service(Map<String, String> o) throws IOException, InterruptedException {
        String down = o.get("downstream");
        String downName = null;
        String downUrl = null;
        if (down != null) {
            int eq = down.indexOf('=');
            downName = down.substring(0, eq);
            downUrl = down.substring(eq + 1);
        }
        Service.Config c = new Service.Config(o.get("name"), Integer.parseInt(o.get("port")),
                o.getOrDefault("mode", "deadline").equals("deadline"), Long.parseLong(o.getOrDefault("work", "0")),
                Long.parseLong(o.getOrDefault("slow-work", "0")),
                Double.parseDouble(o.getOrDefault("slow-fraction", "0")),
                Integer.parseInt(o.getOrDefault("slots", "0")), downName, downUrl,
                Long.parseLong(o.getOrDefault("timeout", "1000")), Integer.parseInt(o.getOrDefault("attempts", "1")),
                Long.parseLong(o.getOrDefault("margin", "20")));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Service s = Service.start(c, e -> {
            synchronized (out) {
                out.println(e.line());
            }
        });
        out.println("listening on " + s.port());
        Thread.currentThread().join();
        return 0;
    }

    private static int demo(Map<String, String> o) throws Exception {
        Cluster.Topology t = Cluster.Topology.demo();
        System.out.printf("user -> gateway -> orders -> inventory, each a separate JVM. The user waits %.1f s.%n",
                t.userWaitMs() / 1000.0);
        System.out.printf("gateway calls orders: %.1f s timeout, %d attempts. orders calls inventory: %.1f s timeout, %d attempts.%n",
                t.gatewayTimeoutMs() / 1000.0, t.gatewayAttempts(), t.ordersTimeoutMs() / 1000.0, t.ordersAttempts());
        System.out.printf("inventory is having a slow day: every call takes %.1f s.%n%n", t.inventoryWorkMs() / 1000.0);
        Run naive = once(t, false);
        print("without deadlines", naive);
        Run dl = once(t, true);
        print("with deadlines", dl);
        System.out.println();
        System.out.println("where the budget went, from the " + Trace.HEADER + " header the user got back (ms):");
        System.out.print(Trace.decode(dl.user().trace()).render(-1));
        if (o.containsKey("svg")) {
            Path p = Path.of(o.get("svg"));
            Files.writeString(p, Timeline.svg(t, naive, dl));
            System.out.println("\nwrote " + p);
        }
        return 0;
    }

    /** One request through a fresh cluster, run until the services fall quiet. */
    public record Run(Cluster.UserResult user, List<Event> events, Analysis.Request stats) {
    }

    static Run once(Cluster.Topology t, boolean deadlines) throws Exception {
        try (Cluster c = Cluster.start(t, deadlines, true)) {
            c.call("warmup");
            c.awaitQuiet(1500, 30_000);
            Cluster.UserResult u = c.call("r1");
            c.awaitQuiet(1500, 60_000);
            List<Event> evs = c.events().stream().filter(e -> e.req().equals("r1")).toList();
            return new Run(u, evs, Analysis.request(u, evs));
        }
    }

    private static void print(String label, Run r) {
        Cluster.UserResult u = r.user();
        Analysis.Request s = r.stats();
        String saw = u.ok() ? "got an answer" : u.outcome().equals("gave up") ? "gave up" : "got a " + u.status();
        System.out.printf("%-18s user %s at %.2f s; services kept working until %.2f s%n", label, saw,
                u.waitedMs() / 1000.0, s.busyUntilMs() / 1000.0);
        System.out.printf("%-18s %d calls between services, %d after the user left; %.2f s of worker time, %.2f s after%n",
                "", s.calls(), s.callsAfter(), s.workMs() / 1000.0, s.workAfterMs() / 1000.0);
    }

    private static int load(Map<String, String> o) throws Exception {
        Cluster.Topology t = Cluster.Topology.load();
        double rate = Double.parseDouble(o.getOrDefault("rate", "30"));
        int seconds = Integer.parseInt(o.getOrDefault("seconds", "20"));
        System.out.printf("%.0f requests/s for %d s. inventory: %d workers, %d ms per call, %.0f%% of calls %.1f s.%n",
                rate, seconds, t.inventorySlots(), t.inventoryWorkMs(), t.inventorySlowFraction() * 100,
                t.inventorySlowMs() / 1000.0);
        System.out.printf("same timeouts as the demo; the user waits %.1f s.%n%n", t.userWaitMs() / 1000.0);
        System.out.printf("%-18s %9s %8s %8s %13s %13s %15s %7s%n", "", "answered", "p50", "p99", "worker time",
                "after user", "for no answer", "drain");
        int failed = 0;
        Analysis.Load[] runs = new Analysis.Load[2];
        for (boolean dl : new boolean[] {false, true}) {
            Analysis.Load l = loadOnce(t, dl, rate, seconds);
            runs[dl ? 1 : 0] = l;
            System.out.printf("%-18s %8.1f%% %6d ms %6d ms %11.1f s %11.1f s %13.1f s %5.1f s%n",
                    dl ? "with deadlines" : "without deadlines", 100 * l.goodput(), l.p50OkMs(), l.p99OkMs(),
                    l.workMs() / 1000.0, l.workAfterMs() / 1000.0, l.workForNothingMs() / 1000.0,
                    l.drainMs() / 1000.0);
            if (l.requests() == 0) {
                failed = 1;
            }
        }
        System.out.println("""

                answered       the user got a 2xx within their wait
                worker time    total time services spent holding a worker
                after user     worker time after the user had their answer or had given up
                for no answer  worker time on requests that never answered the user in time
                drain          how long services kept working after the last user stopped waiting""");
        if (o.containsKey("check")) {
            // The README's claims, with room for a noisy CI machine.
            Analysis.Load naive = runs[0];
            Analysis.Load dl = runs[1];
            List<String> bad = new ArrayList<>();
            if (dl.goodput() < 0.85) {
                bad.add(String.format("with deadlines only %.1f%% answered", 100 * dl.goodput()));
            }
            if (dl.goodput() < naive.goodput() + 0.30) {
                bad.add("deadlines did not answer clearly more requests");
            }
            if (dl.workAfterMs() > 0.01 * dl.workMs()) {
                bad.add(String.format("with deadlines %.1f s of work after users left", dl.workAfterMs() / 1000.0));
            }
            if (naive.workAfterMs() < 0.5 * naive.workMs()) {
                bad.add("without deadlines most work was not wasted; the scenario is not overloaded");
            }
            System.out.println();
            if (bad.isEmpty()) {
                System.out.println("check: ok");
            } else {
                bad.forEach(m -> System.out.println("check failed: " + m));
                failed = 1;
            }
        }
        return failed;
    }

    static Analysis.Load loadOnce(Cluster.Topology t, boolean deadlines, double rate, int seconds) throws Exception {
        try (Cluster c = Cluster.start(t, deadlines, true);
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                c.call("warmup" + i);
            }
            c.awaitQuiet(1500, 30_000);
            List<Future<Cluster.UserResult>> fs = new ArrayList<>();
            long n = Math.round(rate * seconds);
            long start = System.nanoTime();
            for (long i = 0; i < n; i++) {
                long due = start + (long) (i * 1e9 / rate);
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                }
                String id = "q" + i;
                fs.add(pool.submit(() -> c.call(id)));
            }
            List<Cluster.UserResult> users = new ArrayList<>();
            for (Future<Cluster.UserResult> f : fs) {
                users.add(f.get());
            }
            c.awaitQuiet(2000, 180_000);
            return Analysis.load(users, c.events());
        }
    }
}
