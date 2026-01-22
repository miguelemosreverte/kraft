package kraft.dsl.ws

import java.net.URI
import java.net.http.{HttpClient, WebSocket as JWebSocket}
import java.net.http.WebSocket.Listener
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters.*
import scala.concurrent.{Future, Promise, ExecutionContext}
import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * WebSocket DSL - Type-safe WebSocket client and server routing.
 *
 * Server-side example:
 * {{{
 * import kraft.dsl.ws.*
 *
 * val wsRoutes = WebSocketRoutes(
 *   WS("/chat") { connection =>
 *     connection
 *       .onOpen { println("Client connected") }
 *       .onMessage[ChatMessage] { msg =>
 *         connection.send(ChatMessage(s"Echo: ${msg.text}"))
 *       }
 *       .onClose { (code, reason) =>
 *         println(s"Client disconnected: $code - $reason")
 *       }
 *   }
 * )
 * }}}
 *
 * Client-side example:
 * {{{
 * val client = WebSocketClient.connect("wss://echo.websocket.org")
 *   .onMessage[String] { msg => println(s"Received: $msg") }
 *   .onClose { (code, reason) => println(s"Closed: $code") }
 *   .build()
 *
 * client.send("Hello, WebSocket!")
 * }}}
 */

// =============================================================================
// WebSocket Message Types
// =============================================================================

/**
 * Represents a WebSocket message (text or binary).
 */
sealed trait WsMessage:
  def isText: Boolean
  def isBinary: Boolean

object WsMessage:
  case class Text(data: String) extends WsMessage:
    def isText = true
    def isBinary = false

  case class Binary(data: Array[Byte]) extends WsMessage:
    def isText = false
    def isBinary = true

  /** Create a text message */
  def text(data: String): WsMessage = Text(data)

  /** Create a binary message */
  def binary(data: Array[Byte]): WsMessage = Binary(data)

  /** Create a JSON message */
  def json[A: JsonValueCodec](value: A): WsMessage =
    Text(writeToString(value))

// =============================================================================
// WebSocket Close Codes
// =============================================================================

object CloseCode:
  val Normal = 1000
  val GoingAway = 1001
  val ProtocolError = 1002
  val UnsupportedData = 1003
  val NoStatusReceived = 1005
  val AbnormalClosure = 1006
  val InvalidPayload = 1007
  val PolicyViolation = 1008
  val MessageTooBig = 1009
  val MandatoryExtension = 1010
  val InternalError = 1011
  val ServiceRestart = 1012
  val TryAgainLater = 1013

// =============================================================================
// Server-Side WebSocket Connection
// =============================================================================

/**
 * Represents a WebSocket connection on the server side.
 * Used to define handlers and send messages.
 */
trait WebSocketConnection:
  /** Send a text message */
  def send(message: String): Unit

  /** Send a binary message */
  def sendBinary(data: Array[Byte]): Unit

  /** Send a typed JSON message */
  def send[A: JsonValueCodec](message: A): Unit

  /** Close the connection */
  def close(code: Int = CloseCode.Normal, reason: String = ""): Unit

  /** Check if connection is open */
  def isOpen: Boolean

  /** Get connection ID */
  def id: String

/**
 * Builder for configuring WebSocket connection handlers.
 */
class WebSocketConnectionBuilder(
  private val path: String,
  private var onOpenHandler: () => Unit = () => (),
  private var onMessageHandler: WsMessage => Unit = _ => (),
  private var onCloseHandler: (Int, String) => Unit = (_, _) => (),
  private var onErrorHandler: Throwable => Unit = _ => ()
):
  /** Handle connection open */
  def onOpen(handler: => Unit): WebSocketConnectionBuilder =
    onOpenHandler = () => handler
    this

  /** Handle text messages */
  def onTextMessage(handler: String => Unit): WebSocketConnectionBuilder =
    val prev = onMessageHandler
    onMessageHandler = {
      case WsMessage.Text(data) => handler(data)
      case other => prev(other)
    }
    this

  /** Handle binary messages */
  def onBinaryMessage(handler: Array[Byte] => Unit): WebSocketConnectionBuilder =
    val prev = onMessageHandler
    onMessageHandler = {
      case WsMessage.Binary(data) => handler(data)
      case other => prev(other)
    }
    this

  /** Handle typed JSON messages */
  def onMessage[A: JsonValueCodec](handler: A => Unit): WebSocketConnectionBuilder =
    val prev = onMessageHandler
    onMessageHandler = {
      case WsMessage.Text(data) =>
        Try(readFromString[A](data)) match
          case Success(value) => handler(value)
          case Failure(_) => prev(WsMessage.Text(data))
      case other => prev(other)
    }
    this

  /** Handle connection close */
  def onClose(handler: (Int, String) => Unit): WebSocketConnectionBuilder =
    onCloseHandler = handler
    this

  /** Handle errors */
  def onError(handler: Throwable => Unit): WebSocketConnectionBuilder =
    onErrorHandler = handler
    this

  /** Build the route handler */
  def build(): WebSocketRouteHandler =
    WebSocketRouteHandler(path, onOpenHandler, onMessageHandler, onCloseHandler, onErrorHandler)

