package kraft.durable

import org.scalatest.funsuite.AnyFunSuite
import kraft.dsl.durable.storage.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.runtime.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

class EmbeddedStorageSpec extends AnyFunSuite:

  // ===========================================================================
  // EmbeddedStore Tests
  // ===========================================================================

  test("InMemoryStore basic operations"):
    val store = InMemoryStore.open()
    val key = "test-key".getBytes
    val value = "test-value".getBytes

    assert(store.get(key).isEmpty)

    store.put(key, value)
    assert(store.get(key).map(new String(_)) == Some("test-value"))

    store.delete(key)
    assert(store.get(key).isEmpty)

    store.close()

  test("InMemoryStore prefix scan"):
    val store = InMemoryStore.open()

    store.put("prefix:a".getBytes, "1".getBytes)
    store.put("prefix:b".getBytes, "2".getBytes)
    store.put("prefix:c".getBytes, "3".getBytes)
    store.put("other:x".getBytes, "X".getBytes)

    val results = store.scan("prefix:".getBytes).toSeq
    assert(results.size == 3)
    assert(results.map(r => new String(r._2)).sorted == Seq("1", "2", "3"))

    store.close()

  test("InMemoryStore batch operations"):
    val store = InMemoryStore.open()

    store.batch(Seq(
      BatchOp.Put("k1".getBytes, "v1".getBytes),
      BatchOp.Put("k2".getBytes, "v2".getBytes),
      BatchOp.Put("k3".getBytes, "v3".getBytes)
    ))

    assert(store.get("k1".getBytes).isDefined)
    assert(store.get("k2".getBytes).isDefined)
    assert(store.get("k3".getBytes).isDefined)

    store.batch(Seq(
      BatchOp.Delete("k1".getBytes),
      BatchOp.Delete("k2".getBytes)
    ))

    assert(store.get("k1".getBytes).isEmpty)
    assert(store.get("k2".getBytes).isEmpty)
    assert(store.get("k3".getBytes).isDefined)

    store.close()

  // ===========================================================================
  // KeyEncoder Tests
  // ===========================================================================

  test("KeyEncoder journal keys are ordered"):
    val k1 = KeyEncoder.journalKey("wf-1", 0)
    val k2 = KeyEncoder.journalKey("wf-1", 1)
    val k3 = KeyEncoder.journalKey("wf-1", 10)

    // Lexicographic comparison should maintain order
    assert(new String(k1) < new String(k2))
    assert(new String(k2) < new String(k3))

  test("KeyEncoder timer keys are ordered by time"):
    val t1 = KeyEncoder.timerKey(1000, "timer-a")
    val t2 = KeyEncoder.timerKey(2000, "timer-b")
    val t3 = KeyEncoder.timerKey(10000, "timer-c")

    assert(new String(t1) < new String(t2))
    assert(new String(t2) < new String(t3))

  test("KeyEncoder prefixEnd increments correctly"):
    val prefix = "test".getBytes
    val end = KeyEncoder.prefixEnd(prefix)

    assert(new String(prefix) < new String(end))

  // ===========================================================================
  // NodeStorage Tests
  // ===========================================================================

  test("NodeStorage.journal append and getAll"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val entry1 = JournalEntry.call(0, "func1", """{"x":1}""")
    val entry2 = JournalEntry.call(1, "func2", """{"x":2}""")

    storage.journal.append("wf-1", entry1)
    storage.journal.append("wf-1", entry2)

    val entries = storage.journal.getAll("wf-1")
    assert(entries.size == 2)
    assert(entries.map(_.sequenceNumber).sorted == Seq(0, 1))

    storage.close()

  test("NodeStorage.journal complete"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val entry = JournalEntry.call(0, "func1", """{"x":1}""")
    storage.journal.append("wf-1", entry)

    assert(!storage.journal.getAll("wf-1").head.completed)

    storage.journal.complete("wf-1", 0, """{"result":42}""")

    val completed = storage.journal.getAll("wf-1").head
    assert(completed.completed)
    assert(completed.outputJson == Some("""{"result":42}"""))

    storage.close()

  test("NodeStorage.state operations"):
    given JsonValueCodec[String] = JsonCodecMaker.make
    given JsonValueCodec[Int] = JsonCodecMaker.make

    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    assert(storage.state.get[String]("wf-1", "key1").isEmpty)

    storage.state.set("wf-1", "key1", "value1")
    storage.state.set("wf-1", "key2", 42)

    assert(storage.state.get[String]("wf-1", "key1") == Some("value1"))
    assert(storage.state.get[Int]("wf-1", "key2") == Some(42))

    storage.state.delete("wf-1", "key1")
    assert(storage.state.get[String]("wf-1", "key1").isEmpty)

    storage.close()

  test("NodeStorage.workflow create and get"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val metadata = WorkflowMetadata.create("wf-1", "TestWorkflow", """{"input":1}""")

    assert(storage.workflow.create(metadata))
    assert(!storage.workflow.create(metadata)) // Duplicate

    val retrieved = storage.workflow.get("wf-1")
    assert(retrieved.isDefined)
    assert(retrieved.get.workflowId == "wf-1")
    assert(retrieved.get.workflowType == "TestWorkflow")

    storage.close()

  test("NodeStorage.workflow findByStatus"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val wf1 = WorkflowMetadata.create("wf-1", "Test", "{}").copy(status = WorkflowStatus.Pending)
    val wf2 = WorkflowMetadata.create("wf-2", "Test", "{}").copy(status = WorkflowStatus.Running)
    val wf3 = WorkflowMetadata.create("wf-3", "Test", "{}").copy(status = WorkflowStatus.Pending)

    storage.workflow.create(wf1)
    storage.workflow.create(wf2)
    storage.workflow.create(wf3)

    val pending = storage.workflow.findByStatus(WorkflowStatus.Pending, 10)
    assert(pending.size == 2)
    assert(pending.map(_.workflowId).toSet == Set("wf-1", "wf-3"))

    val running = storage.workflow.findByStatus(WorkflowStatus.Running, 10)
    assert(running.size == 1)
    assert(running.head.workflowId == "wf-2")

    storage.close()

  test("NodeStorage.workflow update changes status index"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val wf = WorkflowMetadata.create("wf-1", "Test", "{}").copy(status = WorkflowStatus.Pending)
    storage.workflow.create(wf)

    assert(storage.workflow.findByStatus(WorkflowStatus.Pending, 10).size == 1)
    assert(storage.workflow.findByStatus(WorkflowStatus.Running, 10).isEmpty)

    storage.workflow.update(wf.copy(status = WorkflowStatus.Running))

    assert(storage.workflow.findByStatus(WorkflowStatus.Pending, 10).isEmpty)
    assert(storage.workflow.findByStatus(WorkflowStatus.Running, 10).size == 1)

    storage.close()

  test("NodeStorage.timer schedule and findReady"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    val now = System.currentTimeMillis()
    val timer1 = DurableTimer("t1", "wf-1", 0, now - 1000) // Past - ready
    val timer2 = DurableTimer("t2", "wf-2", 0, now + 10000) // Future - not ready

    storage.timer.schedule(timer1)
    storage.timer.schedule(timer2)

    val ready = storage.timer.findReady(now, 10)
    assert(ready.size == 1)
    assert(ready.head.timerId == "t1")

    storage.close()

  // ===========================================================================
  // NodeRuntime Tests
  // ===========================================================================

  case class TestInput(value: Int)
  case class TestOutput(result: Int)

  given testInputCodec: JsonValueCodec[TestInput] = JsonCodecMaker.make
  given testOutputCodec: JsonValueCodec[TestOutput] = JsonCodecMaker.make

  test("NodeRuntime executes simple workflow"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("double"):
      (ctx, input) => TestOutput(input.value * 2)

    val handle = runtime.submit(workflow, TestInput(21))

    assert(handle.isCompleted)
    assert(handle.result.get.asInstanceOf[WorkflowResult.Completed[TestOutput]].value.result == 42)

    runtime.shutdown()

  test("NodeRuntime replays journal on resume"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    var sideEffectCount = 0

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("withSideEffect"):
      (ctx, input) =>
        given JsonValueCodec[Int] = JsonCodecMaker.make
        val x = ctx.sideEffect("increment"):
          sideEffectCount += 1
          sideEffectCount
        TestOutput(input.value + x)

    // First execution
    val handle1 = runtime.submit(workflow, TestInput(10), "wf-replay-test")
    assert(sideEffectCount == 1)
    assert(handle1.result.get.asInstanceOf[WorkflowResult.Completed[TestOutput]].value.result == 11)

    // Simulate resume (would normally be after restart)
    val handle2 = runtime.resume(workflow, "wf-replay-test")
    // Side effect should NOT run again - replayed from journal
    assert(sideEffectCount == 1)

    runtime.shutdown()

  test("NodeRuntime supports state"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("withState"):
      (ctx, input) =>
        given JsonValueCodec[Int] = JsonCodecMaker.make
        ctx.setState("counter", input.value)
        val saved = ctx.getState[Int]("counter").getOrElse(0)
        TestOutput(saved * 2)

    val handle = runtime.submit(workflow, TestInput(21))
    assert(handle.result.get.asInstanceOf[WorkflowResult.Completed[TestOutput]].value.result == 42)

    runtime.shutdown()

  test("NodeRuntime supports function calls"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)

    case class DoubleReq(x: Int)
    case class DoubleRes(result: Int)
    given JsonValueCodec[DoubleReq] = JsonCodecMaker.make
    given JsonValueCodec[DoubleRes] = JsonCodecMaker.make

    val runtime = NodeRuntime.make(storage)
      .register("double") { (req: DoubleReq) =>
        DoubleRes(req.x * 2)
      }

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("withCall"):
      (ctx, input) =>
        val res = ctx.call[DoubleReq, DoubleRes]("double", DoubleReq(input.value))
        TestOutput(res.result)

    val handle = runtime.submit(workflow, TestInput(21))
    assert(handle.result.get.asInstanceOf[WorkflowResult.Completed[TestOutput]].value.result == 42)

    runtime.shutdown()

  test("NodeRuntime captures failures"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("failing"):
      (ctx, input) =>
        throw new RuntimeException("Intentional failure")

    val handle = runtime.submit(workflow, TestInput(1))

    assert(handle.isFailed)
    val failed = handle.result.get.asInstanceOf[WorkflowResult.Failed[TestOutput]]
    assert(failed.error.getMessage == "Intentional failure")

    runtime.shutdown()

  test("NodeRuntime cancel workflow"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("simple"):
      (ctx, input) => TestOutput(input.value)

    val handle = runtime.submit(workflow, TestInput(1), "wf-to-cancel")

    assert(runtime.cancel("wf-to-cancel"))
    assert(runtime.getStatus("wf-to-cancel").exists(_.status == WorkflowStatus.Cancelled))

    runtime.shutdown()

  test("NodeRuntime getStatus"):
    val store = InMemoryStore.open()
    val storage = NodeStorage.make(store)
    val runtime = NodeRuntime.make(storage)

    val workflow = NodeRuntime.Workflow[TestInput, TestOutput]("simple"):
      (ctx, input) => TestOutput(input.value)

    runtime.submit(workflow, TestInput(1), "wf-status-test")

    val status = runtime.getStatus("wf-status-test")
    assert(status.isDefined)
    assert(status.get.status == WorkflowStatus.Completed)

    runtime.shutdown()
