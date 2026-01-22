# Event Search Application

*Vertical slices architecture for a high-performance event search API — Scala Edition.*

<!-- @meta:title="Kraft Event Search - Application Architecture" -->

---

## Quick Start

```bash
make run                    # Start server on :8080
make test                   # Run tests
curl "localhost:8080/search?starts_at=2024-01-01T00:00:00&ends_at=2024-12-31T23:59:59"
```

---

## Project Structure (Vertical Slices)

```
src/main/scala/kraft/
  main.scala                 — Application entry point (composition root)
  domain/
    Event.scala              — Event entity, business rules
    EventRepository.scala    — EventRepository port (trait)
    EventCodecs.scala        — JSON serialization
  store/
    MemoryStore.scala        — In-memory store adapter
  features/
    search/
      Search.scala           — Handler + Service (complete vertical slice)
    sync/
      Poller.scala           — Provider polling + XML parsing
    metrics/
      MetricsFeature.scala   — Prometheus metrics endpoint
  server/
    HttpServer.scala         — High-performance HTTP server (Netty)
    dsl.scala                — http4s-inspired routing DSL
    Metrics.scala            — Server metrics collection
  bookgen/
    BookGen.scala            — Documentation generator

src/test/scala/kraft/
  SearchSpec.scala           — Search feature tests
  SyncSpec.scala             — Sync feature tests
```

Each feature is self-contained. Tests mirror the main structure.

---

## Chapter 1: System Context