/**
 * Internal handler for a WebSocket route.
 */
case class WebSocketRouteHandler(
  path: String,
  onOpen: () => Unit,
  onMessage: WsMessage => Unit,
  onClose: (Int, String) => Unit,
  onError: Throwable => Unit
)

// =============================================================================
// Server-Side WebSocket Routes
// =============================================================================

/**
 * Collection of WebSocket routes.
 */
class WebSocketRoutes(val routes: Seq[WebSocketRouteHandler]):
  /** Find handler for a path */
  def findHandler(path: String): Option[WebSocketRouteHandler] =
    routes.find(_.path == path)

  /** Combine with another set of routes */
  def <+>(other: WebSocketRoutes): WebSocketRoutes =
    new WebSocketRoutes(routes ++ other.routes)

object WebSocketRoutes:
  def apply(routes: WebSocketRouteHandler*): WebSocketRoutes =
    new WebSocketRoutes(routes.toSeq)

  def fromSeq(routes: Seq[WebSocketRouteHandler]): WebSocketRoutes =
    new WebSocketRoutes(routes)

/** DSL entry point for defining a WebSocket route */
object WS:
  def apply(path: String): WebSocketPathBuilder = WebSocketPathBuilder(path)

case class WebSocketPathBuilder(path: String):
  def onOpen(handler: => Unit): WebSocketConnectionBuilder =
    new WebSocketConnectionBuilder(path).onOpen(handler)

  def onMessage[A: JsonValueCodec](handler: A => Unit): WebSocketConnectionBuilder =
    new WebSocketConnectionBuilder(path).onMessage(handler)

  def onTextMessage(handler: String => Unit): WebSocketConnectionBuilder =
    new WebSocketConnectionBuilder(path).onTextMessage(handler)

  def build(): WebSocketRouteHandler =
    new WebSocketConnectionBuilder(path).build()

// =============================================================================
// Client-Side WebSocket
// =============================================================================

/**
 * WebSocket client for connecting to WebSocket servers.
 */
class WebSocketClient private[ws] (
  private val ws: JWebSocket,
  private val closeLatch: CountDownLatch,
  private val connected: AtomicBoolean
):
  /** Send a text message */
  def send(message: String): WebSocketClient =
    if connected.get() then ws.sendText(message, true)
    this

  /** Send a binary message */
  def sendBinary(data: Array[Byte]): WebSocketClient =
    if connected.get() then ws.sendBinary(ByteBuffer.wrap(data), true)
    this

  /** Send a typed JSON message */
  def send[A: JsonValueCodec](message: A): WebSocketClient =
    send(writeToString(message))

  /** Send a ping */
  def ping(): WebSocketClient =
    if connected.get() then ws.sendPing(ByteBuffer.allocate(0))
    this

  /** Close the connection */
  def close(code: Int = CloseCode.Normal, reason: String = ""): Unit =
    if connected.compareAndSet(true, false) then
      ws.sendClose(code, reason)

  /** Check if connected */
  def isConnected: Boolean = connected.get()

  /** Wait for connection to close */
  def awaitClose(timeout: Long = Long.MaxValue, unit: TimeUnit = TimeUnit.MILLISECONDS): Boolean =
    closeLatch.await(timeout, unit)

object WebSocketClient:
  /**
   * Start building a WebSocket client connection.
   */
  def connect(url: String): WebSocketClientBuilder =
    WebSocketClientBuilder(url)

/**
 * Builder for WebSocket client connections.
 */
