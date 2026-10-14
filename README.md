# deadline

[![CI](https://github.com/ahmed-hashim-pro/deadline/actions/workflows/ci.yml/badge.svg)](https://github.com/ahmed-hashim-pro/deadline/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A small Java 21 library that passes a request's time budget from service to
service. Each hop knows how long the user is still waiting. It stops work
nobody is waiting for, skips retries that couldn't finish, and reports where
the budget went.

Timeouts are usually configured one hop at a time, and each one looks
sensible on its own. The user's app gives up after 2 s. The gateway gives
orders 3.5 s and three attempts, because orders is sometimes slow. Orders
gives inventory 1 s and three attempts, for the same reason. Nobody is
wrong locally. Together, a request the user abandoned at 2 s keeps three
services busy for almost ten seconds. Under load, that abandoned work
crowds out the requests people are still waiting for. gRPC solves this with
deadlines. Plain HTTP services usually have nothing.

```
$ deadline demo
user -> gateway -> orders -> inventory, each a separate JVM. The user waits 2.0 s.
gateway calls orders: 3.5 s timeout, 3 attempts. orders calls inventory: 1.0 s timeout, 3 attempts.
inventory is having a slow day: every call takes 1.5 s.

without deadlines  user gave up at 2.00 s; services kept working until 9.61 s
                   12 calls between services, 9 after the user left; 13.57 s of worker time, 11.07 s after
with deadlines     user got a 504 at 1.86 s; services kept working until 1.86 s
                   3 calls between services, 0 after the user left; 1.81 s of worker time, 0.00 s after

where the budget went, from the Deadline-Trace header the user got back (ms):
hop                          try   start     end  queue  outcome
gateway                        -       0    1854      0  deadline_exceeded (orders ran out of budget)
  orders                       1       0    1839      0  deadline_exceeded (inventory ran out of budget)
    inventory                  1      20     961      0  deadline_exceeded (work stopped after 939 of 1500 ms)
    inventory                  2     966    1818      0  deadline_exceeded (work stopped after 851 of 1500 ms)
    inventory                  3    1826       -      -  not_started (52 ms left, needs 100)
  orders                       2    1849       -      -  not_started (91 ms left, needs 100)
```

The same three services, with the same timeouts and retries, run as
separate JVMs talking HTTP on loopback. The only difference is whether the
budget travels with the request. Without it, the user gives up at 2 s and
nine of the twelve calls between services happen after that, for nobody.
With it, every hop stops when the budget runs out. Retries that couldn't
finish are never sent. The user gets a 504 before giving up, and the 504
says where the time went: each hop's own account, merged on the way back
up.

![Terminal recording: deadline demo runs one slow request through three separate JVMs. Without deadlines the user gives up at 2.00 s and the services keep working until about 9.6 s, with 9 of 12 calls after the user left. With deadlines the user gets a 504 at about 1.9 s, everything has stopped by then, and the trace shows inventory's work cut short twice and the retries that could not finish never started](docs/demo.gif)

![Two timelines of the same slow request, one lane each for gateway, orders and inventory. Without deadlines, the bars turn red at 2 s when the user gives up, and continue: orders is called three times, inventory nine times, the last work ending at about 9.6 s. With deadlines, all three lanes end in blue just before 2 s, when the user gets a 504](docs/timeline.svg)

## Under load

One slow request wastes a few seconds of worker time. Under load, the waste
competes with live requests for the same workers. Here the services take
steady traffic for 20 seconds. Inventory has 8 workers and takes 100 ms per
call, except for 5% of requests, which take 1.5 s. The timeouts are the
demo's. The same 5% of requests are slow in both runs.

```
$ deadline load --rate 30
30 requests/s for 20 s. inventory: 8 workers, 100 ms per call, 5% of calls 1.5 s.
same timeouts as the demo; the user waits 2.0 s.

                    answered      p50      p99   worker time    after user   for no answer   drain
without deadlines      15.2%    142 ms    979 ms       916.6 s       876.9 s         905.7 s  90.2 s
with deadlines         94.5%    129 ms    144 ms       128.8 s         0.0 s          60.5 s   0.0 s

answered       the user got a 2xx within their wait
worker time    total time services spent holding a worker
after user     worker time after the user had their answer or had given up
for no answer  worker time on requests that never answered the user in time
drain          how long services kept working after the last user stopped waiting
```

Without deadlines, every slow request turns into nine 1.5-second inventory
calls. Orders retries three times per gateway attempt, and the gateway
retries orders three times. Inventory's workers fill with retries of
requests whose users have already left. The fast requests queue behind
them, time out, and are retried in turn. Only 15% of users get an answer.
The services spend 877 of their 917 worker-seconds after the user has gone,
and carry on for another 90 seconds after the traffic stops. With
deadlines, a slow request costs about two seconds of inventory time and
then stops, so the fast ones keep flowing.

The gap opens well before saturation. At 8 requests/s, a quarter of that
rate, two runs without deadlines answered 79% and 54%. Retries of the slow
requests come close to filling inventory, so small timing differences tip
it. With deadlines, both runs answered 92%. The 8% they missed are the slow
requests, which no setting of these timeouts can answer in 2 s. Neither
run did any work after users had left.

## Quickstart

You need JDK 21. The Maven wrapper fetches Maven itself.

```bash
git clone https://github.com/ahmed-hashim-pro/deadline.git && cd deadline
./mvnw -q package                         # builds and runs the 49 tests, about half a minute
java -jar target/deadline.jar demo        # the one-request demo above, about 20 s
java -jar target/deadline.jar load        # the load table above, about 3 minutes
```

`demo --svg timeline.svg` also draws the timeline. `load --rate N
--seconds S` changes the traffic, and `--check` exits non-zero if the
README's claims don't hold. CI runs both commands on every push.

With only Docker:

```bash
docker run --rm -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-21 \
  sh -c 'mvn -q package && java -jar target/deadline.jar demo'
```

## Using it

On the server, wrap a handler:

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

DeadlineClient inventory = new DeadlineClient("inventory", URI.create("http://inventory:8080/"),
        CallPolicy.of(1000, 3),                  // the timeout and attempts you'd configure anyway
        HttpClient.newHttpClient());

server.createContext("/", DeadlineHandler.builder("orders", ctx -> {
    for (Item item : items) {
        ctx.check("pricing");                    // throws once nobody is waiting; becomes a 504
        price(item);
    }
    DeadlineClient.Response r = inventory.call(ctx, "/reserve");
    return r.ok() ? Reply.ok("reserved") : new Reply(503, "inventory: " + r.status());
}).slots(new Slots(16)).build());                // 16 workers; queued requests leave when their budget does
```

At the edge, start the clock yourself:

```java
RequestContext ctx = RequestContext.root("web", Clock.system(), 2000);   // the user waits 2 s
DeadlineClient.Response r = gateway.call(ctx, "/checkout");
System.out.print(ctx.trace().render(2000));                               // where it went
```

A request with no `Deadline-Budget-Ms` header has no deadline. Everything
then behaves as it would without the library: configured timeouts, every
retry, no early stops. So services can adopt it one at a time.

## How it works

**The budget travels as milliseconds left, not as a time of day.** A caller
sends `Deadline-Budget-Ms: 1830`. The callee turns that into an instant on
its own monotonic clock the moment the request arrives. Hosts with skewed
clocks still agree, and the cost is that network transit isn't counted,
which the margin below covers. This is what gRPC does with `grpc-timeout`.

**Each attempt gets the smaller of its timeout and what's left.** Before
every attempt, `Plan.next` looks at the policy and the deadline. The caller
waits `min(attemptTimeout, remaining)` and tells the callee that, less a
margin (20 ms by default, 50 ms in the demo), so the callee's answer gets
back inside the wait. An attempt with less than `minUseful + margin` left
is not sent. It couldn't finish, so it would only add load. That's the
`not_started` row in the trace.

**The callee enforces it.** `DeadlineHandler` rejects a request that
arrives with nothing left, without running it. It waits for a worker slot
only as long as the budget allows. It turns `DeadlineExceededException`
from `ctx.check` into a 504. Work stops between checks, so it's
cooperative: a hop that never calls `check` and makes no downstream calls
still runs to the end.

**The trace comes back with the reply.** Each hop returns its spans in a
`Deadline-Trace` response header, with times relative to when it received
the request. The caller shifts them by when it sent the request and nests
them under the attempt, so the edge gets the whole tree without any clock
synchronisation. Because every hop answers before its deadline, the trace
arrives on failures too. A 504 tells you which hop ran out and what it was
doing.

**Without propagation, the same code behaves conventionally.**
`CallPolicy.withPropagate(false)` and `DeadlineHandler.enforce(false)` give
fixed timeouts and blind retries. The demo and the load test switch only
these two flags, so the comparison is like for like.

## How it is tested

- **The decisions, on a virtual clock.** `Plan` is a pure function of the
  policy and a deadline on a `VirtualClock`, so its tests move time by hand.
  They cover the boundaries, the demo's orders hop decision by decision, and
  200,000 random policies against the invariants:
  - the callee's budget is never spent on arrival
  - it never exceeds what's left minus the margin
  - the caller never waits past its own deadline
  - the callee's answer always fits inside the wait
- **The handler and client over real HTTP.** Malformed and expired
  budgets, work cut short, retries on 5xx but not 4xx, forwarded headers,
  attempts not started, and traces merged across two hops.
- **The whole chain, in both modes.** `ClusterTest` runs the demo at half
  speed with all three services in one JVM. It asserts the conventional run
  keeps working long after the user left, with nine inventory calls. It
  asserts the deadline run stops before the user's wait is up, with no work
  after it, and that the user receives inventory's own report.
- **The README's numbers, in CI.** The `scenarios` job runs `demo` and
  `load --check` with three separate JVMs on every push.

Building it turned up three bugs, each now covered by a test:

- **A negative budget was treated as no budget.** `Deadline-Budget-Ms: -20`
  means "already late", but the parser returned -1 for "absent" too, so an
  expired request ran with no deadline at all. A `0` was caught; `-20`
  wasn't.
- **Waste was undercounted.** When a caller had already hung up, writing
  the reply threw, and the handler skipped its listener. So the work the
  demo exists to measure, work finished after the caller left, went
  unrecorded. The first demo showed the conventional run with empty gateway
  and inventory lanes.
- **Nagle ate the margin.** The JDK's built-in HTTP server writes headers
  and body separately and doesn't set `TCP_NODELAY` by default. A small 504
  sat about 40 ms behind delayed ACKs, which is most of a 50 ms margin, so
  the user's 504 arrived just after they gave up. The demo services set
  `sun.net.httpserver.nodelay`.

## What this does not do

- **Stopping is cooperative.** A hop stops at `ctx.check()`, at a
  downstream call, or while queued for a worker slot. A long CPU loop or a
  blocking JDBC call that never checks runs to the end. Nothing interrupts
  threads.
- **It speaks HTTP/1.1 through the JDK only.** `HttpClient` on the way out,
  `com.sun.net.httpserver` on the way in. There's no servlet filter, Spring
  or Netty integration, and no gRPC, which has deadlines already.
- **Retries are immediate.** No backoff, no jitter, no retry budgets or
  hedging. Those reduce retry storms from a different direction, and would
  combine with this.
- **The budget header is trusted.** It's clamped to an hour, but it's not
  an authorization boundary. A caller can always ask for more time than its
  own caller gave it.
- **The demo's "work" is holding a worker.** Each hop sleeps while holding
  a slot, like a request holding a database connection. It's not CPU. The
  numbers come from one machine (12 cores, loopback, other jobs running) and
  show the shape of the effect, not a benchmark of any real system.
- **It's not published to Maven Central.** Build it from source.

## License

MIT