<!-- @c4:level="1" title="System Context" diagram="
    Person(consumer, \"API Consumer\", \"\")
    System(api, \"Event Search API\", \"\")
    System_Ext(provider, \"External Provider\", \"\")

    Rel(consumer, api, \"searches\")
    Rel(api, provider, \"polls\")
" -->

- **API Consumer** — Mobile apps, web clients, internal services
- **Event Search API** — Our system (high throughput, always available)
- **External Provider** — Third-party XML feed (we don't control it)

The provider is external—we decouple from it completely.

---

## Chapter 2: Two Paths

The architecture separates read and write:

**Read Path** (synchronous, fast):
```
API Consumer → Search.routes → Search.Service → EventRepository → Response
```

**Write Path** (background, async):
```
External Provider → Poller → EventRepository (updates store)
```

These paths never block each other. The read path serves from local storage while the write path syncs in the background.

---

## Chapter 3: Feature Slices

### Search Feature (`features/search/Search.scala`)

Complete search functionality in one file—service + handler + caching:

```scala
object Search:
  // Custom decoder for RFC3339 dates
  given QueryDecoder[LocalDateTime] with
    def decode(s: String): Option[LocalDateTime] = parseDateTime(s)

  def routes(repo: EventRepository): HttpRoutes =
    val service = Service(repo)
    val cachedVersion = AtomicLong(0)
    val cachedResponse = AtomicReference[Response](null)

    HttpRoutes(
      // Health check endpoint
      GET("/health") { _ =>
        Ok("""{"status":"healthy"}""")
      },

      // Search with typed parameter extraction
      (GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") withRequest {
        (startsAt, endsAt, req) =>
          validateParams(req.params, startsAt, endsAt) match
            case Some(error) => error
            case None =>
              if startsAt.isEmpty && endsAt.isEmpty then
                getCachedResponse(service, cachedVersion, cachedResponse)
              else
                buildResponse(service.search(startsAt, endsAt))
      }
    )

  // Pure business logic
  class Service(repo: EventRepository):
    def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event] =
      repo.search(startsAt, endsAt)

    def version: Long = repo.version
```

### Sync Feature (`features/sync/Poller.scala`)

Complete synchronization in one file:

```scala
class Poller(
  providerUrl: String,
  repo: EventRepository,
  interval: FiniteDuration = 5.minutes
):
  private val executor = Executors.newSingleThreadScheduledExecutor()

  def start(): Unit =
    executor.scheduleAtFixedRate(
      () => sync(),
      0,
      interval.toSeconds,
      TimeUnit.SECONDS
    )

  private def sync(): Unit =
    Try(fetchAndParse()) match
      case Success(events) =>
        events.foreach(repo.upsert)
        println(s"[Poller] Synced ${events.size} events")
      case Failure(e) =>
        println(s"[Poller] Sync failed: ${e.getMessage}")

  private def fetchAndParse(): Seq[Event] =
    val xml = scala.io.Source.fromURL(providerUrl).mkString
    XmlParser.parseEvents(xml)
```

### Metrics Feature (`features/metrics/MetricsFeature.scala`)

Prometheus metrics as a vertical slice:

```scala
object MetricsFeature:
  def routes(metrics: Metrics): HttpRoutes =
    HttpRoutes(
      GET("/metrics") { _ =>
        OkText(metrics.toPrometheusFormat)
      }
    )
```

---

## Chapter 4: Shared Domain

The `domain/` package contains code used by multiple features.

### Event Entity

```scala
case class Event(
  id: String,
  basePlanId: String,
  planId: String,
  title: String,
  sellMode: SellMode,
  startDateTime: LocalDateTime,
  endDateTime: LocalDateTime,
  minPrice: Option[Double],
  maxPrice: Option[Double]
):
  def isOnline: Boolean = sellMode == SellMode.Online

  def isWithinDateRange(start: Option[LocalDateTime], end: Option[LocalDateTime]): Boolean =
    val afterStart = start.forall(s => !startDateTime.isBefore(s))
    val beforeEnd = end.forall(e => !startDateTime.isAfter(e))
    afterStart && beforeEnd

enum SellMode:
  case Online, Offline
```

### Repository Port

```scala
trait EventRepository:
  def get(id: String): Option[Event]
  def upsert(event: Event): Unit
  def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event]
  def version: Long
  def all: Seq[Event]
```

Business rules live on the entity. The repository is just a port (trait).

---

## Chapter 5: Store Adapters

```scala
// Memory (fast, ephemeral) — Production ready
val store = MemoryStore()

// Usage is identical regardless of implementation
store.upsert(event)
val events = store.search(Some(startDate), Some(endDate))
```

The `MemoryStore` implementation:

```scala
class MemoryStore extends EventRepository:
  private val events = new ConcurrentHashMap[String, Event]()
  private val versionCounter = new AtomicLong(0)

  def upsert(event: Event): Unit =
    events.put(event.id, event)
    versionCounter.incrementAndGet()

  def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event] =
    events.values.asScala
      .filter(_.isOnline)
      .filter(_.isWithinDateRange(startsAt, endsAt))
      .toSeq
      .sortBy(_.startDateTime)

  def version: Long = versionCounter.get()
```

Same interface. Different trade-offs. Swap with one line change.

---

## Chapter 6: Composition Root

The `main.scala` wires everything together:

```scala
@main def main(args: String*): Unit =
  val port = args.headOption.getOrElse("8080").toInt
  val providerUrl = sys.env.getOrElse("PROVIDER_URL", defaultProviderUrl)

  // Shared: Create event store
  val eventStore = MemoryStore()

  // Feature: Sync - Create provider poller
  val poller = Poller(providerUrl, eventStore)

  // Feature: Search - Create routes
  val searchRoutes = Search.routes(eventStore)

  // Feature: Metrics - Create routes
  val metrics = Metrics()
  val metricsRoutes = MetricsFeature.routes(metrics)

  // Compose all routes
  val allRoutes = searchRoutes <+> metricsRoutes

  // Create and start server
  val server = HttpServer(port, allRoutes, metrics)

  // Start background sync
  poller.start()

  // Start HTTP server (blocking)
  server.start()
```

**Key principle**: All construction happens here.
- Features receive shared dependencies through constructors
- They never create their own dependencies
- Easy to test (inject fakes), easy to reconfigure (swap stores)

---

## Chapter 7: The DSL

Routes are defined using an http4s-inspired DSL with typed extraction:

```scala
import kraft.server.dsl.*

val routes = HttpRoutes(
  // Simple route
  GET("/health") { _ => Ok("""{"status":"ok"}""") },

  // Typed query parameters
  (GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") {
    (startsAt, endsAt) => Ok(search(startsAt, endsAt))
  },

  // Typed headers
  (GET / "api") ?> header[String]("Authorization") { auth =>
    auth.map(validate).getOrElse(Unauthorized("Missing token"))
  },

  // JSON body decoding
  (POST / "events") > body[CreateEvent] { event =>
    CreatedJson(event)
  },

  // Custom content types
  GET("/metrics") { _ => OkText(metrics.toPrometheus) },
  GET("/export.csv") { _ => OkCsv(data.toCsv) }
)
```

The DSL provides:
- **Type safety** — Parameters arrive typed, not as strings
- **Composability** — Combine routes with `<+>`
- **Flexibility** — JSON, XML, Protobuf, any content type

For complete DSL documentation with all examples, see the [Server Book DSL Appendix](server-book.html#appendix-the-scala-dsl).

---

## API Reference

### GET /health

Health check endpoint.

**Response:**
```json
{"status":"healthy"}
```

### GET /search

Search for events available for online purchase.

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `starts_at` | RFC3339 DateTime | Filter events starting after this date |
| `ends_at` | RFC3339 DateTime | Filter events ending before this date |

**Response:**
```json
{
  "data": {
    "events": [
      {
        "id": "a1c4-a7f3-...",
        "title": "Concert Name",
        "start_date": "2024-06-15",
        "start_time": "20:00:00",
        "end_date": "2024-06-15",
        "end_time": "23:00:00",
        "min_price": 25.00,
        "max_price": 75.00
      }
    ]
  }
}
```

### GET /metrics

Prometheus-format metrics for monitoring and autoscaling.

**Available Metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| **Request Metrics** | | |
| `http_requests_total` | counter | Total HTTP requests processed |
| `http_requests_by_endpoint{endpoint="..."}` | counter | Requests per endpoint (search, health, metrics) |
| `http_requests_per_second` | gauge | Average RPS since startup |
| **Connection Metrics** | | |
| `http_connections_total` | counter | Total connections opened |
| `http_connections_active` | gauge | Currently active connections |
| **Byte Counters** | | |
| `http_bytes_read_total` | counter | Total bytes read from clients |
| `http_bytes_written_total` | counter | Total bytes written to clients |
| **Cache Metrics** | | |
| `http_cache_hits` | counter | Response cache hits |
| `http_cache_misses` | counter | Response cache misses |
| `http_cache_size` | gauge | Current cache size |
| **Store Metrics** | | |
| `http_store_size` | gauge | Number of events in store |
| **JVM Metrics** | | |
| `jvm_threads_current` | gauge | Current thread count |
| `jvm_memory_heap_used_bytes` | gauge | Heap memory in use |
| `jvm_memory_heap_max_bytes` | gauge | Maximum heap size |
| **Scaling Signals** | | |
| `http_scaling_connection_limit` | gauge | Current connection limit |
| `http_scaling_saturation_ratio` | gauge | active/limit ratio (0.0-1.0) |
| `http_scaling_needs_scaleout` | gauge | 1 if at capacity, 0 otherwise |
| `http_scaling_connection_headroom` | gauge | Remaining connection capacity |
| `http_uptime_seconds` | gauge | Server uptime |

**Example Response:**
```
# HELP http_requests_total Total number of HTTP requests
# TYPE http_requests_total counter
http_requests_total 12345

# HELP http_requests_by_endpoint Requests by endpoint
# TYPE http_requests_by_endpoint counter
http_requests_by_endpoint{endpoint="search"} 10000
http_requests_by_endpoint{endpoint="health"} 2000
http_requests_by_endpoint{endpoint="metrics"} 345

# HELP http_connections_active Currently active connections
# TYPE http_connections_active gauge
http_connections_active 42

# HELP http_scaling_saturation_ratio Connection saturation (active/limit)
# TYPE http_scaling_saturation_ratio gauge
http_scaling_saturation_ratio 0.0420

# HELP http_scaling_needs_scaleout Scale-out signal (1=scale, 0=ok)
# TYPE http_scaling_needs_scaleout gauge
http_scaling_needs_scaleout 0
```

**Kubernetes HPA Integration:**

The scaling metrics integrate directly with Kubernetes Horizontal Pod Autoscaler:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  metrics:
  - type: Pods
    pods:
      metric:
        name: http_scaling_saturation_ratio
      target:
        type: AverageValue
        averageValue: "0.8"  # Scale at 80% saturation
```

For performance benchmarks and scaling strategies, see the [Server Book Scaling Chapter](server-book.html#chapter-8-signals-for-scaling).

---

## Why Vertical Slices?

| Aspect | Benefit |
|--------|---------|
| **Find code** | All search code in `features/search/` |
| **Add feature** | Create new folder, self-contained |
| **Team work** | Teams own slices independently |
| **Testing** | Tests live with the code they test |

### When Vertical Slices Work Best

- Multiple developers work on different features simultaneously
- Features have distinct business logic
- You want independent deployability (future microservices)

### Shared Code Convention

Anything used by 2+ features goes in shared packages:
- `domain/` — Domain entities and ports (traits)
- `store/` — Store adapters
- `server/` — HTTP server infrastructure

---

## Testing

Tests follow the same vertical slice organization:

```scala
class SearchSpec extends AnyFunSuite with Matchers:
  // Service layer tests
  test("Service returns only online events"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online))
    store.upsert(testEvent("2", SellMode.Offline))

    val service = Search.Service(store)
    val events = service.search(None, None)

    events.size shouldBe 1
    events.head.id shouldBe "1"

  // Route layer tests
  test("Search routes return events for /search"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online))

    val routes = Search.routes(store)
    val response = routes(dslRequest("/search"))

    response.get.status.code shouldBe 200
```

Run with: `make test` or `sbt test`

---

---

## Further Reading

- **[Server Book: Performance Journey](server-book.html#chapter-1-the-baseline)** — How we achieved 500K+ RPS
- **[Server Book: Load Shedding](server-book.html#chapter-6-load-shedding-at-the-kernel)** — Kernel-level connection limiting
- **[Server Book: Scaling Signals](server-book.html#chapter-8-signals-for-scaling)** — Prometheus metrics for autoscaling
- **[Server Book: DSL Reference](server-book.html#appendix-the-scala-dsl)** — Complete http4s-inspired DSL documentation
