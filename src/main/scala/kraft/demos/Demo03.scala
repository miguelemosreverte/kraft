package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo03:
  case class Cmd(op: String, args: String); case class In(cmds: List[Cmd]); case class Out(results: List[String])
  given JsonValueCodec[Cmd] = JsonCodecMaker.make; given JsonValueCodec[In] = JsonCodecMaker.make
  given JsonValueCodec[Out] = JsonCodecMaker.make
  given listCmdCodec: JsonValueCodec[List[Cmd]] = JsonCodecMaker.make
  given listStringCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make

  def exec(c: Cmd): String = c.op match
    case "ECHO" => c.args
    case "UPPER" => c.args.toUpperCase
    case "REVERSE" => c.args.reverse
    case "LEN" => c.args.length.toString
    case "ADD" => c.args.split(",").map(_.trim.toInt).sum.toString
    case _ => s"Unknown: ${c.op}"

  val wf = Workflow[In, Out]("commands") { (ctx, in) =>
    var results = ctx.getState[List[String]]("r").getOrElse(Nil)
    var i = ctx.getState[Int]("i").getOrElse(0)
    while i < in.cmds.size do
      val c = in.cmds(i)
      val r = ctx.sideEffect[String](s"cmd$i") { val res = exec(c); println(s"  [Exec] ${c.op}(${c.args}) = $res"); res }
      results = results :+ r; i += 1; ctx.setState("r", results); ctx.setState("i", i)
    Out(results)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 3: Remote Commands ===")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val cmds = List(Cmd("ECHO","Hello"), Cmd("UPPER","world"), Cmd("REVERSE","stressed"), Cmd("LEN","testing"), Cmd("ADD","10,20,30"), Cmd("ECHO","Done"), Cmd("UPPER","loud"), Cmd("REVERSE","level"), Cmd("LEN","abcdefghij"), Cmd("ADD","1,2,3,4"))
    val h = r.submit(wf, In(cmds), "cmd-1")
    println(s"Results: ${h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value.results.mkString(", ")}")
    println("=== Complete ==="); r.shutdown()
