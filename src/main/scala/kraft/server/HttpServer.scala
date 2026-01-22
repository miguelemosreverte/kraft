package kraft.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.{ByteBuf, PooledByteBufAllocator, Unpooled}
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.flush.FlushConsolidationHandler
import io.netty.util.CharsetUtil
import kraft.dsl.*  // Use the new separated DSL package

import java.util.concurrent.atomic.AtomicLong
import scala.util.Try
import scala.compiletime.uninitialized

/**
 * High-performance HTTP server with io_uring support.
 *
 * http4s-inspired DSL:
 * {{{
 * import kraft.server.dsl.*
 *
 * val routes = HttpRoutes(
 *   GET("/health") { _ => Ok("""{"status":"healthy"}""") },
 *
 *   GET(Root / "users" / ":id") { req =>
 *     req.pathParamInt("id") match
 *       case Some(id) => Ok(s"""{"id":$id}""")
 *       case None => BadRequest("Invalid user ID")
 *   },
 *
 *   GET("/search") { req =>
 *     val start = req.params.getAs[LocalDate]("starts_at")
 *     val end = req.params.getAs[LocalDate]("ends_at")
 *     Ok(searchResults(start, end))
 *   },
 *
 *   POST("/events") { req =>
 *     req.as[CreateEvent] match
 *       case Right(event) => Created(event)
 *       case Left(err) => BadRequest(err)
 *   }
 * )
 *
 * HttpServer(routes).start(8080)
 * }}}
 */
object HttpServer:

  // Try to load io_uring, fall back to NIO
  private[server] lazy val (eventLoopGroupClass, channelClass, transportName) = loadTransport()

  private def loadTransport(): (Class[? <: EventLoopGroup], Class[? <: ServerChannel], String) =
    try
      // Load io_uring classes
      val ioUringGroup = Class.forName("io.netty.incubator.channel.uring.IOUringEventLoopGroup")
        .asInstanceOf[Class[EventLoopGroup]]
      val ioUringChannel = Class.forName("io.netty.incubator.channel.uring.IOUringServerSocketChannel")
        .asInstanceOf[Class[ServerChannel]]

      // Verify io_uring is actually available
      val ioUringClass = Class.forName("io.netty.incubator.channel.uring.IOUring")
      val isAvailable = ioUringClass.getMethod("isAvailable").invoke(null).asInstanceOf[Boolean]

      if isAvailable then
        (ioUringGroup, ioUringChannel, "io_uring")
      else
        // Get the unavailability cause for debugging
        val cause = ioUringClass.getMethod("unavailabilityCause").invoke(null).asInstanceOf[Throwable]
        val reason = if cause != null then cause.getMessage else "unknown reason"
        System.err.println(s"[HttpServer] io_uring not available: $reason")
        (classOf[NioEventLoopGroup], classOf[NioServerSocketChannel], "NIO")
    catch
      case e: Throwable =>
        System.err.println(s"[HttpServer] io_uring class load failed: ${e.getMessage}")
        (classOf[NioEventLoopGroup], classOf[NioServerSocketChannel], "NIO")

  def apply(routes: Routes, workers: Int = Runtime.getRuntime.availableProcessors() * 2): HttpServer =
    new HttpServer(routes, workers)

  /** Create server with http4s-style DSL routes */
  def apply(routes: kraft.dsl.HttpRoutes, workers: Int): HttpServerDSL =
    new HttpServerDSL(routes, workers)

  /** Create server with http4s-style DSL routes (default workers) */
  def apply(routes: kraft.dsl.HttpRoutes): HttpServerDSL =
    new HttpServerDSL(routes, Runtime.getRuntime.availableProcessors())

  // ============================================================================
  // DSL Types
  // ============================================================================

  case class Request(
    method: Method,
    path: String,
    query: Map[String, String],
    headers: Map[String, String],
    body: Array[Byte]
  ):
    def bodyAsString: String = new String(body, CharsetUtil.UTF_8)
    def param(name: String): Option[String] = query.get(name)

  case class Response(
    status: Int,
    contentType: String,
    body: Array[Byte]
  )

  object Response:
    def ok(body: String, contentType: String = "application/json"): Response =
      Response(200, contentType, body.getBytes(CharsetUtil.UTF_8))

    def ok(body: Array[Byte], contentType: String): Response =
      Response(200, contentType, body)

    def badRequest(message: String): Response =
      Response(400, "application/json", s"""{"data":null,"error":{"code":"invalid_parameter","message":"$message"}}""".getBytes)

    def notFound: Response =
      Response(404, "application/json", """{"error":"not found"}""".getBytes)

    def methodNotAllowed: Response =
      Response(405, "application/json", """{"error":"method not allowed"}""".getBytes)

  enum Method:
    case GET, POST, PUT, DELETE, PATCH

  object Method:
    def fromNetty(method: HttpMethod): Method = method match
      case HttpMethod.GET    => GET
      case HttpMethod.POST   => POST
      case HttpMethod.PUT    => PUT
      case HttpMethod.DELETE => DELETE
      case HttpMethod.PATCH  => PATCH
      case _                 => GET

  type Handler = Request => Response

  // ============================================================================
  // Routes DSL
  // ============================================================================

  case class Route(method: Method, pathPattern: String, handler: Handler):
    private val pathRegex = pathPattern
      .replaceAll(":([^/]+)", "([^/]+)")
      .r

    def matches(m: Method, path: String): Boolean =
      m == method && pathRegex.matches(path.takeWhile(_ != '?'))

  class Routes(val underlying: Seq[Route]):
    def find(method: Method, path: String): Option[Handler] =
      val cleanPath = path.takeWhile(_ != '?')
      underlying.find(_.matches(method, cleanPath)).map(_.handler)

    def ++(other: Routes): Routes = new Routes(underlying ++ other.underlying)

  object Routes:
    def apply(routes: Route*): Routes = new Routes(routes.toSeq)
    val empty: Routes = new Routes(Seq.empty)

  // Route builders - the http4s-like DSL
  def GET(path: String)(handler: Handler): Route = Route(Method.GET, path, handler)
  def POST(path: String)(handler: Handler): Route = Route(Method.POST, path, handler)
  def PUT(path: String)(handler: Handler): Route = Route(Method.PUT, path, handler)
  def DELETE(path: String)(handler: Handler): Route = Route(Method.DELETE, path, handler)

  // ============================================================================
  // Server Handle trait (in companion object)
  // ============================================================================

  trait Handle extends AutoCloseable:
    def requestCount: Long
    def metrics: Metrics
    def awaitTermination(): Unit

