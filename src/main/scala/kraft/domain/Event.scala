package kraft.domain

import java.time.LocalDateTime
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * Core domain model for events.
 * Immutable case class - the Scala way.
 */
case class Event(
  id: String,
  basePlanId: String,
  planId: String,
  title: String,
  sellMode: SellMode,
  startDateTime: LocalDateTime,
  endDateTime: LocalDateTime,
  sellFrom: Option[LocalDateTime] = None,
  sellTo: Option[LocalDateTime] = None,
  soldOut: Boolean = false,
  minPrice: Option[Double] = None,
  maxPrice: Option[Double] = None,
  firstSeenAt: LocalDateTime = LocalDateTime.now()
):
  def isOnline: Boolean = sellMode == SellMode.Online

  def isInRange(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Boolean =
    val afterStart = startsAt.forall(s => !startDateTime.isBefore(s))
    val beforeEnd = endsAt.forall(e => !startDateTime.isAfter(e))
    afterStart && beforeEnd

enum SellMode:
  case Online, Offline

object SellMode:
  def fromString(s: String): SellMode = s.toLowerCase match
    case "online" => Online
    case _        => Offline

/**
 * Repository trait - port in hexagonal architecture.
 */
trait EventRepository:
  def upsert(event: Event): Unit
  def get(id: String): Option[Event]
  def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event]
  def count: Int
  def version: Long

/**
 * JSON codecs for API responses.
 */
object EventCodecs:
  // Response DTO (matches API contract)
  case class EventDTO(
    id: String,
    title: String,
    start_date: String,
    start_time: Option[String],
    end_date: Option[String],
    end_time: Option[String],
    min_price: Option[Double],
    max_price: Option[Double]
  )

  case class EventList(events: Seq[EventDTO])
  case class ErrorDetail(code: String, message: String)
  case class SearchResponse(data: Option[EventList], error: Option[ErrorDetail])

  // Jsoniter codecs (fast, compile-time generated)
  given JsonValueCodec[EventDTO] = JsonCodecMaker.make
  given JsonValueCodec[EventList] = JsonCodecMaker.make
  given JsonValueCodec[ErrorDetail] = JsonCodecMaker.make
  given JsonValueCodec[SearchResponse] = JsonCodecMaker.make
  given JsonValueCodec[Seq[EventDTO]] = JsonCodecMaker.make

  def toDTO(event: Event): EventDTO = EventDTO(
    id = event.id,
    title = event.title,
    start_date = event.startDateTime.toLocalDate.toString,
    start_time = Some(event.startDateTime.toLocalTime.toString),
    end_date = Some(event.endDateTime.toLocalDate.toString),
    end_time = Some(event.endDateTime.toLocalTime.toString),
    min_price = event.minPrice,
    max_price = event.maxPrice
  )

  def successResponse(events: Seq[Event]): SearchResponse =
    SearchResponse(
      data = Some(EventList(events.map(toDTO))),
      error = None
    )

  def errorResponse(code: String, message: String): SearchResponse =
    SearchResponse(
      data = None,
      error = Some(ErrorDetail(code, message))
    )
