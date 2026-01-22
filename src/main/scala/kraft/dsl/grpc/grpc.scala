package kraft.dsl.grpc

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Try, Success, Failure}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.concurrent.{ConcurrentHashMap, CompletableFuture}
import java.util.concurrent.atomic.AtomicLong

/**
 * gRPC DSL - Type-safe gRPC service definitions and client/server stubs.
 *
 * Service definition example:
 * {{{
 * import kraft.dsl.grpc.*
 *
 * // Define messages
 * case class HelloRequest(name: String)
 * case class HelloReply(message: String)
 *
 * // Define service
 * val greeterService = GrpcService("Greeter")
 *   .unary("SayHello") { (req: HelloRequest) =>
 *     HelloReply(s"Hello, ${req.name}!")
 *   }
 *   .serverStream("SayHelloMany") { (req: HelloRequest) =>
 *     Stream(
 *       HelloReply(s"Hello, ${req.name}!"),
 *       HelloReply(s"Hi again, ${req.name}!"),
 *       HelloReply(s"Goodbye, ${req.name}!")
 *     )
 *   }
 *   .build()
 * }}}
 *
 * Client example:
 * {{{
 * val client = GrpcClient.connect("localhost:50051")
 *   .forService(greeterService)
 *
 * val reply = client.call[HelloRequest, HelloReply]("SayHello", HelloRequest("World"))
 * }}}
 */

// =============================================================================
// gRPC Method Types
// =============================================================================

/**
 * Represents the four types of gRPC methods.
 */
enum GrpcMethodType:
  case Unary           // Single request, single response
  case ServerStreaming // Single request, stream of responses
  case ClientStreaming // Stream of requests, single response
  case BidiStreaming   // Stream of requests, stream of responses

// =============================================================================
// gRPC Status Codes
// =============================================================================

/**
 * Standard gRPC status codes.
 */
object GrpcStatus:
  val OK = 0
  val Cancelled = 1
  val Unknown = 2
  val InvalidArgument = 3
  val DeadlineExceeded = 4
  val NotFound = 5
  val AlreadyExists = 6
  val PermissionDenied = 7
  val ResourceExhausted = 8
  val FailedPrecondition = 9
  val Aborted = 10
  val OutOfRange = 11
  val Unimplemented = 12
  val Internal = 13
  val Unavailable = 14
  val DataLoss = 15
  val Unauthenticated = 16

/**
 * gRPC exception with status code.
 */
case class GrpcException(
  code: Int,
  message: String,
  cause: Option[Throwable] = None
) extends Exception(s"gRPC error $code: $message", cause.orNull)

// =============================================================================
// gRPC Metadata
// =============================================================================

/**
 * Metadata (headers/trailers) for gRPC calls.
 */
case class GrpcMetadata(entries: Map[String, Seq[String]] = Map.empty):
  def get(key: String): Option[String] = entries.get(key).flatMap(_.headOption)
  def getAll(key: String): Seq[String] = entries.getOrElse(key, Seq.empty)
  def add(key: String, value: String): GrpcMetadata =
    val existing = entries.getOrElse(key, Seq.empty)
    GrpcMetadata(entries + (key -> (existing :+ value)))
  def set(key: String, value: String): GrpcMetadata =
    GrpcMetadata(entries + (key -> Seq(value)))

object GrpcMetadata:
  val empty: GrpcMetadata = GrpcMetadata()

// =============================================================================
// gRPC Context
// =============================================================================

/**
 * Context for gRPC method execution.
 */
case class GrpcContext(
  metadata: GrpcMetadata = GrpcMetadata.empty,
  deadline: Option[Long] = None,
  cancellationToken: Option[() => Boolean] = None
):
  def isCancelled: Boolean = cancellationToken.exists(_())
  def remainingTime: Option[Long] = deadline.map(_ - System.currentTimeMillis())

// =============================================================================
// gRPC Method Definition
// =============================================================================

/**
 * A gRPC method definition.
 */
