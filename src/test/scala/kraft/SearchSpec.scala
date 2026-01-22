package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.domain.*
import kraft.domain.EventCodecs.{*, given}
import kraft.store.MemoryStore
import kraft.features.search.Search
import kraft.server.dsl.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import java.time.LocalDateTime

class SearchSpec extends AnyFunSuite with Matchers:

  // ==========================================================================
  // Test Fixtures
  // ==========================================================================

  def testEvent(
    id: String,
    sellMode: SellMode,
    start: LocalDateTime = LocalDateTime.of(2021, 6, 30, 21, 0)
  ): Event =
    Event(
      id = id,
      basePlanId = "base-1",
      planId = "plan-1",
      title = "Test Concert",
      sellMode = sellMode,
      startDateTime = start,
      endDateTime = start.plusHours(2),
      minPrice = Some(15.0),
      maxPrice = Some(30.0)
    )

  // Helper to create DSL request
  def dslRequest(path: String, query: Map[String, String] = Map.empty): Request =
    Request(
      method = GET,
      path = path,
      pathParams = Map.empty,
      params = QueryParams(query),
      headers = Headers(Map.empty),
      body = Array.empty
    )

  // ==========================================================================
  // Service Tests
  // ==========================================================================

  test("Service returns only online events"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online))
    store.upsert(testEvent("2", SellMode.Offline))

    val service = Search.Service(store)
    val events = service.search(None, None)

    events.size shouldBe 1
    events.head.id shouldBe "1"

  test("Service filters by date range"):
    val store = MemoryStore()
    store.upsert(testEvent("june", SellMode.Online, LocalDateTime.of(2021, 6, 15, 20, 0)))
    store.upsert(testEvent("july", SellMode.Online, LocalDateTime.of(2021, 7, 15, 20, 0)))

    val service = Search.Service(store)
    val events = service.search(
      Some(LocalDateTime.of(2021, 6, 1, 0, 0)),
      Some(LocalDateTime.of(2021, 6, 30, 23, 59))
    )

    events.size shouldBe 1
    events.head.id shouldBe "june"

  test("Service returns events sorted by start date"):
    val store = MemoryStore()
    store.upsert(testEvent("later", SellMode.Online, LocalDateTime.of(2021, 7, 1, 20, 0)))
    store.upsert(testEvent("earlier", SellMode.Online, LocalDateTime.of(2021, 6, 1, 20, 0)))

    val service = Search.Service(store)
    val events = service.search(None, None)

    events.size shouldBe 2
    events.head.id shouldBe "earlier"

  // ==========================================================================
  // Route Tests (using DSL)
  // ==========================================================================

  test("Search routes return healthy response for /health"):
    val store = MemoryStore()
    val routes = Search.routes(store)

    val request = dslRequest("/health")
    val response = routes(request)

    response.isDefined shouldBe true
    response.get.status.code shouldBe 200
    new String(response.get.body) should include("healthy")

  test("Search routes return events for /search"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online))

    val routes = Search.routes(store)
    val response = routes(dslRequest("/search"))

    response.isDefined shouldBe true
    response.get.status.code shouldBe 200

    val parsed = readFromString[EventCodecs.SearchResponse](new String(response.get.body))
    parsed.data.isDefined shouldBe true
    parsed.data.get.events.size shouldBe 1

  test("Search routes filter by date range"):
    val store = MemoryStore()
    store.upsert(testEvent("june", SellMode.Online, start = LocalDateTime.of(2021, 6, 15, 20, 0)))
    store.upsert(testEvent("july", SellMode.Online, start = LocalDateTime.of(2021, 7, 15, 20, 0)))

    val routes = Search.routes(store)
    val request = dslRequest("/search", Map(
      "starts_at" -> "2021-06-01T00:00:00",
      "ends_at" -> "2021-06-30T23:59:59"
    ))

    val response = routes(request)
    response.isDefined shouldBe true
    response.get.status.code shouldBe 200

    val parsed = readFromString[EventCodecs.SearchResponse](new String(response.get.body))
    parsed.data.get.events.size shouldBe 1
    parsed.data.get.events.head.id shouldBe "june"

  test("Search routes return 400 for invalid date"):
    val store = MemoryStore()
    val routes = Search.routes(store)

    val request = dslRequest("/search", Map("starts_at" -> "invalid-date"))
    val response = routes(request)

    response.isDefined shouldBe true
    response.get.status.code shouldBe 400
    new String(response.get.body) should include("invalid_parameter")

  // ==========================================================================
  // Response Format Tests
  // ==========================================================================

  test("Response follows API spec format"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online))

    val routes = Search.routes(store)
    val response = routes(dslRequest("/search"))

    response.isDefined shouldBe true
    val json = new String(response.get.body)

    json should include("\"data\"")
    json should include("\"events\"")
    json should not include("\"error\"")

  test("Error response follows API spec format"):
    val store = MemoryStore()
    val routes = Search.routes(store)

    val request = dslRequest("/search", Map("starts_at" -> "bad"))
    val response = routes(request)

    response.isDefined shouldBe true
    val json = new String(response.get.body)

    json should include("\"error\"")
    json should include("invalid_parameter")

  // ==========================================================================
  // Edge Cases
  // ==========================================================================

  test("Empty store returns empty events array"):
    val store = MemoryStore()
    val routes = Search.routes(store)

    val response = routes(dslRequest("/search"))
    response.isDefined shouldBe true

    val parsed = readFromString[EventCodecs.SearchResponse](new String(response.get.body))
    parsed.data.get.events shouldBe empty

  test("Date filtering with no matches returns empty"):
    val store = MemoryStore()
    store.upsert(testEvent("1", SellMode.Online, LocalDateTime.of(2021, 6, 15, 20, 0)))

    val routes = Search.routes(store)
    val request = dslRequest("/search", Map(
      "starts_at" -> "2022-01-01T00:00:00",
      "ends_at" -> "2022-12-31T23:59:59"
    ))

    val response = routes(request)
    response.isDefined shouldBe true

    val parsed = readFromString[EventCodecs.SearchResponse](new String(response.get.body))
    parsed.data.get.events shouldBe empty
