package kraft.dsl.graphql

import scala.util.{Try, Success, Failure}
import scala.collection.mutable
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * GraphQL DSL - Type-safe GraphQL schema definition and execution.
 *
 * Schema definition example:
 * {{{
 * import kraft.dsl.graphql.*
 *
 * val schema = Schema(
 *   Query(
 *     field("user", UserType) {
 *       arg[ID]("id") { id => userRepo.find(id) }
 *     },
 *     field("users", ListType(UserType)) {
 *       resolver { userRepo.findAll() }
 *     }
 *   ),
 *   Mutation(
 *     field("createUser", UserType) {
 *       arg[CreateUserInput]("input") { input =>
 *         userRepo.create(input.name, input.email)
 *       }
 *     }
 *   )
 * )
 *
 * // Execute a query
 * val result = schema.execute("""
 *   query {
 *     user(id: "123") {
 *       name
 *       email
 *     }
 *   }
 * """)
 * }}}
 */

// =============================================================================
// GraphQL Types
// =============================================================================

/**
 * Base trait for GraphQL types.
 */
sealed trait GqlType:
  def name: String
  def isNullable: Boolean = true

object GqlType:
  /** Scalar types */
  case object GqlString extends GqlType:
    val name = "String"

  case object GqlInt extends GqlType:
    val name = "Int"

  case object GqlFloat extends GqlType:
    val name = "Float"

  case object GqlBoolean extends GqlType:
    val name = "Boolean"

  case object GqlID extends GqlType:
    val name = "ID"

  /** List type */
  case class GqlList(elementType: GqlType) extends GqlType:
    val name = s"[${elementType.name}]"

  /** Non-null type */
  case class GqlNonNull(innerType: GqlType) extends GqlType:
    val name = s"${innerType.name}!"
    override def isNullable = false

  /** Object type */
  case class GqlObject(
    name: String,
    fields: Seq[FieldDefinition[?]]
  ) extends GqlType

  /** Input type */
  case class GqlInput(
    name: String,
    fields: Seq[InputFieldDefinition]
  ) extends GqlType

  /** Enum type */
  case class GqlEnum(
    name: String,
    values: Seq[String]
  ) extends GqlType

// Type aliases for convenience
type ID = String
val StringType = GqlType.GqlString
val IntType = GqlType.GqlInt
val FloatType = GqlType.GqlFloat
val BooleanType = GqlType.GqlBoolean
val IDType = GqlType.GqlID
def ListType(elementType: GqlType): GqlType.GqlList = GqlType.GqlList(elementType)
def NonNull(innerType: GqlType): GqlType.GqlNonNull = GqlType.GqlNonNull(innerType)

// =============================================================================
// Field Definitions
// =============================================================================

/**
 * A field in a GraphQL object type.
 */
case class FieldDefinition[A](
  name: String,
  fieldType: GqlType,
  arguments: Seq[ArgumentDefinition[?]] = Seq.empty,
  resolver: ResolverContext => A,
  description: Option[String] = None
)

/**
 * An argument to a GraphQL field.
 */
case class ArgumentDefinition[A](
  name: String,
  argType: GqlType,
  defaultValue: Option[A] = None,
  description: Option[String] = None
)

/**
 * An input field definition.
 */
case class InputFieldDefinition(
  name: String,
  fieldType: GqlType,
  defaultValue: Option[Any] = None
)

// =============================================================================
// Resolver Context
// =============================================================================

/**
 * Context passed to resolvers containing arguments and request info.
 */
case class ResolverContext(
  arguments: Map[String, Any] = Map.empty,
  parent: Option[Any] = None,
  variables: Map[String, Any] = Map.empty,
  operationName: Option[String] = None
):
  /** Get an argument by name */
  def arg[A](name: String): Option[A] =
    arguments.get(name).map(_.asInstanceOf[A])

  /** Get an argument or default */
  def argOrElse[A](name: String, default: => A): A =
    arg[A](name).getOrElse(default)

  /** Get a required argument (throws if missing) */
  def requireArg[A](name: String): A =
    arg[A](name).getOrElse(throw new IllegalArgumentException(s"Missing required argument: $name"))

// =============================================================================
// Field Builders
// =============================================================================

/**
 * Builder for creating field definitions.
 */
