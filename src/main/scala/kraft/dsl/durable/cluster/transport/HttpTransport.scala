package kraft.dsl.durable.cluster.transport

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.client.{HttpClient, HttpMethod, ClientResponse}
import kraft.dsl.*
import kraft.server.{HttpServer, Metrics}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Duration

/**
 * HTTP-based transport for cluster communication.
 *
 * Uses the existing HTTP DSL foundation:
 * - kraft.server.HttpServer for receiving messages (Netty with io_uring)
 * - kraft.dsl.client.HttpClient for sending messages
 *
 * This ensures the cluster reuses the high-performance HTTP infrastructure
 * rather than implementing a separate TCP layer.
 *
 * Endpoints:
 * - POST /cluster/gossip - Handle gossip messages (Ping, Ack, etc.)
 * - POST /cluster/rpc - Handle RPC requests (workflow operations)
 */
class HttpTransport(
  val localAddress: NodeAddress,
  rpcHandler: Option[GossipMessage => Option[GossipMessage]] = None
)(using ExecutionContext) extends ClusterTransport:

  private val running = new AtomicBoolean(false)
  private var messageHandler: Option[MessageHandler] = None
  private var serverHandle: Option[HttpServer.Handle] = None

  // HTTP client for outgoing requests
  private val httpClient = HttpClient()
    .timeoutSeconds(30)
    .defaultHeader("Content-Type", "application/json")

  override def start(): Future[Unit] =
    if !running.compareAndSet(false, true) then
      return Future.failed(new IllegalStateException("Transport already started"))

    // Build HTTP routes for cluster endpoints
    val routes = HttpRoutes(
      POST("/cluster/gossip") { req =>
        try
          val envelope = readFromArray[GossipEnvelope](req.body)
          val response = messageHandler.flatMap { handler =>
            handler.handle(envelope.senderAddress, envelope.message)
          }
          response match
            case Some(respMsg) =>
              val respEnvelope = GossipEnvelope(localAddress, respMsg)
              Ok(writeToArray(respEnvelope), "application/json")
            case None =>
              NoContent
        catch
          case e: Exception =>
            BadRequest(s"Invalid gossip message: ${e.getMessage}")
      }
    )

    // Start HTTP server using the DSL infrastructure
    val server = HttpServer(routes)
    val handle = server.start(localAddress.port)
    serverHandle = Some(handle)

    Future.successful(())

  override def stop(): Future[Unit] =
    if !running.compareAndSet(true, false) then
      return Future.successful(())

    serverHandle.foreach(_.close())
    serverHandle = None
    Future.successful(())

  override def send(to: NodeAddress, message: GossipMessage): Future[Boolean] =
    if !running.get() then
      return Future.successful(false)

    Future {
      try
        val envelope = GossipEnvelope(localAddress, message)
        val response = httpClient
          .post(s"http://${to.host}:${to.port}/cluster/gossip")
          .body(envelope)
          .executeBytes

        response.isSuccess
      catch
        case _: Exception => false
    }

  override def sendAndReceive(
    to: NodeAddress,
    message: GossipMessage,
    timeout: FiniteDuration
  ): Future[Option[GossipMessage]] =
    if !running.get() then
      return Future.successful(None)

    Future {
      try
        val envelope = GossipEnvelope(localAddress, message)
        val client = HttpClient(Duration.ofMillis(timeout.toMillis))
          .defaultHeader("Content-Type", "application/json")

        val response = client
          .post(s"http://${to.host}:${to.port}/cluster/gossip")
          .body(envelope)
          .executeBytes

        response match
          case ClientResponse.Ok(bytes, _, _) if bytes.nonEmpty =>
            val respEnvelope = readFromArray[GossipEnvelope](bytes)
            Some(respEnvelope.message)
          case ClientResponse.NoContent(_, _) =>
            None
          case _ =>
            None
      catch
        case _: Exception => None
    }

  override def onMessage(handler: MessageHandler): Unit =
    messageHandler = Some(handler)

/**
 * Envelope for HTTP transport.
 */
case class GossipEnvelope(
  senderAddress: NodeAddress,
  message: GossipMessage
)

object GossipEnvelope:
  given JsonValueCodec[GossipEnvelope] = JsonCodecMaker.make