class WebSocketClientBuilder private[ws] (
  private val url: String,
  private var onOpenHandler: () => Unit = () => (),
  private var onTextHandler: String => Unit = _ => (),
  private var onBinaryHandler: Array[Byte] => Unit = _ => (),
  private var onCloseHandler: (Int, String) => Unit = (_, _) => (),
  private var onErrorHandler: Throwable => Unit = _ => (),
  private var headers: Map[String, String] = Map.empty,
  private var subprotocols: Seq[String] = Seq.empty
):
  /** Handle connection open */
  def onOpen(handler: => Unit): WebSocketClientBuilder =
    onOpenHandler = () => handler
    this

  /** Handle text messages */
  def onTextMessage(handler: String => Unit): WebSocketClientBuilder =
    onTextHandler = handler
    this

  /** Handle binary messages */
  def onBinaryMessage(handler: Array[Byte] => Unit): WebSocketClientBuilder =
    onBinaryHandler = handler
    this

  /** Handle typed JSON messages */
  def onMessage[A: JsonValueCodec](handler: A => Unit): WebSocketClientBuilder =
    val prev = onTextHandler
    onTextHandler = { data =>
      Try(readFromString[A](data)) match
        case Success(value) => handler(value)
        case Failure(_) => prev(data)
    }
    this

  /** Handle connection close */
  def onClose(handler: (Int, String) => Unit): WebSocketClientBuilder =
    onCloseHandler = handler
    this

  /** Handle errors */
  def onError(handler: Throwable => Unit): WebSocketClientBuilder =
    onErrorHandler = handler
    this

  /** Add a header */
  def header(name: String, value: String): WebSocketClientBuilder =
    headers = headers + (name -> value)
    this

  /** Add subprotocol */
  def subprotocol(protocol: String): WebSocketClientBuilder =
    subprotocols = subprotocols :+ protocol
    this

  /** Build and connect */
  def build(): WebSocketClient =
    val httpClient = HttpClient.newHttpClient()
    val closeLatch = new CountDownLatch(1)
    val connected = new AtomicBoolean(false)
    val textBuffer = new StringBuilder()

    val listener = new Listener:
      override def onOpen(webSocket: JWebSocket): Unit =
        connected.set(true)
        onOpenHandler()
        webSocket.request(1)

      override def onText(webSocket: JWebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
        textBuffer.append(data)
        if last then
          val message = textBuffer.toString()
          textBuffer.clear()
          onTextHandler(message)
        webSocket.request(1)
        CompletableFuture.completedFuture(null)

      override def onBinary(webSocket: JWebSocket, data: ByteBuffer, last: Boolean): CompletionStage[?] =
        val bytes = new Array[Byte](data.remaining())
        data.get(bytes)
        onBinaryHandler(bytes)
        webSocket.request(1)
        CompletableFuture.completedFuture(null)

      override def onClose(webSocket: JWebSocket, statusCode: Int, reason: String): CompletionStage[?] =
        connected.set(false)
        onCloseHandler(statusCode, reason)
        closeLatch.countDown()
        CompletableFuture.completedFuture(null)

      override def onError(webSocket: JWebSocket, error: Throwable): Unit =
        connected.set(false)
        onErrorHandler(error)
        closeLatch.countDown()

    val builder = httpClient.newWebSocketBuilder()
    headers.foreach((k, v) => builder.header(k, v))
    if subprotocols.nonEmpty then
      builder.subprotocols(subprotocols.head, subprotocols.tail*)

    val ws = builder.buildAsync(URI.create(url), listener).join()
    new WebSocketClient(ws, closeLatch, connected)

  /** Build and connect, returning a Future */
  def buildAsync()(using ec: ExecutionContext): Future[WebSocketClient] =
    Future(build())

// =============================================================================
// Message Codec Helpers
// =============================================================================

/**
 * Typeclass for encoding/decoding WebSocket messages.
 */
trait WsCodec[A]:
  def encode(value: A): WsMessage
  def decode(message: WsMessage): Option[A]

object WsCodec:
  /** String codec */
  given WsCodec[String] with
    def encode(value: String): WsMessage = WsMessage.Text(value)
    def decode(message: WsMessage): Option[String] = message match
      case WsMessage.Text(data) => Some(data)
      case _ => None

  /** Binary codec */
  given WsCodec[Array[Byte]] with
    def encode(value: Array[Byte]): WsMessage = WsMessage.Binary(value)
    def decode(message: WsMessage): Option[Array[Byte]] = message match
      case WsMessage.Binary(data) => Some(data)
      case _ => None

  /** JSON codec (requires jsoniter codec in scope) */
  given jsonCodec[A: JsonValueCodec]: WsCodec[A] with
    def encode(value: A): WsMessage = WsMessage.Text(writeToString(value))
    def decode(message: WsMessage): Option[A] = message match
      case WsMessage.Text(data) => Try(readFromString[A](data)).toOption
      case _ => None