class FieldBuilder[A](
  private val name: String,
  private val fieldType: GqlType,
  private var args: Seq[ArgumentDefinition[?]] = Seq.empty,
  private var resolverFn: ResolverContext => A = (_: ResolverContext) => null.asInstanceOf[A],
  private var desc: Option[String] = None
):
  /** Add an argument */
  def arg[B](argName: String, argType: GqlType): FieldBuilder[A] =
    args = args :+ ArgumentDefinition[B](argName, argType)
    this

  /** Add an argument with default */
  def arg[B](argName: String, argType: GqlType, default: B): FieldBuilder[A] =
    args = args :+ ArgumentDefinition[B](argName, argType, Some(default))
    this

  /** Set resolver with context */
  def resolve(fn: ResolverContext => A): FieldBuilder[A] =
    resolverFn = fn
    this

  /** Set simple resolver (no context needed) */
  def resolver(fn: => A): FieldBuilder[A] =
    resolverFn = _ => fn
    this

  /** Add description */
  def description(d: String): FieldBuilder[A] =
    desc = Some(d)
    this

  /** Build the field definition */
  def build(): FieldDefinition[A] =
    FieldDefinition(name, fieldType, args, resolverFn, desc)

/** Create a field builder */
def field[A](name: String, fieldType: GqlType): FieldBuilder[A] =
  new FieldBuilder[A](name, fieldType)

// =============================================================================
// Object Type Builder
// =============================================================================

/**
 * Builder for creating object types.
 */
class ObjectTypeBuilder(
  private val name: String,
  private var fields: Seq[FieldDefinition[?]] = Seq.empty,
  private var desc: Option[String] = None
):
  /** Add a field */
  def field[A](f: FieldDefinition[A]): ObjectTypeBuilder =
    fields = fields :+ f
    this

  /** Add description */
  def description(d: String): ObjectTypeBuilder =
    desc = Some(d)
    this

  /** Build the object type */
  def build(): GqlType.GqlObject =
    GqlType.GqlObject(name, fields)

/** Create an object type builder */
def objectType(name: String): ObjectTypeBuilder =
  new ObjectTypeBuilder(name)

// =============================================================================
// Query and Mutation Roots
// =============================================================================

/**
 * Query root type.
 */
case class QueryRoot(fields: Seq[FieldDefinition[?]])

/**
 * Mutation root type.
 */
case class MutationRoot(fields: Seq[FieldDefinition[?]])

/**
 * Subscription root type.
 */
case class SubscriptionRoot(fields: Seq[FieldDefinition[?]])

/** Create a Query root */
def Query(fields: FieldDefinition[?]*): QueryRoot =
  QueryRoot(fields.toSeq)

/** Create a Mutation root */
def Mutation(fields: FieldDefinition[?]*): MutationRoot =
  MutationRoot(fields.toSeq)

/** Create a Subscription root */
def Subscription(fields: FieldDefinition[?]*): SubscriptionRoot =
  SubscriptionRoot(fields.toSeq)

// =============================================================================
// GraphQL Schema
// =============================================================================

/**
 * A complete GraphQL schema.
 */
