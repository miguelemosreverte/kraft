package kraft.server

/**
 * BACKWARDS COMPATIBILITY LAYER
 *
 * This object re-exports everything from kraft.dsl.* for backwards compatibility.
 * New code should import directly from kraft.dsl.*
 *
 * @deprecated Use `import kraft.dsl.*` instead
 */
object dsl:
  // Core types
  export kraft.dsl.Method
  export kraft.dsl.GET
  export kraft.dsl.POST
  export kraft.dsl.PUT
  export kraft.dsl.DELETE
  export kraft.dsl.PATCH
  export kraft.dsl.HEAD
  export kraft.dsl.OPTIONS
  export kraft.dsl.MethodPath
  export kraft.dsl.Status
  export kraft.dsl.Request
  export kraft.dsl.Response
  export kraft.dsl.QueryParams
  export kraft.dsl.QueryDecoder
  export kraft.dsl.Headers
  export kraft.dsl.ContentType

  // Path types
  export kraft.dsl.Root
  export kraft.dsl.Path
  export kraft.dsl.PathVar
  export kraft.dsl.StringVar
  export kraft.dsl.IntVar
  export kraft.dsl.IntPathVar
  export kraft.dsl.LongVar
  export kraft.dsl.LongPathVar
  export kraft.dsl.UUIDVar
  export kraft.dsl.UUIDPathVar
  export kraft.dsl.`*`
  export kraft.dsl.pathVar
  export kraft.dsl.`->`

  // Routing types
  export kraft.dsl.Route
  export kraft.dsl.RouteHandler
  export kraft.dsl.HttpRoutes
  export kraft.dsl.RouteWithPath
  export kraft.dsl.ParamExtractor
  export kraft.dsl.HeaderExtractor
  export kraft.dsl.RouteWithParam1
  export kraft.dsl.RouteWithParam2
  export kraft.dsl.RouteWithParam3
  export kraft.dsl.RouteWithRequiredParam1
  export kraft.dsl.RouteWithRequiredParam2
  export kraft.dsl.RouteWithHeader1
  export kraft.dsl.RouteWithHeader2
  export kraft.dsl.RouteWithHeaderAndParam

  // Response builders
  export kraft.dsl.Ok
  export kraft.dsl.OkEmpty
  export kraft.dsl.Created
  export kraft.dsl.NoContent
  export kraft.dsl.BadRequest
  export kraft.dsl.NotFound
  export kraft.dsl.MethodNotAllowed
  export kraft.dsl.Unauthorized
  export kraft.dsl.Forbidden
  export kraft.dsl.InternalServerError
  export kraft.dsl.ServiceUnavailable
  export kraft.dsl.respond
  export kraft.dsl.OkXml
  export kraft.dsl.OkHtml
  export kraft.dsl.OkText
  export kraft.dsl.OkCsv
  export kraft.dsl.OkBytes

  // Extractors
  export kraft.dsl.param
  export kraft.dsl.requiredParam
  export kraft.dsl.header
  export kraft.dsl.requiredHeader

  // JSON support
  export kraft.dsl.JsonCodec
  export kraft.dsl.OkJson
  export kraft.dsl.CreatedJson
  export kraft.dsl.RouteWithBody
