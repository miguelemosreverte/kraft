package kraft.dsl.client

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters.*
import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * HTTP Client DSL - Type-safe HTTP client inspired by the server DSL.
 *
 * Example usage:
 * {{{
 * import kraft.dsl.client.*
 *
 * val client = HttpClient()
 *
 * // Simple GET
 * val response = client.get("https://api.example.com/health")
 *
 * // GET with typed response
 * val users: ClientResponse[List[User]] = client
 *   .request(GET, "https://api.example.com/users")
 *   .param("page", 1)
 *   .param("limit", 20)
 *   .header("Authorization", s"Bearer $token")
 *   .execute[List[User]]
 *
 * // POST with body
 * val created = client
 *   .request(POST, "https://api.example.com/events")
 *   .body(CreateEvent("Concert", date))
 *   .execute[Event]
 *
 * // Pattern match on response
 * response match
 *   case ClientResponse.Ok(data) => process(data)
 *   case ClientResponse.NotFound(_) => handleMissing()
 *   case ClientResponse.Error(status, body) => handleError(status, body)
 * }}}
 */

// =============================================================================
// HTTP Methods
// =============================================================================

enum HttpMethod:
  case GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS

// Note: We don't export HttpMethod.* to avoid conflicts with kraft.dsl.GET etc.
// Use HttpMethod.GET or import kraft.dsl.client.HttpMethod.* explicitly if needed.

// =============================================================================
// Client Response
// =============================================================================

/**
 * Type-safe response wrapper with pattern matching support.
 */
sealed trait ClientResponse[+A]:
  def status: Int
  def headers: Map[String, String]
  def isSuccess: Boolean = status >= 200 && status < 300
  def isClientError: Boolean = status >= 400 && status < 500
  def isServerError: Boolean = status >= 500

  /** Map over successful response body */
  def map[B](f: A => B): ClientResponse[B]

  /** FlatMap over successful response */
  def flatMap[B](f: A => ClientResponse[B]): ClientResponse[B]

  /** Get body or default */
  def getOrElse[B >: A](default: => B): B

  /** Convert to Option */
  def toOption: Option[A]

  /** Convert to Either */
  def toEither: Either[ClientResponse[Nothing], A]