class Schema(
  val query: QueryRoot,
  val mutation: Option[MutationRoot] = None,
  val subscription: Option[SubscriptionRoot] = None,
  val types: Seq[GqlType] = Seq.empty
):
  private val queryFields = query.fields.map(f => f.name -> f).toMap
  private val mutationFields = mutation.map(_.fields.map(f => f.name -> f).toMap).getOrElse(Map.empty)

  /**
   * Execute a GraphQL query/mutation.
   */
  def execute(
    query: String,
    variables: Map[String, Any] = Map.empty,
    operationName: Option[String] = None
  ): GraphQLResult =
    Try {
      val parsed = parseQuery(query)
      executeOperation(parsed, variables, operationName)
    } match
      case Success(data) => GraphQLResult(Some(data), None)
      case Failure(ex) => GraphQLResult(None, Some(GraphQLError(ex.getMessage)))

  /**
   * Execute a GraphQL query/mutation from JSON.
   */
  def executeJson(requestJson: String): String =
    import GraphQLCodecs.given
    Try {
      val request = readFromString[GraphQLRequest](requestJson)
      val result = execute(
        request.query,
        request.variables.getOrElse(Map.empty),
        request.operationName
      )
      writeToString(result)
    }.getOrElse {
      writeToString(GraphQLResult(None, Some(GraphQLError("Invalid request"))))
    }

  private def parseQuery(query: String): ParsedOperation =
    // Simple parser for basic queries
    val trimmed = query.trim
    val opType = if trimmed.startsWith("mutation") then OperationType.Mutation
                 else OperationType.Query

    // Extract field selections (very simplified)
    val selectionsStart = trimmed.indexOf('{')
    val selectionsEnd = trimmed.lastIndexOf('}')
    if selectionsStart < 0 || selectionsEnd < 0 then
      throw new IllegalArgumentException("Invalid query: missing braces")

    val selectionsStr = trimmed.substring(selectionsStart + 1, selectionsEnd).trim
    val selections = parseSelections(selectionsStr)

    ParsedOperation(opType, selections)

  private def parseSelections(str: String): Seq[FieldSelection] =
    // Very simplified parser - handles basic field selections
    val fields = mutable.ListBuffer[FieldSelection]()
    var i = 0
    val len = str.length

    while i < len do
      // Skip whitespace
      while i < len && str(i).isWhitespace do i += 1
      if i >= len then return fields.toSeq

      // Parse field name
      val nameStart = i
      while i < len && (str(i).isLetterOrDigit || str(i) == '_') do i += 1
      val name = str.substring(nameStart, i)
      if name.isEmpty then
        i += 1 // skip unknown char
      else
        // Check for arguments
        var args = Map.empty[String, Any]
        while i < len && str(i).isWhitespace do i += 1
        if i < len && str(i) == '(' then
          val argsEnd = str.indexOf(')', i)
          if argsEnd > i then
            args = parseArguments(str.substring(i + 1, argsEnd))
            i = argsEnd + 1

        // Check for sub-selections
        var subSelections = Seq.empty[FieldSelection]
        while i < len && str(i).isWhitespace do i += 1
        if i < len && str(i) == '{' then
          val depth = findMatchingBrace(str, i)
          subSelections = parseSelections(str.substring(i + 1, depth))
          i = depth + 1

        fields += FieldSelection(name, args, subSelections)

    fields.toSeq

  private def parseArguments(str: String): Map[String, Any] =
    // Simple argument parser: name: value, name: value
    val args = mutable.Map[String, Any]()
    str.split(',').foreach { part =>
      val colonIdx = part.indexOf(':')
      if colonIdx > 0 then
        val name = part.substring(0, colonIdx).trim
        val valueStr = part.substring(colonIdx + 1).trim
        val value = parseValue(valueStr)
        args(name) = value
    }
    args.toMap

  private def parseValue(str: String): Any =
    val trimmed = str.trim
    if trimmed.startsWith("\"") && trimmed.endsWith("\"") then
      trimmed.substring(1, trimmed.length - 1)
    else if trimmed == "true" then true
    else if trimmed == "false" then false
    else if trimmed == "null" then null
    else if trimmed.contains('.') then trimmed.toDoubleOption.getOrElse(trimmed)
    else trimmed.toIntOption.getOrElse(trimmed)

  private def findMatchingBrace(str: String, start: Int): Int =
    var depth = 1
    var i = start + 1
    while i < str.length && depth > 0 do
      if str(i) == '{' then depth += 1
      else if str(i) == '}' then depth -= 1
      i += 1
    i - 1

  private def executeOperation(
    op: ParsedOperation,
    variables: Map[String, Any],
    operationName: Option[String]
  ): Map[String, Any] =
    val fields = op.opType match
      case OperationType.Query => queryFields
      case OperationType.Mutation => mutationFields

    val results = mutable.Map[String, Any]()
    op.selections.foreach { selection =>
      fields.get(selection.name) match
        case Some(fieldDef) =>
          val ctx = ResolverContext(
            arguments = selection.arguments,
            variables = variables,
            operationName = operationName
          )
          val value = fieldDef.resolver(ctx)
          results(selection.name) = serializeValue(value, selection.subSelections)
        case None =>
          throw new IllegalArgumentException(s"Unknown field: ${selection.name}")
    }
    Map("data" -> results.toMap)

  private def serializeValue(value: Any, subSelections: Seq[FieldSelection]): Any =
    value match
      case null => null
      case opt: Option[?] => opt.map(v => serializeValue(v, subSelections)).orNull
      case seq: Seq[?] => seq.map(v => serializeValue(v, subSelections))
      case map: Map[?, ?] if subSelections.isEmpty => map
      case _ if subSelections.isEmpty => value
      case obj =>
        // For objects with sub-selections, we'd need reflection or a type-class
        // For now, just return the object
        value

  /** Generate SDL (Schema Definition Language) */
  def toSDL: String =
    val sb = new StringBuilder

    sb.append("type Query {\n")
    query.fields.foreach { f =>
      val args = if f.arguments.isEmpty then ""
                 else f.arguments.map(a => s"${a.name}: ${a.argType.name}").mkString("(", ", ", ")")
      sb.append(s"  ${f.name}$args: ${f.fieldType.name}\n")
    }
    sb.append("}\n")

    mutation.foreach { m =>
      sb.append("\ntype Mutation {\n")
      m.fields.foreach { f =>
        val args = if f.arguments.isEmpty then ""
                   else f.arguments.map(a => s"${a.name}: ${a.argType.name}").mkString("(", ", ", ")")
        sb.append(s"  ${f.name}$args: ${f.fieldType.name}\n")
      }
      sb.append("}\n")
    }

    sb.toString

