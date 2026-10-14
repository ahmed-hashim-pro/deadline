package io.github.ahmedhashim.deadline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Calls one downstream service on behalf of a request. Each attempt gets the
 * smaller of the configured timeout and what is left of the request's budget;
 * the callee is told that budget; an attempt that could not finish in time is
 * not sent at all; and the callee's trace is merged into the request's.
 */
public final class DeadlineClient {

    /** Called after every attempt, including the ones not sent. Times are wall-clock ms. */
    @FunctionalInterface
    public interface Listener {
        void attempt(RequestContext ctx, String target, int attempt, long startEpochMs, long endEpochMs,
                     String outcome);
    }

    public record Response(int status, String body, int attempts) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private final String target;
    private final URI base;
    private final CallPolicy policy;
    private final HttpClient http;
    private final Listener listener;

    public DeadlineClient(String target, URI base, CallPolicy policy, HttpClient http, Listener listener) {
        this.target = target;
        this.base = base;
        this.policy = policy;
        this.http = http;
        this.listener = listener;
    }

    public DeadlineClient(String target, URI base, CallPolicy policy, HttpClient http) {
        this(target, base, policy, http, null);
    }

    public CallPolicy policy() {
        return policy;
    }

    public Response call(RequestContext ctx, String path) throws InterruptedException {
        int status = 504;
        String body = "";
        String spanPath = ctx.name() + ">" + target;
        int attempt = 0;
        while (attempt < policy.maxAttempts()) {
            attempt++;
            Plan plan = Plan.next(policy, ctx.deadline());
            long startMs = ctx.elapsedMs();
            long startEpoch = System.currentTimeMillis();
            if (plan instanceof Plan.Skip skip) {
                ctx.trace().add(new Span(spanPath, attempt, startMs, startMs, 0, "not_started",
                        skip.remainingMs() + " ms left, needs " + skip.neededMs()));
                notify(ctx, attempt, startEpoch, "not_started");
                return new Response(504, "deadline: attempt " + attempt + " not started", attempt - 1);
            }
            Plan.Send send = (Plan.Send) plan;
            HttpRequest.Builder rb = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofMillis(Math.max(1, send.waitMs()))).GET();
            if (send.budgetMs() >= 0) {
                rb.header(Budget.HEADER, Long.toString(send.budgetMs()));
            }
            for (Map.Entry<String, String> h : ctx.forward().entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
            long sentNanos = ctx.clock().nanos();
            String outcome;
            try {
                HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                status = r.statusCode();
                body = r.body();
                outcome = status / 100 == 2 ? "ok" : status == 504 ? "deadline_exceeded" : "error_" + status;
                Trace child = Trace.decode(r.headers().firstValue(Trace.HEADER).orElse(null));
                if (child.spans().isEmpty()) {
                    ctx.trace().add(new Span(spanPath, attempt, startMs, ctx.elapsedMs(), 0, outcome, ""));
                } else {
                    long offset = (sentNanos - ctx.startNanos()) / 1_000_000L;
                    ctx.trace().merge(child, offset, ctx.name(), attempt);
                }
            } catch (HttpTimeoutException e) {
                status = 504;
                outcome = "timeout";
                ctx.trace().add(new Span(spanPath, attempt, startMs, ctx.elapsedMs(), 0, outcome,
                        "stopped waiting after " + send.waitMs() + " ms"));
            } catch (IOException e) {
                status = 502;
                outcome = "error_502";
                ctx.trace().add(new Span(spanPath, attempt, startMs, ctx.elapsedMs(), 0, outcome,
                        String.valueOf(e.getMessage())));
            }
            notify(ctx, attempt, startEpoch, outcome);
            if (status / 100 == 2 || status / 100 == 4) {
                return new Response(status, body, attempt);
            }
        }
        return new Response(status, body, attempt);
    }

    private void notify(RequestContext ctx, int attempt, long startEpoch, String outcome) {
        if (listener != null) {
            listener.attempt(ctx, target, attempt, startEpoch, System.currentTimeMillis(), outcome);
        }
    }
}
