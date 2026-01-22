package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.store.MemoryStore
import kraft.features.sync.Sync
import kraft.features.search.Search
import kraft.server.dsl.*
import kraft.domain.EventCodecs
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.sun.net.httpserver.{HttpServer => JdkHttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress

class SyncSpec extends AnyFunSuite with Matchers:

  val testXml = """<?xml version="1.0" encoding="UTF-8"?>
    |<planList version="1">
    |  <output>
    |    <base_plan base_plan_id="291" sell_mode="online" title="Camela en concierto">
    |      <plan plan_id="100" plan_start_date="2024-06-30T21:00:00" plan_end_date="2024-06-30T22:30:00"
    |            sell_from="2024-04-01T00:00:00" sell_to="2024-06-30T20:00:00" sold_out="false">
    |        <zone zone_id="40" capacity="200" price="20.00" name="Platea" numbered="true"/>
    |        <zone zone_id="38" capacity="500" price="15.00" name="General" numbered="false"/>
    |      </plan>
    |    </base_plan>
    |    <base_plan base_plan_id="322" sell_mode="offline" title="Offline Event">
    |      <plan plan_id="200" plan_start_date="2024-07-15T19:00:00" plan_end_date="2024-07-15T21:00:00"
    |            sell_from="2024-05-01T00:00:00" sell_to="2024-07-15T18:00:00" sold_out="false">
    |        <zone zone_id="50" capacity="100" price="25.00" name="VIP" numbered="true"/>
    |      </plan>
    |    </base_plan>
    |  </output>
    |</planList>""".stripMargin

  // ==========================================================================
  // Sync Tests
  // ==========================================================================

  test("Poller syncs events from provider"):
    withMockServer(testXml) { url =>
      val store = MemoryStore()
      val poller = Sync.poller(store).withUrl(url)

      val result = poller.sync()

      result.isRight shouldBe true
      result.toOption.get shouldBe 2
      store.count shouldBe 2
    }

  test("Poller parses event details correctly"):
    withMockServer(testXml) { url =>
      val store = MemoryStore()
      val poller = Sync.poller(store).withUrl(url)

      poller.sync()

      val onlineEvents = store.search(None, None)
      onlineEvents.size shouldBe 1 // Only online events

      val event = onlineEvents.head
      event.title shouldBe "Camela en concierto"
      event.minPrice shouldBe Some(15.0)
      event.maxPrice shouldBe Some(20.0)
    }

  test("Poller handles server errors"):
    withMockServer("", statusCode = 500) { url =>
      val store = MemoryStore()
      val poller = Sync.poller(store).withUrl(url)

      val result = poller.sync()

      result.isLeft shouldBe true
      store.count shouldBe 0
    }

  test("Poller stats track fetches and errors"):
    withMockServer(testXml) { url =>
      val store = MemoryStore()
      val poller = Sync.poller(store).withUrl(url)

      poller.sync()
      poller.sync()

      val stats = poller.stats
      stats("fetch_count") shouldBe 2
      stats("error_count") shouldBe 0
    }

  // ==========================================================================
  // Integration: Sync + Search Flow
  // ==========================================================================

  test("Full sync-search flow"):
    val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
      |<planList version="1">
      |  <output>
      |    <base_plan base_plan_id="base-1" sell_mode="online" title="Test Concert">
      |      <plan plan_id="evt-1" plan_start_date="2021-06-30T21:00:00" plan_end_date="2021-06-30T23:00:00"
      |            sell_from="2021-01-01T00:00:00" sell_to="2021-06-30T20:00:00" sold_out="false">
      |        <zone zone_id="z1" capacity="100" price="50.00" name="General" numbered="false"/>
      |      </plan>
      |    </base_plan>
      |    <base_plan base_plan_id="base-2" sell_mode="online" title="Another Show">
      |      <plan plan_id="evt-2" plan_start_date="2021-07-15T19:00:00" plan_end_date="2021-07-15T22:00:00"
      |            sell_from="2021-01-01T00:00:00" sell_to="2021-07-15T18:00:00" sold_out="false">
      |        <zone zone_id="z2" capacity="200" price="75.00" name="VIP" numbered="true"/>
      |      </plan>
      |    </base_plan>
      |  </output>
      |</planList>""".stripMargin

    withMockServer(syncXml) { url =>
      val store = MemoryStore()

      // Sync events
      val poller = Sync.poller(store).withUrl(url)
      poller.sync()

      store.count shouldBe 2

      // Search for synced events using DSL
      val routes = Search.routes(store)
      val request = Request(
        method = GET,
        path = "/search",
        pathParams = Map.empty,
        params = QueryParams(Map(
          "starts_at" -> "2021-06-01T00:00:00",
          "ends_at" -> "2021-07-31T23:59:59"
        )),
        headers = Headers(Map.empty),
        body = Array.empty
      )

      val response = routes(request)
      response.isDefined shouldBe true
      response.get.status.code shouldBe 200

      import EventCodecs.given
      val parsed = readFromString[EventCodecs.SearchResponse](new String(response.get.body))
      parsed.data.get.events.size shouldBe 2

      val titles = parsed.data.get.events.map(_.title).toSet
      titles should contain("Test Concert")
      titles should contain("Another Show")
    }

  // ==========================================================================
  // Helpers
  // ==========================================================================

  private def withMockServer[T](responseBody: String, statusCode: Int = 200)(f: String => T): T =
    val server = JdkHttpServer.create(InetSocketAddress(0), 0)
    val port = server.getAddress.getPort

    server.createContext("/", new HttpHandler:
      override def handle(exchange: HttpExchange): Unit =
        val bytes = responseBody.getBytes("UTF-8")
        exchange.sendResponseHeaders(statusCode, if statusCode == 200 then bytes.length else -1)
        if statusCode == 200 then
          exchange.getResponseBody.write(bytes)
        exchange.close()
    )

    server.start()
    try f(s"http://localhost:$port/")
    finally server.stop(0)
