package kraft

/**
 * HTTP DSL - A lightweight, type-safe DSL for defining HTTP routes.
 *
 * ## Quick Start
 *
 * {{{
 * import kraft.dsl.*
 *
 * val routes = HttpRoutes(
 *   GET("/health") { _ => Ok("""{"status":"healthy"}""") },
 *
 *   GET("/users") ? param[Int]("page") { page =>
 *     Ok(s"""{"page":${page.getOrElse(1)}}""")
 *   },
 *
 *   GET("/users/:id") { req =>
 *     req.pathParamInt("id") match
 *       case Some(id) => Ok(s"""{"id":$id}""")
 *       case None => BadRequest("Invalid user ID")
 *   }
 * )
 * }}}
 *
 * ## Pattern Matching Style (http4s-like)
 *
 * {{{
 * import kraft.dsl.*
 *
 * val routes = HttpRoutes.of {
 *   case GET -> Root / "health" => Ok("healthy")
 *   case GET -> Root / "users" / IntVar(id) => Ok(s"User $id")
 *   case req @ POST -> Root / "users" => handleCreate(req)
 * }
 * }}}
 *
 * ## With JSON (requires JsonCodec instance)
 *
 * {{{
 * import kraft.dsl.*
 * import kraft.dsl.json.jsoniter.given  // For Jsoniter integration
 *
 * case class User(name: String, age: Int)
 * given JsonValueCodec[User] = JsonCodecMaker.make
 *
 * val routes = HttpRoutes(
 *   GET("/user") { _ => OkJson(User("Alice", 30)) },
 *   POST("/user") > [User] { user => CreatedJson(user) }
 * )
 * }}}
 *
 * ## Composing Routes
 *
 * {{{
 * val healthRoutes = HttpRoutes(GET("/health") { _ => Ok("ok") })
 * val userRoutes = HttpRoutes(GET("/users") { _ => Ok("[]") })
 *
 * val allRoutes = healthRoutes <+> userRoutes
 * }}}
 */
package object dsl {
  // Re-export everything for convenient single import

  // Types are defined directly in dsl package files:
  // - core.scala: Method, Status, Request, Response, Headers, QueryParams, etc.
  // - path.scala: Root, Path, PathVar, IntVar, etc.
  // - routing.scala: HttpRoutes, Route, response builders, extractors
  // - json.scala: JsonCodec, OkJson, CreatedJson, etc.
}
