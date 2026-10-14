package io.github.ahmedhashim.deadline.demo;

import com.sun.net.httpserver.HttpServer;
import io.github.ahmedhashim.deadline.CallPolicy;
import io.github.ahmedhashim.deadline.DeadlineClient;
import io.github.ahmedhashim.deadline.DeadlineExceededException;
import io.github.ahmedhashim.deadline.DeadlineHandler;
import io.github.ahmedhashim.deadline.RequestContext;
import io.github.ahmedhashim.deadline.Slots;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One hop of the demo: holds a worker for a while (the "work"), then optionally
 * calls the next hop. Work is simulated by holding a slot, like a request
 * holding a database connection; with a deadline it is cut short when the
 * budget runs out.
 */
public final class Service implements AutoCloseable {
    /** Measurement only: the demo's request id, passed along so events can be grouped. Never used to decide anything. */
    public static final String REQUEST_ID = "X-Demo-Request";

    /**
     * @param workMs       how long the work holds a worker
     * @param slowMs       for a {@code slowFraction} of requests the work takes this long instead
     * @param slots        how many requests can work at once
     * @param downstreamUrl the next hop, or null
     * @param marginMs     budget each caller keeps back to receive the reply
     */
    public record Config(String name, int port, boolean deadlines, long workMs, long slowMs, double slowFraction,
                         int slots, String downstream, String downstreamUrl, long timeoutMs, int attempts,
                         long marginMs) {
    }

    static {
        // The JDK server writes headers and body separately; without TCP_NODELAY a
        // small reply can sit ~40 ms behind Nagle and delayed ACKs, which is most
        // of a hop's budget margin. Must be set before the first HttpServer.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private final HttpServer server;
    private final ExecutorService exec;
    private final int port;

    private Service(HttpServer server, ExecutorService exec) {
        this.server = server;
        this.exec = exec;
        this.port = server.getAddress().getPort();
    }

    public int port() {
        return port;
    }

    public static Service start(Config c, Consumer<Event> events) throws IOException {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        DeadlineClient.Listener callLog = (ctx, target, attempt, t0, t1, out) -> events
                .accept(new Event("call", c.name(), target, reqId(ctx), attempt, t0, t1, out));
        DeadlineClient next = c.downstreamUrl() == null ? null
                : new DeadlineClient(c.downstream(), URI.create(c.downstreamUrl()),
                        CallPolicy.of(c.timeoutMs(), c.attempts()).withPropagate(c.deadlines())
                                .withMargin(Duration.ofMillis(c.marginMs())), http, callLog);

        DeadlineHandler.App app = ctx -> {
            long ms = workFor(c, reqId(ctx));
            if (ms > 0) {
                hold(ctx, ms, c.name(), events);
            }
            if (next != null) {
                DeadlineClient.Response r = next.call(ctx, "/");
                if (!r.ok()) {
                    if (ctx.deadline() != null && r.status() == 504) {
                        throw new DeadlineExceededException(c.downstream() + " ran out of budget");
                    }
                    return new DeadlineHandler.Reply(503, c.downstream() + " failed after " + r.attempts()
                            + " attempts: " + r.status());
                }
            }
            return DeadlineHandler.Reply.ok("ok from " + c.name());
        };
        DeadlineHandler handler = DeadlineHandler.builder(c.name(), app)
                .enforce(c.deadlines())
                .slots(c.slots() > 0 ? new Slots(c.slots()) : Slots.unlimited())
                .reserve(Duration.ofMillis(Math.max(1, c.marginMs() / 5)))
                .forward(Set.of(REQUEST_ID))
                .listener((ctx, own) -> events.accept(new Event("done", c.name(), "", reqId(ctx), 0,
                        ctx.startEpochMs(), System.currentTimeMillis(), own.outcome())))
                .build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", c.port()), 4096);
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(exec);
        server.createContext("/", handler);
        server.start();
        return new Service(server, exec);
    }

    /** Holds the worker for {@code ms}, or until the deadline if that comes first. */
    static void hold(RequestContext ctx, long ms, String svc, Consumer<Event> events) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        long allowed = ctx.deadline() == null ? ms : Math.max(0, Math.min(ms, ctx.deadline().remainingMillis()));
        Thread.sleep(allowed);
        boolean cut = allowed < ms;
        events.accept(new Event("work", svc, "", reqId(ctx), 0, t0, System.currentTimeMillis(),
                cut ? "cancelled" : "done"));
        if (cut) {
            throw new DeadlineExceededException("work stopped after " + allowed + " of " + ms + " ms");
        }
    }

    /** Deterministic per request id, so both modes see the same slow requests. */
    static long workFor(Config c, String req) {
        if (req.startsWith("warmup")) {
            return Math.min(c.workMs(), 1); // warms the JVMs up without waiting for a slow chain; never measured
        }
        if (c.slowFraction() <= 0) {
            return c.workMs();
        }
        long h = (req + "/" + c.name()).hashCode() & 0xffffffffL;
        h ^= h >>> 15;
        h *= 0x2c1b3c6dL;
        h ^= h >>> 12;
        double u = (h & 0xffffff) / (double) 0x1000000;
        return u < c.slowFraction() ? c.slowMs() : c.workMs();
    }

    private static String reqId(RequestContext ctx) {
        return ctx.forward().getOrDefault(REQUEST_ID, "-");
    }

    @Override
    public void close() {
        server.stop(0);
        exec.shutdownNow();
    }
}
