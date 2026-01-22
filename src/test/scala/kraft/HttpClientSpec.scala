package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.client.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

// Test data types
case class HttpBinResponse(
  url: String,
  args: Map[String, String] = Map.empty,
  headers: Map[String, String] = Map.empty,
  origin: String = "",
  data: String = ""
  // Note: 'json' field omitted because httpbin returns mixed types (strings and ints)
)

case class HttpBinPost(name: String, value: Int)

object HttpBinCodecs:
  given JsonValueCodec[HttpBinResponse] = JsonCodecMaker.make
  given JsonValueCodec[HttpBinPost] = JsonCodecMaker.make
  given JsonValueCodec[Map[String, String]] = JsonCodecMaker.make

class HttpClientSpec extends AnyFunSuite with Matchers:
  import HttpBinCodecs.given

  // Use httpbin.org for integration tests - a public HTTP testing service
  private val httpbin = "https://httpbin.org"

  // ==========================================================================
  // Unit tests for ClientResponse
  // ==========================================================================

  test("ClientResponse.Ok is success"):
    val response = ClientResponse.Ok("data")
    response.isSuccess shouldBe true
    response.isClientError shouldBe false
    response.isServerError shouldBe false
    response.status shouldBe 200

  test("ClientResponse.Created is success"):
    val response = ClientResponse.Created("data")
    response.isSuccess shouldBe true
    response.status shouldBe 201

  test("ClientResponse.NotFound is client error"):
    val response = ClientResponse.NotFound("not found")
    response.isSuccess shouldBe false
    response.isClientError shouldBe true
    response.status shouldBe 404

  test("ClientResponse.ServerError is server error"):
    val response = ClientResponse.ServerError("error")
    response.isSuccess shouldBe false
    response.isServerError shouldBe true
    response.status shouldBe 500

  test("ClientResponse.map transforms successful response"):
    val response: ClientResponse[Int] = ClientResponse.Ok(42)
    val mapped = response.map(_ * 2)
    mapped.getOrElse(0) shouldBe 84

  test("ClientResponse.map does not transform error"):
    val response: ClientResponse[Int] = ClientResponse.NotFound("error")
    val mapped = response.map(_ * 2)
    mapped shouldBe a[ClientResponse.NotFound]

  test("ClientResponse.flatMap chains successful responses"):
    val response: ClientResponse[Int] = ClientResponse.Ok(42)
    val chained = response.flatMap(v => ClientResponse.Ok(v.toString))
    chained.getOrElse("") shouldBe "42"

  test("ClientResponse.toOption returns Some for success"):
    val response: ClientResponse[Int] = ClientResponse.Ok(42)
    response.toOption shouldBe Some(42)

  test("ClientResponse.toOption returns None for error"):
    val response: ClientResponse[Int] = ClientResponse.NotFound("error")
    response.toOption shouldBe None

  test("ClientResponse.toEither returns Right for success"):
    val response: ClientResponse[Int] = ClientResponse.Ok(42)
    response.toEither shouldBe Right(42)

  test("ClientResponse.toEither returns Left for error"):
    val response: ClientResponse[Int] = ClientResponse.NotFound("error")
    response.toEither.isLeft shouldBe true

  test("ClientResponse.getOrElse returns value for success"):
    val response: ClientResponse[Int] = ClientResponse.Ok(42)
    response.getOrElse(0) shouldBe 42

  test("ClientResponse.getOrElse returns default for error"):
    val response: ClientResponse[Int] = ClientResponse.NotFound("error")
    response.getOrElse(99) shouldBe 99

  // ==========================================================================
  // Unit tests for HttpClient configuration
  // ==========================================================================

  test("HttpClient can be created with default settings"):
    val client = HttpClient()
    client should not be null

  test("HttpClient can set timeout"):
    val client = HttpClient().timeoutSeconds(10)
    client should not be null

  test("HttpClient can set base URL"):
    val client = HttpClient.withBaseUrl("https://api.example.com")
    client should not be null

  test("HttpClient can add default headers"):
    val client = HttpClient()
      .defaultHeader("Authorization", "Bearer token")
      .defaultHeader("User-Agent", "TestClient/1.0")
    client should not be null

  // ==========================================================================
  // Integration tests (against httpbin.org)
  // ==========================================================================

  test("GET request returns typed response"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client.get(s"$httpbin/get").execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    response.isSuccess shouldBe true
    val body = response.getOrElse(HttpBinResponse(""))
    body.url should include("/get")

  test("GET with query parameters"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .get(s"$httpbin/get")
      .param("page", 1)
      .param("limit", 20)
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.args.get("page") shouldBe Some("1")
    body.args.get("limit") shouldBe Some("20")

  test("GET with optional parameter"):
    val client = HttpClient().timeoutSeconds(10)
    val maybePage: Option[Int] = Some(5)
    val maybeSort: Option[String] = None

    val response = client
      .get(s"$httpbin/get")
      .paramOpt("page", maybePage)
      .paramOpt("sort", maybeSort)
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.args.get("page") shouldBe Some("5")
    body.args.get("sort") shouldBe None

  test("GET with custom headers"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .get(s"$httpbin/get")
      .header("X-Custom-Header", "test-value")
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    // httpbin normalizes header names
    body.headers.get("X-Custom-Header") shouldBe Some("test-value")

  test("GET with bearer token"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .get(s"$httpbin/get")
      .bearer("my-secret-token")
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.headers.get("Authorization") shouldBe Some("Bearer my-secret-token")

  test("GET raw string response"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client.get(s"$httpbin/get").executeString

    response shouldBe a[ClientResponse.Ok[?]]
    response.getOrElse("") should include("httpbin.org")

  test("POST with JSON body"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .post(s"$httpbin/post")
      .body(HttpBinPost("test", 42))
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.data should include("test")
    body.data should include("42")

  test("POST with string body"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .post(s"$httpbin/post")
      .bodyString("Hello, World!", "text/plain")
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.data shouldBe "Hello, World!"

  test("PUT request"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .put(s"$httpbin/put")
      .body(HttpBinPost("updated", 100))
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.data should include("updated")

  test("DELETE request"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .delete(s"$httpbin/delete")
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]

  test("PATCH request"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client
      .patch(s"$httpbin/patch")
      .body(HttpBinPost("patched", 1))
      .execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.data should include("patched")

  test("404 returns NotFound"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client.get(s"$httpbin/status/404").executeString

    response shouldBe a[ClientResponse.NotFound]
    response.status shouldBe 404

  test("401 returns Unauthorized"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client.get(s"$httpbin/status/401").executeString

    response shouldBe a[ClientResponse.Unauthorized]
    response.status shouldBe 401

  test("500 returns ServerError"):
    val client = HttpClient().timeoutSeconds(10)
    val response = client.get(s"$httpbin/status/500").executeString

    response shouldBe a[ClientResponse.ServerError]
    response.status shouldBe 500

  test("Connection error on invalid host"):
    val client = HttpClient().timeoutSeconds(2)
    val response = client.get("http://invalid.invalid.invalid").executeString

    response shouldBe a[ClientResponse.ConnectionError]
    response.status shouldBe -1

  test("Base URL simplifies requests"):
    val client = HttpClient.withBaseUrl(httpbin).timeoutSeconds(10)
    val response = client.get("get").execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    response.getOrElse(HttpBinResponse("")).url should include("/get")

  test("Default headers are applied to all requests"):
    val client = HttpClient()
      .timeoutSeconds(10)
      .defaultHeader("X-Default-Header", "always-present")

    val response = client.get(s"$httpbin/get").execute[HttpBinResponse]

    response shouldBe a[ClientResponse.Ok[?]]
    val body = response.getOrElse(HttpBinResponse(""))
    body.headers.get("X-Default-Header") shouldBe Some("always-present")
