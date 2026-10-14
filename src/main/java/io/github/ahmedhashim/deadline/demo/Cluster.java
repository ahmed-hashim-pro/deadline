package io.github.ahmedhashim.deadline.demo;

import io.github.ahmedhashim.deadline.Budget;
import io.github.ahmedhashim.deadline.Trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three services in a chain, user to gateway to orders to inventory, either as
 * separate JVM processes (the demo) or in this JVM (the tests). Also plays the
 * user, who gives up after {@link Topology#userWaitMs}.
 */
public final class Cluster implements AutoCloseable {

    /** Timeouts and retries as a team might configure them, one hop at a time. */
    public record Topology(long userWaitMs,
                           long gatewayTimeoutMs, int gatewayAttempts,
                           long ordersWorkMs, long ordersTimeoutMs, int ordersAttempts,
                           long inventoryWorkMs, long inventorySlowMs, double inventorySlowFraction,
                           int inventorySlots, long marginMs) {

        /** One slow request: inventory takes 1.5 s, more than orders waits for it. */
        public static Topology demo() {
            return new Topology(2000, 3500, 3, 20, 1000, 3, 1500, 0, 0, 2, 50);
        }

        /** The same timeouts under steady traffic, with 5% of inventory calls slow. */
        public static Topology load() {
            return new Topology(2000, 3500, 3, 20, 1000, 3, 100, 1500, 0.05, 8, 50);
        }

        /** {@link #demo()} at half the time, for tests. */
        public static Topology demoFast() {
            return new Topology(1000, 1750, 3, 10, 500, 3, 750, 0, 0, 2, 50);
        }
    }

    /** What the user saw. */
    public record UserResult(String req, long sentEpochMs, long stoppedEpochMs, int status, String outcome,
                             String trace) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public long waitedMs() {
            return stoppedEpochMs - sentEpochMs;
        }
    }

    private final Topology topo;
    private final boolean deadlines;
    private final List<Process> procs = new ArrayList<>();
    private final List<Service> local = new ArrayList<>();
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final AtomicLong lastEvent = new AtomicLong(System.currentTimeMillis());
    private final HttpClient user = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    private String gatewayUrl;

    private Cluster(Topology topo, boolean deadlines) {
        this.topo = topo;
        this.deadlines = deadlines;
    }

    public static Cluster start(Topology t, boolean deadlines, boolean processes) throws IOException,
            InterruptedException {
        Cluster c = new Cluster(t, deadlines);
        try {
            int inv = freePort();
            int ord = freePort();
            int gw = freePort();
            List<Service.Config> cfgs = List.of(
                    new Service.Config("inventory", inv, deadlines, t.inventoryWorkMs(), t.inventorySlowMs(),
                            t.inventorySlowFraction(), t.inventorySlots(), null, null, 0, 0, t.marginMs()),
                    new Service.Config("orders", ord, deadlines, t.ordersWorkMs(), 0, 0, 0, "inventory",
                            "http://127.0.0.1:" + inv + "/", t.ordersTimeoutMs(), t.ordersAttempts(), t.marginMs()),
                    new Service.Config("gateway", gw, deadlines, 0, 0, 0, 0, "orders",
                            "http://127.0.0.1:" + ord + "/", t.gatewayTimeoutMs(), t.gatewayAttempts(), t.marginMs()));
            for (Service.Config cfg : cfgs) {
                if (processes) {
                    c.spawn(cfg);
                } else {
                    c.local.add(Service.start(cfg, c::record));
                }
            }
            c.gatewayUrl = "http://127.0.0.1:" + gw + "/";
            return c;
        } catch (IOException | InterruptedException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private void record(Event e) {
        events.add(e);
        lastEvent.set(System.currentTimeMillis());
    }

    private void spawn(Service.Config cfg) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "service", "--name", cfg.name(), "--port", Integer.toString(cfg.port()),
                "--mode", cfg.deadlines() ? "deadline" : "naive", "--work", Long.toString(cfg.workMs()),
                "--slow-work", Long.toString(cfg.slowMs()), "--slow-fraction", Double.toString(cfg.slowFraction()),
                "--slots", Integer.toString(cfg.slots()), "--margin", Long.toString(cfg.marginMs())));
        if (cfg.downstreamUrl() != null) {
            cmd.addAll(List.of("--downstream", cfg.downstream() + "=" + cfg.downstreamUrl(), "--timeout",
                    Long.toString(cfg.timeoutMs()), "--attempts", Integer.toString(cfg.attempts())));
        }
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        procs.add(p);
        CountDownLatch up = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("listening")) {
                        up.countDown();
                        continue;
                    }
                    Event e = Event.parse(line);
                    if (e != null) {
                        record(e);
                    }
                }
            } catch (IOException ignored) {
                // the process went away; close() handles it
            }
        });
        if (!up.await(30, TimeUnit.SECONDS)) {
            throw new IOException(cfg.name() + " did not start");
        }
    }

    /** Sends one request as the user, who waits {@code userWaitMs} and then gives up. */
    public UserResult call(String req) throws InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gatewayUrl))
                .timeout(Duration.ofMillis(topo.userWaitMs())).header(Service.REQUEST_ID, req).GET();
        if (deadlines) {
            // The user's client is a caller like any other: it keeps a margin to
            // receive the reply and hands on the rest.
            b.header(Budget.HEADER, Long.toString(topo.userWaitMs() - topo.marginMs()));
        }
        long sent = System.currentTimeMillis();
        try {
            HttpResponse<String> r = user.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String outcome = r.statusCode() / 100 == 2 ? "ok" : "error " + r.statusCode();
            return new UserResult(req, sent, System.currentTimeMillis(), r.statusCode(), outcome,
                    r.headers().firstValue(Trace.HEADER).orElse(""));
        } catch (HttpTimeoutException e) {
            return new UserResult(req, sent, sent + topo.userWaitMs(), 0, "gave up", "");
        } catch (IOException e) {
            return new UserResult(req, sent, System.currentTimeMillis(), 0, "error " + e.getMessage(), "");
        }
    }

    /** Waits until no service has done anything for {@code quietMs}, or {@code maxMs} passes. */
    public void awaitQuiet(long quietMs, long maxMs) throws InterruptedException {
        long until = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < until) {
            if (System.currentTimeMillis() - lastEvent.get() >= quietMs) {
                return;
            }
            Thread.sleep(50);
        }
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public Topology topology() {
        return topo;
    }

    public boolean deadlines() {
        return deadlines;
    }

    @Override
    public void close() {
        for (Service s : local) {
            s.close();
        }
        for (Process p : procs) {
            p.destroy();
        }
        for (Process p : procs) {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        user.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
