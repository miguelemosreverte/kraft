package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.concurrent.{Executors, CountDownLatch}

object Demo08:
  case class Task(id: Int, data: Int); case class In(tasks: List[Task]); case class Out(results: List[Int], time: Long)
  given JsonValueCodec[Task] = JsonCodecMaker.make; given JsonValueCodec[In] = JsonCodecMaker.make
  given JsonValueCodec[Out] = JsonCodecMaker.make
  given listTaskCodec: JsonValueCodec[List[Task]] = JsonCodecMaker.make
  given listIntCodec: JsonValueCodec[List[Int]] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make; given JsonValueCodec[Long] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make

  val results = new java.util.concurrent.ConcurrentHashMap[Int, Int]()

  val wf = Workflow[In, Out]("fanout") { (ctx, in) =>
    val start = ctx.sideEffect[Long]("start") { System.currentTimeMillis() }
    ctx.sideEffect[String]("parallel") {
      val exec = Executors.newFixedThreadPool(4); val latch = new CountDownLatch(in.tasks.size)
      in.tasks.foreach { t => exec.submit(new Runnable { def run() = { Thread.sleep(50 + t.data % 50); results.put(t.id, t.data * t.data); println(s"  [W${t.id % 4}] Task ${t.id}: ${t.data}² = ${t.data * t.data}"); latch.countDown() } }) }
      latch.await(); exec.shutdown(); "done"
    }
    val res = ctx.sideEffect[List[Int]]("collect") { in.tasks.map(t => results.get(t.id)) }
    val elapsed = ctx.sideEffect[Long]("end") { System.currentTimeMillis() - start }
    Out(res, elapsed)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 8: Parallel Fan-Out ===")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val tasks = (1 to 10).map(i => Task(i, i * 10)).toList
    println(s"Distributing ${tasks.size} tasks...")
    val h = r.submit(wf, In(tasks), "fanout-1")
    val out = h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value
    println(s"\nResults: ${out.results.mkString(", ")}")
    println(s"Total time: ${out.time}ms")
    println("=== Complete ==="); r.shutdown()
