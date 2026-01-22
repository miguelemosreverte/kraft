package kraft.dsl

import java.nio.charset.StandardCharsets

/**
 * Test adapter for unit testing HTTP routes without a running server.
 *
 * ## Usage
 *
 * {{{
 * import kraft.dsl.*
 * import kraft.dsl.testing.*
 *
 * val routes = HttpRoutes(
 *   GET("/health") { _ => Ok("""{"status":"healthy"}""") },
 *   GET("/users/:id") { req =>
 *     req.pathParamInt("id") match
 *       case Some(id) => Ok(s"""{"id":$id}""")
 *       case None => BadRequest("Invalid ID")
 *   }
 * )
 *
 * // In your test:
 * val client = TestClient(routes)
 *
 * val response = client.get("/health")
 * assert(response.status == Status.Ok)
 * assert(response.bodyText == """{"status":"healthy"}""")
 *
 * val userResponse = client.get("/users/42")
 * assert(userResponse.status == Status.Ok)
 *
 * val notFoundResponse = client.get("/nonexistent")
 * assert(notFoundResponse.status == Status.NotFound)
 * }}}
 */
object testing:

  /**
   * Test client for executing requests against routes without a server.
   */
  class TestClient(routes: HttpRoutes):

    /** Execute a GET request */
    def get(path: String, headers: Map[String, String] = Map.empty): Response =
      execute(GET, path, headers, Array.empty)

    /** Execute a POST request with string body */
    def post(path: String, body: String = "", headers: Map[String, String] = Map.empty): Response =
      execute(POST, path, headers, body.getBytes(StandardCharsets.UTF_8))

    /** Execute a POST request with byte array body */
    def post(path: String, body: Array[Byte], headers: Map[String, String]): Response =
      execute(POST, path, headers, body)

    /** Execute a PUT request with string body */
    def put(path: String, body: String = "", headers: Map[String, String] = Map.empty): Response =
      execute(PUT, path, headers, body.getBytes(StandardCharsets.UTF_8))

    /** Execute a DELETE request */
    def delete(path: String, headers: Map[String, String] = Map.empty): Response =
      execute(DELETE, path, headers, Array.empty)

    /** Execute a PATCH request with string body */
    def patch(path: String, body: String = "", headers: Map[String, String] = Map.empty): Response =
      execute(PATCH, path, headers, body.getBytes(StandardCharsets.UTF_8))

    /** Execute an arbitrary request */
    def execute(
      method: Method,
      path: String,
      headers: Map[String, String] = Map.empty,
      body: Array[Byte] = Array.empty
    ): Response =
      val (cleanPath, queryParams) = parsePath(path)
      val request = Request(
        method = method,
        path = cleanPath,
        pathParams = Map.empty,
        params = QueryParams(queryParams),
        headers = Headers(headers),
        body = body
      )
      routes(request).getOrElse(NotFound)

    /** Execute with JSON body */
    def postJson[T: JsonCodec](path: String, body: T, headers: Map[String, String] = Map.empty): Response =
      val jsonBody = JsonCodec[T].encode(body)
      val headersWithContentType = headers + ("Content-Type" -> ContentType.Json)
      execute(POST, path, headersWithContentType, jsonBody)

    def putJson[T: JsonCodec](path: String, body: T, headers: Map[String, String] = Map.empty): Response =
      val jsonBody = JsonCodec[T].encode(body)
      val headersWithContentType = headers + ("Content-Type" -> ContentType.Json)
      execute(PUT, path, headersWithContentType, jsonBody)

    def patchJson[T: JsonCodec](path: String, body: T, headers: Map[String, String] = Map.empty): Response =
      val jsonBody = JsonCodec[T].encode(body)
      val headersWithContentType = headers + ("Content-Type" -> ContentType.Json)
      execute(PATCH, path, headersWithContentType, jsonBody)

    private def parsePath(path: String): (String, Map[String, String]) =
      val idx = path.indexOf('?')
      if idx < 0 then (path, Map.empty)
      else
        val cleanPath = path.substring(0, idx)
        val queryString = path.substring(idx + 1)
        val params = queryString
          .split('&')
          .flatMap { param =>
            val eq = param.indexOf('=')
            if eq > 0 then
              val key = param.substring(0, eq)
              val value = java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8")
              Some(key -> value)
            else None
          }
          .toMap
        (cleanPath, params)

  object TestClient:
    def apply(routes: HttpRoutes): TestClient = new TestClient(routes)

  // ============================================================================
  // Response Extensions for Testing
  // ============================================================================

  extension (response: Response)
    /** Get body as UTF-8 string */
    def bodyText: String = new String(response.body, StandardCharsets.UTF_8)

    /** Decode body as JSON */
    def bodyAs[T: JsonCodec]: Either[String, T] = JsonCodec[T].decode(response.body)

    /** Check if response is successful (2xx) */
    def isSuccess: Boolean = response.status.code >= 200 && response.status.code < 300

    /** Check if response is client error (4xx) */
    def isClientError: Boolean = response.status.code >= 400 && response.status.code < 500

    /** Check if response is server error (5xx) */
    def isServerError: Boolean = response.status.code >= 500

    /** Get header value */
    def header(name: String): Option[String] = response.headers.get(name)

    /** Get content type */
    def contentType: Option[String] = response.headers.get("Content-Type")

  // ============================================================================
  // Request Builder for Complex Test Scenarios
  // ============================================================================

  /** Fluent request builder for tests */
  class RequestBuilder private (
    private val method: Method,
    private val path: String,
    private val headers: Map[String, String],
    private val body: Array[Byte]
  ):
    def withHeader(name: String, value: String): RequestBuilder =
      new RequestBuilder(method, path, headers + (name -> value), body)

    def withHeaders(newHeaders: Map[String, String]): RequestBuilder =
      new RequestBuilder(method, path, headers ++ newHeaders, body)

    def withBody(newBody: String): RequestBuilder =
      new RequestBuilder(method, path, headers, newBody.getBytes(StandardCharsets.UTF_8))

    def withBody(newBody: Array[Byte]): RequestBuilder =
      new RequestBuilder(method, path, headers, newBody)

    def withJsonBody[T: JsonCodec](value: T): RequestBuilder =
      new RequestBuilder(
        method,
        path,
        headers + ("Content-Type" -> ContentType.Json),
        JsonCodec[T].encode(value)
      )

    def withAuth(token: String): RequestBuilder =
      withHeader("Authorization", s"Bearer $token")

    def withBasicAuth(username: String, password: String): RequestBuilder =
      val credentials = java.util.Base64.getEncoder.encodeToString(
        s"$username:$password".getBytes(StandardCharsets.UTF_8)
      )
      withHeader("Authorization", s"Basic $credentials")

    /** Execute the request against routes */
    def execute(routes: HttpRoutes): Response =
      TestClient(routes).execute(method, path, headers, body)

    /** Execute and get the request object (for debugging) */
    def toRequest: Request =
      val (cleanPath, queryParams) = parsePath(path)
      Request(
        method = method,
        path = cleanPath,
        pathParams = Map.empty,
        params = QueryParams(queryParams),
        headers = Headers(headers),
        body = body
      )

    private def parsePath(path: String): (String, Map[String, String]) =
      val idx = path.indexOf('?')
      if idx < 0 then (path, Map.empty)
      else
        val cleanPath = path.substring(0, idx)
        val queryString = path.substring(idx + 1)
        val params = queryString
          .split('&')
          .flatMap { param =>
            val eq = param.indexOf('=')
            if eq > 0 then Some(param.substring(0, eq) -> param.substring(eq + 1))
            else None
          }
          .toMap
        (cleanPath, params)

  object RequestBuilder:
    def get(path: String): RequestBuilder =
      new RequestBuilder(GET, path, Map.empty, Array.empty)

    def post(path: String): RequestBuilder =
      new RequestBuilder(POST, path, Map.empty, Array.empty)

    def put(path: String): RequestBuilder =
      new RequestBuilder(PUT, path, Map.empty, Array.empty)

    def delete(path: String): RequestBuilder =
      new RequestBuilder(DELETE, path, Map.empty, Array.empty)

    def patch(path: String): RequestBuilder =
      new RequestBuilder(PATCH, path, Map.empty, Array.empty)

  // ============================================================================
  // Assertions (for use with any test framework)
  // ============================================================================

  /** Assert response status */
  def assertStatus(response: Response, expected: Status): Unit =
    assert(response.status == expected,
      s"Expected status ${expected.code} ${expected.reason}, got ${response.status.code} ${response.status.reason}")

  /** Assert response body contains string */
  def assertBodyContains(response: Response, substring: String): Unit =
    assert(response.bodyText.contains(substring),
      s"Expected body to contain '$substring', got: ${response.bodyText}")

  /** Assert response body equals string */
  def assertBodyEquals(response: Response, expected: String): Unit =
    assert(response.bodyText == expected,
      s"Expected body '$expected', got: ${response.bodyText}")

  /** Assert response header */
  def assertHeader(response: Response, name: String, expected: String): Unit =
    assert(response.headers.get(name).contains(expected),
      s"Expected header $name='$expected', got: ${response.headers.get(name)}")
