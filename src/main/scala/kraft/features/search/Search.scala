package kraft.features.search

import kraft.domain.{Event, EventRepository, EventCodecs}
import kraft.domain.EventCodecs.{*, given}
import kraft.dsl.*  // Use new DSL package directly
import com.github.plokhotnyuk.jsoniter_scala.core.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}
import scala.util.Try

/**
 * Search feature - complete vertical slice.
 * Service + Handler + Response caching in one file.
 *
 * http4s-inspired DSL with typed parameter extraction:
 * {{{
 * // Simple endpoint
 * GET("/health") { _ => Ok("""{"status":"healthy"}""") }
 *
 * // With typed query params - params arrive already parsed!
 * GET /:? "/search" ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") {
 *   (startsAt, endsAt) => Ok(search(startsAt, endsAt))
 * }
 *
 * // With body
 * POST /:? "/events" > body[CreateEvent] { event => CreatedJson(event) }
 * }}}
 */
object Search:

  // Custom decoder for LocalDateTime (RFC3339 format)
  given QueryDecoder[LocalDateTime] with
    def decode(s: String): Option[LocalDateTime] = parseDateTime(s)

  // Error messages following API spec
  private val invalidStartsAtMsg = "starts_at must be a valid RFC3339 datetime"
  private val invalidEndsAtMsg = "ends_at must be a valid RFC3339 datetime"

  /**
   * Create routes for the search feature.
   * Uses http4s-style DSL with typed parameter extraction.
   */
  def routes(repo: EventRepository): HttpRoutes =
    val service = Service(repo)
    val cachedVersion = AtomicLong(0)
    val cachedResponse = AtomicReference[Response](null)

    HttpRoutes(
      // Health endpoint - simple form
      GET("/health") { _ =>
        Ok("""{"status":"healthy"}""")
      },

      // Search endpoint - typed params extracted directly!
      // The (startsAt, endsAt) arrive as Option[LocalDateTime] - already parsed!
      (GET / "search") ? param[LocalDateTime]("starts_at") & param[LocalDateTime]("ends_at") withRequest {
        (startsAt, endsAt, req) =>
          // Validate: if raw param exists but parsed is None, it was invalid
          validateParams(req.params, startsAt, endsAt) match
            case Some(error) => error
            case None =>
              // Fast path: no params = use cache
              if startsAt.isEmpty && endsAt.isEmpty then
                getCachedResponse(service, cachedVersion, cachedResponse)
              else
                buildResponse(service.search(startsAt, endsAt))
      }
    )

  /**
   * Validate that if a param was provided, it was successfully parsed.
   * Returns error response if validation fails.
   */
  private def validateParams(
    params: QueryParams,
    startsAt: Option[LocalDateTime],
    endsAt: Option[LocalDateTime]
  ): Option[Response] =
    if params.has("starts_at") && startsAt.isEmpty then
      Some(badRequestResponse(invalidStartsAtMsg))
    else if params.has("ends_at") && endsAt.isEmpty then
      Some(badRequestResponse(invalidEndsAtMsg))
    else
      None

  private def getCachedResponse(
    service: Service,
    cachedVersion: AtomicLong,
    cachedResponse: AtomicReference[Response]
  ): Response =
    val currentVersion = service.version
    if cachedVersion.get() == currentVersion then
      val cached = cachedResponse.get()
      if cached != null then return cached

    val response = buildResponse(service.search(None, None))
    cachedResponse.set(response)
    cachedVersion.set(currentVersion)
    response

  private def buildResponse(events: Seq[Event]): Response =
    val body = writeToArray(EventCodecs.successResponse(events))
    Ok(body, "application/json")

  private def badRequestResponse(message: String): Response =
    val body = writeToArray(EventCodecs.errorResponse("invalid_parameter", message))
    BadRequest(body)

  private def parseDateTime(s: String): Option[LocalDateTime] =
    Try(LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME)).toOption
      .orElse(Try(LocalDateTime.parse(s.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME)).toOption)

  /**
   * Service layer - pure business logic.
   */
  class Service(repo: EventRepository):
    def search(startsAt: Option[LocalDateTime], endsAt: Option[LocalDateTime]): Seq[Event] =
      repo.search(startsAt, endsAt)

    def version: Long = repo.version
