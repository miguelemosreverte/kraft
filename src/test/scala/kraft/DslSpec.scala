package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.*
import kraft.dsl.testing.{TestClient, RequestBuilder}

class DslSpec extends AnyFunSuite with Matchers:

  test("TestClient can test simple GET routes"):
    val routes = HttpRoutes(
      GET("/health") { _ => Ok("""{"status":"healthy"}""") }
    )

    val client = TestClient(routes)
    val response = client.get("/health")

    response.status shouldBe Status.Ok
    new String(response.body) shouldBe """{"status":"healthy"}"""

  test("TestClient returns NotFound for unknown routes"):
    val routes = HttpRoutes(
      GET("/known") { _ => Ok("ok") }
    )

    val client = TestClient(routes)
    val response = client.get("/unknown")

    response.status shouldBe Status.NotFound

  test("TestClient extracts query parameters via request"):
    val routes = HttpRoutes(
      GET("/search") { req =>
        val page = req.params.getAs[Int]("page").getOrElse(1)
        Ok(s"""{"page":$page}""")
      }
    )

    val client = TestClient(routes)

    val response1 = client.get("/search")
    new String(response1.body) shouldBe """{"page":1}"""

    val response2 = client.get("/search?page=5")
    new String(response2.body) shouldBe """{"page":5}"""

  test("Routes can be composed with <+>"):
    val healthRoutes = HttpRoutes(
      GET("/health") { _ => Ok("healthy") }
    )

    val userRoutes = HttpRoutes(
      GET("/users") { _ => Ok("[]") }
    )

    val allRoutes = healthRoutes <+> userRoutes

    val client = TestClient(allRoutes)
    client.get("/health").status shouldBe Status.Ok
    client.get("/users").status shouldBe Status.Ok

  test("Path pattern matching works"):
    val routes = HttpRoutes(
      GET(Root / "users" / StringVar("id") / "posts") { req =>
        val userId = req.pathParam("id").getOrElse("unknown")
        Ok(s"""{"userId":"$userId"}""")
      }
    )

    val client = TestClient(routes)
    val response = client.get("/users/42/posts")
    response.status shouldBe Status.Ok
    new String(response.body) shouldBe """{"userId":"42"}"""

  test("Response helpers create correct status codes"):
    Ok("test").status shouldBe Status.Ok
    BadRequest("error").status shouldBe Status.BadRequest
    NotFound.status shouldBe Status.NotFound
    Created("created").status shouldBe Status.Created
    InternalServerError().status shouldBe Status.InternalServerError

  test("Response helpers set content type"):
    val jsonResponse = Ok("""{"key":"value"}""")
    jsonResponse.headers.get("Content-Type") shouldBe Some("application/json")

    val htmlResponse = OkHtml("<html></html>")
    htmlResponse.headers.get("Content-Type") shouldBe Some("text/html")

    val textResponse = OkText("plain text")
    textResponse.headers.get("Content-Type") shouldBe Some("text/plain")

  test("Headers can be accessed from request"):
    val routes = HttpRoutes(
      GET("/whoami") { req =>
        val userAgent = req.headers.userAgent.getOrElse("unknown")
        Ok(s"""{"agent":"$userAgent"}""")
      }
    )

    val client = TestClient(routes)
    val response = client.get("/whoami", Map("User-Agent" -> "TestClient/1.0"))
    new String(response.body) should include("TestClient/1.0")

  test("POST requests with body work"):
    val routes = HttpRoutes(
      POST("/echo") { req =>
        Ok(req.bodyText)
      }
    )

    val client = TestClient(routes)
    val response = client.post("/echo", "Hello, World!")
    new String(response.body) shouldBe "Hello, World!"
