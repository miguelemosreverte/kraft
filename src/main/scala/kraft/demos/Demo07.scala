package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo07:
  case class In(orderId: String, items: List[String]); case class Out(state: String, transitions: List[String])
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given JsonValueCodec[List[String]] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make

  val states = List("Created", "Validated", "PaymentPending", "Paid", "Preparing", "Shipped", "InTransit", "Delivered")

  val wf = Workflow[In, Out]("order-sm") { (ctx, in) =>
    var (state, trans, i) = (ctx.getState[String]("s").getOrElse("Created"), ctx.getState[List[String]]("t").getOrElse(Nil), ctx.getState[Int]("i").getOrElse(0))
    println(s"  [${in.orderId}] Items: ${in.items.mkString(", ")}")
    while i < states.size - 1 && state != "Delivered" do
      val from = state; val to = states(i + 1)
      ctx.sideEffect[String](s"trans$i") { Thread.sleep(30); "ok" }
      state = to; trans = trans :+ s"$from -> $to"
      println(s"  [${in.orderId}] $from -> $to")
      i += 1; ctx.setState("s", state); ctx.setState("t", trans); ctx.setState("i", i)
    Out(state, trans)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 7: Order State Machine ===\n")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    List(("ORD-001", List("Laptop", "Mouse")), ("ORD-002", List("Monitor")), ("ORD-003", List("Keyboard", "Webcam"))).zipWithIndex.foreach { case ((id, items), idx) =>
      println(s"Order ${idx + 1}:"); val h = r.submit(wf, In(id, items), s"order-$idx")
      val out = h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value
      println(s"  Final: ${out.state} (${out.transitions.size} transitions)\n")
    }
    println("=== Complete ==="); r.shutdown()