sealed trait GrpcMethod:
  def name: String
  def methodType: GrpcMethodType

case class UnaryMethod[Req, Res](
  name: String,
  handler: (Req, GrpcContext) => Res
)(using val reqCodec: JsonValueCodec[Req], val resCodec: JsonValueCodec[Res]) extends GrpcMethod:
  def methodType = GrpcMethodType.Unary

case class ServerStreamMethod[Req, Res](
  name: String,
  handler: (Req, GrpcContext) => LazyList[Res]
)(using val reqCodec: JsonValueCodec[Req], val resCodec: JsonValueCodec[Res]) extends GrpcMethod:
  def methodType = GrpcMethodType.ServerStreaming

case class ClientStreamMethod[Req, Res](
  name: String,
  handler: (LazyList[Req], GrpcContext) => Res
)(using val reqCodec: JsonValueCodec[Req], val resCodec: JsonValueCodec[Res]) extends GrpcMethod:
  def methodType = GrpcMethodType.ClientStreaming

case class BidiStreamMethod[Req, Res](
  name: String,
  handler: (LazyList[Req], GrpcContext) => LazyList[Res]
)(using val reqCodec: JsonValueCodec[Req], val resCodec: JsonValueCodec[Res]) extends GrpcMethod:
  def methodType = GrpcMethodType.BidiStreaming

// =============================================================================
// gRPC Service Definition
// =============================================================================

/**
 * A gRPC service containing multiple methods.
 */
case class GrpcServiceDef(
  name: String,
  methods: Map[String, GrpcMethod]
):
  def findMethod(methodName: String): Option[GrpcMethod] = methods.get(methodName)

/**
 * Builder for gRPC services.
 */
class GrpcServiceBuilder(
  private val name: String,
  private val methods: Map[String, GrpcMethod] = Map.empty
):
  /**
   * Add a unary method (single request, single response).
   */
  def unary[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: Req => Res): GrpcServiceBuilder =
    val method = UnaryMethod[Req, Res](methodName, (req, _) => handler(req))
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Add a unary method with context access.
   */
  def unaryWithContext[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: (Req, GrpcContext) => Res): GrpcServiceBuilder =
    val method = UnaryMethod[Req, Res](methodName, handler)
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Add a server streaming method (single request, stream of responses).
   */
  def serverStream[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: Req => LazyList[Res]): GrpcServiceBuilder =
    val method = ServerStreamMethod[Req, Res](methodName, (req, _) => handler(req))
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Add a server streaming method with context.
   */
  def serverStreamWithContext[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: (Req, GrpcContext) => LazyList[Res]): GrpcServiceBuilder =
    val method = ServerStreamMethod[Req, Res](methodName, handler)
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Add a client streaming method (stream of requests, single response).
   */
  def clientStream[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: LazyList[Req] => Res): GrpcServiceBuilder =
    val method = ClientStreamMethod[Req, Res](methodName, (reqs, _) => handler(reqs))
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Add a bidirectional streaming method.
   */
  def bidiStream[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String
  )(handler: LazyList[Req] => LazyList[Res]): GrpcServiceBuilder =
    val method = BidiStreamMethod[Req, Res](methodName, (reqs, _) => handler(reqs))
    new GrpcServiceBuilder(name, methods + (methodName -> method))

  /**
   * Build the service definition.
   */
  def build(): GrpcServiceDef = GrpcServiceDef(name, methods)

/**
 * Entry point for defining gRPC services.
 */
object GrpcService:
  def apply(name: String): GrpcServiceBuilder = new GrpcServiceBuilder(name)

// =============================================================================
// gRPC Server
// =============================================================================

/**
 * A collection of gRPC services that can handle requests.
 */
