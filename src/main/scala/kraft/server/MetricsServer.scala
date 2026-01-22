package kraft.server

import com.sun.net.httpserver.{HttpServer as JdkHttpServer, HttpExchange}
import java.net.InetSocketAddress

/**
 * Lightweight metrics server on a dedicated port.
 * Uses JDK HttpServer for minimal overhead - this is just for Prometheus scraping.
 */
class MetricsServer(metrics: Metrics, port: Int = 9090):
  private var server: JdkHttpServer = _

  def start(): MetricsServer =
    server = JdkHttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/metrics", (exchange: HttpExchange) => {
      val response = metrics.toPrometheusFormat.getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
      exchange.sendResponseHeaders(200, response.length)
      val os = exchange.getResponseBody
      os.write(response)
      os.close()
    })
    server.setExecutor(null) // Use default executor
    server.start()
    println(s"[MetricsServer] Started on port $port")
    this

  def stop(): Unit =
    if server != null then
      server.stop(0)
      println("[MetricsServer] Stopped")

object MetricsServer:
  def apply(metrics: Metrics, port: Int = 9090): MetricsServer =
    new MetricsServer(metrics, port)
