package kraft.dsl

import java.nio.charset.StandardCharsets

/**
 * Routing DSL for HTTP routes.
 *
 * Provides composable route definitions:
 * {{{
 * val routes = HttpRoutes(
 *   GET("/health") { _ => Ok("healthy") },
 *   GET("/users") ? param[Int]("page") { page => Ok(s"Page: $page") },
 *   POST("/users") > body[CreateUser] { user => Created(user.id) }
 * )
 * }}}
 */

// ============================================================================
// Response Builders
// ============================================================================

/** Ok response with string body */
def Ok(body: String, contentType: String = ContentType.Json): Response =
  Response(Status.Ok, Map("Content-Type" -> contentType), body.getBytes(StandardCharsets.UTF_8))

/** Ok response with raw bytes */
def Ok(body: Array[Byte], contentType: String): Response =
  Response(Status.Ok, Map("Content-Type" -> contentType), body)

/** Empty Ok response */
val OkEmpty: Response = Response(Status.Ok, Map.empty, Array.empty)

/** Created response with string body */
def Created(body: String, contentType: String = ContentType.Json): Response =
  Response(Status.Created, Map("Content-Type" -> contentType), body.getBytes(StandardCharsets.UTF_8))

/** No content response */
val NoContent: Response = Response(Status.NoContent, Map.empty, Array.empty)

