package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.graphql.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

class GraphQLSpec extends AnyFunSuite with Matchers:

  // ==========================================================================
  // Type system tests
  // ==========================================================================

  test("Scalar types have correct names"):
    StringType.name shouldBe "String"
    IntType.name shouldBe "Int"
    FloatType.name shouldBe "Float"
    BooleanType.name shouldBe "Boolean"
    IDType.name shouldBe "ID"

  test("ListType wraps element type"):
    val listType = ListType(StringType)
    listType.name shouldBe "[String]"

  test("NonNull wraps inner type"):
    val nonNull = NonNull(StringType)
    nonNull.name shouldBe "String!"
    nonNull.isNullable shouldBe false

  test("Nested types have correct names"):
    val listOfNonNull = ListType(NonNull(IntType))
    listOfNonNull.name shouldBe "[Int!]"

  // ==========================================================================
  // Field definition tests
  // ==========================================================================

  test("field creates a field builder"):
    val builder = field[String]("name", StringType)
    builder shouldBe a[FieldBuilder[?]]

  test("FieldBuilder can add arguments"):
    val fieldDef = field[String]("user", StringType)
      .arg[String]("id", IDType)
      .resolve(ctx => s"User ${ctx.arg[String]("id").getOrElse("unknown")}")
      .build()

    fieldDef.name shouldBe "user"
    fieldDef.arguments.length shouldBe 1
    fieldDef.arguments.head.name shouldBe "id"

  test("FieldBuilder can add description"):
    val fieldDef = field[String]("name", StringType)
      .description("The user's name")
      .resolver("John")
      .build()

    fieldDef.description shouldBe Some("The user's name")

  test("FieldBuilder resolver works"):
    val fieldDef = field[Int]("count", IntType)
      .resolver(42)
      .build()

    fieldDef.resolver(ResolverContext()) shouldBe 42

  test("FieldBuilder resolve with context works"):
    val fieldDef = field[String]("greeting", StringType)
      .arg[String]("name", StringType)
      .resolve { ctx =>
        val name = ctx.argOrElse("name", "World")
        s"Hello, $name!"
      }
      .build()

    val ctx = ResolverContext(arguments = Map("name" -> "Alice"))
    fieldDef.resolver(ctx) shouldBe "Hello, Alice!"

  // ==========================================================================
  // ResolverContext tests
  // ==========================================================================

  test("ResolverContext.arg returns argument value"):
    val ctx = ResolverContext(arguments = Map("id" -> "123", "count" -> 5))

    ctx.arg[String]("id") shouldBe Some("123")
    ctx.arg[Int]("count") shouldBe Some(5)
    ctx.arg[String]("missing") shouldBe None

  test("ResolverContext.argOrElse returns default for missing"):
    val ctx = ResolverContext(arguments = Map("id" -> "123"))

    ctx.argOrElse("id", "default") shouldBe "123"
    ctx.argOrElse("missing", "default") shouldBe "default"

  test("ResolverContext.requireArg throws for missing"):
    val ctx = ResolverContext(arguments = Map("id" -> "123"))

    ctx.requireArg[String]("id") shouldBe "123"
    assertThrows[IllegalArgumentException] {
      ctx.requireArg[String]("missing")
    }

  // ==========================================================================
  // Query/Mutation root tests
  // ==========================================================================

  test("Query creates a QueryRoot"):
    val query = Query(
      field[String]("hello", StringType).resolver("world").build()
    )

    query shouldBe a[QueryRoot]
    query.fields.length shouldBe 1

  test("Mutation creates a MutationRoot"):
    val mutation = Mutation(
      field[Boolean]("doSomething", BooleanType).resolver(true).build()
    )

    mutation shouldBe a[MutationRoot]
    mutation.fields.length shouldBe 1

  // ==========================================================================
  // Schema tests
  // ==========================================================================

  test("Schema can be created with query only"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      )
    )

    schema.query should not be null
    schema.mutation shouldBe None

  test("Schema can be created with query and mutation"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      ),
      Mutation(
        field[Boolean]("doIt", BooleanType).resolver(true).build()
      )
    )

    schema.query should not be null
    schema.mutation should not be None

  // ==========================================================================
  // Query execution tests
  // ==========================================================================

  test("Schema executes simple query"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      )
    )

    val result = schema.execute("{ hello }")

    result.errors shouldBe None
    result.data should not be None
    result.data.get("data").asInstanceOf[Map[String, Any]]("hello") shouldBe "world"

  test("Schema executes query with arguments"):
    val schema = Schema(
      Query(
        field[String]("greet", StringType)
          .arg[String]("name", StringType)
          .resolve(ctx => s"Hello, ${ctx.argOrElse("name", "World")}!")
          .build()
      )
    )

    val result = schema.execute("""{ greet(name: "Alice") }""")

    result.errors shouldBe None
    val data = result.data.get("data").asInstanceOf[Map[String, Any]]
    data("greet") shouldBe "Hello, Alice!"

  test("Schema executes query with integer argument"):
    val schema = Schema(
      Query(
        field[Int]("double", IntType)
          .arg[Int]("value", IntType)
          .resolve(ctx => ctx.argOrElse[Int]("value", 0) * 2)
          .build()
      )
    )

    val result = schema.execute("{ double(value: 21) }")

    result.errors shouldBe None
    val data = result.data.get("data").asInstanceOf[Map[String, Any]]
    data("double") shouldBe 42

  test("Schema executes multiple fields"):
    val schema = Schema(
      Query(
        field[String]("name", StringType).resolver("John").build(),
        field[Int]("age", IntType).resolver(30).build()
      )
    )

    val result = schema.execute("{ name age }")

    result.errors shouldBe None
    val data = result.data.get("data").asInstanceOf[Map[String, Any]]
    data("name") shouldBe "John"
    data("age") shouldBe 30

  test("Schema returns error for unknown field"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      )
    )

    val result = schema.execute("{ unknown }")

    result.errors should not be None
    result.errors.get.message should include("Unknown field")

  test("Schema executes mutation"):
    var counter = 0
    val schema = Schema(
      Query(
        field[Int]("count", IntType).resolver(counter).build()
      ),
      Mutation(
        field[Int]("increment", IntType).resolve { _ =>
          counter += 1
          counter
        }.build()
      )
    )

    val result = schema.execute("mutation { increment }")

    result.errors shouldBe None
    val data = result.data.get("data").asInstanceOf[Map[String, Any]]
    data("increment") shouldBe 1
    counter shouldBe 1

  // ==========================================================================
  // SDL generation tests
  // ==========================================================================

  test("Schema.toSDL generates query type"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build(),
        field[Int]("count", IntType).resolver(0).build()
      )
    )

    val sdl = schema.toSDL
    sdl should include("type Query {")
    sdl should include("hello: String")
    sdl should include("count: Int")

  test("Schema.toSDL includes arguments"):
    val schema = Schema(
      Query(
        field[String]("greet", StringType)
          .arg[String]("name", StringType)
          .resolver("hello")
          .build()
      )
    )

    val sdl = schema.toSDL
    sdl should include("greet(name: String): String")

  test("Schema.toSDL includes mutation type"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      ),
      Mutation(
        field[Boolean]("doIt", BooleanType).resolver(true).build()
      )
    )

    val sdl = schema.toSDL
    sdl should include("type Query {")
    sdl should include("type Mutation {")
    sdl should include("doIt: Boolean")

  // ==========================================================================
  // JSON execution tests
  // ==========================================================================

  test("Schema.executeJson handles JSON request"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      )
    )

    val request = """{"query": "{ hello }"}"""
    val response = schema.executeJson(request)

    response should include("data")
    response should include("hello")
    response should include("world")

  test("Schema.executeJson handles invalid JSON"):
    val schema = Schema(
      Query(
        field[String]("hello", StringType).resolver("world").build()
      )
    )

    val response = schema.executeJson("not json")
    response should include("error")
    response should include("Invalid request")

  // ==========================================================================
  // Real-world example
  // ==========================================================================

  test("Real-world user schema example"):
    // Simulate a user repository
    case class User(id: String, name: String, email: String)
    val users = Map(
      "1" -> User("1", "Alice", "alice@example.com"),
      "2" -> User("2", "Bob", "bob@example.com")
    )

    val UserType = objectType("User").build()

    val schema = Schema(
      Query(
        field[Option[User]]("user", UserType)
          .arg[String]("id", IDType)
          .resolve { ctx =>
            val id = ctx.argOrElse("id", "")
            users.get(id)
          }
          .build(),

        field[List[User]]("users", ListType(UserType))
          .resolver(users.values.toList)
          .build()
      ),
      Mutation(
        field[User]("createUser", UserType)
          .arg[String]("name", StringType)
          .arg[String]("email", StringType)
          .resolve { ctx =>
            val name = ctx.argOrElse("name", "")
            val email = ctx.argOrElse("email", "")
            User("new-id", name, email)
          }
          .build()
      )
    )

    // Test query
    val result1 = schema.execute("""{ user(id: "1") }""")
    result1.errors shouldBe None

    // Test SDL generation
    val sdl = schema.toSDL
    sdl should include("user(id: ID): User")
    sdl should include("users: [User]")
    sdl should include("createUser(name: String, email: String): User")
