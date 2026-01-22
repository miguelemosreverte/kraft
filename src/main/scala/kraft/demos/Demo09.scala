package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.concurrent.atomic.AtomicInteger

object Demo09:
  case class Req(endpoint: String, failCount: Int); case class In(reqs: List[Req]); case class Out(results: List[String])
  given JsonValueCodec[Req] = JsonCodecMaker.make; given JsonValueCodec[In] = JsonCodecMaker.make
  given JsonValueCodec[Out] = JsonCodecMaker.make
  given listReqCodec: JsonValueCodec[List[Req]] = JsonCodecMaker.make
  given listStringCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make; given JsonValueCodec[Int] = JsonCodecMaker.make

  val counters = new java.util.concurrent.ConcurrentHashMap[String, AtomicInteger]()

  def callApi(r: Req): String =
    val c = counters.computeIfAbsent(r.endpoint, _ => new AtomicInteger(0))
    val attempt = c.incrementAndGet()
    if attempt <= r.failCount then { println(s"    [API] ${r.endpoint} attempt $attempt FAILED"); throw new Exception(s"Transient failure") }
    println(s"    [API] ${r.endpoint} attempt $attempt SUCCESS"); s"OK from ${r.endpoint}"

  val wf = Workflow[In, Out]("retry") { (ctx, in) =>
    var (results, i) = (ctx.getState[List[String]]("r").getOrElse(Nil), ctx.getState[Int]("i").getOrElse(0))
    while i < in.reqs.size do
      val req = in.reqs(i)
      println(s"  Calling ${req.endpoint} (will fail ${req.failCount} times)...")
      val res = ctx.sideEffect[String](s"call$i") {
        var (att, ok, r) = (0, false, ""); var lastErr: Throwable = null
        while att < 5 && !ok do { att += 1; try { r = callApi(req); ok = true } catch { case e: Throwable => lastErr = e; if att < 5 then { println(s"    Retry in ${att * 50}ms..."); Thread.sleep(att * 50) } } }
        if ok then r else s"FAILED: ${lastErr.getMessage}"
      }
      results = results :+ s"${req.endpoint}: $res"; i += 1; ctx.setState("r", results); ctx.setState("i", i); println()
    Out(results)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 9: Automatic Retry ===\n")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    val reqs = List(Req("/ok", 0), Req("/fails-2x", 2), Req("/fails-3x", 3), Req("/always-fails", 10), Req("/ok-2", 0), Req("/fails-1x", 1), Req("/ok-3", 0), Req("/fails-2x-b", 2), Req("/ok-4", 0), Req("/fails-1x-b", 1))
    val h = r.submit(wf, In(reqs), "retry-1")
    val out = h.result.get.asInstanceOf[WorkflowResult.Completed[Out]].value
    println("--- Summary ---"); out.results.foreach(r => println(s"  ${if r.contains("FAILED") then "✗" else "✓"} $r"))
    println("\n=== Complete ==="); r.shutdown()
