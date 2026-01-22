package kraft.dsl

import scala.util.Try

/**
 * Path matching DSL for HTTP routes.
 *
 * Provides http4s-style path matching with extractors:
 * {{{
 * GET -> Root / "users" / IntVar(id) / "posts"
 * GET -> Root / "files" / *           // wildcard
 * }}}
 */

// ============================================================================
// Root Path
// ============================================================================

/** Root path segment - starting point for path patterns */
object Root:
  def /(segment: String): Path = Path(List(segment))
  def /(extractor: PathVar[?]): Path = Path(List(extractor))

// ============================================================================
// Path Pattern
// ============================================================================

/** Represents a path pattern with segments and extractors */
case class Path(segments: List[String | PathVar[?]]):
  def /(segment: String): Path = Path(segments :+ segment)
  def /(extractor: PathVar[?]): Path = Path(segments :+ extractor)

  /** Match a path string and extract path parameters */
  def matches(path: String): Option[Map[String, String]] =
    val parts = path.stripPrefix("/").split("/").filter(_.nonEmpty).toList
    if parts.length != segments.length then None
    else
      val extractions = scala.collection.mutable.Map[String, String]()
      val matched = segments.zip(parts).forall {
        case (s: String, p) => s == p
        case (v: PathVar[?], p) =>
          if v.validate(p) then
            extractions(v.name) = p
            true
          else false
      }
      if matched then Some(extractions.toMap) else None

  override def toString: String =
    "/" + segments.map {
      case s: String => s
      case v: PathVar[?] => s":${v.name}"
    }.mkString("/")

// ============================================================================
// Path Variable Extractors
// ============================================================================

/** Path variable extractor trait */
trait PathVar[T]:
  def name: String
  def validate(s: String): Boolean
  def extract(s: String): Option[T]

/** Extract a String path variable */
case class StringVar(name: String) extends PathVar[String]:
  def validate(s: String): Boolean = s.nonEmpty
  def extract(s: String): Option[String] = Some(s)

/** Named path variable for explicit naming */
def pathVar(name: String): StringVar = StringVar(name)

// ============================================================================
// Type-Safe Path Extractors (Unapply Pattern)
// ============================================================================

/** Extract and validate an Int path variable */
object IntVar:
  def unapply(s: String): Option[Int] = s.toIntOption
  def apply(name: String): IntPathVar = IntPathVar(name)

case class IntPathVar(name: String) extends PathVar[Int]:
  def validate(s: String): Boolean = s.toIntOption.isDefined
  def extract(s: String): Option[Int] = s.toIntOption

/** Extract and validate a Long path variable */
object LongVar:
  def unapply(s: String): Option[Long] = s.toLongOption
  def apply(name: String): LongPathVar = LongPathVar(name)

case class LongPathVar(name: String) extends PathVar[Long]:
  def validate(s: String): Boolean = s.toLongOption.isDefined
  def extract(s: String): Option[Long] = s.toLongOption

/** Extract UUID path variable */
object UUIDVar:
  def unapply(s: String): Option[java.util.UUID] =
    Try(java.util.UUID.fromString(s)).toOption
  def apply(name: String): UUIDPathVar = UUIDPathVar(name)

case class UUIDPathVar(name: String) extends PathVar[java.util.UUID]:
  def validate(s: String): Boolean = Try(java.util.UUID.fromString(s)).isSuccess
  def extract(s: String): Option[java.util.UUID] = Try(java.util.UUID.fromString(s)).toOption

/** Wildcard segment - matches any non-empty string */
object * extends PathVar[String]:
  val name: String = "*"
  def validate(s: String): Boolean = s.nonEmpty
  def extract(s: String): Option[String] = Some(s)

// ============================================================================
// Pattern Matching Extractor
// ============================================================================

/** Extractor for matching request method and path in pattern matching */
object -> :
  def unapply(req: Request): Option[(Request, MethodPath)] =
    val pathSegments = req.path.stripPrefix("/").split("/").filter(_.nonEmpty).toList
    Some((req, MethodPath(req.method, Path(pathSegments))))
