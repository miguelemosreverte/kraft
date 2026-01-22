package kraft.store

import kraft.domain.{Event, EventRepository, SellMode}
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * Thread-safe in-memory event store.
 * Uses ConcurrentHashMap for lock-free reads.
 */
class MemoryStore extends EventRepository:
  private val events = ConcurrentHashMap[String, Event]()
  private val versionCounter = AtomicLong(0)

  override def upsert(event: Event): Unit =
    events.compute(event.id, (_, existing) =>
      val updated = Option(existing) match
        case Some(e) => event.copy(firstSeenAt = e.firstSeenAt)
        case None    => event
      versionCounter.incrementAndGet()
      updated
    )

  override def get(id: String): Option[Event] =
    Option(events.get(id))

  override def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event] =
    events.values().asScala
      .filter(_.isOnline)
      .filter(_.isInRange(startsAt, endsAt))
      .toSeq
      .sortBy(_.startDateTime)

  override def count: Int = events.size()

  override def version: Long = versionCounter.get()
