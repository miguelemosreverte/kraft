package kraft.dsl

import java.nio.charset.StandardCharsets

/**
 * Core types for the HTTP DSL.
 * These are server-agnostic and can be used with any HTTP backend.
 */

// ============================================================================
// HTTP Methods
// ============================================================================

sealed trait Method:
  def ->(path: Path): MethodPath = MethodPath(this, path)
  def ->(root: Root.type): MethodPath = MethodPath(this, Path(Nil))

case object GET extends Method
case object POST extends Method
case object PUT extends Method
case object DELETE extends Method
case object PATCH extends Method
case object HEAD extends Method
case object OPTIONS extends Method

case class MethodPath(method: Method, path: Path)

// ============================================================================
// HTTP Status Codes
// ============================================================================

enum Status(val code: Int, val reason: String):
  case Ok extends Status(200, "OK")
  case Created extends Status(201, "Created")
  case Accepted extends Status(202, "Accepted")
  case NoContent extends Status(204, "No Content")
  case MovedPermanently extends Status(301, "Moved Permanently")
  case Found extends Status(302, "Found")
  case NotModified extends Status(304, "Not Modified")
  case BadRequest extends Status(400, "Bad Request")
  case Unauthorized extends Status(401, "Unauthorized")
  case Forbidden extends Status(403, "Forbidden")
  case NotFound extends Status(404, "Not Found")
  case MethodNotAllowed extends Status(405, "Method Not Allowed")
  case Conflict extends Status(409, "Conflict")
  case Gone extends Status(410, "Gone")
  case UnprocessableEntity extends Status(422, "Unprocessable Entity")
  case TooManyRequests extends Status(429, "Too Many Requests")
  case InternalServerError extends Status(500, "Internal Server Error")
  case NotImplemented extends Status(501, "Not Implemented")
  case BadGateway extends Status(502, "Bad Gateway")
  case ServiceUnavailable extends Status(503, "Service Unavailable")

// ============================================================================
// Request
// ============================================================================

/** HTTP Request - server-agnostic representation */
case class Request(
  method: Method,
  path: String,
  pathParams: Map[String, String],
  params: QueryParams,
  headers: Headers,
  body: Array[Byte]
):
  /** Get body as UTF-8 string */
  def bodyText: String = new String(body, StandardCharsets.UTF_8)

  /** Get path parameter by name */
  def pathParam(name: String): Option[String] = pathParams.get(name)

  /** Get path parameter as Int */
  def pathParamInt(name: String): Option[Int] = pathParams.get(name).flatMap(_.toIntOption)

  /** Get path parameter as Long */
  def pathParamLong(name: String): Option[Long] = pathParams.get(name).flatMap(_.toLongOption)

// ============================================================================
// Response
// ============================================================================

/** HTTP Response - server-agnostic representation */
case class Response(
  status: Status,
  headers: Map[String, String],
  body: Array[Byte]
):
  def withHeader(name: String, value: String): Response =
    copy(headers = headers + (name -> value))

  def withContentType(ct: String): Response =
    withHeader("Content-Type", ct)

// ============================================================================
// Query Parameters
// ============================================================================

/** Query parameters with typed accessors */
case class QueryParams(underlying: Map[String, String]):
  def get(name: String): Option[String] = underlying.get(name)

  def getOrElse(name: String, default: => String): String =
    underlying.getOrElse(name, default)

  def getAs[T](name: String)(using decoder: QueryDecoder[T]): Option[T] =
    underlying.get(name).flatMap(decoder.decode)

  def require(name: String): Either[String, String] =
    underlying.get(name).toRight(s"Missing required parameter: $name")

  def requireAs[T](name: String)(using decoder: QueryDecoder[T]): Either[String, T] =
    underlying.get(name) match
      case None => Left(s"Missing required parameter: $name")
      case Some(v) => decoder.decode(v).toRight(s"Invalid value for $name: $v")

  def has(name: String): Boolean = underlying.contains(name)
  def all: Map[String, String] = underlying

object QueryParams:
  val empty: QueryParams = QueryParams(Map.empty)

// ============================================================================
// Query Decoder Type Class
// ============================================================================

/** Type class for decoding query parameters */
trait QueryDecoder[T]:
  def decode(s: String): Option[T]

object QueryDecoder:
  given QueryDecoder[String] with
    def decode(s: String): Option[String] = Some(s)

  given QueryDecoder[Int] with
    def decode(s: String): Option[Int] = s.toIntOption

  given QueryDecoder[Long] with
    def decode(s: String): Option[Long] = s.toLongOption

  given QueryDecoder[Double] with
    def decode(s: String): Option[Double] = s.toDoubleOption

  given QueryDecoder[Boolean] with
    def decode(s: String): Option[Boolean] = s.toLowerCase match
      case "true" | "1" | "yes" => Some(true)
      case "false" | "0" | "no" => Some(false)
      case _ => None

  given QueryDecoder[java.time.LocalDate] with
    def decode(s: String): Option[java.time.LocalDate] =
      scala.util.Try(java.time.LocalDate.parse(s)).toOption

  given QueryDecoder[java.time.LocalDateTime] with
    def decode(s: String): Option[java.time.LocalDateTime] =
      scala.util.Try(java.time.LocalDateTime.parse(s)).toOption

// ============================================================================
// Headers
// ============================================================================

/** HTTP headers with typed accessors */
case class Headers(underlying: Map[String, String]):
  def get(name: String): Option[String] =
    underlying.get(name).orElse(underlying.get(name.toLowerCase))

  def contentType: Option[String] = get("Content-Type")
  def contentLength: Option[Long] = get("Content-Length").flatMap(_.toLongOption)
  def authorization: Option[String] = get("Authorization")
  def accept: Option[String] = get("Accept")
  def userAgent: Option[String] = get("User-Agent")
  def host: Option[String] = get("Host")

  def has(name: String): Boolean = get(name).isDefined
  def all: Map[String, String] = underlying

object Headers:
  val empty: Headers = Headers(Map.empty)

// ============================================================================
// Content Types
// ============================================================================

/** Common content types */
object ContentType:
  val Json = "application/json"
  val Xml = "application/xml"
  val Html = "text/html"
  val Plain = "text/plain"
  val OctetStream = "application/octet-stream"
  val Protobuf = "application/x-protobuf"
  val FormUrlEncoded = "application/x-www-form-urlencoded"
  val Csv = "text/csv"
