package kraft.dsl

import java.nio.charset.StandardCharsets

/**
 * Pluggable JSON codec abstraction.
 *
 * This trait can be implemented for different JSON libraries:
 * - Jsoniter (high performance)
 * - Circe (type-safe, functional)
 * - uPickle (simple)
 * - Play JSON
 *
 * Example implementation for Jsoniter:
 * {{{
 * import com.github.plokhotnyuk.jsoniter_scala.core.*
 * import com.github.plokhotnyuk.jsoniter_scala.macros.*
 *
 * given jsoniterCodec[T: JsonValueCodec]: JsonCodec[T] with
 *   def encode(value: T): Array[Byte] = writeToArray(value)
 *   def decode(bytes: Array[Byte]): Either[String, T] =
 *     try Right(readFromArray[T](bytes))
 *     catch case e: Exception => Left(e.getMessage)
 * }}}
 */
trait JsonCodec[T]:
  def encode(value: T): Array[Byte]
  def decode(bytes: Array[Byte]): Either[String, T]

  /** Decode from string */
  def decodeString(s: String): Either[String, T] =
    decode(s.getBytes(StandardCharsets.UTF_8))

  /** Encode to string */
  def encodeString(value: T): String =
    new String(encode(value), StandardCharsets.UTF_8)

object JsonCodec:
  /** Summon a codec instance */
  def apply[T](using codec: JsonCodec[T]): JsonCodec[T] = codec

  /** Create a codec from encode/decode functions */
  def instance[T](
    enc: T => Array[Byte],
    dec: Array[Byte] => Either[String, T]
  ): JsonCodec[T] = new JsonCodec[T]:
    def encode(value: T): Array[Byte] = enc(value)
    def decode(bytes: Array[Byte]): Either[String, T] = dec(bytes)

// ============================================================================
// JSON-aware Response Builders
// ============================================================================

/** Ok response with JSON-serializable body */
def OkJson[T: JsonCodec](body: T): Response =
  Response(Status.Ok, Map("Content-Type" -> ContentType.Json), JsonCodec[T].encode(body))

/** Created response with JSON-serializable body */
def CreatedJson[T: JsonCodec](body: T): Response =
  Response(Status.Created, Map("Content-Type" -> ContentType.Json), JsonCodec[T].encode(body))

// ============================================================================
// JSON-aware Request Extensions
// ============================================================================

extension (request: Request)
  /** Decode body as JSON to type T */
  def as[T: JsonCodec]: Either[String, T] =
    JsonCodec[T].decode(request.body)

  /** Decode body, returning Option instead of Either */
  def asOpt[T: JsonCodec]: Option[T] =
    JsonCodec[T].decode(request.body).toOption

// ============================================================================
// JSON-aware Route Builders
// ============================================================================

extension (rwp: RouteWithPath)
  /** Add body extractor: > body[T] */
  def >[T: JsonCodec]: RouteWithBody[T] =
    RouteWithBody(rwp.method, rwp.path)

/** Route builder with JSON body extraction */
case class RouteWithBody[T: JsonCodec](method: Method, path: Path):
  /** Build route with handler receiving decoded body */
  def apply(handler: T => Response): Route =
    Route(method, path, req =>
      req.as[T] match
        case Right(body) => handler(body)
        case Left(err) => BadRequest(err)
    )

  /** Build route with handler receiving (body, request) */
  def withRequest(handler: (T, Request) => Response): Route =
    Route(method, path, req =>
      req.as[T] match
        case Right(body) => handler(body, req)
        case Left(err) => BadRequest(err)
    )

// ============================================================================
// Jsoniter Integration (Optional - only if jsoniter is on classpath)
// ============================================================================

/**
 * Jsoniter integration module.
 * Import this when using jsoniter-scala:
 * {{{
 * import kraft.dsl.json.jsoniter.given
 * }}}
 */
object jsoniter:
  import scala.util.Try

  /** Create a JsonCodec from a Jsoniter JsonValueCodec */
  def fromJsoniter[T](using jvc: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[T]): JsonCodec[T] =
    new JsonCodec[T]:
      def encode(value: T): Array[Byte] =
        com.github.plokhotnyuk.jsoniter_scala.core.writeToArray(value)
      def decode(bytes: Array[Byte]): Either[String, T] =
        Try(com.github.plokhotnyuk.jsoniter_scala.core.readFromArray[T](bytes))
          .toEither
          .left.map(_.getMessage)

  /** Automatic derivation from Jsoniter codecs */
  given autoJsoniter[T](using jvc: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[T]): JsonCodec[T] =
    fromJsoniter[T]
