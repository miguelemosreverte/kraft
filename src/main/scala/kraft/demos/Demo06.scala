package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo06:
  case class In(intervals: List[Int]); case class Out(events: List[String])
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given listIntCodec: JsonValueCodec[List[Int]] = JsonCodecMaker.make
  given listStringCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make; given JsonValueCodec[Long] = JsonCodecMaker.make

  val wf = Workflow[In, Out]("timer") { (ctx, in) =>
    var (events, i) = (ctx.getState[List[String]]("e").getOrElse(Nil), ctx.getState[Int]("i").getOrElse(0))
    val start = ctx.sideEffect[Long]("start") { System.currentTimeMillis() }
    while i < in.intervals.size do
      val iv = in.intervals(i)
      ctx.sideEffect[String](s"sleep$i") { Thread.sleep(iv); "ok" }
      val elapsed = ctx.sideEffect[Long](s"time$i") { System.currentTimeMillis() - start }
      val ev = s"Step ${i+1}: Slept ${iv}ms (total: ${elapsed}ms)"
      println(s"  $ev"); events = events :+ ev; i += 1; ctx.setState("e", events); ctx.setState("i", i)
    Out(events)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 6: Durable Timer ===")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val h = r.submit(wf, In(List(50, 100, 75, 50, 100, 75, 50, 100, 75, 50)), "timer-1")
    val out = h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value
    println(s"\n${out.events.size} timed events completed")
    println("=== Complete ==="); r.shutdown()
