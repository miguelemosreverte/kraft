package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo04:
  case class Op(typ: String, v: Int); case class In(ops: List[Op]); case class Out(value: Int, history: List[String])
  given JsonValueCodec[Op] = JsonCodecMaker.make; given JsonValueCodec[In] = JsonCodecMaker.make
  given JsonValueCodec[Out] = JsonCodecMaker.make
  given listOpCodec: JsonValueCodec[List[Op]] = JsonCodecMaker.make
  given listStringCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make

  val wf = Workflow[In, Out]("counter") { (ctx, in) =>
    var (value, history, i) = (ctx.getState[Int]("v").getOrElse(0), ctx.getState[List[String]]("h").getOrElse(Nil), ctx.getState[Int]("i").getOrElse(0))
    while i < in.ops.size do
      val op = in.ops(i); val old = value
      val desc = ctx.sideEffect[String](s"op$i") {
        val (nv, d) = op.typ match
          case "+" => (value + op.v, s"$old + ${op.v} = ${old + op.v}")
          case "-" => (value - op.v, s"$old - ${op.v} = ${old - op.v}")
          case "*" => (value * op.v, s"$old * ${op.v} = ${old * op.v}")
          case "=" => (op.v, s"$old -> ${op.v}")
        value = nv; println(s"  $d"); d
      }
      history = history :+ desc; i += 1; ctx.setState("v", value); ctx.setState("h", history); ctx.setState("i", i)
    Out(value, history)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 4: Durable Counter ===")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val ops = List(Op("+",10), Op("+",5), Op("*",2), Op("-",3), Op("+",7), Op("*",3), Op("-",10), Op("+",1), Op("=",0), Op("+",100))
    val h = r.submit(wf, In(ops), "ctr-1")
    val out = h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value
    println(s"\nFinal value: ${out.value}")
    println("=== Complete ==="); r.shutdown()
