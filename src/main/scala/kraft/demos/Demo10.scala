package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo10:
  case class In(batch: String, count: Int, crashAt: Option[Int]); case class Out(processed: Int)
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make

  val processedItems = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  def processItem(batch: String, n: Int): String =
    val id = s"$batch-$n"
    if processedItems.putIfAbsent(id, java.lang.Boolean.TRUE) != null then throw new Exception(s"DUPLICATE: $id")
    id

  val wf = Workflow[In, Out]("batch") { (ctx, in) =>
    var n = ctx.getState[Int]("n").getOrElse(0)
    println(s"  [${in.batch}] Starting from item $n")
    while n < in.count do
      if in.crashAt.contains(n) then { println(s"  [${in.batch}] !!! CRASH at item $n !!!"); throw new Exception("Simulated crash") }
      ctx.sideEffect[String](s"item$n") { val id = processItem(in.batch, n); println(s"  [${in.batch}] Processed $id"); id }
      n += 1; ctx.setState("n", n)
      if n % 3 == 0 then println(s"  [${in.batch}] *** CHECKPOINT at $n ***")
    Out(n)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 10: Checkpoint and Recovery ===\n")
    val store = InMemoryStore.open(); val storage = NodeStorage.make(store)

    println("Phase 1: Run until crash at item 5")
    val r1 = NodeRuntime.make(storage, NodeConfig(nodeId = "v1"))
    try { r1.submit(wf, In("batch-001", 10, Some(5)), "recovery-wf") } catch { case _: Exception => println("  Workflow crashed\n") }

    println("Phase 2: Resume after 'restart' (items 0-4 not reprocessed)")
    val r2 = NodeRuntime.make(storage, NodeConfig(nodeId = "v2"))
    val wf2 = Workflow[In, Out]("batch") { (ctx, in) => // Non-crashing version for resume
      var n = ctx.getState[Int]("n").getOrElse(0)
      println(s"  [${in.batch}] Resuming from item $n")
      while n < in.count do
        ctx.sideEffect[String](s"item$n") { val id = processItem(in.batch, n); println(s"  [${in.batch}] Processed $id"); id }
        n += 1; ctx.setState("n", n)
        if n % 3 == 0 then println(s"  [${in.batch}] *** CHECKPOINT at $n ***")
      Out(n)
    }
    r2.resume(wf2, "recovery-wf")

    println(s"\n--- Verification ---")
    println(s"  Total unique items: ${processedItems.size()} (expected: 10)")
    println(s"  No duplicates: ${processedItems.size() == 10}")
    println("\n=== Complete ==="); r2.shutdown()
