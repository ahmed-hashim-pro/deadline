package io.github.ahmedhashim.deadline;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The handler and client over real HTTP on loopback. */
class HttpTest {
    static {
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
        http.close();
    }

    private URI serve(DeadlineHandler h) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", h);
        s.start();
        servers.add(s);
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/");
    }

    private HttpResponse<String> get(URI u, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aMalformedBudgetIsABadRequest() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        URI u = serve(DeadlineHandler.builder("svc", ctx -> {
            ran.incrementAndGet();
            return DeadlineHandler.Reply.ok("");
        }).build());
        assertEquals(400, get(u, Map.of(Budget.HEADER, "soon")).statusCode());
        assertEquals(0, ran.get());
    }

    @Test
    void aRequestThatArrivesWithNoBudgetIsNotWorkedOn() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        URI u = serve(DeadlineHandler.builder("svc", ctx -> {
            ran.incrementAndGet();
            return DeadlineHandler.Reply.ok("");
        }).build());
        for (String b : List.of("0", "-20", "3")) {
            HttpResponse<String> r = get(u, Map.of(Budget.HEADER, b));
            assertEquals(504, r.statusCode(), b);
            Span own = Trace.decode(r.headers().firstValue(Trace.HEADER).orElseThrow()).spans().get(0);
            assertEquals("deadline_exceeded", own.outcome());
        }
        assertEquals(0, ran.get());
        assertEquals(200, get(u, Map.of()).statusCode(), "no header, no deadline");
    }

    @Test
    void theAppSeesTheBudgetLessTheReserve() throws Exception {
        AtomicReference<Long> seen = new AtomicReference<>();
        URI u = serve(DeadlineHandler.builder("svc", ctx -> {
            seen.set(ctx.deadline().remainingMillis());
            return DeadlineHandler.Reply.ok("");
        }).reserve(Duration.ofMillis(30)).build());
        get(u, Map.of(Budget.HEADER, "500"));
        assertTrue(seen.get() <= 470 && seen.get() > 400, "remaining " + seen.get());
    }

    @Test
    void notEnforcingIgnoresTheHeader() throws Exception {
        AtomicReference<Deadline> seen = new AtomicReference<>();
        URI u = serve(DeadlineHandler.builder("svc", ctx -> {
            seen.set(ctx.deadline());
            return DeadlineHandler.Reply.ok("");
        }).enforce(false).build());
        assertEquals(200, get(u, Map.of(Budget.HEADER, "0")).statusCode());
        assertEquals(null, seen.get());
    }

    @Test
    void workCutShortIsA504WithItsReason() throws Exception {
        URI u = serve(DeadlineHandler.builder("svc", ctx -> {
            while (true) {
                Thread.sleep(5);
                ctx.check("crunching");
            }
        }).build());
        long t0 = System.nanoTime();
        HttpResponse<String> r = get(u, Map.of(Budget.HEADER, "200"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(504, r.statusCode());
        assertTrue(ms < 600, "answered in " + ms + " ms");
        Span own = Trace.decode(r.headers().firstValue(Trace.HEADER).orElseThrow()).spans().get(0);
        assertTrue(own.detail().contains("crunching"), own.detail());
    }

    @Test
    void theClientRetriesServerErrorsButNotClientErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(503);
        URI u = serve(DeadlineHandler.builder("down", ctx -> {
            calls.incrementAndGet();
            return new DeadlineHandler.Reply(status.get(), "");
        }).build());
        DeadlineClient c = new DeadlineClient("down", u, CallPolicy.of(1000, 3), http);
        DeadlineClient.Response r = c.call(RequestContext.root("up", Clock.system(), 5000), "/");
        assertEquals(503, r.status());
        assertEquals(3, calls.get());
        status.set(404);
        calls.set(0);
        r = c.call(RequestContext.root("up", Clock.system(), 5000), "/");
        assertEquals(404, r.status());
        assertEquals(1, calls.get());
    }

    @Test
    void theCalleeIsToldTheBudgetAndForwardedHeaders() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        URI u = serve(DeadlineHandler.builder("down", ctx -> {
            got.add(ctx.budgetMs() + " " + ctx.forward().get("X-Request-Id"));
            return DeadlineHandler.Reply.ok("");
        }).forward(Set.of("X-Request-Id")).build());
        DeadlineClient c = new DeadlineClient("down", u, CallPolicy.of(300, 1), http);
        RequestContext ctx = new RequestContext("up", Clock.system(), Deadline.afterMillis(5000, Clock.system()), 5000,
                Map.of("X-Request-Id", "abc"));
        assertTrue(c.call(ctx, "/").ok());
        assertEquals(List.of("280 abc"), got, "the timeout was the limit, less the 20 ms margin");

        got.clear();
        RequestContext tight = RequestContext.root("up", Clock.system(), 150);
        c.call(tight, "/");
        String[] f = got.get(0).split(" ");
        long budget = Long.parseLong(f[0]);
        assertTrue(budget <= 130 && budget > 80, "the deadline was the limit: " + budget);
    }

    @Test
    void anAttemptThatCannotFinishIsNotSentAndSaysWhy() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URI u = serve(DeadlineHandler.builder("down", ctx -> {
            calls.incrementAndGet();
            return DeadlineHandler.Reply.ok("");
        }).build());
        DeadlineClient c = new DeadlineClient("down", u, CallPolicy.of(1000, 3), http);
        RequestContext ctx = RequestContext.root("up", Clock.system(), 40);
        DeadlineClient.Response r = c.call(ctx, "/");
        assertEquals(504, r.status());
        assertEquals(0, calls.get());
        Span s = ctx.trace().spans().get(0);
        assertEquals("up>down", s.path());
        assertEquals("not_started", s.outcome());
        assertTrue(s.detail().endsWith("needs 70"), s.detail());
    }

    @Test
    void theCalleesTraceIsMergedUnderTheAttempt() throws Exception {
        URI inner = serve(DeadlineHandler.builder("inner", ctx -> {
            Thread.sleep(20);
            return DeadlineHandler.Reply.ok("");
        }).build());
        DeadlineClient toInner = new DeadlineClient("inner", inner, CallPolicy.of(1000, 1), http);
        URI outer = serve(DeadlineHandler.builder("outer", ctx -> {
            toInner.call(ctx, "/");
            return DeadlineHandler.Reply.ok("");
        }).build());
        DeadlineClient c = new DeadlineClient("outer", outer, CallPolicy.of(1000, 1), http);
        RequestContext root = RequestContext.root("edge", Clock.system(), 2000);
        assertTrue(c.call(root, "/").ok());
        List<String> paths = root.trace().spans().stream().map(s -> s.path() + "#" + s.attempt()).toList();
        assertEquals(List.of("edge>outer#1", "edge>outer>inner#1"), paths);
        Span in = root.trace().spans().get(1);
        assertTrue(in.endMs() - in.startMs() >= 20);
    }

    /** Regression: work finished after the caller hung up was never reported. */
    @Test
    void theListenerHearsAboutWorkWhoseCallerHungUp() throws Exception {
        CountDownLatch heard = new CountDownLatch(1);
        AtomicReference<Span> own = new AtomicReference<>();
        URI u = serve(DeadlineHandler.builder("slow", ctx -> {
            Thread.sleep(400);
            return DeadlineHandler.Reply.ok("x".repeat(100_000));
        }).enforce(false).listener((ctx, s) -> {
            own.set(s);
            heard.countDown();
        }).build());
        DeadlineClient c = new DeadlineClient("slow", u, CallPolicy.of(100, 1).withPropagate(false), http);
        DeadlineClient.Response r = c.call(RequestContext.root("up", Clock.system(), -1), "/");
        assertEquals(504, r.status());
        assertTrue(heard.await(5, TimeUnit.SECONDS), "listener ran");
        assertTrue(own.get().endMs() >= 400);
        assertFalse(own.get().outcome().isEmpty());
    }
}
