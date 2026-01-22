# Kraft DSL: Type-Safe HTTP Routing

*An http4s-inspired DSL for building type-safe, expressive HTTP routes.*

<!-- @meta:title="Kraft DSL - Type-Safe HTTP Routing" -->
<!-- @meta:author="The Kraft Team" -->
<!-- @meta:generated="true" -->

---

## Introduction

The Kraft DSL is a standalone routing library that provides type-safe HTTP request handling. Inspired by [http4s](https://http4s.org), it brings the best of functional programming to HTTP routing while remaining server-agnostic.

### Key Features

- **Type-safe parameter extraction** — Query params, headers, and bodies arrive already typed
- **Compile-time safety** — Invalid routes fail at compile time, not runtime
- **Server-agnostic** — Works with any HTTP backend (Netty, Jetty, etc.)
- **Testable** — Test routes without starting a server using `TestClient`
- **Composable** — Combine route sets with the `<+>` operator
- **Zero runtime overhead** — No reflection, no runtime type checking

### Quick Example

```scala
import kraft.dsl.*

val routes = HttpRoutes(
  // Simple route
  GET("/health") { _ => Ok("""{"status":"ok"}""") },

  // With typed query params - params arrive already parsed!
  (GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") {
    (startsAt, endsAt) => Ok(search(startsAt, endsAt))
  },

  // With JSON body
  (POST / "events") > body[CreateEvent] { event =>
    CreatedJson(event)
  }
)
```

---

## Chapter 1: Getting Started

### Installation

The DSL is part of the Kraft server library. Import it with:

```scala
import kraft.dsl.*
```

For backwards compatibility with older code:

```scala
import kraft.server.dsl.*  // Re-exports everything from kraft.dsl
```

### Your First Route

The simplest route handles a request and returns a response:

```scala
val routes = HttpRoutes(
  GET("/health") { req =>
    Ok("""{"status":"healthy"}""")
  }
)
```

Breaking this down:
- `GET("/health")` — Matches GET requests to `/health`
- `{ req => ... }` — Handler function receives the request
- `Ok(...)` — Returns HTTP 200 with JSON body

### Running Routes

Routes are server-agnostic. Connect them to any HTTP server:

```scala
// With Kraft's Netty server
HttpServer(routes).start(8080)

// Or test them directly
val client = TestClient(routes)
val response = client.get("/health")
assert(response.status == Status.Ok)
```

---

## Chapter 2: Simple Routes

For routes that need full request access:

```scala
// Health check
GET("/health") { req =>
  Ok("""{"status":"healthy"}""")
}

// Using request details
GET("/echo") { req =>
  val userAgent = req.headers.userAgent.getOrElse("unknown")
  val host = req.headers.host.getOrElse("localhost")
  Ok(s"""{"userAgent":"$userAgent","host":"$host"}""")
}

// POST with JSON body parsing
POST("/events") { req =>
  req.as[CreateEvent] match
    case Right(event) => CreatedJson(event)
    case Left(err) => BadRequest(err)
}

// Access all query parameters
GET("/debug") { req =>
  val allParams = req.params.all
  Ok(s"""{"params":${allParams.size}}""")
}
```

### Available HTTP Methods

```scala
GET("/path") { req => ... }
POST("/path") { req => ... }
PUT("/path") { req => ... }
DELETE("/path") { req => ... }
PATCH("/path") { req => ... }
HEAD("/path") { req => ... }
OPTIONS("/path") { req => ... }
```

---

## Chapter 3: Typed Query Parameters

The DSL's killer feature: parameters arrive as `Option[T]`, already parsed. Invalid values become `None`.

### Single Parameter

```scala
// Single optional parameter
(GET / "users") ? param[Int]("page") { page =>
  // page: Option[Int]
  val pageNum = page.getOrElse(1)
  Ok(listUsers(pageNum))
}
```

### Multiple Parameters

```scala
// Two parameters
(GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") {
  (startsAt, endsAt) =>
    // startsAt: Option[LocalDateTime]
    // endsAt: Option[LocalDateTime]
    Ok(search(startsAt, endsAt))
}

// Three parameters
(GET / "filter") ? param[String]("category") & param[Int]("limit") & param[Int]("offset") {
  (category, limit, offset) =>
    val cat = category.getOrElse("all")
    val lim = limit.getOrElse(20)
    val off = offset.getOrElse(0)
    Ok(filter(cat, lim, off))
}
```

### Required Parameters

Use `?!` and `&!` for required parameters. Returns 400 if missing:

```scala
// Required parameter - returns 400 if missing
(GET / "user") ?! param[Long]("id") { id =>
  // id: Long (not Option!)
  Ok(getUser(id))
}

// Two required parameters
(GET / "range") ?! param[Int]("from") &! param[Int]("to") { (from, to) =>
  // Both are required - 400 if either missing
  Ok(getRange(from, to))
}
```

### Built-in Parameter Types

The following types work out of the box:

| Type | Example Input | Parsed As |
|------|---------------|-----------|
| `String` | `name=Alice` | `Some("Alice")` |
| `Int` | `page=5` | `Some(5)` |
| `Long` | `id=123456789` | `Some(123456789L)` |
| `Double` | `price=19.99` | `Some(19.99)` |
| `Boolean` | `active=true` | `Some(true)` |
| `LocalDate` | `date=2024-01-15` | `Some(LocalDate)` |
| `LocalDateTime` | `ts=2024-01-15T10:30:00` | `Some(LocalDateTime)` |

### With Request Access

Use `withRequest` when you need both typed params and the full request:

```scala
(GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") withRequest {
  (startsAt, endsAt, req) =>
    // Validate: if raw param exists but parsed is None, it was invalid
    if req.params.has("starts_at") && startsAt.isEmpty then
      BadRequest("Invalid starts_at format")
    else if req.params.has("ends_at") && endsAt.isEmpty then
      BadRequest("Invalid ends_at format")
    else
      Ok(search(startsAt, endsAt))
}
```

---

## Chapter 4: Typed Headers

Headers work the same way as parameters:

```scala
// Single header
(GET / "api/protected") ?> header[String]("Authorization") { auth =>
  auth match
    case Some(token) if token.startsWith("Bearer ") =>
      val jwt = token.stripPrefix("Bearer ")
      Ok(protectedData(jwt))
    case Some(_) => BadRequest("Invalid authorization format")
    case None => Unauthorized("Missing authorization header")
}

// Multiple headers
(GET / "api/trace") ?> header[String]("Authorization") &> header[String]("X-Request-Id") {
  (auth, requestId) =>
    val reqId = requestId.getOrElse(java.util.UUID.randomUUID.toString)
    Ok(tracedResponse(auth, reqId))
}
```

### Common Header Shortcuts

```scala
GET("/info") { req =>
  val ct = req.headers.contentType      // Option[String]
  val ua = req.headers.userAgent        // Option[String]
  val auth = req.headers.authorization  // Option[String]
  val host = req.headers.host           // Option[String]
  val accept = req.headers.accept       // Option[String]
  Ok("...")
}
```

---

## Chapter 5: Typed Body Decoding

For POST/PUT with JSON bodies, use the `>` operator:

```scala
// Simple body decoding
(POST / "events") > body[CreateEvent] { event =>
  // event: CreateEvent (already decoded from JSON)
  val saved = repository.save(event)
  CreatedJson(saved)
}

// Body with request access
(POST / "events") > body[CreateEvent] withRequest { (event, req) =>
  val userId = req.headers.get("X-User-Id")
  val saved = repository.save(event, userId)
  CreatedJson(saved)
}
```

### JSON Codec Integration

The DSL uses a pluggable `JsonCodec` trait. For Jsoniter-scala:

```scala
import kraft.dsl.*
import kraft.dsl.json.jsoniter.given
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class Event(id: String, name: String)
given JsonValueCodec[Event] = JsonCodecMaker.make

// Now you can use OkJson and CreatedJson
(POST / "events") > body[Event] { event =>
  CreatedJson(event)  // Automatically serializes to JSON
}
```

---

## Chapter 6: Combined Extraction

Mix parameters, headers, and body in any combination:

```scala
// Params + Headers
(GET / "data") ? param[Int]("page") &> header[String]("Authorization") {
  (page, auth) =>
    auth match
      case Some(token) => Ok(getData(page.getOrElse(1), token))
      case None => Unauthorized("Token required")
}

// Two params + header
(GET / "search") ? param[String]("q") & param[Int]("limit") &> header[String]("X-Api-Key") {
  (query, limit, apiKey) =>
    apiKey match
      case Some(key) if isValidKey(key) =>
        Ok(search(query.getOrElse(""), limit.getOrElse(10)))
      case _ => Forbidden("Invalid API key")
}

// Header + params (order matters for the operator)
(GET / "api") ?> header[String]("Authorization") & param[Int]("page") {
  (auth, page) => Ok(apiData(auth, page))
}
```

---

## Chapter 7: Path Variables

Extract values from URL paths using pattern matching:

```scala
// Using pattern matching style
HttpRoutes.of {
  case req @ GET -> Root / "users" / IntVar(id) =>
    Ok(getUser(id))

  case req @ GET -> Root / "posts" / LongVar(postId) / "comments" / IntVar(commentId) =>
    Ok(getComment(postId, commentId))

  case req @ GET -> Root / "files" / UUIDVar(fileId) =>
    Ok(getFile(fileId))
}
```

### Available Path Extractors

| Extractor | Type | Example Match |
|-----------|------|---------------|
| `IntVar(id)` | `Int` | `/users/42` → `42` |
| `LongVar(id)` | `Long` | `/posts/123456789` → `123456789L` |
| `UUIDVar(id)` | `java.util.UUID` | `/files/550e8400-e29b-...` |
| `StringVar("name")` | `String` | `/users/alice` → `"alice"` |

### Named Path Parameters

You can also use named path parameters:

```scala
val routes = HttpRoutes(
  GET(Root / "users" / StringVar("id") / "posts") { req =>
    val userId = req.pathParam("id").getOrElse("unknown")
    Ok(s"""{"userId":"$userId"}""")
  }
)
```

---

## Chapter 8: Response Builders

The DSL provides response builders for common HTTP status codes.

### Success Responses

```scala
Ok("body")                        // 200 with JSON
Ok("body", "text/plain")          // 200 with custom content type
OkJson(myObject)                  // 200 with auto-serialized JSON
OkText("plain text")              // 200 with text/plain
OkHtml("<html>...</html>")        // 200 with text/html
OkXml("<root>...</root>")         // 200 with application/xml
OkCsv("a,b,c")                    // 200 with text/csv
OkBytes(bytes, "image/png")       // 200 with binary data

Created("body")                   // 201 Created
CreatedJson(myObject)             // 201 with auto-serialized JSON
NoContent                         // 204 No Content
```

### Error Responses

```scala
BadRequest("error message")       // 400 Bad Request
Unauthorized("not logged in")     // 401 Unauthorized
Forbidden("access denied")        // 403 Forbidden
NotFound                          // 404 Not Found
MethodNotAllowed("GET, POST")     // 405 Method Not Allowed
InternalServerError("oops")       // 500 Internal Server Error
ServiceUnavailable("maintenance") // 503 Service Unavailable
```

### Custom Response

```scala
respond(Status.Ok, myData, "application/x-custom")
respond(Status.Created, body, ContentType.Json)
```

### Content Type Constants

```scala
import kraft.dsl.ContentType

ContentType.Json           // "application/json"
ContentType.Xml            // "application/xml"
ContentType.Html           // "text/html"
ContentType.Plain          // "text/plain"
ContentType.Csv            // "text/csv"
ContentType.Protobuf       // "application/x-protobuf"
ContentType.OctetStream    // "application/octet-stream"
ContentType.FormUrlEncoded // "application/x-www-form-urlencoded"
```

---

## Chapter 9: Route Composition

Combine route sets with the `<+>` operator:

```scala
// Feature-based organization
object HealthRoutes:
  val routes = HttpRoutes(
    GET("/health") { _ => Ok("""{"status":"ok"}""") },
    GET("/ready") { _ => Ok("""{"ready":true}""") }
  )

object SearchRoutes:
  def routes(repo: EventRepository) = HttpRoutes(
    (GET / "search") ? param[String]("q") { q =>
      Ok(repo.search(q.getOrElse("")))
    }
  )

object MetricsRoutes:
  def routes(metrics: Metrics) = HttpRoutes(
    GET("/metrics") { _ => OkText(metrics.toPrometheus) }
  )

// Compose them all
val allRoutes =
  HealthRoutes.routes <+>
  SearchRoutes.routes(eventRepo) <+>
  MetricsRoutes.routes(metrics)
```

### Combining with Varargs

```scala
val combined = HttpRoutes(
  GET("/a") { _ => Ok("a") },
  GET("/b") { _ => Ok("b") },
  GET("/c") { _ => Ok("c") }
)
```

---

## Chapter 10: Custom Type Decoders

The DSL uses `QueryDecoder[T]` for parsing. Add custom decoders with a simple `given`:

```scala
// Custom ID type
case class UserId(value: Long)

given QueryDecoder[UserId] with
  def decode(s: String): Option[UserId] =
    s.toLongOption.map(UserId(_))

// Now use it in routes
(GET / "users") ? param[UserId]("id") { userId =>
  userId match
    case Some(id) => Ok(getUser(id))
    case None => BadRequest("Invalid user ID")
}
```

### Custom Enum

```scala
enum Status:
  case Active, Inactive, Pending

given QueryDecoder[Status] with
  def decode(s: String): Option[Status] =
    s.toLowerCase match
      case "active" => Some(Status.Active)
      case "inactive" => Some(Status.Inactive)
      case "pending" => Some(Status.Pending)
      case _ => None

(GET / "users") ? param[Status]("status") { status =>
  Ok(filterByStatus(status.getOrElse(Status.Active)))
}
```

### Custom Date Format

```scala
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

given QueryDecoder[LocalDateTime] with
  def decode(s: String): Option[LocalDateTime] =
    // Handle RFC3339 with timezone
    Try(LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME)).toOption
      .orElse(Try(LocalDateTime.parse(s.replace("Z", ""))).toOption)
```

---

## Chapter 11: Testing Routes

The DSL includes `TestClient` for unit testing routes without starting a server:

```scala
import kraft.dsl.testing.{TestClient, RequestBuilder}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MyRoutesSpec extends AnyFunSuite with Matchers:

  test("health endpoint returns ok"):
    val routes = HttpRoutes(
      GET("/health") { _ => Ok("""{"status":"healthy"}""") }
    )

    val client = TestClient(routes)
    val response = client.get("/health")

    response.status shouldBe Status.Ok
    response.bodyText shouldBe """{"status":"healthy"}"""

  test("search with query params"):
    val routes = HttpRoutes(
      (GET / "search") ? param[Int]("page") { page =>
        Ok(s"""{"page":${page.getOrElse(1)}}""")
      }
    )

    val client = TestClient(routes)

    client.get("/search").bodyText shouldBe """{"page":1}"""
    client.get("/search?page=5").bodyText shouldBe """{"page":5}"""

  test("POST with body"):
    val routes = HttpRoutes(
      POST("/echo") { req => Ok(req.bodyText) }
    )

    val client = TestClient(routes)
    val response = client.post("/echo", "Hello!")

    response.bodyText shouldBe "Hello!"

  test("custom headers"):
    val routes = HttpRoutes(
      GET("/whoami") { req =>
        val ua = req.headers.userAgent.getOrElse("unknown")
        Ok(s"""{"agent":"$ua"}""")
      }
    )

    val client = TestClient(routes)
    val response = client.get("/whoami", Map("User-Agent" -> "TestBot/1.0"))

    response.bodyText should include("TestBot/1.0")
```

### TestClient Methods

```scala
val client = TestClient(routes)

// GET requests
client.get("/path")
client.get("/path", headers = Map("Authorization" -> "Bearer token"))

// POST requests
client.post("/path", "body content")
client.post("/path", "body", headers = Map("Content-Type" -> "text/plain"))

// Response helpers
response.status          // Status enum
response.bodyText        // Body as String
response.body            // Body as Array[Byte]
response.headers         // Map of headers
response.isSuccess       // true if 2xx
response.isClientError   // true if 4xx
response.isServerError   // true if 5xx
```

---

## Chapter 12: Real-World Example

Here's how the search feature uses the DSL:

```scala
package kraft.features.search

import kraft.dsl.*
import java.time.LocalDateTime
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}

object Search:
  // Custom decoder for our date format
  given QueryDecoder[LocalDateTime] with
    def decode(s: String): Option[LocalDateTime] =
      parseRFC3339(s)

  def routes(repo: EventRepository): HttpRoutes =
    val service = Service(repo)
    val cachedVersion = AtomicLong(0)
    val cachedResponse = AtomicReference[Response](null)

    HttpRoutes(
      // Health endpoint - simple form
      GET("/health") { _ =>
        Ok("""{"status":"healthy"}""")
      },

      // Search endpoint with typed params and validation
      (GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") withRequest {
        (startsAt, endsAt, req) =>
          // Validate: if raw param exists but parsed is None, format was invalid
          if req.params.has("starts_at") && startsAt.isEmpty then
            BadRequest(errorJson("starts_at must be a valid RFC3339 datetime"))
          else if req.params.has("ends_at") && endsAt.isEmpty then
            BadRequest(errorJson("ends_at must be a valid RFC3339 datetime"))
          else if startsAt.isEmpty && endsAt.isEmpty then
            // Fast path: use cached response
            getCachedResponse(service, cachedVersion, cachedResponse)
          else
            // Filter by date range
            OkJson(service.search(startsAt, endsAt))
      }
    )
```

---

## Chapter 13: HTTP Client DSL

The DSL includes a type-safe HTTP client that mirrors the server-side routing patterns:

```scala
import kraft.dsl.client.*

val client = HttpClient()

// Simple GET request with typed response
val response = client
  .get("https://api.example.com/users/1")
  .execute[User]

// Pattern match on response
response match
  case ClientResponse.Ok(user) => println(s"Got user: ${user.name}")
  case ClientResponse.NotFound(_) => println("User not found")
  case ClientResponse.ServerError(msg, _, _) => println(s"Server error: $msg")
```

### Creating a Client

```scala
// Default client
val client = HttpClient()

// With custom timeout
val client = HttpClient().timeoutSeconds(30)

// With base URL
val client = HttpClient.withBaseUrl("https://api.example.com")

// With default headers
val client = HttpClient()
  .defaultHeader("User-Agent", "MyApp/1.0")
  .defaultHeader("Accept", "application/json")
```

### Making Requests

```scala
// GET with query parameters
val response = client
  .get("https://api.example.com/users")
  .param("page", 1)
  .param("limit", 20)
  .execute[List[User]]

// Optional parameters
val maybePage: Option[Int] = Some(5)
client.get("/users").paramOpt("page", maybePage)

// POST with JSON body
val response = client
  .post("https://api.example.com/users")
  .body(CreateUser("Alice", "alice@example.com"))
  .execute[User]

// PUT, PATCH, DELETE
client.put("/users/1").body(updateData).execute[User]
client.patch("/users/1").body(partialUpdate).execute[User]
client.delete("/users/1").executeNoContent

// Raw string body
client.post("/webhook")
  .bodyString(xmlPayload, "application/xml")
  .executeString
```

### Headers and Authentication

```scala
// Custom headers
val response = client
  .get("/api/data")
  .header("X-Api-Key", apiKey)
  .header("X-Request-Id", requestId)
  .execute[Data]

// Bearer token authentication
val response = client
  .get("/api/protected")
  .bearer(jwtToken)
  .execute[ProtectedData]

// Basic authentication
val response = client
  .get("/api/basic")
  .basicAuth("username", "password")
  .execute[Data]
```

### Response Handling

The `ClientResponse[A]` type provides pattern matching and functional operations:

```scala
// Pattern matching
response match
  case ClientResponse.Ok(data) => process(data)
  case ClientResponse.Created(data) => created(data)
  case ClientResponse.NoContent => success()
  case ClientResponse.BadRequest(body) => handleBadRequest(body)
  case ClientResponse.Unauthorized(body) => handleUnauth(body)
  case ClientResponse.NotFound(body) => handleNotFound(body)
  case ClientResponse.ServerError(body, status, _) => handleError(status, body)
  case ClientResponse.ConnectionError(msg, _) => handleNetworkError(msg)

// Functional operations
val name: String = response
  .map(_.name)
  .getOrElse("Unknown")

val result: Either[ClientResponse[Nothing], User] = response.toEither

val maybeUser: Option[User] = response.toOption

// Check status
response.isSuccess     // true for 2xx
response.isClientError // true for 4xx
response.isServerError // true for 5xx
```

### Response Types

| Type | Status | Description |
|------|--------|-------------|
| `Ok[A]` | 200 | Successful response with body |
| `Created[A]` | 201 | Resource created |
| `NoContent` | 204 | Success, no body |
| `BadRequest` | 400 | Client error |
| `Unauthorized` | 401 | Authentication required |
| `Forbidden` | 403 | Access denied |
| `NotFound` | 404 | Resource not found |
| `ServerError` | 5xx | Server error |
| `ConnectionError` | -1 | Network/connection failure |
| `Other[A]` | any | Other status codes |

### Real-World Example

```scala
import kraft.dsl.client.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class Event(id: String, title: String, date: String)
given JsonValueCodec[Event] = JsonCodecMaker.make
given JsonValueCodec[List[Event]] = JsonCodecMaker.make

class EventApiClient(baseUrl: String, apiKey: String):
  private val client = HttpClient
    .withBaseUrl(baseUrl)
    .timeoutSeconds(30)
    .defaultHeader("X-Api-Key", apiKey)

  def getEvents(page: Int = 1): Either[String, List[Event]] =
    client
      .get("events")
      .param("page", page)
      .execute[List[Event]]
      .toEither
      .left.map(_.toString)

  def getEvent(id: String): Option[Event] =
    client
      .get(s"events/$id")
      .execute[Event]
      .toOption

  def createEvent(title: String, date: String): Either[String, Event] =
    client
      .post("events")
      .body(Event("", title, date))
      .execute[Event]
      .toEither
      .left.map(_.toString)

// Usage
val api = EventApiClient("https://api.example.com", "my-api-key")
val events = api.getEvents(page = 1)
```

---

## Chapter 14: WebSocket DSL

The DSL includes support for WebSocket connections, both client and server side.

### Client-Side WebSocket

Connect to WebSocket servers with type-safe message handling:

```scala
import kraft.dsl.ws.*

// Create a WebSocket client
val client = WebSocketClient.connect("wss://api.example.com/ws")
  .onOpen {
    println("Connected!")
  }
  .onMessage[ChatMessage] { msg =>
    println(s"${msg.sender}: ${msg.text}")
  }
  .onClose { (code, reason) =>
    println(s"Disconnected: $code - $reason")
  }
  .onError { ex =>
    println(s"Error: ${ex.getMessage}")
  }
  .build()

// Send messages
client.send("Hello, World!")
client.send(ChatMessage("Hi!", "Alice"))

// Close when done
client.close()
```

### Message Types

```scala
// Text message
val textMsg = WsMessage.text("Hello")

// Binary message
val binaryMsg = WsMessage.binary(Array[Byte](1, 2, 3))

// JSON message (auto-serialized)
val jsonMsg = WsMessage.json(ChatMessage("Hi", "Bob"))
```

### Server-Side WebSocket Routes

Define WebSocket handlers for your server:

```scala
import kraft.dsl.ws.*

val wsRoutes = WebSocketRoutes(
  WS("/chat")
    .onOpen { println("Client connected") }
    .onMessage[ChatMessage] { msg =>
      // Handle incoming messages
      broadcast(msg)
    }
    .onClose { (code, reason) =>
      println(s"Client disconnected: $code")
    }
    .build(),

  WS("/notifications")
    .onTextMessage { text =>
      println(s"Notification: $text")
    }
    .build()
)

// Compose routes
val allWsRoutes = wsRoutes <+> otherWsRoutes
```

### Close Codes

Standard WebSocket close codes are available:

```scala
CloseCode.Normal         // 1000 - Normal closure
CloseCode.GoingAway      // 1001 - Going away
CloseCode.ProtocolError  // 1002 - Protocol error
CloseCode.AbnormalClosure // 1006 - Abnormal closure
```

### Type-Safe Message Codecs

The `WsCodec` typeclass handles encoding/decoding:

```scala
// Built-in codecs
summon[WsCodec[String]]      // Text messages
summon[WsCodec[Array[Byte]]] // Binary messages

// JSON codec (automatic with JsonValueCodec)
case class GameEvent(action: String, data: Map[String, Int])
given JsonValueCodec[GameEvent] = JsonCodecMaker.make

// Now GameEvent can be sent/received as JSON
client.send(GameEvent("move", Map("x" -> 10, "y" -> 20)))
```

---

## Chapter 15: GraphQL DSL

The DSL includes a type-safe GraphQL schema definition and execution engine.

### Defining a Schema

```scala
import kraft.dsl.graphql.*

val schema = Schema(
  Query(
    field[String]("hello", StringType)
      .resolver("world")
      .build(),

    field[Option[User]]("user", UserType)
      .arg[String]("id", IDType)
      .resolve { ctx =>
        val id = ctx.argOrElse("id", "")
        userRepo.find(id)
      }
      .build(),

    field[List[User]]("users", ListType(UserType))
      .resolver(userRepo.findAll())
      .build()
  ),
  Mutation(
    field[User]("createUser", UserType)
      .arg[String]("name", StringType)
      .arg[String]("email", StringType)
      .resolve { ctx =>
        userRepo.create(
          ctx.requireArg("name"),
          ctx.requireArg("email")
        )
      }
      .build()
  )
)
```

### GraphQL Types

```scala
// Scalar types
StringType   // String
IntType      // Int
FloatType    // Float
BooleanType  // Boolean
IDType       // ID

// List type
ListType(UserType)        // [User]

// Non-null type
NonNull(StringType)       // String!

// Nested types
ListType(NonNull(IntType)) // [Int!]
```

### Field Definitions

```scala
// Simple field with static value
field[String]("greeting", StringType)
  .resolver("Hello!")
  .build()

// Field with arguments
field[User]("user", UserType)
  .arg[String]("id", IDType)
  .resolve { ctx =>
    userRepo.find(ctx.requireArg("id"))
  }
  .build()

// Field with description
field[Int]("count", IntType)
  .description("Total number of items")
  .resolver(items.size)
  .build()
```

### Resolver Context

The `ResolverContext` provides access to arguments and variables:

```scala
resolve { ctx =>
  // Get optional argument
  val page = ctx.arg[Int]("page")  // Option[Int]

  // Get with default
  val limit = ctx.argOrElse("limit", 10)

  // Get required (throws if missing)
  val id = ctx.requireArg[String]("id")

  // Access variables
  val vars = ctx.variables
}
```

### Executing Queries

```scala
// Execute a query string
val result = schema.execute("""
  query {
    user(id: "123") {
      name
      email
    }
  }
""")

// Check for errors
result.errors match
  case Some(error) => println(s"Error: ${error.message}")
  case None => println(s"Data: ${result.data}")

// Execute from JSON request
val jsonRequest = """{"query": "{ hello }"}"""
val jsonResponse = schema.executeJson(jsonRequest)
```

### Generating SDL

Generate Schema Definition Language from your schema:

```scala
val sdl = schema.toSDL
println(sdl)
// Output:
// type Query {
//   hello: String
//   user(id: ID): User
//   users: [User]
// }
//
// type Mutation {
//   createUser(name: String, email: String): User
// }
```

### Real-World Example

```scala
import kraft.dsl.graphql.*
import kraft.dsl.*

case class Event(id: String, title: String, startsAt: String)

val EventType = objectType("Event").build()

class EventGraphQL(repo: EventRepository):
  val schema = Schema(
    Query(
      field[List[Event]]("events", ListType(EventType))
        .arg[String]("startsAt", StringType)
        .arg[String]("endsAt", StringType)
        .resolve { ctx =>
          val startsAt = ctx.arg[String]("startsAt").map(parseDate)
          val endsAt = ctx.arg[String]("endsAt").map(parseDate)
          repo.search(startsAt, endsAt)
        }
        .build(),

      field[Option[Event]]("event", EventType)
        .arg[String]("id", IDType)
        .resolve(ctx => repo.find(ctx.requireArg("id")))
        .build()
    )
  )

  // HTTP route for GraphQL endpoint
  val routes = HttpRoutes(
    POST("/graphql") { req =>
      val response = schema.executeJson(req.bodyText)
      Ok(response)
    }
  )
```

---

## Chapter 16: gRPC DSL

The gRPC DSL provides type-safe gRPC service definitions and client/server stubs.

### Defining a Service

```scala
import kraft.dsl.grpc.*

// Define request/response types
case class HelloRequest(name: String)
case class HelloReply(message: String)

// Define the service
val greeterService = GrpcService("Greeter")
  .unary[HelloRequest, HelloReply]("SayHello") { req =>
    HelloReply(s"Hello, ${req.name}!")
  }
  .serverStream[HelloRequest, HelloReply]("SayHelloMany") { req =>
    LazyList(
      HelloReply(s"Hello, ${req.name}!"),
      HelloReply(s"Hi again, ${req.name}!"),
      HelloReply(s"Goodbye, ${req.name}!")
    )
  }
  .build()
```

### Method Types

The DSL supports all four gRPC method types:

```scala
// Unary: single request, single response
.unary[Req, Res]("Method") { req => response }

// Server streaming: single request, stream of responses
.serverStream[Req, Res]("Method") { req => LazyList(...) }

// Client streaming: stream of requests, single response
.clientStream[Req, Res]("Method") { requests => response }

// Bidirectional streaming: stream of requests, stream of responses
.bidiStream[Req, Res]("Method") { requests => LazyList(...) }
```

### Context Access

Access metadata and cancellation in handlers:

```scala
val service = GrpcService("AuthService")
  .unaryWithContext[HelloRequest, HelloReply]("Greet") { (req, ctx) =>
    val token = ctx.metadata.get("authorization").getOrElse("none")
    if ctx.isCancelled then throw GrpcException(GrpcStatus.Cancelled, "Cancelled")
    HelloReply(s"Hello ${req.name}, token: $token")
  }
  .build()
```

### Creating a Server

```scala
val server = GrpcServer(greeterService, calculatorService)

// Handle requests
server.handleUnary("Greeter", "SayHello", requestJson) match
  case Right(responseJson) => // Success
  case Left(error) => // GrpcException with status code
```

### Creating a Client

```scala
val client = GrpcClient.connect("localhost:50051")
  .withMetadata("authorization", "Bearer token")
  .build()

// Make a unary call
val result = client.call[HelloRequest, HelloReply](
  "Greeter", "SayHello", HelloRequest("World")
)

result match
  case Right(reply) => println(reply.message)
  case Left(error) => println(s"Error: ${error.code} - ${error.message}")
```

### Typed Service Stubs

Create typed stubs for cleaner code:

```scala
val stub = ServiceStub.forService("Greeter")
  .withClient(client)

val result = stub.unary[HelloRequest, HelloReply]("SayHello", HelloRequest("Alice"))
```

### Standard Status Codes

```scala
GrpcStatus.OK              // 0
GrpcStatus.Cancelled       // 1
GrpcStatus.InvalidArgument // 3
GrpcStatus.NotFound        // 5
GrpcStatus.Internal        // 13
GrpcStatus.Unavailable     // 14
```
## DSL Operator Reference

| Operator | Meaning | Example |
|----------|---------|---------|
| `GET("/path")` | Simple route | `GET("/health") { req => Ok("ok") }` |
| `GET / "path"` | Path builder | `(GET / "users" / "list")` |
| `? param[T]("name")` | Optional param | `? param[Int]("page")` |
| `?! param[T]("name")` | Required param | `?! param[Int]("page")` |
| `& param[T]("name")` | Additional optional param | `& param[Int]("limit")` |
| `&! param[T]("name")` | Additional required param | `&! param[Int]("limit")` |
| `?> header[T]("name")` | Optional header | `?> header[String]("Authorization")` |
| `&> header[T]("name")` | Additional header | `&> header[String]("X-Request-Id")` |
| `> body[T]` | Body decoder | `> body[CreateEvent]` |
| `withRequest` | Access full request | `{ (param, req) => ... }` |
| `<+>` | Compose routes | `routes1 <+> routes2` |

---

## Implementation Details

Under the hood, the DSL compiles to efficient route matching:

1. **RouteWithPath** — Holds method + path pattern
2. **ParamExtractor** — Extracts and decodes query params using `QueryDecoder[T]`
3. **HeaderExtractor** — Extracts and decodes headers
4. **Route builders** — Chain extractors with `?`, `&`, `&>`, `>`
5. **HttpRoutes** — Partial function matching requests to responses

The result is **zero runtime overhead** beyond the actual extraction work—no reflection, no runtime type checking. Types are resolved at compile time, making the DSL both safe and fast.

---

## Further Reading

- **[Application Book](app-book.html)** — How features use the DSL in practice
- **[Server Book](server-book.html)** — The high-performance HTTP server
- **[BookGen](bookgen-book.html)** — Documentation generation
- **[Durable Execution](durable-book.html)** — Durable workflows with cluster support

<!-- @footer:generated_at -->
