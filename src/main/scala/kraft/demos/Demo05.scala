package kraft.demos
import kraft.dsl.durable.storage.*, kraft.dsl.durable.runtime.*, kraft.dsl.durable.runtime.NodeRuntime.*, kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*, com.github.plokhotnyuk.jsoniter_scala.macros.*

object Demo05:
  case class In(id: String, failAt: Option[String]); case class Out(ok: Boolean, booked: List[String], compensated: List[String])
  given JsonValueCodec[In] = JsonCodecMaker.make; given JsonValueCodec[Out] = JsonCodecMaker.make
  given JsonValueCodec[List[String]] = JsonCodecMaker.make; given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make

  val wf = Workflow[In, Out]("saga") { (ctx, in) =>
    var (booked, comp) = (ctx.getState[List[String]]("b").getOrElse(Nil), ctx.getState[List[String]]("c").getOrElse(Nil))
    try
      if !booked.contains("hotel") then
        ctx.sideEffect[String]("hotel") { if in.failAt.contains("hotel") then throw new Exception("Hotel failed"); "ok" }
        println(s"  ✓ Hotel booked"); booked = booked :+ "hotel"; ctx.setState("b", booked)
      if !booked.contains("flight") then
        ctx.sideEffect[String]("flight") { if in.failAt.contains("flight") then throw new Exception("Flight failed"); "ok" }
        println(s"  ✓ Flight booked"); booked = booked :+ "flight"; ctx.setState("b", booked)
      if !booked.contains("car") then
        ctx.sideEffect[String]("car") { if in.failAt.contains("car") then throw new Exception("Car failed"); "ok" }
        println(s"  ✓ Car booked"); booked = booked :+ "car"; ctx.setState("b", booked)
      println("  All bookings successful!"); Out(true, booked, comp)
    catch case e: Exception =>
      println(s"  ✗ ${e.getMessage} - Compensating...")
      if booked.contains("flight") then { ctx.sideEffect[String]("undo-flight") { "ok" }; println("  ↩ Flight cancelled"); comp = comp :+ "flight" }
      if booked.contains("hotel") then { ctx.sideEffect[String]("undo-hotel") { "ok" }; println("  ↩ Hotel cancelled"); comp = comp :+ "hotel" }
      ctx.setState("c", comp); Out(false, booked, comp)
  }

  def main(args: Array[String]): Unit =
    println("=== Demo 5: Saga Pattern ===\n")
    val r = NodeRuntime.make(NodeStorage.make(InMemoryStore.open()))
    println("Scenario 1: Success"); r.submit(wf, In("1", None), "s1"); println()
    println("Scenario 2: Flight fails"); r.submit(wf, In("2", Some("flight")), "s2"); println()
    println("Scenario 3: Car fails"); r.submit(wf, In("3", Some("car")), "s3")
    println("\n=== Complete ==="); r.shutdown()
