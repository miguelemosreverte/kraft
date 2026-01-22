# KraftServer: The Performance Journey

*Building a high-performance HTTP server with io_uring, load shedding, and adaptive scaling.*

<!-- @meta:title="KraftServer - High-Performance HTTP Server" -->
<!-- @meta:author="The Performance Team" -->
<!-- @meta:generated="true" -->

---

For application architecture details (domain modeling, clean architecture, use cases), see the [Application Book](app-book.html).

---

## Preface: The Challenge

This document originates from a technical interview challenge: **build a microservice that integrates event plans from an external provider into Kraft's marketplace**.

The constraints are real-world:
- External provider API may be slow, unreliable, or completely down
- Response time must be in **hundreds of milliseconds**, regardless of provider state
- Historical events must be preserved even after they disappear from the provider
- The system should handle **5k-10k requests per second**

### The Real Challenge

Unlike typical CRUD APIs where data is local, we depend on an external XML feed. The naive approach—fetch from provider on every request—fails immediately:

- Provider latency becomes our latency
- Provider downtime becomes our downtime
- No historical data when events disappear

**The solution: decouple fetching from serving.**

We poll the provider in the background, store everything persistently, and serve from our cache. The external provider becomes a data source, not a dependency.

### A Clean History

This project follows disciplined continuous integration:

- **Each chapter is a commit** with measurable improvements
- **Benchmarks prove every optimization** before merging
- **The document evolves** as the system improves

---

## Prologue: The Goal

We set out to build a resilient, high-performance event search API. The rules:

1. Serve events filtered by date range (`starts_at`, `ends_at`)
2. Only return events with `sell_mode: "online"`
3. Preserve historical events
4. Respond in hundreds of milliseconds, always
5. Handle high traffic (5k-10k RPS target)

<!-- @git:show="branches" -->

This is the story of how we got there.

---

## Chapter 1: The Baseline

*"You have to know where you are to know how far you've come."*