class GrpcServer(val services: Map[String, GrpcServiceDef]):
  /**
   * Handle a unary request.
   */
  def handleUnary(
    serviceName: String,
    methodName: String,
    requestJson: String,
    metadata: GrpcMetadata = GrpcMetadata.empty
  ): Either[GrpcException, String] =
    for
      service <- services.get(serviceName)
        .toRight(GrpcException(GrpcStatus.NotFound, s"Service not found: $serviceName"))
      method <- service.findMethod(methodName)
        .toRight(GrpcException(GrpcStatus.NotFound, s"Method not found: $methodName"))
      result <- method match
        case m: UnaryMethod[?, ?] => handleUnaryMethod(m, requestJson, metadata)
        case _ => Left(GrpcException(GrpcStatus.Unimplemented, "Not a unary method"))
    yield result

  private def handleUnaryMethod(
    method: UnaryMethod[?, ?],
    requestJson: String,
    metadata: GrpcMetadata
  ): Either[GrpcException, String] =
    Try {
      val reqCodec = method.reqCodec.asInstanceOf[JsonValueCodec[Any]]
      val resCodec = method.resCodec.asInstanceOf[JsonValueCodec[Any]]
      val request = readFromString[Any](requestJson)(using reqCodec)
      val ctx = GrpcContext(metadata = metadata)
      val response = method.handler.asInstanceOf[(Any, GrpcContext) => Any](request, ctx)
      writeToString(response)(using resCodec)
    }.toEither.left.map(e => GrpcException(GrpcStatus.Internal, e.getMessage, Some(e)))

  /**
   * Handle a server streaming request.
   */
  def handleServerStream(
    serviceName: String,
    methodName: String,
    requestJson: String,
    metadata: GrpcMetadata = GrpcMetadata.empty
  ): Either[GrpcException, LazyList[String]] =
    for
      service <- services.get(serviceName)
        .toRight(GrpcException(GrpcStatus.NotFound, s"Service not found: $serviceName"))
      method <- service.findMethod(methodName)
        .toRight(GrpcException(GrpcStatus.NotFound, s"Method not found: $methodName"))
      result <- method match
        case m: ServerStreamMethod[?, ?] => handleServerStreamMethod(m, requestJson, metadata)
        case _ => Left(GrpcException(GrpcStatus.Unimplemented, "Not a server streaming method"))
    yield result

  private def handleServerStreamMethod(
    method: ServerStreamMethod[?, ?],
    requestJson: String,
    metadata: GrpcMetadata
  ): Either[GrpcException, LazyList[String]] =
    Try {
      val reqCodec = method.reqCodec.asInstanceOf[JsonValueCodec[Any]]
      val resCodec = method.resCodec.asInstanceOf[JsonValueCodec[Any]]
      val request = readFromString[Any](requestJson)(using reqCodec)
      val ctx = GrpcContext(metadata = metadata)
      val responses = method.handler.asInstanceOf[(Any, GrpcContext) => LazyList[Any]](request, ctx)
      responses.map(r => writeToString(r)(using resCodec))
    }.toEither.left.map(e => GrpcException(GrpcStatus.Internal, e.getMessage, Some(e)))

  /**
   * Find a service by name.
   */
  def findService(name: String): Option[GrpcServiceDef] = services.get(name)

object GrpcServer:
  def apply(services: GrpcServiceDef*): GrpcServer =
    new GrpcServer(services.map(s => s.name -> s).toMap)

// =============================================================================
// gRPC Client
// =============================================================================

/**
 * A gRPC client stub for making calls.
 */
