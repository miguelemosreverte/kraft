# Kraft Event Search API

*High-Performance Event Search*

<!-- @meta:title="Kraft Event Search API - Documentation" -->

---

## Performance Highlights

| Metric | Value |
|--------|-------|
| **Max Throughput** | <!-- @metric:tag="v5-response-cache" format="short" --> RPS |
| **P99 Latency** | <!-- @metric:tag="v4-observability" field="p99_latency_us" --> µs |
| **Improvement** | <!-- @metric:tag="v5-response-cache" field="improvement_percent" -->% over Gin baseline |

---

## Documentation

### [Application Book](app-book.html)

Clean architecture and domain-driven design. Covers domain modeling, use cases, ports & adapters, and the dependency rule.

### [Server Library](server-book.html)

The journey to 500K+ RPS. Covers io_uring, load shedding, adaptive limits, scaling signals, and benchmark data.

### [BookGen](bookgen-book.html)

Living documentation generator. Embed metrics, charts, and diagrams directly in Markdown with real benchmark data.

### [DSL Book](dsl-book.html)

HTTP DSL implementation. Covers the domain-specific language for defining routes, handlers, and middleware.

### [Durable Execution Book](durable-book.html)

Durable workflows with cluster support. Covers journaling, replay, SWIM gossip protocol, consistent hashing, and failover.

---

*Kraft Durable Workflows - Clean Architecture*