The Go version started with [Gin](https://github.com/gin-gonic/gin), one of Go's most popular web frameworks. For Scala, we use Netty with a custom http4s-inspired DSL:

```scala
val routes = HttpRoutes(
  GET("/search") { req =>
    val startsAt = req.params.getAs[LocalDate]("starts_at")
    val endsAt = req.params.getAs[LocalDate]("ends_at")

    val events = store.search(startsAt, endsAt)
    Ok(Json.obj("data" -> Json.obj("events" -> events), "error" -> JNull))
  }
)

HttpServer(routes).start(8080)
```

Simple. Readable. A perfect baseline.

### The Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Netty HTTP Server                       │
│                                                          │
│   GET /search ──────▶ Handler ──────▶ Store ──────▶ JSON │
│                                                          │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                  Background Poller                       │
│                                                          │
│   Ticker ──────▶ Fetch XML ──────▶ Parse ──────▶ Store  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Key Design Decisions

1. **sync.Map for storage** — Lock-free reads for high concurrency
2. **Background polling** — Decouples provider latency from request latency
3. **UUID generation** — Deterministic IDs from base_plan_id + plan_id
4. **Historical preservation** — Events are never deleted, only updated

**Result: <!-- @metric:tag="v1-gin-baseline" --> requests/second**

This became our baseline—already exceeding the 5-10K RPS target by an order of magnitude. But we're just getting started.

---

## Chapter 2: The Big Leap

*"Sometimes the biggest gains come from changing foundations."*

Gin is great for developer productivity, but it's built on `net/http`. For raw performance, we need something closer to the metal: [FastHTTP](https://github.com/valyala/fasthttp).

FastHTTP is designed from the ground up for speed:
- **Zero-allocation** request/response handling
- **Worker pool** instead of goroutine-per-request
- **Optimized HTTP parser** written in pure Go

### Why FastHTTP is Faster

1. **Object pooling** — Request/response objects are reused, not allocated
2. **No reflection** — Direct byte manipulation instead of interface{}
3. **Optimized routing** — Simple switch vs regex matching
4. **Buffer reuse** — Writes go to pooled buffers

**Result: <!-- @metric:tag="v2-fasthttp" --> requests/second**

This is the power of choosing the right foundation. FastHTTP removes the HTTP layer as a bottleneck.

---

## Chapter 3: Into the Kernel

*"The fastest code is the code that doesn't run."*

FastHTTP gave us significant gains, but we're still bound by the fundamental model: userspace makes syscalls, kernel does I/O, kernel returns to userspace. Each syscall has overhead—context switches, kernel mode transitions, scheduling decisions.

What if we could skip most of that?

### Enter io_uring

[io_uring](https://kernel.dk/io_uring.pdf) is a Linux kernel feature (5.1+) that revolutionizes I/O:

- **Submission queue** — Userspace writes I/O requests to shared memory
- **Completion queue** — Kernel writes results to shared memory
- **No syscalls per I/O** — Batched submissions, polled completions
- **Zero-copy** — Data moves directly between socket and userspace buffers

```
Traditional I/O:                    io_uring:

User ──syscall──▶ Kernel           User ──write──▶ SQ (shared memory)
     ◀─return───                        ◀─read───  CQ (shared memory)
User ──syscall──▶ Kernel                          (kernel polls SQ)
     ◀─return───                                  (kernel writes CQ)
     ...
```

### The Implementation

We use [Netty's io_uring transport](https://github.com/netty/netty-incubator-transport-io_uring), which provides the same kernel-bypass benefits:

```scala
// Transport auto-detection: io_uring → Epoll → NIO
private def loadTransport(): (Class[_ <: EventLoopGroup], Class[_ <: ServerChannel]) =
  try
    val ioUringGroup = Class.forName("io.netty.incubator.channel.uring.IOUringEventLoopGroup")
    val ioUringChannel = Class.forName("io.netty.incubator.channel.uring.IOUringServerSocketChannel")
    if IOUring.isAvailable then (ioUringGroup, ioUringChannel)
    else fallbackToNIO()
  catch case _: Throwable => fallbackToNIO()

// Zero-copy response writing
private def buildResponse(response: Response): FullHttpResponse =
  val content = Unpooled.wrappedBuffer(response.body)  // No copy!
  new DefaultFullHttpResponse(HTTP_1_1, OK, content)
```

Key optimizations:
1. **Pre-built responses** — Health check and common errors are compiled once
2. **Zero-copy parsing** — Extract method/path without allocating strings
3. **Direct buffer writes** — Response bytes go straight to kernel buffers

**Result: <!-- @metric:tag="v3-iouring" --> requests/second**

This is what happens when you eliminate the syscall overhead.

---

## Chapter 4: The Real World

*"Synthetic benchmarks lie. Real traffic tells the truth."*

Standard benchmarks use HTTP keep-alive—one TCP connection handles many requests. But real users often create new connections. Mobile apps, browser tabs, API clients—each may open fresh connections.

```
BENCHMARK:              REAL USERS:
Request 1 ──▶           [New TCP] Request 1 ──▶
         ◀── Response            ◀── Response [Close]
Request 2 ──▶           [New TCP] Request 2 ──▶
         ◀── Response            ◀── Response [Close]
(same connection)       (new connection each time)
```

### Stress Testing

We created `scripts/stress_test.sh` to measure real-world performance:

```bash
# Test with keep-alive (synthetic)
wrk -t4 -c500 -d30s http://localhost:8080/search

# Test without keep-alive (real-world)
wrk -t4 -c500 -d30s -H "Connection: close" http://localhost:8080/search
```

**Key insight**: io_uring handles connection churn dramatically better than traditional servers.

---

## Chapter 5: Observability Without Sacrifice

*"You can't improve what you can't measure—but measurement shouldn't slow you down."*

High-performance systems need monitoring. But every metric collected is CPU time stolen from request handling.

### The Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   io_uring Server                        │
│  ┌─────────────────────┐    ┌───────────────────────┐   │
│  │   Main Server :8080 │    │  Metrics Server :9090 │   │
│  │   (io_uring)        │    │  (standard net/http)  │   │
│  │   Hot path!         │    │  /metrics endpoint    │   │
│  └─────────────────────┘    └───────────────────────┘   │
│           │                            │                 │
│     atomic.Uint64 ◀────────────────────┘                 │
│     (lock-free counters)                                 │
└─────────────────────────────────────────────────────────┘
```

Key decisions:
- **Separate port**: Metrics don't impact the hot path
- **Atomic counters**: Lock-free updates for thread-safe metrics
- **No external dependencies**: Pure Go, Prometheus-compatible format

### Metrics Available

```bash
# Request metrics
http_requests_total
http_requests_per_second

# Connection metrics
http_connections_total
http_connections_active

# Scaling signals
http_scaling_connection_limit
http_scaling_rejection_rate
http_scaling_needs_scaleout
```

The overhead is **<1%** — observability without sacrifice.

---

## Chapter 6: Load Shedding at the Kernel

*"The best way to handle excess load is to never let it reach your application."*

We know from stress testing that performance degrades at high connection counts. The solution: **reject excess connections at the kernel level** before they consume application resources.

### The iptables Rule

```bash
# Limit connections to 500 on port 8080
iptables -A INPUT -p tcp --syn --dport 8080 \
    -m connlimit --connlimit-above 500 --connlimit-mask 0 \
    -j REJECT --reject-with tcp-reset
```

```
┌─────────────────────────────────────────────────────────┐
│                     KERNEL LEVEL                         │
│                                                          │
│   Client SYN ──────► connlimit check ───► Allow (≤500)  │
│                           │                              │
│                           └───► Reject with RST (>500)  │
│                                                          │
│   Server stays at optimal throughput regardless of load  │
└─────────────────────────────────────────────────────────┘
```

Benefits:
- **Zero-cost rejection**: Kernel handles it at TCP level
- **No application changes**: Server code unchanged
- **Instant protection**: Works immediately on SYN packets

### Two Types of Benchmarks: Throughput vs. Stress Testing

Before we discuss load shedding, it's important to understand that we measure performance in two fundamentally different ways:

**1. Internal Throughput Benchmarks** (wrk running *inside* Docker)
- Measures raw implementation speed with zero network overhead
- Shows the true capability of each optimization: ~550-640K RPS with io_uring
- This is what the performance progression chart (Chapter 1-5) demonstrates

**2. External Stress Testing** (wrk running *outside* Docker)
- Simulates real-world traffic arriving from the network
- Required for testing kernel-level protections like `iptables`
- Lower absolute numbers (~85-110K RPS) due to Docker networking overhead
- But reveals **sustained behavior under overload**—which is what matters in production

Why the difference? The `iptables` connlimit rule operates on the Linux kernel's INPUT chain, which only processes **external traffic**. Localhost connections bypass this chain entirely. So to demonstrate kernel-level load shedding, we must test from outside the container.

### Why Not Application-Level Load Shedding?

A common approach is to reject excess requests at the application level (returning HTTP 503). But this has a critical flaw: **by the time you return 503, you've already:**

1. Accepted the TCP connection
2. Parsed the HTTP request
3. Allocated memory for the request context
4. Consumed CPU cycles

Under sustained load, these costs accumulate. The server gradually exhausts resources, and eventually collapses completely.

<!-- @chart:query="SELECT elapsed_seconds, observed_rps as value, test_type as series FROM stress_test_series ORDER BY series, elapsed_seconds" x="elapsed_seconds" y="value" series="series" datasets='{"no_protection": {"label": "No Protection", "borderColor": "#e74c3c", "borderWidth": 3}, "app_level": {"label": "App-Level (HTTP 503)", "borderColor": "#f39c12", "borderWidth": 3}, "kernel_level": {"label": "Kernel-Level (iptables)", "borderColor": "#27ae60", "borderWidth": 3}}' options='{"scales": {"y": {"title": {"display": true, "text": "Requests per Second (External Traffic)"}}, "x": {"title": {"display": true, "text": "Elapsed Time (seconds)"}}}}' title="Stress Test: Sustained Throughput Under Escalating External Load" -->

The chart above shows **external stress test** results—what happens when real-world traffic overwhelms the server:

1. **No Protection** (red): Server accepts all connections → **collapses to 0 RPS** at ~1200 connections
2. **Application-Level** (orange): HTTP 503 rejection → **still collapses** because resources were wasted before rejection
3. **Kernel-Level** (green): `iptables` rejects at TCP layer → **stable ~85-110K RPS** even at 2000 connections

**Key Insight**: The absolute RPS is lower than internal benchmarks because traffic traverses Docker networking. But the *pattern* is what matters: kernel-level protection maintains **sustained throughput** while other approaches collapse under the same load.

### Putting It Together

| Benchmark Type | What It Measures | io_uring Result |
|----------------|------------------|-----------------|
| **Internal throughput** | Raw implementation speed | ~550K RPS |
| **External stress test** | Sustained behavior under load | ~85-110K RPS (stable with iptables) |

Both numbers are important:
- **550K RPS** proves we built a high-performance server
- **Stable under stress** proves it won't collapse in production

The internal benchmark shows we achieved our performance goal. The stress test shows we can *sustain* it when real traffic arrives from the network—thanks to kernel-level load shedding

### Reproducing the Results

Three experiment branches implement different load shedding strategies:

| Branch | Strategy | Implementation |
|--------|----------|----------------|
| `experiment/load-shedding-none` | No protection | Server accepts all connections |
| `experiment/load-shedding-app-level` | HTTP 503 | `MaxConnections=500` in `OnOpen()` |
| `experiment/load-shedding-kernel` | iptables | `connlimit` rejects at TCP layer |

To reproduce: `make bench-all` (see Makefile for details).

---

## Chapter 7: Adaptive Limits

*"The optimal limit depends on your hardware. Let the system discover it."*

A fixed connection limit of 500 works, but what if your hardware can handle more? What if network conditions change? The optimal limit varies by machine, load pattern, and time of day.

### The Feedback Loop

```
┌─────────────────────────────────────────────────────────┐
│                 ADAPTIVE FEEDBACK LOOP                   │
│                                                          │
│  Prometheus ───► Adaptive ───► iptables ───► Server     │
│  Metrics        Limiter       connlimit                  │
│      ▲                                          │        │
│      └──────────────────────────────────────────┘        │
│                    (observe RPS)                         │
└─────────────────────────────────────────────────────────┘
```

The adaptive limiter reads throughput from Prometheus, adjusts the `iptables connlimit` rule, and observes the result. Over time, it converges to the optimal connection limit for the current conditions.

### The Algorithm

```
1. START with initial limit (e.g., 800 connections)
2. MONITOR RPS every 5 seconds via Prometheus
3. IF RPS drops > 10% from peak → DECREASE limit by 100
4. IF RPS stable for 3 periods → INCREASE limit by 100
5. REMEMBER historical performance at each limit
6. REPEAT → Converges to optimal limit
```

### Adaptive Limiter in Action

The chart below shows the adaptive limiter finding the optimal connection limit, with throughput and limit changes correlated:

<!-- @chart:query="SELECT elapsed_seconds, observed_rps as value, 'throughput' as series FROM adaptive_limiter_series UNION ALL SELECT elapsed_seconds, connection_limit as value, 'limit' as series FROM adaptive_limiter_series ORDER BY elapsed_seconds" x="elapsed_seconds" y="value" series="series" datasets='{"throughput": {"label": "Requests/sec", "borderColor": "#3498db", "borderWidth": 2, "fill": false, "yAxisID": "y"}, "limit": {"label": "Connection Limit", "borderColor": "#9b59b6", "borderWidth": 2, "fill": false, "yAxisID": "y1"}}' options='{"scales": {"y": {"type": "linear", "position": "left", "title": {"display": true, "text": "Requests per Second"}}, "y1": {"type": "linear", "position": "right", "title": {"display": true, "text": "Connection Limit"}, "grid": {"drawOnChartArea": false}}, "x": {"title": {"display": true, "text": "Elapsed Time (seconds)"}}}}' title="Throughput vs Connection Limit (Adaptive)" -->

**What the chart shows:**

- **Blue line (left axis)**: RPS fluctuates as the limiter experiments
- **Purple line (right axis)**: `iptables connlimit` adjusts up and down

The limiter starts at 800, increases while throughput improves, and backs off when performance degrades.

### Why Adaptive?

| Fixed Limit | Adaptive Limit |
|-------------|----------------|
| May leave performance on the table | Discovers optimal for your hardware |
| Can't respond to changing conditions | Adjusts to load patterns |
| One size fits none | Self-tuning |

The adaptive limiter runs as a sidecar process, reading metrics and updating iptables rules. The server code remains unchanged—all tuning happens at the kernel level.

To reproduce: `make adaptive-bench` (see Makefile for details).

---

## Chapter 8: Signals for Scaling

*"Once you've maximized a single node, the next step is horizontal scaling."*

With connection capping, excess connections are rejected at the kernel level. The server stays healthy, but users are being turned away. How do we signal that we need more capacity?

### Scaling Metrics

Our Prometheus endpoint exposes three key metrics for scaling decisions:

| Metric | Description | Scaling Use |
|--------|-------------|-------------|
| `http_scaling_rejection_rate` | Rejections/sec | Alert if increasing |
| `http_scaling_needs_scaleout` | 1 if rejecting | Boolean trigger |
| `http_scaling_saturation_ratio` | active/limit % | HPA threshold |

### Scaling Signals Under Gradual Load

The chart below shows how throughput changes as active connections increase from 100 to 800 against a 500-connection limit:

<!-- @chart:query="SELECT active_connections, observed_rps as value, 'throughput' as series FROM scaling_signals_series GROUP BY active_connections UNION ALL SELECT active_connections, saturation_ratio * 100 as value, 'saturation' as series FROM scaling_signals_series GROUP BY active_connections ORDER BY active_connections" x="active_connections" y="value" series="series" datasets='{"throughput": {"label": "Requests/sec", "borderColor": "#3498db", "borderWidth": 2, "fill": false, "yAxisID": "y"}, "saturation": {"label": "Saturation %", "borderColor": "#e74c3c", "borderWidth": 2, "fill": false, "yAxisID": "y1"}}' options='{"scales": {"y": {"type": "linear", "position": "left", "title": {"display": true, "text": "Requests per Second"}}, "y1": {"type": "linear", "position": "right", "title": {"display": true, "text": "Saturation %"}, "grid": {"drawOnChartArea": false}}, "x": {"title": {"display": true, "text": "Active Connections"}}}}' title="Throughput vs Connections (Scaling Signals)" -->

**Interpreting the signals:**

1. **Saturation < 80%**: Healthy headroom—no action needed
2. **Saturation > 80%**: Approaching capacity—prepare to scale
3. **Saturation = 100%**: At capacity—scale out now (throughput plateaus)

These metrics integrate directly with Kubernetes HPA (Horizontal Pod Autoscaler) or any monitoring/alerting system.

To reproduce: `make scaling-bench` (see Makefile for details).

---

## Bonus Chapter: Response Caching

*"The fastest response is the one already built."*

With the networking stack fully optimized, we can squeeze out additional performance at the application layer. On every request we:

1. **sync.Map iteration** — Scanning all events
2. **Sorting** — Ordering results by date
3. **Conversion** — StoredEvent → API Event
4. **JSON marshaling** — Building the response body

For our most common request (`GET /search` with no parameters), this work produces the same result every time—until the data changes.

### Version-Based Cache Invalidation

We add a version counter to the store:

```scala
class EventRepository:
  private val events = ConcurrentHashMap[String, StoredEvent]()
  private val versionCounter = AtomicLong(0)

  def version: Long = versionCounter.get()

  def upsert(event: StoredEvent): Unit =
    events.put(event.id, event)
    versionCounter.incrementAndGet()
```

The server caches the pre-built HTTP response and only rebuilds when the version changes:

```scala
private val cachedVersion = AtomicLong(0)
private val cachedResponse = AtomicReference[Response](null)

def getCachedResponse(service: Service): Response =
  if cachedVersion.get() == service.version then
    Option(cachedResponse.get()).getOrElse(rebuildCache(service))
  else rebuildCache(service)
```

**Result: <!-- @metric:tag="v5-response-cache" --> requests/second**

This optimization is particularly effective because our external provider polling model means data changes infrequently (every 5 minutes), while requests are constant.

---

## Epilogue: The Full Journey

<!-- @table:query="SELECT tag as Version, technique as Technique, baseline_rps as RPS, COALESCE(printf('+%.1f%%', improvement_percent), 'Baseline') as Improvement FROM versions ORDER BY chapter_number" title="Performance Evolution" -->

<!-- @chart:query="SELECT technique, baseline_rps FROM versions ORDER BY chapter_number" type="bar" title="The Performance Journey" options='{"indexAxis": "y", "plugins": {"legend": {"display": false}}}' -->

### What We Learned

1. **Framework choice matters** — Gin→FastHTTP gave significant gains
2. **io_uring is transformational** — Eliminates syscall overhead
3. **Platform matters** — Linux outperforms macOS for I/O
4. **Test real patterns** — Keep-alive hides connection overhead
5. **Throughput stability > peak** — Consistent performance matters
6. **Collaborate with the OS** — iptables load shedding keeps throughput stable
7. **Let the system self-tune** — Adaptive limits discover optimal configurations
8. **Provide signals, not solutions** — The server's job ends at exposing metrics

---

*This document was generated from hand-written narrative combined with live benchmark data.*

---

## Further Reading

- **[HTTP DSL Book](dsl-book.html)** — Complete guide to the type-safe routing DSL
- **[Application Book](app-book.html)** — Clean architecture and domain-driven design
- **[BookGen](bookgen-book.html)** — Living documentation generator

<!-- @footer:generated_at -->
