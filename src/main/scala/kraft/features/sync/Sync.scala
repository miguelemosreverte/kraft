package kraft.features.sync

import kraft.domain.{Event, EventRepository, SellMode}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}
import scala.util.{Try, Using}
import scala.xml.{XML, Elem, Node}
import java.net.{URI, HttpURLConnection}
import java.io.InputStream
import scala.compiletime.uninitialized

/**
 * Sync feature - polls external provider and upserts events.
 * Complete vertical slice: parsing + polling + scheduling.
 */
object Sync:

  private val defaultUrl = "https://provider.code-challenge.kraftup.com/api/events"
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  /**
   * Create a new poller for syncing events from external provider.
   */
  def poller(repo: EventRepository): Poller = Poller(repo)

  /**
   * Poller - handles periodic sync with the provider.
   */
  class Poller(repo: EventRepository):
    private var url: String = defaultUrl
    private var pollInterval: Long = 30000 // 30 seconds
    private var scheduler: ScheduledExecutorService = uninitialized
    private val running = AtomicBoolean(false)
    private val fetchCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    def withUrl(providerUrl: String): Poller =
      this.url = providerUrl
      this

    def withPollInterval(millis: Long): Poller =
      this.pollInterval = millis
      this

    def sync(): Either[Throwable, Int] =
      Try {
        val xml = fetchXml(url)
        val events = parseEvents(xml)
        events.foreach(repo.upsert)
        fetchCount.incrementAndGet()
        println(s"[Poller] Synced ${events.size} events (total in store: ${repo.count})")
        events.size
      }.toEither.left.map { e =>
        errorCount.incrementAndGet()
        e
      }

    def start(): Poller =
      if running.compareAndSet(false, true) then
        scheduler = Executors.newSingleThreadScheduledExecutor(r =>
          val t = Thread(r, "sync-poller")
          t.setDaemon(true)
          t
        )
        scheduler.scheduleAtFixedRate(() => sync(), 0, pollInterval, TimeUnit.MILLISECONDS)
        println(s"[Poller] Started polling every ${pollInterval}ms from $url")
      this

    def stop(): Unit =
      if running.compareAndSet(true, false) then
        if scheduler != null then
          scheduler.shutdown()
          scheduler.awaitTermination(5, TimeUnit.SECONDS)
        println(s"[Poller] Stopped. Fetches: ${fetchCount.get()}, Errors: ${errorCount.get()}")

    def stats: Map[String, Long] = Map(
      "fetch_count" -> fetchCount.get(),
      "error_count" -> errorCount.get()
    )

  // ============================================================================
  // XML Parsing
  // ============================================================================

  private def fetchXml(url: String): Elem =
    val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("GET")
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(30000)

    if connection.getResponseCode != 200 then
      throw RuntimeException(s"HTTP ${connection.getResponseCode} from provider")

    Using.resource(connection.getInputStream) { is =>
      XML.load(is)
    }

  private def parseEvents(xml: Elem): Seq[Event] =
    val basePlans = (xml \\ "base_plan") ++ (xml \\ "base_event")

    basePlans.flatMap { basePlan =>
      val basePlanId = attr(basePlan, "base_plan_id").orElse(attr(basePlan, "base_event_id")).getOrElse("")
      val sellMode = SellMode.fromString(attr(basePlan, "sell_mode").getOrElse("offline"))
      val title = attr(basePlan, "title").getOrElse("Untitled")

      val plans = (basePlan \ "plan") ++ (basePlan \ "event")

      plans.map { plan =>
        val planId = attr(plan, "plan_id").orElse(attr(plan, "event_id")).getOrElse("")
        val zones = plan \ "zone"
        val prices = zones.flatMap(z => attr(z, "price").flatMap(p => Try(p.toDouble).toOption))

        Event(
          id = s"$basePlanId-$planId",
          basePlanId = basePlanId,
          planId = planId,
          title = title,
          sellMode = sellMode,
          startDateTime = parseDate(attr(plan, "plan_start_date").orElse(attr(plan, "event_start_date")).getOrElse("")),
          endDateTime = parseDate(attr(plan, "plan_end_date").orElse(attr(plan, "event_end_date")).getOrElse("")),
          sellFrom = attr(plan, "sell_from").map(parseDate),
          sellTo = attr(plan, "sell_to").map(parseDate),
          soldOut = attr(plan, "sold_out").contains("true"),
          minPrice = if prices.nonEmpty then Some(prices.min) else None,
          maxPrice = if prices.nonEmpty then Some(prices.max) else None
        )
      }
    }.toSeq

  private def attr(node: Node, name: String): Option[String] =
    node.attribute(name).flatMap(_.headOption).map(_.text)

  private def parseDate(s: String): LocalDateTime =
    Try(LocalDateTime.parse(s, dateFormatter))
      .getOrElse(LocalDateTime.now())