class GrpcClient private[grpc] (
  private val host: String,
  private val port: Int,
  private val defaultMetadata: GrpcMetadata = GrpcMetadata.empty,
  private val mockServer: Option[GrpcServer] = None
):
  /**
   * Make a unary call.
   */
  def call[Req: JsonValueCodec, Res: JsonValueCodec](
    serviceName: String,
    methodName: String,
    request: Req,
    metadata: GrpcMetadata = GrpcMetadata.empty
  ): Either[GrpcException, Res] =
    val combinedMetadata = GrpcMetadata(defaultMetadata.entries ++ metadata.entries)
    val requestJson = writeToString(request)

    mockServer match
      case Some(server) =>
        server.handleUnary(serviceName, methodName, requestJson, combinedMetadata)
          .flatMap { responseJson =>
            Try(readFromString[Res](responseJson)).toEither
              .left.map(e => GrpcException(GrpcStatus.Internal, e.getMessage, Some(e)))
          }
      case None =>
        // In a real implementation, this would make an HTTP/2 gRPC call
        Left(GrpcException(GrpcStatus.Unavailable, s"No connection to $host:$port"))

  /**
   * Make a server streaming call.
   */
  def callServerStream[Req: JsonValueCodec, Res: JsonValueCodec](
    serviceName: String,
    methodName: String,
    request: Req,
    metadata: GrpcMetadata = GrpcMetadata.empty
  ): Either[GrpcException, LazyList[Res]] =
    val combinedMetadata = GrpcMetadata(defaultMetadata.entries ++ metadata.entries)
    val requestJson = writeToString(request)

    mockServer match
      case Some(server) =>
        server.handleServerStream(serviceName, methodName, requestJson, combinedMetadata)
          .flatMap { responseJsons =>
            Try {
              responseJsons.map(json => readFromString[Res](json))
            }.toEither.left.map(e => GrpcException(GrpcStatus.Internal, e.getMessage, Some(e)))
          }
      case None =>
        Left(GrpcException(GrpcStatus.Unavailable, s"No connection to $host:$port"))

  /**
   * Create a new client with additional default metadata.
   */
  def withMetadata(metadata: GrpcMetadata): GrpcClient =
    new GrpcClient(host, port, GrpcMetadata(defaultMetadata.entries ++ metadata.entries), mockServer)

/**
 * Builder for gRPC clients.
 */
class GrpcClientBuilder private[grpc] (
  private val host: String,
  private val port: Int,
  private val metadata: GrpcMetadata = GrpcMetadata.empty
):
  def withMetadata(key: String, value: String): GrpcClientBuilder =
    new GrpcClientBuilder(host, port, metadata.set(key, value))

  def build(): GrpcClient = new GrpcClient(host, port, metadata)

  /** For testing: connect to a mock server */
  def withMockServer(server: GrpcServer): GrpcClient =
    new GrpcClient(host, port, metadata, Some(server))

object GrpcClient:
  /**
   * Start building a gRPC client connection.
   */
  def connect(address: String): GrpcClientBuilder =
    val parts = address.split(":")
    val host = parts(0)
    val port = if parts.length > 1 then parts(1).toInt else 443
    new GrpcClientBuilder(host, port)

  /**
   * Create a client connected to localhost.
   */
  def localhost(port: Int): GrpcClientBuilder =
    new GrpcClientBuilder("localhost", port)

// =============================================================================
// gRPC Service Stub Helper
// =============================================================================

/**
 * Trait for generated service stubs.
 */
trait GrpcStub[S]:
  def client: GrpcClient
  def serviceName: String

/**
 * DSL for creating typed service stubs.
 */
object ServiceStub:
  /**
   * Create a stub builder for a service.
   */
  def forService(serviceName: String): ServiceStubBuilder =
    ServiceStubBuilder(serviceName)

case class ServiceStubBuilder(serviceName: String):
  def withClient(client: GrpcClient): TypedServiceStub =
    TypedServiceStub(serviceName, client)

/**
 * A typed service stub for making calls.
 */
class TypedServiceStub(val serviceName: String, val client: GrpcClient):
  /**
   * Make a unary call to a method.
   */
  def unary[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String,
    request: Req
  ): Either[GrpcException, Res] =
    client.call[Req, Res](serviceName, methodName, request)

  /**
   * Make a server streaming call.
   */
  def serverStream[Req: JsonValueCodec, Res: JsonValueCodec](
    methodName: String,
    request: Req
  ): Either[GrpcException, LazyList[Res]] =
    client.callServerStream[Req, Res](serviceName, methodName, request)
