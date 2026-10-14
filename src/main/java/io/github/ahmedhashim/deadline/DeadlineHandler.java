package io.github.ahmedhashim.deadline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Wraps a service's handler. It reads the caller's budget, waits for a worker
 * slot no longer than the budget allows, runs the handler, turns a
 * {@link DeadlineExceededException} into a 504, and returns the trace of what
 * happened in the {@link Trace#HEADER} response header.
 */
public final class DeadlineHandler implements HttpHandler {

    /** The service's own logic. */
    @FunctionalInterface
    public interface App {
        Reply handle(RequestContext ctx) throws Exception;
    }

    public record Reply(int status, String body) {
        public static Reply ok(String body) {
            return new Reply(200, body);
        }
    }

    /** Called once per request after the reply is sent, with this hop's own span. */
    @FunctionalInterface
    public interface Listener {
        void finished(RequestContext ctx, Span own);
    }

    private final String name;
    private final App app;
    private final boolean enforce;
    private final Slots slots;
    private final Duration reserve;
    private final Clock clock;
    private final Set<String> forward;
    private final Listener listener;

    private DeadlineHandler(Builder b) {
        this.name = b.name;
        this.app = b.app;
        this.enforce = b.enforce;
        this.slots = b.slots;
        this.reserve = b.reserve;
        this.clock = b.clock;
        this.forward = Set.copyOf(b.forward);
        this.listener = b.listener;
    }

    public static Builder builder(String name, App app) {
        return new Builder(name, app);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        OptionalLong received;
        try {
            received = enforce ? Budget.parse(ex.getRequestHeaders().getFirst(Budget.HEADER)) : OptionalLong.empty();
        } catch (IllegalArgumentException e) {
            send(ex, 400, e.getMessage(), "");
            return;
        }
        long budget = received.isPresent() ? Math.max(0, received.getAsLong()) : -1;
        Deadline deadline = received.isEmpty() ? null
                : Deadline.afterMillis(Math.max(0, budget - reserve.toMillis()), clock);
        Map<String, String> fwd = new HashMap<>();
        for (String h : forward) {
            String v = ex.getRequestHeaders().getFirst(h);
            if (v != null) {
                fwd.put(h, v);
            }
        }
        RequestContext ctx = new RequestContext(name, clock, deadline, budget, fwd);

        Reply reply;
        String outcome;
        String detail = "";
        long queued = 0;
        if (deadline != null && budget <= reserve.toMillis()) {
            reply = new Reply(504, "arrived with no budget left");
            outcome = "deadline_exceeded";
            detail = "arrived with " + received.getAsLong() + " ms";
        } else {
            boolean acquired = false;
            try {
                queued = slots.acquire(deadline);
                if (queued < 0) {
                    queued = ctx.elapsedMs();
                    reply = new Reply(504, "budget ran out waiting for a worker");
                    outcome = "deadline_exceeded";
                    detail = "budget ran out in the queue";
                } else {
                    acquired = true;
                    reply = app.handle(ctx);
                    outcome = reply.status() / 100 == 2 ? "ok" : "error_" + reply.status();
                }
            } catch (DeadlineExceededException e) {
                reply = new Reply(504, e.getMessage());
                outcome = "deadline_exceeded";
                detail = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply = new Reply(503, "interrupted");
                outcome = "error_503";
            } catch (Exception e) {
                reply = new Reply(503, String.valueOf(e.getMessage()));
                outcome = "error_503";
                detail = String.valueOf(e.getMessage());
            } finally {
                if (acquired) {
                    slots.release();
                }
            }
        }
        Span own = new Span(name, 0, 0, ctx.elapsedMs(), queued, outcome, detail);
        Trace out = new Trace();
        out.add(own);
        for (Span s : ctx.trace().spans()) {
            out.add(s);
        }
        try {
            send(ex, reply.status(), reply.body(), out.encode());
        } catch (IOException e) {
            // The caller hung up before we answered: the work above was for nobody.
            own = new Span(name, 0, 0, own.endMs(), queued, outcome, "caller had gone: " + e.getMessage());
            ex.close();
        } finally {
            if (listener != null) {
                listener.finished(ctx, own);
            }
        }
    }

    private static void send(HttpExchange ex, int status, String body, String trace) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (!trace.isEmpty()) {
            ex.getResponseHeaders().set(Trace.HEADER, trace);
        }
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
        ex.close();
    }

    public static final class Builder {
        private final String name;
        private final App app;
        private boolean enforce = true;
        private Slots slots = Slots.unlimited();
        private Duration reserve = Duration.ofMillis(5);
        private Clock clock = Clock.system();
        private Set<String> forward = Set.of();
        private Listener listener;

        private Builder(String name, App app) {
            if (name.isEmpty() || name.contains(">")) {
                throw new IllegalArgumentException("bad service name: " + name);
            }
            this.name = name;
            this.app = Objects.requireNonNull(app);
        }

        /** False ignores incoming budgets: the conventional behaviour, for comparison. */
        public Builder enforce(boolean e) {
            this.enforce = e;
            return this;
        }

        public Builder slots(Slots s) {
            this.slots = s;
            return this;
        }

        /** Budget kept back to write the reply. */
        public Builder reserve(Duration d) {
            this.reserve = d;
            return this;
        }

        public Builder clock(Clock c) {
            this.clock = c;
            return this;
        }

        /** Request headers to copy onto downstream calls (request ids, for example). */
        public Builder forward(Set<String> headers) {
            this.forward = headers;
            return this;
        }

        public Builder listener(Listener l) {
            this.listener = l;
            return this;
        }

        public DeadlineHandler build() {
            return new DeadlineHandler(this);
        }
    }
}
