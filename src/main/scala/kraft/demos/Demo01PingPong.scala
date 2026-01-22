package kraft.demos

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.runtime.*
import kraft.dsl.durable.runtime.NodeRuntime.*
import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.duration.*

object Demo01PingPong:
  case class Message(from: String, content: String, count: Int)
  case class PingPongInput(myName: String, otherName: String, startFirst: Boolean)
  case class PingPongOutput(messagesSent: Int, messagesReceived: Int)

  given JsonValueCodec[Message] = JsonCodecMaker.make
  given JsonValueCodec[PingPongInput] = JsonCodecMaker.make
  given JsonValueCodec[PingPongOutput] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make
  given JsonValueCodec[Boolean] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make

  // Shared message queues (simulating inter-node communication)
  private val queues = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.ConcurrentLinkedQueue[Message]]()

  def getQueue(name: String): java.util.concurrent.ConcurrentLinkedQueue[Message] =
    queues.computeIfAbsent(name, _ => new java.util.concurrent.ConcurrentLinkedQueue[Message]())

  val pingPongWorkflow = Workflow[PingPongInput, PingPongOutput]("ping-pong") { (ctx, input) =>
    var sent = ctx.getState[Int]("sent").getOrElse(0)
    var received = ctx.getState[Int]("received").getOrElse(0)
    val maxMessages = 10

    // If we start first, send initial ping
    if input.startFirst && sent == 0 then
      val msg = Message(input.myName, "PING", 1)
      ctx.sideEffect[String](s"send-${sent + 1}") {
        getQueue(input.otherName).offer(msg)
        println(s"  [${input.myName}] Sent: ${msg.content} #${msg.count}")
        "sent"
      }
      sent += 1
      ctx.setState("sent", sent)

    // Exchange messages until we reach 10 each
    while sent < maxMessages || received < maxMessages do
      // Try to receive a message
      val maybeMsg = ctx.sideEffect[String](s"recv-attempt-$received") {
        val polled = getQueue(input.myName).poll()
        if polled != null then
          s"${polled.from}|${polled.content}|${polled.count}"
        else
          ""
      }

      if maybeMsg.nonEmpty then
        val parts = maybeMsg.split('|')
        val msgFrom = parts(0)
        val msgContent = parts(1)
        val msgCount = parts(2).toInt

        received += 1
        ctx.setState("received", received)
        println(s"  [${input.myName}] Received: $msgContent #$msgCount from $msgFrom")

        // Reply if we haven't sent enough
        if sent < maxMessages then
          val replyContent = if msgContent == "PING" then "PONG" else "PING"
          val reply = Message(input.myName, replyContent, msgCount + 1)
          ctx.sideEffect[String](s"send-${sent + 1}") {
            getQueue(input.otherName).offer(reply)
            println(s"  [${input.myName}] Sent: ${reply.content} #${reply.count}")
            "sent"
          }
          sent += 1
          ctx.setState("sent", sent)
      else
        // Brief wait if no message
        Thread.sleep(10)

    PingPongOutput(sent, received)
  }

  def main(args: Array[String]): Unit =
    println("Starting Ping-Pong Demo...")
    println("")

    // Create two separate nodes with their own storage
    val store1 = InMemoryStore.open()
    val storage1 = NodeStorage.make(store1)
    val runtime1 = NodeRuntime.make(storage1, NodeConfig(nodeId = "node-alice"))

    val store2 = InMemoryStore.open()
    val storage2 = NodeStorage.make(store2)
    val runtime2 = NodeRuntime.make(storage2, NodeConfig(nodeId = "node-bob"))

    // Run both workflows in parallel threads
    val alice = new Thread(() => {
      val handle = runtime1.submit(pingPongWorkflow, PingPongInput("Alice", "Bob", startFirst = true), "alice-wf")
      handle.result match
        case Some(WorkflowResult.Completed(out)) =>
          println(s"\n  [Alice] Complete: sent=${out.messagesSent}, received=${out.messagesReceived}")
        case other =>
          println(s"\n  [Alice] Result: $other")
    })

    val bob = new Thread(() => {
      val handle = runtime2.submit(pingPongWorkflow, PingPongInput("Bob", "Alice", startFirst = false), "bob-wf")
      handle.result match
        case Some(WorkflowResult.Completed(out)) =>
          println(s"\n  [Bob] Complete: sent=${out.messagesSent}, received=${out.messagesReceived}")
        case other =>
          println(s"\n  [Bob] Result: $other")
    })

    alice.start()
    bob.start()
    alice.join()
    bob.join()

    println("\n=== Ping-Pong Demo Complete ===")

    runtime1.shutdown()
    runtime2.shutdown()
