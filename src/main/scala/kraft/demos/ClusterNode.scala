package kraft.demos

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.runtime.*
import kraft.dsl.durable.runtime.NodeRuntime.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.cluster.*
import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.api.WorkflowService
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Multi-node cluster runner.
 *
 * Usage:
 *   sbt "runMain kraft.demos.ClusterNode seed <bind-port>"
 *   sbt "runMain kraft.demos.ClusterNode join <bind-port> <seed-host:port>"
 *
 * Example (run on two machines):
 *   Machine A (192.168.0.48): sbt "runMain kraft.demos.ClusterNode seed 7800"
 *   Machine B (192.168.0.130): sbt "runMain kraft.demos.ClusterNode join 7800 192.168.0.48:7800"
 */
object ClusterNode:

  // Sample workflow for testing
  case class ProcessInput(message: String, count: Int)
  case class ProcessOutput(result: String)
  given JsonValueCodec[ProcessInput] = JsonCodecMaker.make
  given JsonValueCodec[ProcessOutput] = JsonCodecMaker.make

  val processWorkflow = Workflow[ProcessInput, ProcessOutput]("process") { (ctx, input) =>
    println(s"  [${ctx.workflowId}] Processing: ${input.message}")
    Thread.sleep(100) // Simulate work
    ProcessOutput(s"Processed '${input.message}' x${input.count}")
  }

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage:")
      println("  ClusterNode seed <port>           - Start seed node")
      println("  ClusterNode join <port> <seed>    - Join existing cluster")
      println()
      println("Example:")
      println("  sbt \"runMain kraft.demos.ClusterNode seed 7800\"")
      println("  sbt \"runMain kraft.demos.ClusterNode join 7800 192.168.0.48:7800\"")
      sys.exit(1)

    val mode = args(0)
    val port = args(1).toInt

    mode match
      case "seed" => runSeedNode(port)
      case "join" =>
        if args.length < 3 then
          println("Error: join mode requires seed address (host:port)")
          sys.exit(1)
        val seedAddr = args(2)
        val parts = seedAddr.split(":")
        val seedHost = parts(0)
        val seedPort = parts(1).toInt
        runWorkerNode(port, seedHost, seedPort)
      case _ =>
        println(s"Unknown mode: $mode (use 'seed' or 'join')")
        sys.exit(1)

  def runSeedNode(port: Int): Unit =
    println("=" * 60)
    println(s"Kraft Cluster - Seed Node (port $port)")
    println("=" * 60)
    println()

    val storage = NodeStorage.make(InMemoryStore.open())
    val nodeId = s"seed-$port"

    val config = ClusterConfig(
      nodeId = NodeId(nodeId),
      bindAddress = NodeAddress("0.0.0.0", port),
      seedNodes = Nil // Seed has no seeds
    )

    val runtime = ClusterRuntime(config, storage)

    println(s"Starting seed node $nodeId on port $port...")
    Await.result(runtime.start(), 10.seconds)
    println(s"  Seed node ready!")
    println()

    // Start HTTP API for external clients
    val apiPort = port + 1000 // e.g., 7800 -> 8800
    val apiServer = WorkflowService.start(runtime, apiPort)
    println(s"  HTTP API listening on port $apiPort")
    println()

    printStatus(runtime)

    // Keep running and periodically show status
    println("Press Ctrl+C to stop")
    println()

    var running = true
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down...")
      running = false
      Await.result(runtime.stop(), 5.seconds)
      apiServer.close()
    }))

    while running do
      Thread.sleep(5000)
      printStatus(runtime)

  def runWorkerNode(port: Int, seedHost: String, seedPort: Int): Unit =
    println("=" * 60)
    println(s"Kraft Cluster - Worker Node (port $port)")
    println("=" * 60)
    println()

    val storage = NodeStorage.make(InMemoryStore.open())
    val nodeId = s"worker-$port"

    val config = ClusterConfig(
      nodeId = NodeId(nodeId),
      bindAddress = NodeAddress("0.0.0.0", port),
      seedNodes = List(NodeAddress(seedHost, seedPort))
    )

    val runtime = ClusterRuntime(config, storage)

    println(s"Starting worker node $nodeId...")
    println(s"  Connecting to seed: $seedHost:$seedPort")
    Await.result(runtime.start(), 10.seconds)
    println(s"  Joined cluster!")
    println()

    // Start HTTP API
    val apiPort = port + 1000
    val apiServer = WorkflowService.start(runtime, apiPort)
    println(s"  HTTP API listening on port $apiPort")
    println()

    printStatus(runtime)

    println("Press Ctrl+C to stop")
    println()

    var running = true
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down...")
      running = false
      Await.result(runtime.stop(), 5.seconds)
      apiServer.close()
    }))

    while running do
      Thread.sleep(5000)
      printStatus(runtime)

  def printStatus(runtime: ClusterRuntime): Unit =
    val members = runtime.aliveMembers
    val stats = runtime.stats
    println(s"[${java.time.LocalTime.now}] Cluster: ${members.size} alive nodes")
    members.foreach { m =>
      println(s"  - ${m.id.value} @ ${m.address}")
    }
    println()
