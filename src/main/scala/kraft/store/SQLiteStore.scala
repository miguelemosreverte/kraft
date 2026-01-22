package kraft.store

import kraft.domain.{Event, EventRepository, SellMode}
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Try, Using}

/**
 * SQLite-backed event store for persistence.
 * Survives restarts and provides durable storage.
 */
class SQLiteStore(dbPath: String) extends EventRepository with AutoCloseable:

  private val connection: Connection = {
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
    migrate(conn)
    conn
  }

  private val versionCounter: AtomicLong = {
    val stmt = connection.createStatement()
    val rs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM events")
    val v = if rs.next() then rs.getLong(1) else 0L
    rs.close()
    stmt.close()
    AtomicLong(v)
  }

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

  private def migrate(conn: Connection): Unit =
    val schema = """
      CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY,
        base_plan_id TEXT NOT NULL,
        plan_id TEXT NOT NULL,
        title TEXT NOT NULL,
        sell_mode TEXT NOT NULL,
        start_datetime TEXT NOT NULL,
        end_datetime TEXT NOT NULL,
        sell_from TEXT,
        sell_to TEXT,
        sold_out INTEGER NOT NULL DEFAULT 0,
        min_price REAL,
        max_price REAL,
        first_seen_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL,
        version INTEGER NOT NULL DEFAULT 0
      );
      CREATE INDEX IF NOT EXISTS idx_events_sell_mode ON events(sell_mode);
      CREATE INDEX IF NOT EXISTS idx_events_start_datetime ON events(start_datetime);
    """
    val stmt = conn.createStatement()
    schema.split(";").filter(_.trim.nonEmpty).foreach(s => stmt.execute(s.trim))
    stmt.close()

  override def upsert(event: Event): Unit =
    val existing = get(event.id)
    val firstSeenAt = existing.map(_.firstSeenAt).getOrElse(LocalDateTime.now())
    val lastSeenAt = LocalDateTime.now()
    val newVersion = versionCounter.incrementAndGet()

    val sql = """
      INSERT INTO events (
        id, base_plan_id, plan_id, title, sell_mode,
        start_datetime, end_datetime, sell_from, sell_to,
        sold_out, min_price, max_price,
        first_seen_at, last_seen_at, version
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        base_plan_id = excluded.base_plan_id,
        plan_id = excluded.plan_id,
        title = excluded.title,
        sell_mode = excluded.sell_mode,
        start_datetime = excluded.start_datetime,
        end_datetime = excluded.end_datetime,
        sell_from = excluded.sell_from,
        sell_to = excluded.sell_to,
        sold_out = excluded.sold_out,
        min_price = excluded.min_price,
        max_price = excluded.max_price,
        last_seen_at = excluded.last_seen_at,
        version = excluded.version
    """

    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, event.id)
    stmt.setString(2, event.basePlanId)
    stmt.setString(3, event.planId)
    stmt.setString(4, event.title)
    stmt.setString(5, event.sellMode.toString.toLowerCase)
    stmt.setString(6, event.startDateTime.format(formatter))
    stmt.setString(7, event.endDateTime.format(formatter))
    stmt.setString(8, event.sellFrom.map(_.format(formatter)).orNull)
    stmt.setString(9, event.sellTo.map(_.format(formatter)).orNull)
    stmt.setInt(10, if event.soldOut then 1 else 0)
    stmt.setObject(11, event.minPrice.orNull)
    stmt.setObject(12, event.maxPrice.orNull)
    stmt.setString(13, firstSeenAt.format(formatter))
    stmt.setString(14, lastSeenAt.format(formatter))
    stmt.setLong(15, newVersion)
    stmt.executeUpdate()
    stmt.close()

  override def get(id: String): Option[Event] =
    val sql = """
      SELECT id, base_plan_id, plan_id, title, sell_mode,
             start_datetime, end_datetime, sell_from, sell_to,
             sold_out, min_price, max_price,
             first_seen_at, last_seen_at
      FROM events WHERE id = ?
    """
    val stmt = connection.prepareStatement(sql)
    stmt.setString(1, id)
    val rs = stmt.executeQuery()
    val result = if rs.next() then Some(rowToEvent(rs)) else None
    rs.close()
    stmt.close()
    result

  override def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event] =
    val baseQuery = "SELECT id, base_plan_id, plan_id, title, sell_mode, start_datetime, end_datetime, sell_from, sell_to, sold_out, min_price, max_price, first_seen_at, last_seen_at FROM events WHERE sell_mode = 'online'"

    val (query, params) = (startsAt, endsAt) match
      case (Some(s), Some(e)) =>
        (baseQuery + " AND start_datetime >= ? AND start_datetime <= ? ORDER BY start_datetime",
         Seq(s.format(formatter), e.format(formatter)))
      case (Some(s), None) =>
        (baseQuery + " AND start_datetime >= ? ORDER BY start_datetime",
         Seq(s.format(formatter)))
      case (None, Some(e)) =>
        (baseQuery + " AND start_datetime <= ? ORDER BY start_datetime",
         Seq(e.format(formatter)))
      case (None, None) =>
        (baseQuery + " ORDER BY start_datetime", Seq.empty)

    val stmt = connection.prepareStatement(query)
    params.zipWithIndex.foreach { case (p, i) => stmt.setString(i + 1, p) }

    val rs = stmt.executeQuery()
    val results = scala.collection.mutable.ArrayBuffer[Event]()
    while rs.next() do
      results += rowToEvent(rs)
    rs.close()
    stmt.close()
    results.toSeq

  override def count: Int =
    val stmt = connection.createStatement()
    val rs = stmt.executeQuery("SELECT COUNT(*) FROM events")
    val c = if rs.next() then rs.getInt(1) else 0
    rs.close()
    stmt.close()
    c

  override def version: Long = versionCounter.get()

  override def close(): Unit = connection.close()

  private def rowToEvent(rs: ResultSet): Event =
    Event(
      id = rs.getString("id"),
      basePlanId = rs.getString("base_plan_id"),
      planId = rs.getString("plan_id"),
      title = rs.getString("title"),
      sellMode = SellMode.fromString(rs.getString("sell_mode")),
      startDateTime = LocalDateTime.parse(rs.getString("start_datetime"), formatter),
      endDateTime = LocalDateTime.parse(rs.getString("end_datetime"), formatter),
      sellFrom = Option(rs.getString("sell_from")).map(LocalDateTime.parse(_, formatter)),
      sellTo = Option(rs.getString("sell_to")).map(LocalDateTime.parse(_, formatter)),
      soldOut = rs.getInt("sold_out") == 1,
      minPrice = Option(rs.getObject("min_price")).map(_.asInstanceOf[Number].doubleValue()),
      maxPrice = Option(rs.getObject("max_price")).map(_.asInstanceOf[Number].doubleValue()),
      firstSeenAt = LocalDateTime.parse(rs.getString("first_seen_at"), formatter)
    )

object SQLiteStore:
  def apply(dbPath: String): SQLiteStore = new SQLiteStore(dbPath)