// ============================================================================
// Server Implementation
// ============================================================================

class HttpServer(routes: HttpServer.Routes, workers: Int):
  import HttpServer.*

  private val serverMetrics = Metrics()
  private var bossGroup: EventLoopGroup = uninitialized
  private var workerGroup: EventLoopGroup = uninitialized
  private var channel: Channel = uninitialized

  // Add built-in /metrics endpoint to routes
  private val metricsRoute = GET("/metrics") { _ =>
    Response.ok(serverMetrics.toPrometheusFormat, "text/plain; charset=utf-8")
  }
  private val allRoutes = new Routes(metricsRoute +: routes.underlying)

  def start(port: Int): Handle =
    bossGroup = eventLoopGroupClass.getDeclaredConstructor(classOf[Int]).newInstance(1)
    workerGroup = eventLoopGroupClass.getDeclaredConstructor(classOf[Int]).newInstance(workers)

    val bootstrap = ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(channelClass)
      .option(ChannelOption.SO_BACKLOG, Integer.valueOf(8192))
      .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
      .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
      .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
      .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
      .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32 * 1024, 64 * 1024))
      .childHandler(new ChannelInitializer[SocketChannel]:
        override def initChannel(ch: SocketChannel): Unit =
          ch.pipeline()
            .addLast(FlushConsolidationHandler(256, true))
            .addLast(HttpServerCodec())
            .addLast(HttpObjectAggregator(65536))
            .addLast(ConnectionTracker(serverMetrics))
            .addLast(RequestHandler(allRoutes, serverMetrics))
      )

    channel = bootstrap.bind(port).sync().channel()
    println(s"[HttpServer] Started on port $port with $transportName transport ($workers workers)")
    println(s"[HttpServer] Metrics available at http://localhost:$port/metrics")

    new Handle:
      def requestCount: Long = serverMetrics.totalRequests
      def metrics: Metrics = serverMetrics
      def awaitTermination(): Unit = channel.closeFuture().sync()
      def close(): Unit =
        channel.close().sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        println(s"[HttpServer] Stopped. Total requests: ${serverMetrics.totalRequests}")