object Schema:
  def apply(query: QueryRoot): Schema =
    new Schema(query)

  def apply(query: QueryRoot, mutation: MutationRoot): Schema =
    new Schema(query, Some(mutation))

  def apply(query: QueryRoot, mutation: MutationRoot, subscription: SubscriptionRoot): Schema =
    new Schema(query, Some(mutation), Some(subscription))

// =============================================================================
// Internal Types
// =============================================================================

private enum OperationType:
  case Query, Mutation

private case class ParsedOperation(
  opType: OperationType,
  selections: Seq[FieldSelection]
)

private case class FieldSelection(
  name: String,
  arguments: Map[String, Any],
  subSelections: Seq[FieldSelection]
)

// =============================================================================
// Result Types
// =============================================================================

case class GraphQLRequest(
  query: String,
  variables: Option[Map[String, Any]] = None,
  operationName: Option[String] = None
)

case class GraphQLResult(
  data: Option[Map[String, Any]],
  errors: Option[GraphQLError]
)

case class GraphQLError(
  message: String,
  locations: Option[Seq[GraphQLLocation]] = None,
  path: Option[Seq[String]] = None
)

case class GraphQLLocation(line: Int, column: Int)

// JSON codecs
object GraphQLCodecs:
  given JsonValueCodec[Map[String, Any]] = new JsonValueCodec[Map[String, Any]]:
    def decodeValue(in: JsonReader, default: Map[String, Any]): Map[String, Any] =
      if in.isNextToken('{') then
        val map = mutable.Map[String, Any]()
        if !in.isNextToken('}') then
          in.rollbackToken()
          while
            val key = in.readKeyAsString()
            val value = readAny(in)
            map(key) = value
            in.isNextToken(',')
          do ()
        map.toMap
      else
        in.readNullOrError(default, "expected '{' or null")

    def encodeValue(x: Map[String, Any], out: JsonWriter): Unit =
      out.writeObjectStart()
      x.foreach { (k, v) =>
        out.writeKey(k)
        writeAny(v, out)
      }
      out.writeObjectEnd()

    def nullValue: Map[String, Any] = Map.empty

    private def readAny(in: JsonReader): Any =
      val b = in.nextToken()
      in.rollbackToken()
      b match
        case '"' => in.readString(null)
        case 't' | 'f' => in.readBoolean()
        case 'n' => in.readNullOrError(null, "expected null"); null
        case '{' => decodeValue(in, Map.empty)
        case '[' =>
          val list = mutable.ListBuffer[Any]()
          if in.isNextToken('[') then
            if !in.isNextToken(']') then
              in.rollbackToken()
              while
                list += readAny(in)
                in.isNextToken(',')
              do ()
          list.toList
        case _ =>
          val s = in.readStringAsCharBuf()
          val str = s.toString
          if str.contains('.') then str.toDoubleOption.getOrElse(str)
          else str.toLongOption.map(_.toInt).getOrElse(str)

    private def writeAny(value: Any, out: JsonWriter): Unit =
      value match
        case null => out.writeNull()
        case s: String => out.writeVal(s)
        case i: Int => out.writeVal(i)
        case l: Long => out.writeVal(l)
        case d: Double => out.writeVal(d)
        case b: Boolean => out.writeVal(b)
        case m: Map[?, ?] =>
          out.writeObjectStart()
          m.foreach { (k, v) =>
            out.writeKey(k.toString)
            writeAny(v, out)
          }
          out.writeObjectEnd()
        case s: Seq[?] =>
          out.writeArrayStart()
          s.foreach(v => writeAny(v, out))
          out.writeArrayEnd()
        case opt: Option[?] =>
          opt match
            case Some(v) => writeAny(v, out)
            case None => out.writeNull()
        case other => out.writeVal(other.toString)

  given JsonValueCodec[GraphQLRequest] = JsonCodecMaker.make
  given JsonValueCodec[GraphQLResult] = JsonCodecMaker.make
  given JsonValueCodec[GraphQLError] = JsonCodecMaker.make
  given JsonValueCodec[GraphQLLocation] = JsonCodecMaker.make