/** Bad request with error message */
def BadRequest(message: String): Response =
  Response(Status.BadRequest, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

/** Bad request with raw body */
def BadRequest(body: Array[Byte]): Response =
  Response(Status.BadRequest, Map("Content-Type" -> ContentType.Json), body)

val NotFound: Response =
  Response(Status.NotFound, Map("Content-Type" -> ContentType.Json),
    """{"error":"not found"}""".getBytes(StandardCharsets.UTF_8))

def NotFound(message: String): Response =
  Response(Status.NotFound, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

val MethodNotAllowed: Response =
  Response(Status.MethodNotAllowed, Map("Content-Type" -> ContentType.Json),
    """{"error":"method not allowed"}""".getBytes(StandardCharsets.UTF_8))

def Unauthorized(message: String = "unauthorized"): Response =
  Response(Status.Unauthorized, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

def Forbidden(message: String = "forbidden"): Response =
  Response(Status.Forbidden, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

def InternalServerError(message: String = "internal server error"): Response =
  Response(Status.InternalServerError, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

def ServiceUnavailable(message: String = "service unavailable"): Response =
  Response(Status.ServiceUnavailable, Map("Content-Type" -> ContentType.Json),
    s"""{"error":"$message"}""".getBytes(StandardCharsets.UTF_8))

/** Generic response builder */
def respond(status: Status, body: Array[Byte], contentType: String): Response =
  Response(status, Map("Content-Type" -> contentType), body)

def respond(status: Status, body: String, contentType: String): Response =
  Response(status, Map("Content-Type" -> contentType), body.getBytes(StandardCharsets.UTF_8))

/** Content-type specific builders */
def OkXml(body: String): Response =
  Response(Status.Ok, Map("Content-Type" -> ContentType.Xml), body.getBytes(StandardCharsets.UTF_8))

def OkHtml(body: String): Response =
  Response(Status.Ok, Map("Content-Type" -> ContentType.Html), body.getBytes(StandardCharsets.UTF_8))

def OkText(body: String): Response =
  Response(Status.Ok, Map("Content-Type" -> ContentType.Plain), body.getBytes(StandardCharsets.UTF_8))

def OkCsv(body: String): Response =
  Response(Status.Ok, Map("Content-Type" -> ContentType.Csv), body.getBytes(StandardCharsets.UTF_8))

def OkBytes(body: Array[Byte], contentType: String): Response =
  Response(Status.Ok, Map("Content-Type" -> contentType), body)

// ============================================================================
// Route Definition
// ============================================================================

type RouteHandler = PartialFunction[Request, Response]

/** A single route definition */
case class Route(method: Method, path: Path, handler: Request => Response):
  /** Check if this route can potentially match the request */
  def canMatch(req: Request): Boolean =
    req.method == method && path.matches(req.path).isDefined

  /** Try to match and handle the request */
  def matchRequest(req: Request): Option[Response] =
    if req.method != method then None
    else
      path.matches(req.path).map { pathParams =>
        handler(req.copy(pathParams = pathParams))
      }

// ============================================================================
// HttpRoutes - Composable Route Container
// ============================================================================

/** Container for HTTP routes */
class HttpRoutes private[dsl] (private[dsl] val handler: RouteHandler):
  def apply(request: Request): Option[Response] =
    handler.lift(request)

  def orElse(other: HttpRoutes): HttpRoutes =
    new HttpRoutes(handler.orElse(other.handler))

  /** Compose routes with <+> operator */
  def <+>(other: HttpRoutes): HttpRoutes = orElse(other)

  /** Get all defined routes for inspection/documentation */
  private[dsl] var routes: Seq[Route] = Seq.empty

object HttpRoutes:
  /** Create routes from a partial function using pattern matching */
  def of(pf: PartialFunction[(Request, MethodPath), Response]): HttpRoutes =
    new HttpRoutes({
      case req if pf.isDefinedAt((req, MethodPath(req.method, Path(Nil)))) =>
        pf((req, MethodPath(req.method, Path(Nil))))
      case req if {
        val methodPath = MethodPath(req.method, pathToPattern(req.path))
        pf.isDefinedAt((req, methodPath))
      } =>
        val methodPath = MethodPath(req.method, pathToPattern(req.path))
        pf((req, methodPath))
    })

  /** Create routes with explicit route definitions */
  def apply(routes: Route*): HttpRoutes =
    val httpRoutes = new HttpRoutes({
      case req if routes.exists(_.canMatch(req)) =>
        routes.view
          .flatMap(_.matchRequest(req))
          .head
    })
    httpRoutes.routes = routes
    httpRoutes

  val empty: HttpRoutes = new HttpRoutes(PartialFunction.empty)

  private def pathToPattern(path: String): Path =
    Path(path.stripPrefix("/").split("/").filter(_.nonEmpty).toList)

// ============================================================================
// Route Builder DSL
// ============================================================================

private def pathFromString(path: String): Path =
  val segments = path.stripPrefix("/").split("/").filter(_.nonEmpty).toList
  Path(segments.map {
    case s if s.startsWith(":") => StringVar(s.stripPrefix(":"))
    case s => s
  })

extension (method: Method)
  /** Simple form: GET("/path") { req => response } */
  def apply(path: String)(handler: Request => Response): Route =
    Route(method, pathFromString(path), handler)

  def apply(path: Path)(handler: Request => Response): Route =
    Route(method, path, handler)

  /** Chaining form: GET / "path" */
  def /(segment: String): RouteWithPath =
    RouteWithPath(method, pathFromString(segment))

  def /(path: Path): RouteWithPath =
    RouteWithPath(method, path)

// ============================================================================
// Route Builder with Path
// ============================================================================

case class RouteWithPath(method: Method, path: Path):
  /** Add optional query param: ? param[T]("name") */
  def ?[A](extractor: ParamExtractor[A]): RouteWithParam1[A] =
    RouteWithParam1(method, path, extractor)

  /** Add required query param: ?! param[T]("name") */
  def ?![A](extractor: ParamExtractor[A]): RouteWithRequiredParam1[A] =
    RouteWithRequiredParam1(method, path, extractor)

  /** Add header extractor: ?> header[T]("name") */
  def ?>[A](extractor: HeaderExtractor[A]): RouteWithHeader1[A] =
    RouteWithHeader1(method, path, extractor)

  /** Simple handler with request */
  def apply(handler: Request => Response): Route =
    Route(method, path, handler)

// ============================================================================
// Parameter Extractors
// ============================================================================

/** Extractor for a single query parameter */
case class ParamExtractor[A](name: String, decoder: QueryDecoder[A]):
  def extract(params: QueryParams): Option[A] =
    params.get(name).flatMap(decoder.decode)

  def extractRequired(params: QueryParams): Either[String, A] =
    params.get(name) match
      case None => Left(s"Missing required parameter: $name")
      case Some(v) => decoder.decode(v).toRight(s"Invalid value for $name: $v")

/** Create optional param extractor */
def param[A](name: String)(using decoder: QueryDecoder[A]): ParamExtractor[A] =
  ParamExtractor(name, decoder)

/** Create required param extractor */
def requiredParam[A](name: String)(using decoder: QueryDecoder[A]): ParamExtractor[A] =
  ParamExtractor(name, decoder)

// ============================================================================
// Header Extractors
// ============================================================================

/** Extractor for a single header */
case class HeaderExtractor[A](name: String, decoder: QueryDecoder[A]):
  def extract(headers: Headers): Option[A] =
    headers.get(name).flatMap(decoder.decode)

  def extractRequired(headers: Headers): Either[String, A] =
    headers.get(name) match
      case None => Left(s"Missing required header: $name")
      case Some(v) => decoder.decode(v).toRight(s"Invalid value for header $name: $v")

/** Create optional header extractor */
def header[A](name: String)(using decoder: QueryDecoder[A]): HeaderExtractor[A] =
  HeaderExtractor(name, decoder)

/** Create required header extractor */
def requiredHeader[A](name: String)(using decoder: QueryDecoder[A]): HeaderExtractor[A] =
  HeaderExtractor(name, decoder)

// ============================================================================
// Route Builders with Parameters
// ============================================================================

case class RouteWithParam1[A](method: Method, path: Path, p1: ParamExtractor[A]):
  def &[B](p2: ParamExtractor[B]): RouteWithParam2[A, B] =
    RouteWithParam2(method, path, p1, p2)

  def apply(handler: Option[A] => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params)))

  def withRequest(handler: (Option[A], Request) => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params), req))

case class RouteWithRequiredParam1[A](method: Method, path: Path, p1: ParamExtractor[A]):
  def &![B](p2: ParamExtractor[B]): RouteWithRequiredParam2[A, B] =
    RouteWithRequiredParam2(method, path, p1, p2)

  def apply(handler: A => Response): Route =
    Route(method, path, req =>
      p1.extractRequired(req.params) match
        case Right(a) => handler(a)
        case Left(err) => BadRequest(err)
    )

case class RouteWithParam2[A, B](method: Method, path: Path, p1: ParamExtractor[A], p2: ParamExtractor[B]):
  def &[C](p3: ParamExtractor[C]): RouteWithParam3[A, B, C] =
    RouteWithParam3(method, path, p1, p2, p3)

  def apply(handler: (Option[A], Option[B]) => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params), p2.extract(req.params)))

  def withRequest(handler: (Option[A], Option[B], Request) => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params), p2.extract(req.params), req))

