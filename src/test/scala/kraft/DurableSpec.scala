package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.durable.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicInteger

// Test types
case class OrderInput(orderId: String, amount: Double)
case class OrderResult(orderId: String, status: String)
case class PaymentRequest(amount: Double)
case class PaymentResult(transactionId: String)

object DurableTestCodecs:
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[Double] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make
  given JsonValueCodec[Unit] = new JsonValueCodec[Unit]:
    def decodeValue(in: JsonReader, default: Unit): Unit =
      in.readRawValAsBytes()
      ()
    def encodeValue(x: Unit, out: JsonWriter): Unit = out.writeNull()
    def nullValue: Unit = ()
  given JsonValueCodec[OrderInput] = JsonCodecMaker.make
  given JsonValueCodec[OrderResult] = JsonCodecMaker.make
  given JsonValueCodec[PaymentRequest] = JsonCodecMaker.make
  given JsonValueCodec[PaymentResult] = JsonCodecMaker.make

class DurableSpec extends AnyFunSuite with Matchers:
  import DurableTestCodecs.given

  // ==========================================================================
  // RetryPolicy tests
  // ==========================================================================

  test("RetryPolicy.default has sensible defaults"):
    val policy = RetryPolicy.default
    policy.maxAttempts shouldBe 3
    policy.initialDelay shouldBe 100.millis
    policy.backoffMultiplier shouldBe 2.0

  test("RetryPolicy.noRetry doesn't retry"):
    val policy = RetryPolicy.noRetry
    policy.maxAttempts shouldBe 1
    policy.shouldRetry(1, new Exception()) shouldBe false

  test("RetryPolicy calculates exponential backoff"):
    val policy = RetryPolicy(initialDelay = 100.millis, backoffMultiplier = 2.0)

    policy.delayFor(1).toMillis shouldBe 100
    policy.delayFor(2).toMillis shouldBe 200
    policy.delayFor(3).toMillis shouldBe 400

  test("RetryPolicy respects maxDelay"):
    val policy = RetryPolicy(
      initialDelay = 1.second,
      maxDelay = 2.seconds,
      backoffMultiplier = 10.0
    )

    policy.delayFor(1) shouldBe 1.second
    policy.delayFor(2) shouldBe 2.seconds  // Capped at maxDelay
    policy.delayFor(3) shouldBe 2.seconds

  test("RetryPolicy.shouldRetry checks attempt count"):
    val policy = RetryPolicy(maxAttempts = 3)

    policy.shouldRetry(1, new Exception()) shouldBe true
    policy.shouldRetry(2, new Exception()) shouldBe true
    policy.shouldRetry(3, new Exception()) shouldBe false

  // ==========================================================================
  // Journal tests
  // ==========================================================================

  test("Journal tracks sequence numbers"):
    val journal = new Journal()

    journal.nextSequence() shouldBe 0
    journal.nextSequence() shouldBe 1
    journal.nextSequence() shouldBe 2
    journal.currentSequence() shouldBe 3

  test("Journal can append and retrieve entries"):
    val journal = new Journal()
    val entry = JournalEntry(
      sequenceNumber = 0,
      entryType = JournalEntryType.Call,
      name = "TestCall",
      inputJson = Some("""{"x":1}"""),
      outputJson = None
    )

    journal.append(entry)
    journal.get(0) shouldBe Some(entry)
    journal.get(1) shouldBe None

  test("Journal can complete entries"):
    val journal = new Journal()
    val entry = JournalEntry(
      sequenceNumber = 0,
      entryType = JournalEntryType.Call,
      name = "TestCall",
      inputJson = Some("{}"),
      outputJson = None
    )

    journal.append(entry)
    journal.complete(0, """{"result":"done"}""")

    val completed = journal.get(0).get
    completed.completed shouldBe true
    completed.outputJson shouldBe Some("""{"result":"done"}""")

  test("Journal.allEntries returns sorted entries"):
    val journal = new Journal()
    journal.append(JournalEntry(2, JournalEntryType.Call, "c", None, None))
    journal.append(JournalEntry(0, JournalEntryType.Call, "a", None, None))
    journal.append(JournalEntry(1, JournalEntryType.Call, "b", None, None))

    val entries = journal.allEntries
    entries.map(_.name) shouldBe Seq("a", "b", "c")

  // ==========================================================================
  // StateStore tests
  // ==========================================================================

  test("InMemoryStateStore can get and set state"):
    val store = new InMemoryStateStore()

    store.set("instance1", "counter", 42)
    store.get[Int]("instance1", "counter") shouldBe Some(42)

  test("InMemoryStateStore returns None for missing key"):
    val store = new InMemoryStateStore()
    store.get[String]("instance1", "missing") shouldBe None

  test("InMemoryStateStore can clear state"):
    val store = new InMemoryStateStore()
    store.set("instance1", "key", "value")
    store.clear("instance1", "key")

    store.get[String]("instance1", "key") shouldBe None

  test("InMemoryStateStore isolates instances"):
    val store = new InMemoryStateStore()
    store.set("instance1", "key", "value1")
    store.set("instance2", "key", "value2")

    store.get[String]("instance1", "key") shouldBe Some("value1")
    store.get[String]("instance2", "key") shouldBe Some("value2")

  // ==========================================================================
  // FunctionRegistry tests
  // ==========================================================================

  test("FunctionRegistry can register and call functions"):
    val registry = new FunctionRegistry()
      .register[Int, Int]("double") { x => x * 2 }
      .register[String, String]("greet") { name => s"Hello, $name!" }

    registry.handle[Int, Int]("double", 21) shouldBe 42
    registry.handle[String, String]("greet", "World") shouldBe "Hello, World!"

  test("FunctionRegistry throws for unknown function"):
    val registry = new FunctionRegistry()

    assertThrows[DurableException] {
      registry.handle[Int, Int]("unknown", 1)
    }

  // ==========================================================================
  // Workflow definition tests
  // ==========================================================================

  test("Workflow.apply creates a workflow definition"):
    val workflow = Workflow[String, String]("TestWorkflow") { (ctx, input) =>
      s"Processed: $input"
    }

    workflow.name shouldBe "TestWorkflow"

  test("Workflow.unit creates workflow without input"):
    val workflow = Workflow.unit[Int]("Counter") { ctx =>
      42
    }

    workflow.name shouldBe "Counter"

  // ==========================================================================
  // WorkflowRuntime tests
  // ==========================================================================

  test("WorkflowRuntime can start a simple workflow"):
    val workflow = Workflow[String, String]("Echo") { (ctx, input) =>
      s"Echo: $input"
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, "Hello")

    handle.isCompleted shouldBe true
    handle.result.get.toOption.get shouldBe "Echo: Hello"

  test("WorkflowRuntime supports sideEffect"):
    val counter = new AtomicInteger(0)

    val workflow = Workflow[Int, Int]("Counter") { (ctx, input) =>
      val value = ctx.sideEffect("increment")(counter.incrementAndGet())
      value + input
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, 10)

    handle.result.get.toOption.get shouldBe 11
    counter.get() shouldBe 1

  test("WorkflowRuntime supports state"):
    val workflow = Workflow[String, String]("Stateful") { (ctx, input) =>
      ctx.setState("name", input)
      val name = ctx.getState[String]("name")
      s"Hello, ${name.getOrElse("Unknown")}!"
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, "Alice")

    handle.result.get.toOption.get shouldBe "Hello, Alice!"

  test("WorkflowRuntime supports uuid generation"):
    val workflow = Workflow.unit[String]("UuidGenerator") { ctx =>
      ctx.uuid()
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, ())

    handle.result.get.toOption.get should have length 36  // UUID format

  test("WorkflowRuntime supports random generation"):
    val workflow = Workflow.unit[Double]("RandomGenerator") { ctx =>
      ctx.random()
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, ())

    val result = handle.result.get.toOption.get
    result should be >= 0.0
    result should be < 1.0

  test("WorkflowRuntime captures failures"):
    val workflow = Workflow[String, String]("Failing") { (ctx, input) =>
      throw new RuntimeException("Intentional failure")
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, "input")

    handle.isFailed shouldBe true
    handle.result.get.isFailed shouldBe true

  test("WorkflowRuntime with custom call handler"):
    val registry = new FunctionRegistry()
      .register[String, String]("greet") { name => s"Hello, $name!" }

    val workflow = Workflow[String, String]("Caller") { (ctx, input) =>
      ctx.call[String, String]("greet", input)
    }

    val runtime = new WorkflowRuntime().withCallHandler(registry)
    val handle = runtime.start(workflow, "World")

    handle.result.get.toOption.get shouldBe "Hello, World!"

  // ==========================================================================
  // WorkflowResult tests
  // ==========================================================================

  test("WorkflowResult.Completed contains value"):
    val result = WorkflowResult.Completed("success")

    result.isCompleted shouldBe true
    result.isFailed shouldBe false
    result.toOption shouldBe Some("success")
    result.toEither shouldBe Right("success")

  test("WorkflowResult.Failed contains error"):
    val error = new RuntimeException("failed")
    val result = WorkflowResult.Failed(error)

    result.isCompleted shouldBe false
    result.isFailed shouldBe true
    result.toOption shouldBe None
    result.toEither shouldBe Left(error)

  test("WorkflowResult.Running is not completed"):
    val result = WorkflowResult.Running

    result.isCompleted shouldBe false
    result.isFailed shouldBe false
    result.toOption shouldBe None

  // ==========================================================================
  // WorkflowHandle tests
  // ==========================================================================

  test("WorkflowHandle provides journal access"):
    val workflow = Workflow[String, String]("Journaled") { (ctx, input) =>
      ctx.sideEffect("log")("logged")
      input
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(workflow, "test")

    handle.journal should not be None
    handle.journal.get.allEntries.length should be > 0

  // ==========================================================================
  // Saga tests
  // ==========================================================================

  test("Saga executes steps in order"):
    val steps = new java.util.ArrayList[String]()

    val saga = Saga[String]("OrderProcess") { ctx =>
      ctx.step("step1", { steps.add("step1"); "done1" }, _ => steps.add("undo1"))
      ctx.step("step2", { steps.add("step2"); "done2" }, _ => steps.add("undo2"))
      "completed"
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(saga, ())

    handle.result.get.toOption.get shouldBe "completed"
    steps.toArray.toSeq shouldBe Seq("step1", "step2")

  test("Saga rolls back on failure"):
    val steps = new java.util.ArrayList[String]()

    val saga = Saga[String]("FailingSaga") { ctx =>
      ctx.step("step1", { steps.add("step1"); "done1" }, _ => steps.add("undo1"))
      ctx.step("step2", { steps.add("step2"); "done2" }, _ => steps.add("undo2"))
      throw new RuntimeException("Failure after steps")
    }

    val runtime = new WorkflowRuntime()
    val handle = runtime.start(saga, ())

    handle.isFailed shouldBe true
    // Compensations should be called in reverse order
    steps.toArray.toSeq should contain allOf("step1", "step2", "undo2", "undo1")

  // ==========================================================================
  // DurableException tests
  // ==========================================================================

  test("DurableException contains message"):
    val ex = DurableException("Something failed")
    ex.message shouldBe "Something failed"
    ex.getMessage shouldBe "Something failed"

  test("DurableException can wrap cause"):
    val cause = new RuntimeException("root")
    val ex = DurableException("Wrapper", Some(cause))

    ex.cause shouldBe Some(cause)
    ex.getCause shouldBe cause

  // ==========================================================================
  // Integration test
  // ==========================================================================

  test("Full order processing workflow"):
    // Set up function registry
    val registry = new FunctionRegistry()
      .register[OrderInput, Boolean]("ValidateOrder") { order =>
        order.amount > 0
      }
      .register[PaymentRequest, PaymentResult]("ProcessPayment") { req =>
        PaymentResult(s"tx-${System.currentTimeMillis()}")
      }

    // Define workflow
    val orderWorkflow = Workflow[OrderInput, OrderResult]("ProcessOrder") { (ctx, order) =>
      // Validate
      val isValid = ctx.call[OrderInput, Boolean]("ValidateOrder", order)
      if !isValid then
        OrderResult(order.orderId, "INVALID")
      else
        // Process payment
        val payment = ctx.call[PaymentRequest, PaymentResult](
          "ProcessPayment",
          PaymentRequest(order.amount)
        )

        // Record state
        ctx.setState("lastTransaction", payment.transactionId)

        // Generate confirmation number
        val confirmation = ctx.uuid()

        OrderResult(order.orderId, s"CONFIRMED:${confirmation.take(8)}")
    }

    val runtime = new WorkflowRuntime().withCallHandler(registry)
    val handle = runtime.start(orderWorkflow, OrderInput("order-123", 99.99))

    handle.isCompleted shouldBe true
    val result = handle.result.get.toOption.get
    result.orderId shouldBe "order-123"
    result.status should startWith("CONFIRMED:")

  test("Workflow with retry"):
    var attempts = 0
    val registry = new FunctionRegistry()
      .register[Int, Int]("FlakyService") { _ =>
        attempts += 1
        if attempts < 3 then throw new RuntimeException("Temporary failure")
        attempts
      }

    val workflow = Workflow[Int, Int]("RetryingWorkflow") { (ctx, input) =>
      ctx.call[Int, Int]("FlakyService", input, RetryPolicy(maxAttempts = 5, initialDelay = 1.millis))
    }

    val runtime = new WorkflowRuntime().withCallHandler(registry)
    val handle = runtime.start(workflow, 0)

    handle.isCompleted shouldBe true
    handle.result.get.toOption.get shouldBe 3
    attempts shouldBe 3