// ============================================================================
// Netty Handlers
// ============================================================================

private class ConnectionTracker(metrics: Metrics) extends ChannelInboundHandlerAdapter:
  override def channelActive(ctx: ChannelHandlerContext): Unit =
    metrics.connectionOpened()
    super.channelActive(ctx)

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    metrics.connectionClosed()
    super.channelInactive(ctx)

private class RequestHandler(
  routes: HttpServer.Routes,
  metrics: Metrics
) extends SimpleChannelInboundHandler[FullHttpRequest]:
  import HttpServer.*

  override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest): Unit =
    metrics.requestProcessed()

    val method = Method.fromNetty(msg.method())
    val uri = msg.uri()
    val path = uri.takeWhile(_ != '?')
    val query = parseQuery(uri)
    val headers = parseHeaders(msg)
    val body = readBody(msg)

    // Track bytes read
    metrics.addBytesRead(body.length)

    val request = Request(method, path, query, headers, body)

    val response = routes.find(method, path) match
      case Some(handler) =>
        try handler(request)
        catch case e: Exception =>
          Response(500, "application/json", s"""{"error":"${e.getMessage}"}""".getBytes)
      case None =>
        Response.notFound

    val nettyResponse = buildNettyResponse(response)

    // Track bytes written
    metrics.addBytesWritten(response.body.length)

    ctx.writeAndFlush(nettyResponse)

  private def parseQuery(uri: String): Map[String, String] =
    val idx = uri.indexOf('?')
    if idx < 0 then Map.empty
    else
      uri.substring(idx + 1)
        .split('&')
        .flatMap { param =>
          val eq = param.indexOf('=')
          if eq > 0 then Some(param.substring(0, eq) -> java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8"))
          else None
        }
        .toMap

  private def parseHeaders(msg: FullHttpRequest): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    msg.headers().entries().asScala.map(e => e.getKey -> e.getValue).toMap

  private def readBody(msg: FullHttpRequest): Array[Byte] =
    val content = msg.content()
    val bytes = new Array[Byte](content.readableBytes())
    content.readBytes(bytes)
    bytes

  private def buildNettyResponse(response: Response): FullHttpResponse =
    val content = Unpooled.wrappedBuffer(response.body)
    val nettyResponse = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(response.status),
      content
    )
    nettyResponse.headers()
      .set(HttpHeaderNames.CONTENT_TYPE, response.contentType)
      .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
      .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
    nettyResponse

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    // Silently handle expected errors (connection resets are normal under high load)
    ctx.close()

// ============================================================================
// DSL-based Server Implementation
// ============================================================================

class HttpServerDSL(routes: kraft.dsl.HttpRoutes, workers: Int):
  import HttpServer.{eventLoopGroupClass, channelClass, transportName, Handle}
  import kraft.features.metrics.MetricsFeature

  private val serverMetrics = Metrics()
  private var bossGroup: EventLoopGroup = uninitialized
  private var workerGroup: EventLoopGroup = uninitialized
  private var channel: Channel = uninitialized

  // Built-in /metrics endpoint from metrics feature
  private val allRoutes = MetricsFeature.routes(serverMetrics) <+> routes

  def start(port: Int): Handle =
    bossGroup = eventLoopGroupClass.getDeclaredConstructor(classOf[Int]).newInstance(1)
    workerGroup = eventLoopGroupClass.getDeclaredConstructor(classOf[Int]).newInstance(workers)

    val bootstrap = ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(channelClass)
      .option(ChannelOption.SO_BACKLOG, Integer.valueOf(8192))
      .option(ChannelOption.SO_REUSEADDR, java.lang.Boolean.TRUE)
      .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
      .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
      .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
      .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
      .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32 * 1024, 64 * 1024))
      .childHandler(new ChannelInitializer[SocketChannel]:
        override def initChannel(ch: SocketChannel): Unit =
          ch.pipeline()
            .addLast(FlushConsolidationHandler(256, true))
            .addLast(HttpServerCodec())
            .addLast(HttpObjectAggregator(65536))
            .addLast(ConnectionTracker(serverMetrics))
            .addLast(DslRequestHandler(allRoutes, serverMetrics))
      )

    channel = bootstrap.bind(port).sync().channel()
    println(s"[HttpServer] Started on port $port with $transportName transport ($workers workers)")
    println(s"[HttpServer] Metrics available at http://localhost:$port/metrics")

    new Handle:
      def requestCount: Long = serverMetrics.totalRequests
      def metrics: Metrics = serverMetrics
      def awaitTermination(): Unit = channel.closeFuture().sync()
      def close(): Unit =
        channel.close().sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        println(s"[HttpServer] Stopped. Total requests: ${serverMetrics.totalRequests}")

