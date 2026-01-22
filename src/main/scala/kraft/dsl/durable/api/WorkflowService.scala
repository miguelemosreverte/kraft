package kraft.dsl.durable.api

import kraft.dsl.*
import kraft.dsl.durable.cluster.*
import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.runtime.NodeRuntime
import kraft.dsl.durable.storage.NodeStorage
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Future, ExecutionContext}

/**
 * HTTP/gRPC API for workflow operations.
 *
 * This provides the endpoints that TypeScript and other language clients use.
 *
 * Endpoints:
 *   POST /workflows/submit       - Submit a new workflow
 *   GET  /workflows/:id          - Get workflow info
 *   GET  /workflows/:id/result   - Wait for workflow result (long-polling)
 *   GET  /workflows/:id/events   - Get workflow events
 *   POST /workflows/:id/signal   - Send signal to workflow
 *   POST /workflows/:id/cancel   - Cancel workflow
 *   GET  /workflows              - List workflows
 *   GET  /health                 - Cluster health
 */
object WorkflowService:

  // ============================================================================
  // Request/Response Types
  // ============================================================================

  case class SubmitRequest(
    workflowName: String,
    workflowId: String,
    input: String  // JSON string
  )

  case class SubmitResponse(
    workflowId: String,
    status: String
  )

  case class WorkflowInfoResponse(
    workflowId: String,
    name: String,
    status: String,
    createdAt: Long,
    completedAt: Option[Long],
    input: String,
    output: Option[String],
    error: Option[String]
  )

  case class ResultResponse(
    workflowId: String,
    status: String,
    output: Option[String],
    error: Option[String]
  )

  case class EventResponse(
    workflowId: String,
    step: String,
    status: String,
    timestamp: Long,
    data: Option[String]
  )

  case class SignalRequest(
    signalName: String,
    data: Option[String]
  )

  case class HealthResponse(
    status: String,
    nodeId: String,
    nodes: Int,
    activeWorkflows: Int
  )

  case class ErrorResponse(
    error: String,
    code: String
  )

  // JSON codecs
  given JsonValueCodec[SubmitRequest] = JsonCodecMaker.make
  given JsonValueCodec[SubmitResponse] = JsonCodecMaker.make
  given JsonValueCodec[WorkflowInfoResponse] = JsonCodecMaker.make
  given JsonValueCodec[ResultResponse] = JsonCodecMaker.make
  given JsonValueCodec[EventResponse] = JsonCodecMaker.make
  given JsonValueCodec[SignalRequest] = JsonCodecMaker.make
  given JsonValueCodec[HealthResponse] = JsonCodecMaker.make
  given JsonValueCodec[ErrorResponse] = JsonCodecMaker.make
  given workflowListCodec: JsonValueCodec[Seq[WorkflowInfoResponse]] = JsonCodecMaker.make
  given eventListCodec: JsonValueCodec[Seq[EventResponse]] = JsonCodecMaker.make

  // ============================================================================
  // Routes
  // ============================================================================

  /**
   * Create HTTP routes for the workflow API.
   */
  def routes(runtime: ClusterRuntime)(using ExecutionContext): HttpRoutes =
    HttpRoutes(
      // Submit workflow
      POST("/workflows/submit") { req =>
        try
          val request = readFromArray[SubmitRequest](req.body)
          // TODO: Actually submit to runtime
          val response = SubmitResponse(request.workflowId, "pending")
          Ok(writeToArray(response), "application/json")
        catch
          case e: Exception =>
            BadRequest(writeToArray(ErrorResponse(e.getMessage, "INVALID_REQUEST")), "application/json")
      },

      // Get workflow info
      GET("/workflows/:id") { req =>
        req.pathParam("id") match
          case Some(workflowId) =>
            // TODO: Get from runtime
            val response = WorkflowInfoResponse(
              workflowId = workflowId,
              name = "unknown",
              status = "running",
              createdAt = System.currentTimeMillis(),
              completedAt = None,
              input = "{}",
              output = None,
              error = None
            )
            Ok(writeToArray(response), "application/json")
          case None =>
            BadRequest(writeToArray(ErrorResponse("Missing workflow ID", "MISSING_ID")), "application/json")
      },

      // Get workflow result (long-polling)
      GET("/workflows/:id/result") { req =>
        req.pathParam("id") match
          case Some(workflowId) =>
            // TODO: Implement long-polling wait for result
            val response = ResultResponse(
              workflowId = workflowId,
              status = "running",
              output = None,
              error = None
            )
            Ok(writeToArray(response), "application/json")
          case None =>
            BadRequest(writeToArray(ErrorResponse("Missing workflow ID", "MISSING_ID")), "application/json")
      },

      // Get workflow events
      GET("/workflows/:id/events") { req =>
        req.pathParam("id") match
          case Some(workflowId) =>
            // TODO: Get events from journal
            val events = Seq.empty[EventResponse]
            Ok(writeToArray(events), "application/json")
          case None =>
            BadRequest(writeToArray(ErrorResponse("Missing workflow ID", "MISSING_ID")), "application/json")
      },

      // Signal workflow
      POST("/workflows/:id/signal/:signal") { req =>
        (req.pathParam("id"), req.pathParam("signal")) match
          case (Some(workflowId), Some(signalName)) =>
            // TODO: Send signal to workflow
            NoContent
          case _ =>
            BadRequest(writeToArray(ErrorResponse("Missing parameters", "MISSING_PARAMS")), "application/json")
      },

      // Cancel workflow
      POST("/workflows/:id/cancel") { req =>
        req.pathParam("id") match
          case Some(workflowId) =>
            // TODO: Cancel workflow
            NoContent
          case None =>
            BadRequest(writeToArray(ErrorResponse("Missing workflow ID", "MISSING_ID")), "application/json")
      },

      // List workflows
      GET("/workflows") { req =>
        // TODO: Query workflows from storage
        val workflows = Seq.empty[WorkflowInfoResponse]
        Ok(writeToArray(workflows), "application/json")
      },

      // Health check
      GET("/health") { req =>
        val response = HealthResponse(
          status = "healthy",
          nodeId = runtime.nodeId.value,
          nodes = runtime.aliveMembers.size,
          activeWorkflows = 0  // TODO: Get from runtime
        )
        Ok(writeToArray(response), "application/json")
      }
    )

  /**
   * Start the API server on specified port.
   */
  def start(runtime: ClusterRuntime, port: Int)(using ExecutionContext): kraft.server.HttpServer.Handle =
    import kraft.server.HttpServer
    val server = HttpServer(routes(runtime))
    server.start(port)
