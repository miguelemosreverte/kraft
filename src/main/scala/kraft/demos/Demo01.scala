package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo01:
  case class In(name: String, other: String, first: Boolean); case class Out(sent: Int, recv: Int)
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make
  val queues = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.ConcurrentLinkedQueue[String]]()
  def q(n: String) = queues.computeIfAbsent(n, _ => new java.util.concurrent.ConcurrentLinkedQueue[String]())

  val wf = Workflow[In, Out]("pingpong") { (ctx, in) =>
    var (s, r) = (ctx.getState[Int]("s").getOrElse(0), ctx.getState[Int]("r").getOrElse(0))
    if in.first && s == 0 then { ctx.sideEffect[String](s"s$s") { q(in.other).offer(s"PING:1:${in.name}"); "ok" }; println(s"[${in.name}] PING #1"); s += 1; ctx.setState("s", s) }
    while s < 10 || r < 10 do
      val m = ctx.sideEffect[String](s"r$r") { Option(q(in.name).poll()).getOrElse("") }
      if m.nonEmpty then { val Array(t, n, f) = m.split(':'); r += 1; ctx.setState("r", r); println(s"[${in.name}] Got $t #$n from $f")
        if s < 10 then { val nt = if t == "PING" then "PONG" else "PING"; ctx.sideEffect[String](s"s$s") { q(in.other).offer(s"$nt:${n.toInt+1}:${in.name}"); "ok" }; println(s"[${in.name}] $nt #${n.toInt+1}"); s += 1; ctx.setState("s", s) }
      } else Thread.sleep(5)
    Out(s, r)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 1: Ping-Pong ===")
    val (r1, r2) = (NodeRuntime.make(NodeStorage.make(InMemoryStore.open())), NodeRuntime.make(NodeStorage.make(InMemoryStore.open())))
    val t1 = new Thread(() => { r1.submit(wf, In("Alice", "Bob", true), "a"); println("[Alice] Done") })
    val t2 = new Thread(() => { r2.submit(wf, In("Bob", "Alice", false), "b"); println("[Bob] Done") })
    t1.start(); t2.start(); t1.join(); t2.join()
    println("=== Complete ==="); r1.shutdown(); r2.shutdown()