// ============================================================================
// DSL Netty Handler
// ============================================================================

private class DslRequestHandler(
  routes: kraft.dsl.HttpRoutes,
  metrics: Metrics
) extends SimpleChannelInboundHandler[FullHttpRequest]:
  // Use kraft.dsl types explicitly to avoid conflict with HttpServer internal types
  import kraft.dsl.{Request as DslRequest, Response as DslResponse, Method as DslMethod, *}

  override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest): Unit =
    metrics.requestProcessed()

    val method = methodFromNetty(msg.method())
    val uri = msg.uri()
    val path = uri.takeWhile(_ != '?')
    val queryParams = parseQuery(uri)
    val headerMap = parseHeaders(msg)
    val body = readBody(msg)

    metrics.addBytesRead(body.length)

    val request = DslRequest(
      method = method,
      path = path,
      pathParams = Map.empty,
      params = QueryParams(queryParams),
      headers = Headers(headerMap),
      body = body
    )

    val response = try
      routes(request).getOrElse(NotFound)
    catch
      case e: MatchError => NotFound
      case e: Exception =>
        InternalServerError(e.getMessage)

    val nettyResponse = buildNettyResponse(response)
    metrics.addBytesWritten(response.body.length)
    ctx.writeAndFlush(nettyResponse)

  private def methodFromNetty(method: HttpMethod): DslMethod = method match
    case HttpMethod.GET     => kraft.dsl.GET
    case HttpMethod.POST    => kraft.dsl.POST
    case HttpMethod.PUT     => kraft.dsl.PUT
    case HttpMethod.DELETE  => kraft.dsl.DELETE
    case HttpMethod.PATCH   => kraft.dsl.PATCH
    case HttpMethod.HEAD    => kraft.dsl.HEAD
    case HttpMethod.OPTIONS => kraft.dsl.OPTIONS
    case _                  => kraft.dsl.GET

  private def parseQuery(uri: String): Map[String, String] =
    val idx = uri.indexOf('?')
    if idx < 0 then Map.empty
    else
      uri.substring(idx + 1)
        .split('&')
        .flatMap { param =>
          val eq = param.indexOf('=')
          if eq > 0 then Some(param.substring(0, eq) -> java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8"))
          else None
        }
        .toMap

  private def parseHeaders(msg: FullHttpRequest): Map[String, String] =
    import scala.jdk.CollectionConverters.*
    msg.headers().entries().asScala.map(e => e.getKey -> e.getValue).toMap

  private def readBody(msg: FullHttpRequest): Array[Byte] =
    val content = msg.content()
    val bytes = new Array[Byte](content.readableBytes())
    content.readBytes(bytes)
    bytes

  private def buildNettyResponse(response: DslResponse): FullHttpResponse =
    val content = Unpooled.wrappedBuffer(response.body)
    val nettyResponse = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(response.status.code),
      content
    )
    // Set headers from response
    response.headers.foreach { case (k, v) =>
      nettyResponse.headers().set(k, v)
    }
    nettyResponse.headers()
      .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
      .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
    nettyResponse

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    // Silently handle expected errors (connection resets are normal under high load)
    ctx.close()