object ClientResponse:
  // Success responses (2xx)
  case class Ok[A](body: A, status: Int = 200, headers: Map[String, String] = Map.empty) extends ClientResponse[A]:
    def map[B](f: A => B): ClientResponse[B] = Ok(f(body), status, headers)
    def flatMap[B](f: A => ClientResponse[B]): ClientResponse[B] = f(body)
    def getOrElse[B >: A](default: => B): B = body
    def toOption: Option[A] = Some(body)
    def toEither: Either[ClientResponse[Nothing], A] = Right(body)

  case class Created[A](body: A, status: Int = 201, headers: Map[String, String] = Map.empty) extends ClientResponse[A]:
    def map[B](f: A => B): ClientResponse[B] = Created(f(body), status, headers)
    def flatMap[B](f: A => ClientResponse[B]): ClientResponse[B] = f(body)
    def getOrElse[B >: A](default: => B): B = body
    def toOption: Option[A] = Some(body)
    def toEither: Either[ClientResponse[Nothing], A] = Right(body)

  case class NoContent(status: Int = 204, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  // Client errors (4xx)
  case class BadRequest(body: String, status: Int = 400, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  case class Unauthorized(body: String, status: Int = 401, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  case class Forbidden(body: String, status: Int = 403, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  case class NotFound(body: String, status: Int = 404, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  // Server errors (5xx)
  case class ServerError(body: String, status: Int = 500, headers: Map[String, String] = Map.empty) extends ClientResponse[Nothing]:
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  // Generic error (connection failed, timeout, etc.)
  case class ConnectionError(message: String, cause: Option[Throwable] = None) extends ClientResponse[Nothing]:
    val status: Int = -1
    val headers: Map[String, String] = Map.empty
    def map[B](f: Nothing => B): ClientResponse[B] = this
    def flatMap[B](f: Nothing => ClientResponse[B]): ClientResponse[B] = this
    def getOrElse[B >: Nothing](default: => B): B = default
    def toOption: Option[Nothing] = None
    def toEither: Either[ClientResponse[Nothing], Nothing] = Left(this)

  // Generic status for other codes
  case class Other[A](body: A, status: Int, headers: Map[String, String] = Map.empty) extends ClientResponse[A]:
    def map[B](f: A => B): ClientResponse[B] = Other(f(body), status, headers)
    def flatMap[B](f: A => ClientResponse[B]): ClientResponse[B] = if isSuccess then f(body) else this.asInstanceOf[ClientResponse[B]]
    def getOrElse[B >: A](default: => B): B = if isSuccess then body else default
    def toOption: Option[A] = if isSuccess then Some(body) else None
    def toEither: Either[ClientResponse[Nothing], A] = if isSuccess then Right(body) else Left(this.asInstanceOf[ClientResponse[Nothing]])

// =============================================================================
// Request Builder
// =============================================================================

/**
 * Fluent request builder with type-safe parameter and header setting.
 */
class RequestBuilder(
  private val client: HttpClient,
  private val method: HttpMethod,
  private val baseUrl: String,
  private val params: List[(String, String)] = Nil,
  private val headerMap: Map[String, String] = Map.empty,
  private val bodyContent: Option[Array[Byte]] = None,
  private val contentType: Option[String] = None
):
  /** Add a query parameter */
  def param(name: String, value: Any): RequestBuilder =
    new RequestBuilder(client, method, baseUrl, (name, value.toString) :: params, headerMap, bodyContent, contentType)

  /** Add a query parameter if value is defined */
  def paramOpt[A](name: String, value: Option[A]): RequestBuilder =
    value.fold(this)(v => param(name, v))

  /** Add a header */
  def header(name: String, value: String): RequestBuilder =
    new RequestBuilder(client, method, baseUrl, params, headerMap + (name -> value), bodyContent, contentType)

  /** Add Authorization header with Bearer token */
  def bearer(token: String): RequestBuilder =
    header("Authorization", s"Bearer $token")

  /** Add basic auth header */
  def basicAuth(username: String, password: String): RequestBuilder =
    val credentials = java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes)
    header("Authorization", s"Basic $credentials")

  /** Set JSON body */
  def body[A: JsonValueCodec](value: A): RequestBuilder =
    val bytes = writeToArray(value)
    new RequestBuilder(client, method, baseUrl, params, headerMap, Some(bytes), Some("application/json"))

  /** Set raw body with content type */
  def bodyRaw(content: Array[Byte], contentType: String): RequestBuilder =
    new RequestBuilder(client, method, baseUrl, params, headerMap, Some(content), Some(contentType))

  /** Set string body with content type */
  def bodyString(content: String, contentType: String = "text/plain"): RequestBuilder =
    bodyRaw(content.getBytes("UTF-8"), contentType)

  /** Execute request and decode response as type A */
  def execute[A: JsonValueCodec]: ClientResponse[A] =
    client.executeRequest(buildRequest(), bytes => readFromArray[A](bytes))

  /** Execute request and get raw string response */
  def executeString: ClientResponse[String] =
    client.executeRequest(buildRequest(), bytes => new String(bytes, "UTF-8"))

  /** Execute request and get raw bytes */
  def executeBytes: ClientResponse[Array[Byte]] =
    client.executeRequest(buildRequest(), identity)

  /** Execute request expecting no body (204 No Content) */
  def executeNoContent: ClientResponse[Unit] =
    client.executeRequest(buildRequest(), _ => ())

  private def buildRequest(): HttpRequest =
    val urlWithParams = if params.isEmpty then baseUrl
    else
      val queryString = params.reverse
        .map((k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}")
        .mkString("&")
      if baseUrl.contains("?") then s"$baseUrl&$queryString"
      else s"$baseUrl?$queryString"

    val builder = HttpRequest.newBuilder(URI.create(urlWithParams))

    // Set headers
    headerMap.foreach((k, v) => builder.header(k, v))
    contentType.foreach(ct => builder.header("Content-Type", ct))

    // Set method and body
    val bodyPublisher = bodyContent match
      case Some(bytes) => BodyPublishers.ofByteArray(bytes)
      case None => BodyPublishers.noBody()

    method match
      case HttpMethod.GET => builder.GET()
      case HttpMethod.POST => builder.POST(bodyPublisher)
      case HttpMethod.PUT => builder.PUT(bodyPublisher)
      case HttpMethod.DELETE => builder.DELETE()
      case HttpMethod.PATCH => builder.method("PATCH", bodyPublisher)
      case HttpMethod.HEAD => builder.method("HEAD", BodyPublishers.noBody())
      case HttpMethod.OPTIONS => builder.method("OPTIONS", BodyPublishers.noBody())

    builder.build()

// =============================================================================
// HTTP Client
// =============================================================================

/**
 * Type-safe HTTP client with fluent API.
 *
 * {{{
 * val client = HttpClient()
 *   .timeout(30.seconds)
 *   .defaultHeader("User-Agent", "MyApp/1.0")
 *
 * val response = client
 *   .request(GET, "https://api.example.com/users")
 *   .param("page", 1)
 *   .execute[List[User]]
 * }}}
 */
class HttpClient(
  private val timeout: Duration = Duration.ofSeconds(30),
  private val defaultHeaders: Map[String, String] = Map.empty,
  private val baseUrl: Option[String] = None
):
  private lazy val underlying: JHttpClient = JHttpClient.newBuilder()
    .connectTimeout(timeout)
    .build()

  /** Create a new client with different timeout */
  def timeout(duration: Duration): HttpClient =
    new HttpClient(duration, defaultHeaders, baseUrl)

  /** Create a new client with different timeout in seconds */
  def timeoutSeconds(seconds: Int): HttpClient =
    timeout(Duration.ofSeconds(seconds))

  /** Add a default header to all requests */
  def defaultHeader(name: String, value: String): HttpClient =
    new HttpClient(timeout, defaultHeaders + (name -> value), baseUrl)

  /** Set base URL for all requests */
  def withBaseUrl(url: String): HttpClient =
    new HttpClient(timeout, defaultHeaders, Some(url.stripSuffix("/")))

  /** Start building a request */
  def request(method: HttpMethod, url: String): RequestBuilder =
    val fullUrl = baseUrl.fold(url)(base => s"$base/$url".replace("//", "/").replaceFirst(":/", "://"))
    val builder = new RequestBuilder(this, method, fullUrl)
    defaultHeaders.foldLeft(builder) { case (b, (k, v)) => b.header(k, v) }

  // Convenience methods
  def get(url: String): RequestBuilder = request(HttpMethod.GET, url)
  def post(url: String): RequestBuilder = request(HttpMethod.POST, url)
  def put(url: String): RequestBuilder = request(HttpMethod.PUT, url)
  def delete(url: String): RequestBuilder = request(HttpMethod.DELETE, url)
  def patch(url: String): RequestBuilder = request(HttpMethod.PATCH, url)

  /** Execute a built request and decode response */
  private[client] def executeRequest[A](req: HttpRequest, decoder: Array[Byte] => A): ClientResponse[A] =
    Try(underlying.send(req, BodyHandlers.ofByteArray())) match
      case Failure(ex) =>
        ClientResponse.ConnectionError(ex.getMessage, Some(ex))

      case Success(response) =>
        val headers = response.headers().map().asScala.map((k, v) => k -> v.asScala.mkString(", ")).toMap
        val body = response.body()
        val status = response.statusCode()

        Try(decoder(body)) match
          case Failure(ex) =>
            // Decoding failed - return as string error
            ClientResponse.ServerError(s"Failed to decode response: ${ex.getMessage}", status, headers)

          case Success(decoded) =>
            status match
              case 200 => ClientResponse.Ok(decoded, status, headers)
              case 201 => ClientResponse.Created(decoded, status, headers)
              case 204 => ClientResponse.NoContent(status, headers).asInstanceOf[ClientResponse[A]]
              case s if s >= 200 && s < 300 => ClientResponse.Other(decoded, status, headers)
              case 400 => ClientResponse.BadRequest(new String(body, "UTF-8"), status, headers)
              case 401 => ClientResponse.Unauthorized(new String(body, "UTF-8"), status, headers)
              case 403 => ClientResponse.Forbidden(new String(body, "UTF-8"), status, headers)
              case 404 => ClientResponse.NotFound(new String(body, "UTF-8"), status, headers)
              case s if s >= 400 && s < 500 => ClientResponse.BadRequest(new String(body, "UTF-8"), status, headers)
              case s if s >= 500 => ClientResponse.ServerError(new String(body, "UTF-8"), status, headers)
              case _ => ClientResponse.Other(decoded, status, headers)

object HttpClient:
  /** Create a new HTTP client with default settings */
  def apply(): HttpClient = new HttpClient()

  /** Create a new HTTP client with custom timeout */
  def apply(timeout: Duration): HttpClient = new HttpClient(timeout)

  /** Create a new HTTP client with base URL */
  def withBaseUrl(url: String): HttpClient = new HttpClient().withBaseUrl(url)
