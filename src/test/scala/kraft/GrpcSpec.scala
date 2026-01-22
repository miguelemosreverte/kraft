package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.grpc.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

// Test message types
case class HelloRequest(name: String)
case class HelloReply(message: String)
case class NumberRequest(value: Int)
case class NumberReply(result: Int)

object GrpcTestCodecs:
  given JsonValueCodec[HelloRequest] = JsonCodecMaker.make
  given JsonValueCodec[HelloReply] = JsonCodecMaker.make
  given JsonValueCodec[NumberRequest] = JsonCodecMaker.make
  given JsonValueCodec[NumberReply] = JsonCodecMaker.make

class GrpcSpec extends AnyFunSuite with Matchers:
  import GrpcTestCodecs.given

  // ==========================================================================
  // GrpcStatus tests
  // ==========================================================================

  test("GrpcStatus has standard codes"):
    GrpcStatus.OK shouldBe 0
    GrpcStatus.NotFound shouldBe 5
    GrpcStatus.Internal shouldBe 13
    GrpcStatus.Unavailable shouldBe 14
    GrpcStatus.Unimplemented shouldBe 12

  // ==========================================================================
  // GrpcMetadata tests
  // ==========================================================================

  test("GrpcMetadata can get and set values"):
    val meta = GrpcMetadata.empty
      .set("authorization", "Bearer token")
      .add("x-custom", "value1")
      .add("x-custom", "value2")

    meta.get("authorization") shouldBe Some("Bearer token")
    meta.getAll("x-custom") shouldBe Seq("value1", "value2")
    meta.get("missing") shouldBe None

  test("GrpcMetadata.empty has no entries"):
    val meta = GrpcMetadata.empty
    meta.get("any") shouldBe None
    meta.getAll("any") shouldBe Seq.empty

  // ==========================================================================
  // GrpcContext tests
  // ==========================================================================

  test("GrpcContext provides metadata access"):
    val meta = GrpcMetadata.empty.set("auth", "token")
    val ctx = GrpcContext(metadata = meta)

    ctx.metadata.get("auth") shouldBe Some("token")
    ctx.isCancelled shouldBe false

  test("GrpcContext can check cancellation"):
    var cancelled = false
    val ctx = GrpcContext(cancellationToken = Some(() => cancelled))

    ctx.isCancelled shouldBe false
    cancelled = true
    ctx.isCancelled shouldBe true

  test("GrpcContext can check deadline"):
    val future = System.currentTimeMillis() + 10000
    val ctx = GrpcContext(deadline = Some(future))

    ctx.remainingTime should not be None
    ctx.remainingTime.get should be > 0L

  // ==========================================================================
  // GrpcMethodType tests
  // ==========================================================================

  test("GrpcMethodType has all four types"):
    GrpcMethodType.Unary shouldBe a[GrpcMethodType]
    GrpcMethodType.ServerStreaming shouldBe a[GrpcMethodType]
    GrpcMethodType.ClientStreaming shouldBe a[GrpcMethodType]
    GrpcMethodType.BidiStreaming shouldBe a[GrpcMethodType]

  // ==========================================================================
  // GrpcService builder tests
  // ==========================================================================

  test("GrpcService creates a builder"):
    val builder = GrpcService("TestService")
    builder shouldBe a[GrpcServiceBuilder]

  test("GrpcService can define unary methods"):
    val service = GrpcService("Greeter")
      .unary[HelloRequest, HelloReply]("SayHello") { req =>
        HelloReply(s"Hello, ${req.name}!")
      }
      .build()

    service.name shouldBe "Greeter"
    service.methods.size shouldBe 1
    service.findMethod("SayHello") should not be None

  test("GrpcService can define multiple methods"):
    val service = GrpcService("Calculator")
      .unary[NumberRequest, NumberReply]("Double") { req =>
        NumberReply(req.value * 2)
      }
      .unary[NumberRequest, NumberReply]("Square") { req =>
        NumberReply(req.value * req.value)
      }
      .build()

    service.methods.size shouldBe 2
    service.findMethod("Double") should not be None
    service.findMethod("Square") should not be None

  test("GrpcService can define server streaming methods"):
    val service = GrpcService("Counter")
      .serverStream[NumberRequest, NumberReply]("CountUp") { req =>
        LazyList.from(1).take(req.value).map(NumberReply(_))
      }
      .build()

    service.findMethod("CountUp") shouldBe a[Some[?]]
    service.findMethod("CountUp").get.methodType shouldBe GrpcMethodType.ServerStreaming

  test("GrpcService can define client streaming methods"):
    val service = GrpcService("Aggregator")
      .clientStream[NumberRequest, NumberReply]("Sum") { reqs =>
        NumberReply(reqs.map(_.value).sum)
      }
      .build()

    service.findMethod("Sum") shouldBe a[Some[?]]
    service.findMethod("Sum").get.methodType shouldBe GrpcMethodType.ClientStreaming

  test("GrpcService can define bidi streaming methods"):
    val service = GrpcService("Echo")
      .bidiStream[HelloRequest, HelloReply]("Chat") { reqs =>
        reqs.map(r => HelloReply(s"Echo: ${r.name}"))
      }
      .build()

    service.findMethod("Chat") shouldBe a[Some[?]]
    service.findMethod("Chat").get.methodType shouldBe GrpcMethodType.BidiStreaming

  test("GrpcService unary with context access"):
    val service = GrpcService("AuthService")
      .unaryWithContext[HelloRequest, HelloReply]("Greet") { (req, ctx) =>
        val token = ctx.metadata.get("authorization").getOrElse("none")
        HelloReply(s"Hello ${req.name}, token: $token")
      }
      .build()

    service.findMethod("Greet") should not be None

  // ==========================================================================
  // GrpcServer tests
  // ==========================================================================

  test("GrpcServer can hold multiple services"):
    val service1 = GrpcService("Service1")
      .unary[HelloRequest, HelloReply]("Method1") { _ => HelloReply("1") }
      .build()

    val service2 = GrpcService("Service2")
      .unary[HelloRequest, HelloReply]("Method2") { _ => HelloReply("2") }
      .build()

    val server = GrpcServer(service1, service2)
    server.findService("Service1") should not be None
    server.findService("Service2") should not be None

  test("GrpcServer handles unary requests"):
    val service = GrpcService("Greeter")
      .unary[HelloRequest, HelloReply]("SayHello") { req =>
        HelloReply(s"Hello, ${req.name}!")
      }
      .build()

    val server = GrpcServer(service)
    val result = server.handleUnary("Greeter", "SayHello", """{"name":"World"}""")

    result.isRight shouldBe true
    result.toOption.get should include("Hello, World!")

  test("GrpcServer returns error for unknown service"):
    val server = GrpcServer()
    val result = server.handleUnary("Unknown", "Method", "{}")

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe GrpcStatus.NotFound

  test("GrpcServer returns error for unknown method"):
    val service = GrpcService("Greeter")
      .unary[HelloRequest, HelloReply]("SayHello") { _ => HelloReply("hi") }
      .build()

    val server = GrpcServer(service)
    val result = server.handleUnary("Greeter", "Unknown", "{}")

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe GrpcStatus.NotFound

  test("GrpcServer handles server streaming"):
    val service = GrpcService("Counter")
      .serverStream[NumberRequest, NumberReply]("CountUp") { req =>
        LazyList.from(1).take(req.value).map(NumberReply(_))
      }
      .build()

    val server = GrpcServer(service)
    val result = server.handleServerStream("Counter", "CountUp", """{"value":3}""")

    result.isRight shouldBe true
    val responses = result.toOption.get.toList
    responses.size shouldBe 3

  // ==========================================================================
  // GrpcClient tests
  // ==========================================================================

  test("GrpcClient.connect creates a builder"):
    val builder = GrpcClient.connect("localhost:50051")
    builder shouldBe a[GrpcClientBuilder]

  test("GrpcClient.localhost creates builder for local connection"):
    val builder = GrpcClient.localhost(50051)
    builder shouldBe a[GrpcClientBuilder]

  test("GrpcClient can add metadata"):
    val client = GrpcClient.connect("localhost:50051")
      .withMetadata("authorization", "Bearer token")
      .build()

    client shouldBe a[GrpcClient]

  test("GrpcClient can call mock server"):
    val service = GrpcService("Greeter")
      .unary[HelloRequest, HelloReply]("SayHello") { req =>
        HelloReply(s"Hello, ${req.name}!")
      }
      .build()

    val server = GrpcServer(service)
    val client = GrpcClient.connect("localhost:50051")
      .withMockServer(server)

    val result = client.call[HelloRequest, HelloReply](
      "Greeter", "SayHello", HelloRequest("World")
    )

    result.isRight shouldBe true
    result.toOption.get.message shouldBe "Hello, World!"

  test("GrpcClient returns error without server"):
    val client = GrpcClient.connect("localhost:50051").build()

    val result = client.call[HelloRequest, HelloReply](
      "Greeter", "SayHello", HelloRequest("World")
    )

    result.isLeft shouldBe true
    result.left.toOption.get.code shouldBe GrpcStatus.Unavailable

  test("GrpcClient can call server streaming"):
    val service = GrpcService("Counter")
      .serverStream[NumberRequest, NumberReply]("CountUp") { req =>
        LazyList.from(1).take(req.value).map(NumberReply(_))
      }
      .build()

    val server = GrpcServer(service)
    val client = GrpcClient.connect("localhost:50051")
      .withMockServer(server)

    val result = client.callServerStream[NumberRequest, NumberReply](
      "Counter", "CountUp", NumberRequest(3)
    )

    result.isRight shouldBe true
    result.toOption.get.toList.map(_.result) shouldBe List(1, 2, 3)

  // ==========================================================================
  // ServiceStub tests
  // ==========================================================================

  test("ServiceStub can make typed calls"):
    val service = GrpcService("Greeter")
      .unary[HelloRequest, HelloReply]("SayHello") { req =>
        HelloReply(s"Hello, ${req.name}!")
      }
      .build()

    val server = GrpcServer(service)
    val client = GrpcClient.connect("localhost:50051")
      .withMockServer(server)

    val stub = ServiceStub.forService("Greeter").withClient(client)

    val result = stub.unary[HelloRequest, HelloReply]("SayHello", HelloRequest("Alice"))
    result.isRight shouldBe true
    result.toOption.get.message shouldBe "Hello, Alice!"

  test("ServiceStub can call server streaming"):
    val service = GrpcService("Numbers")
      .serverStream[NumberRequest, NumberReply]("Generate") { req =>
        LazyList.from(1).take(req.value).map(n => NumberReply(n * 10))
      }
      .build()

    val server = GrpcServer(service)
    val client = GrpcClient.connect("localhost:50051").withMockServer(server)
    val stub = ServiceStub.forService("Numbers").withClient(client)

    val result = stub.serverStream[NumberRequest, NumberReply]("Generate", NumberRequest(3))
    result.isRight shouldBe true
    result.toOption.get.toList.map(_.result) shouldBe List(10, 20, 30)

  // ==========================================================================
  // GrpcException tests
  // ==========================================================================

  test("GrpcException contains code and message"):
    val ex = GrpcException(GrpcStatus.InvalidArgument, "Bad request")
    ex.code shouldBe 3
    ex.message shouldBe "Bad request"
    ex.getMessage should include("gRPC error 3")

  test("GrpcException can wrap cause"):
    val cause = new RuntimeException("root cause")
    val ex = GrpcException(GrpcStatus.Internal, "Failed", Some(cause))

    ex.cause shouldBe Some(cause)
    ex.getCause shouldBe cause

  // ==========================================================================
  // Integration test
  // ==========================================================================

  test("Full gRPC service flow"):
    // Define service
    val calculatorService = GrpcService("Calculator")
      .unary[NumberRequest, NumberReply]("Add10") { req =>
        NumberReply(req.value + 10)
      }
      .unary[NumberRequest, NumberReply]("Multiply2") { req =>
        NumberReply(req.value * 2)
      }
      .serverStream[NumberRequest, NumberReply]("Range") { req =>
        LazyList.range(0, req.value).map(NumberReply(_))
      }
      .build()

    // Create server
    val server = GrpcServer(calculatorService)

    // Create client with mock server
    val client = GrpcClient.connect("localhost:50051")
      .withMockServer(server)

    // Make calls
    val add10Result = client.call[NumberRequest, NumberReply](
      "Calculator", "Add10", NumberRequest(5)
    )
    add10Result.toOption.get.result shouldBe 15

    val mul2Result = client.call[NumberRequest, NumberReply](
      "Calculator", "Multiply2", NumberRequest(7)
    )
    mul2Result.toOption.get.result shouldBe 14

    val rangeResult = client.callServerStream[NumberRequest, NumberReply](
      "Calculator", "Range", NumberRequest(5)
    )
    rangeResult.toOption.get.toList.map(_.result) shouldBe List(0, 1, 2, 3, 4)