case class RouteWithRequiredParam2[A, B](method: Method, path: Path, p1: ParamExtractor[A], p2: ParamExtractor[B]):
  def apply(handler: (A, B) => Response): Route =
    Route(method, path, req =>
      (p1.extractRequired(req.params), p2.extractRequired(req.params)) match
        case (Right(a), Right(b)) => handler(a, b)
        case (Left(err), _) => BadRequest(err)
        case (_, Left(err)) => BadRequest(err)
    )

case class RouteWithParam3[A, B, C](method: Method, path: Path, p1: ParamExtractor[A], p2: ParamExtractor[B], p3: ParamExtractor[C]):
  def apply(handler: (Option[A], Option[B], Option[C]) => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params), p2.extract(req.params), p3.extract(req.params)))

  def withRequest(handler: (Option[A], Option[B], Option[C], Request) => Response): Route =
    Route(method, path, req => handler(p1.extract(req.params), p2.extract(req.params), p3.extract(req.params), req))

// ============================================================================
// Route Builders with Headers
// ============================================================================

case class RouteWithHeader1[A](method: Method, path: Path, h1: HeaderExtractor[A]):
  def &>[B](h2: HeaderExtractor[B]): RouteWithHeader2[A, B] =
    RouteWithHeader2(method, path, h1, h2)

  def &[B](p: ParamExtractor[B]): RouteWithHeaderAndParam[A, B] =
    RouteWithHeaderAndParam(method, path, h1, p)

  def apply(handler: Option[A] => Response): Route =
    Route(method, path, req => handler(h1.extract(req.headers)))

  def withRequest(handler: (Option[A], Request) => Response): Route =
    Route(method, path, req => handler(h1.extract(req.headers), req))

case class RouteWithHeader2[A, B](method: Method, path: Path, h1: HeaderExtractor[A], h2: HeaderExtractor[B]):
  def apply(handler: (Option[A], Option[B]) => Response): Route =
    Route(method, path, req => handler(h1.extract(req.headers), h2.extract(req.headers)))

  def withRequest(handler: (Option[A], Option[B], Request) => Response): Route =
    Route(method, path, req => handler(h1.extract(req.headers), h2.extract(req.headers), req))

case class RouteWithHeaderAndParam[H, P](method: Method, path: Path, h: HeaderExtractor[H], p: ParamExtractor[P]):
  def apply(handler: (Option[H], Option[P]) => Response): Route =
    Route(method, path, req => handler(h.extract(req.headers), p.extract(req.params)))

  def withRequest(handler: (Option[H], Option[P], Request) => Response): Route =
    Route(method, path, req => handler(h.extract(req.headers), p.extract(req.params), req))
