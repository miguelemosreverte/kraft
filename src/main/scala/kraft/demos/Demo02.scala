package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo02:
  case class In(id: String); case class Out(count: Int)
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make
  val people = List("Alice", "Bob", "Charlie")
  val msgs = List("Hello!", "How are you?", "Good thanks!", "Working on project", "Cool!", "What's the plan?", "About 2 weeks", "Need help?", "Will let you know", "Bye!")

  val wf = Workflow[In, Out]("chat") { (ctx, in) =>
    var n = ctx.getState[Int]("n").getOrElse(0)
    println(s"[Chat ${in.id}] Starting from message $n")
    while n < 10 do
      val msg = ctx.sideEffect[String](s"m$n") { val m = s"[${people(n % 3)}]: ${msgs(n)}"; println(s"  $m"); m }
      n += 1; ctx.setState("n", n); Thread.sleep(30)
    Out(n)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 2: Chat Session ===")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val h = r.submit(wf, In("sess-1"), "chat-1")
    println(s"Result: ${h.result}")
    println("=== Complete ==="); r.shutdown()
