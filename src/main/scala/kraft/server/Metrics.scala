package kraft.server

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import scala.io.Source
import scala.util.{Try, Using}

/**
 * Prometheus-compatible metrics collector for the HTTP server.
 * Matches the Go io_uring server metrics output format.
 */
class Metrics:
  private val startTime = System.currentTimeMillis()

  // Connection tracking
  private val connectionsTotal = AtomicLong(0)
  private val connectionsClosed = AtomicLong(0)

  // Request tracking
  private val requestsTotal = AtomicLong(0)
  private val requestsByEndpoint = new ConcurrentHashMap[String, AtomicLong]()

  // Byte counters
  private val bytesRead = AtomicLong(0)
  private val bytesWritten = AtomicLong(0)

  // Cache metrics (for response cache)
  private val cacheHits = AtomicLong(0)
  private val cacheMisses = AtomicLong(0)
  private val cacheSize = AtomicInteger(0)

  // Store size (set externally)
  private val storeSize = AtomicInteger(0)

  // Scaling configuration
  private var connectionLimit: Int = 1000
  private var saturationThreshold: Double = 0.80

  def connectionOpened(): Unit = connectionsTotal.incrementAndGet()
  def connectionClosed(): Unit = connectionsClosed.incrementAndGet()

  def requestProcessed(): Unit = requestsTotal.incrementAndGet()
  def requestProcessed(endpoint: String): Unit =
    requestsTotal.incrementAndGet()
    requestsByEndpoint.computeIfAbsent(endpoint, _ => AtomicLong(0)).incrementAndGet()

  def addBytesRead(n: Long): Unit = bytesRead.addAndGet(n)
  def addBytesWritten(n: Long): Unit = bytesWritten.addAndGet(n)

  def recordCacheHit(): Unit = cacheHits.incrementAndGet()
  def recordCacheMiss(): Unit = cacheMisses.incrementAndGet()
  def setCacheSize(size: Int): Unit = cacheSize.set(size)
  def setStoreSize(size: Int): Unit = storeSize.set(size)

  def setConnectionLimit(limit: Int): Unit = connectionLimit = limit
  def setSaturationThreshold(threshold: Double): Unit = saturationThreshold = threshold

  def activeConnections: Long = connectionsTotal.get() - connectionsClosed.get()
  def totalRequests: Long = requestsTotal.get()
  def uptimeSeconds: Double = (System.currentTimeMillis() - startTime) / 1000.0

  /**
   * Read limiter metrics from /tmp/scaling_metrics file.
   * This file is written by adaptive_limiter.sh with iptables data.
   * Returns (connectionLimit, rejectionRate, totalRejections)
   */
  private def readLimiterMetrics(): (Int, Int, Int) =
    val file = new File("/tmp/scaling_metrics")
    if !file.exists() then return (0, 0, 0)

    Try {
      Using.resource(Source.fromFile(file)) { source =>
        var connLimit = 0
        var rejRate = 0
        var totalRej = 0

        for line <- source.getLines() do
          val parts = line.trim.split("\\s+")
          if parts.length == 2 then
            val value = Try(parts(1).toInt).getOrElse(0)
            parts(0) match
              case "connection_limit" => connLimit = value
              case "rejection_rate" => rejRate = value
              case "total_rejections" => totalRej = value
              case _ => ()

        (connLimit, rejRate, totalRej)
      }
    }.getOrElse((0, 0, 0))

  /**
   * Generate Prometheus-format metrics output.
   * Format matches the Go io_uring server output.
   */
  def toPrometheusFormat: String =
    val runtime = Runtime.getRuntime
    val memoryBean = ManagementFactory.getMemoryMXBean
    val heapUsage = memoryBean.getHeapMemoryUsage
    val uptime = uptimeSeconds
    val requests = requestsTotal.get()
    val activeConns = activeConnections

    val sb = new StringBuilder

    // Request metrics
    sb.append("# HELP http_requests_total Total number of HTTP requests\n")
    sb.append("# TYPE http_requests_total counter\n")
    sb.append(s"http_requests_total $requests\n")

    // Requests by endpoint
    sb.append("# HELP http_requests_by_endpoint Requests by endpoint\n")
    sb.append("# TYPE http_requests_by_endpoint counter\n")
    val endpoints = List("search", "health", "metrics", "not_found")
    endpoints.foreach { endpoint =>
      val count = Option(requestsByEndpoint.get(endpoint)).map(_.get()).getOrElse(0L)
      sb.append(s"""http_requests_by_endpoint{endpoint="$endpoint"} $count\n""")
    }

    // Byte counters
    sb.append("# HELP http_bytes_read_total Total bytes read from clients\n")
    sb.append("# TYPE http_bytes_read_total counter\n")
    sb.append(s"http_bytes_read_total ${bytesRead.get()}\n")

    sb.append("# HELP http_bytes_written_total Total bytes written to clients\n")
    sb.append("# TYPE http_bytes_written_total counter\n")
    sb.append(s"http_bytes_written_total ${bytesWritten.get()}\n")

    // Connection metrics
    sb.append("# HELP http_connections_total Total connections opened\n")
    sb.append("# TYPE http_connections_total counter\n")
    sb.append(s"http_connections_total ${connectionsTotal.get()}\n")

    sb.append("# HELP http_connections_active Currently active connections\n")
    sb.append("# TYPE http_connections_active gauge\n")
    sb.append(s"http_connections_active $activeConns\n")

    // Cache metrics
    sb.append("# HELP http_cache_hits Total cache hits\n")
    sb.append("# TYPE http_cache_hits counter\n")
    sb.append(s"http_cache_hits ${cacheHits.get()}\n")

    sb.append("# HELP http_cache_misses Total cache misses\n")
    sb.append("# TYPE http_cache_misses counter\n")
    sb.append(s"http_cache_misses ${cacheMisses.get()}\n")

    sb.append("# HELP http_cache_size Current cache size\n")
    sb.append("# TYPE http_cache_size gauge\n")
    sb.append(s"http_cache_size ${cacheSize.get()}\n")

    // Store metrics
    sb.append("# HELP http_store_size Current store size\n")
    sb.append("# TYPE http_store_size gauge\n")
    sb.append(s"http_store_size ${storeSize.get()}\n")

    // Uptime and RPS
    sb.append("# HELP http_uptime_seconds Server uptime in seconds\n")
    sb.append("# TYPE http_uptime_seconds gauge\n")
    sb.append(f"http_uptime_seconds $uptime%.2f\n")

    if uptime > 0 then
      val rps = requests / uptime
      sb.append("# HELP http_requests_per_second Average requests per second\n")
      sb.append("# TYPE http_requests_per_second gauge\n")
      sb.append(f"http_requests_per_second $rps%.2f\n")

    // JVM metrics (equivalent to Go runtime metrics)
    sb.append("# HELP jvm_threads_current Current number of threads\n")
    sb.append("# TYPE jvm_threads_current gauge\n")
    sb.append(s"jvm_threads_current ${Thread.activeCount()}\n")

    sb.append("# HELP jvm_memory_heap_used_bytes Bytes of heap memory in use\n")
    sb.append("# TYPE jvm_memory_heap_used_bytes gauge\n")
    sb.append(s"jvm_memory_heap_used_bytes ${heapUsage.getUsed}\n")

    sb.append("# HELP jvm_memory_heap_max_bytes Maximum heap memory available\n")
    sb.append("# TYPE jvm_memory_heap_max_bytes gauge\n")
    sb.append(s"jvm_memory_heap_max_bytes ${heapUsage.getMax}\n")

    sb.append("# HELP jvm_memory_total_bytes Total memory available to JVM\n")
    sb.append("# TYPE jvm_memory_total_bytes gauge\n")
    sb.append(s"jvm_memory_total_bytes ${runtime.totalMemory()}\n")

    sb.append("# HELP jvm_memory_free_bytes Free memory in JVM\n")
    sb.append("# TYPE jvm_memory_free_bytes gauge\n")
    sb.append(s"jvm_memory_free_bytes ${runtime.freeMemory()}\n")

    // Read limiter metrics from /tmp/scaling_metrics (written by adaptive_limiter.sh)
    val (limiterConnLimit, limiterRejRate, limiterTotalRej) = readLimiterMetrics()
    val effectiveLimit = if limiterConnLimit > 0 then limiterConnLimit else connectionLimit

    // Scaling metrics
    sb.append("# HELP http_scaling_connection_limit Current connection limit\n")
    sb.append("# TYPE http_scaling_connection_limit gauge\n")
    sb.append(s"http_scaling_connection_limit $effectiveLimit\n")

    // Rejection metrics from iptables (via adaptive_limiter.sh)
    sb.append("# HELP http_scaling_rejection_rate Connections rejected per second\n")
    sb.append("# TYPE http_scaling_rejection_rate gauge\n")
    sb.append(s"http_scaling_rejection_rate $limiterRejRate\n")

    sb.append("# HELP http_scaling_total_rejections Total connections rejected\n")
    sb.append("# TYPE http_scaling_total_rejections counter\n")
    sb.append(s"http_scaling_total_rejections $limiterTotalRej\n")

    // Calculate saturation ratio
    val safeActiveConns = if activeConns > 1000000 then 0 else activeConns
    val saturationRatio = if effectiveLimit > 0 then safeActiveConns.toDouble / effectiveLimit else 0.0

    sb.append("# HELP http_scaling_saturation_ratio Connection saturation (active/limit)\n")
    sb.append("# TYPE http_scaling_saturation_ratio gauge\n")
    sb.append(f"http_scaling_saturation_ratio $saturationRatio%.4f\n")

    // Scale-out signal - also trigger if rejections are happening
    val scaleSignal = if saturationRatio >= saturationThreshold || limiterRejRate > 0 then 1 else 0
    sb.append("# HELP http_scaling_needs_scaleout Scale-out signal (1=scale, 0=ok)\n")
    sb.append("# TYPE http_scaling_needs_scaleout gauge\n")
    sb.append(s"http_scaling_needs_scaleout $scaleSignal\n")

    // Connection headroom
    val headroom = effectiveLimit - safeActiveConns.toInt
    sb.append("# HELP http_scaling_connection_headroom Remaining connection capacity\n")
    sb.append("# TYPE http_scaling_connection_headroom gauge\n")
    sb.append(s"http_scaling_connection_headroom $headroom\n")

    sb.toString()

object Metrics:
  def apply(): Metrics = new Metrics()
