package kraft.features.metrics

import kraft.dsl.*  // Use new DSL package directly
import kraft.server.Metrics

/**
 * Metrics feature - Prometheus-compatible observability.
 * This is its own vertical slice following clean architecture.
 */
object MetricsFeature:

  /**
   * Create routes for the metrics feature.
   * Exposes /metrics endpoint with Prometheus-format output.
   */
  def routes(metrics: Metrics): HttpRoutes =
    HttpRoutes(
      GET("/metrics") { _ =>
        Ok(metrics.toPrometheusFormat, "text/plain; charset=utf-8")
      }
    )
