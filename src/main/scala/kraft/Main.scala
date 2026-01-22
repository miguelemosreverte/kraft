package kraft

import kraft.server.{HttpServer, MetricsServer}
import kraft.server.dsl.*
import kraft.store.MemoryStore
import kraft.features.search.Search
import kraft.features.sync.Sync

/**
 * Fever Event Search API - Scala Edition
 *
 * A high-performance event search service using:
 * - Netty with io_uring transport (on Linux)
 * - http4s-inspired DSL for expressive routing
 * - Zero-allocation hot paths
 */
@main def main(args: String*): Unit =
  val port = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
  val workers = args.lift(1).flatMap(_.toIntOption).getOrElse(Runtime.getRuntime.availableProcessors() * 4)
  val providerUrl = sys.env.getOrElse("PROVIDER_URL", "https://provider.code-challenge.kraftup.com/api/events")

  println("""
    |╔═══════════════════════════════════════════════════════════════╗
    |║           Fever Event Search API - Scala Edition              ║
    |╠═══════════════════════════════════════════════════════════════╣
    |║  • Netty with io_uring transport (Linux)                      ║
    |║  • http4s-inspired DSL                                        ║
    |║  • Response caching with version tracking                     ║
    |╚═══════════════════════════════════════════════════════════════╝
    |""".stripMargin)

  // Initialize store
  val eventStore = MemoryStore()

  // Start background sync
  val poller = Sync.poller(eventStore)
    .withUrl(providerUrl)
    .withPollInterval(30000)
    .start()

  // Define routes using http4s-like DSL
  // See how expressive this is:
  //   GET("/path") { req => Ok(...) }
  //   req.params.get("name")
  //   req.params.getAs[Int]("id")
  //   req.as[MyType]
  val routes = Search.routes(eventStore)

  // Start server with DSL routes
  val server = HttpServer(routes, workers).start(port)

  // Start dedicated metrics server on port 9090 (for Prometheus scraping)
  val metricsServer = MetricsServer(server.metrics, 9090).start()

  println(s"""
    |  Port:     $port
    |  Metrics:  9090
    |  Workers:  $workers
    |  Provider: $providerUrl
    |
    |  Endpoints:
    |    GET /health              - Health check
    |    GET /search              - List all online events
    |    GET /search?starts_at=.. - Filter by date range
    |    GET :9090/metrics        - Prometheus metrics
    |
    |  Test with:
    |    curl http://localhost:$port/health
    |    curl http://localhost:$port/search
    |    curl http://localhost:9090/metrics
    |
    |  Benchmark with:
    |    wrk -t$workers -c100 -d10s http://localhost:$port/search
    |""".stripMargin)

  // Graceful shutdown
  Runtime.getRuntime.addShutdownHook(Thread(() =>
    println("\nShutting down...")
    poller.stop()
    metricsServer.stop()
    server.close()
  ))

  server.awaitTermination()
