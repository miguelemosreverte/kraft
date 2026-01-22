package kraft.demos

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.runtime.*
import kraft.dsl.durable.runtime.NodeRuntime.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.cluster.*
import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.transport.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.time.Instant

/**
 * Demo 11: Multi-Node Cluster Formation
 *
 * Demonstrates the core cluster capabilities:
 * - Starting a 3-node cluster with seed node discovery
 * - SWIM gossip protocol for membership
 * - Automatic node discovery via gossip propagation
 * - Cluster state visibility across nodes
 *
 * This is the foundation for distributed durable workflows.
 */
object Demo11:
  // Simple workflow for demonstration
  case class TaskInput(name: String, value: Int)
  case class TaskResult(name: String, computed: Int, processedBy: String)

  given JsonValueCodec[TaskInput] = JsonCodecMaker.make
  given JsonValueCodec[TaskResult] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make

  def main(args: Array[String]): Unit =
    println("=" * 60)
    println("Demo 11: Multi-Node Cluster Formation")
    println("=" * 60)
    println()
    println("This demo shows 3 nodes forming a cluster using SWIM gossip.")
    println("Nodes discover each other automatically via seed nodes.")
    println()

    // Create a shared in-memory transport registry
    // In production, HttpTransport would be used for real network communication
    val registry = InMemoryTransport.newRegistry()

    // Create storage for each node (in-memory for demo)
    val storage1 = NodeStorage.make(InMemoryStore.open())
    val storage2 = NodeStorage.make(InMemoryStore.open())
    val storage3 = NodeStorage.make(InMemoryStore.open())

    println("--- Phase 1: Starting Seed Node ---")
    println()

    // Node 1 is the seed node - it has no seeds to contact
    println("Starting node-1 (seed node) on port 7800...")
    val runtime1 = ClusterRuntime.forTestingWithRegistry(
      nodeId = "node-1",
      port = 7800,
      seeds = Nil,  // Seed node has no seeds
      storage = storage1,
      registry = registry
    )
    Await.result(runtime1.start(), 5.seconds)
    println("  ✓ node-1 started as seed")
    println(s"  ✓ node-1 sees ${runtime1.aliveMembers.size} alive member(s)")
    println()

    println("--- Phase 2: Nodes Join via Seed ---")
    println()

    // Node 2 joins via seed
    println("Starting node-2 on port 7801 (joining via seed at 7800)...")
    val runtime2 = ClusterRuntime.forTestingWithRegistry(
      nodeId = "node-2",
      port = 7801,
      seeds = List(NodeAddress("127.0.0.1", 7800)),
      storage = storage2,
      registry = registry
    )
    Await.result(runtime2.start(), 5.seconds)
    Thread.sleep(200) // Allow gossip propagation
    println("  ✓ node-2 joined cluster")
    println()

    // Node 3 joins via seed
    println("Starting node-3 on port 7802 (joining via seed at 7800)...")
    val runtime3 = ClusterRuntime.forTestingWithRegistry(
      nodeId = "node-3",
      port = 7802,
      seeds = List(NodeAddress("127.0.0.1", 7800)),
      storage = storage3,
      registry = registry
    )
    Await.result(runtime3.start(), 5.seconds)
    Thread.sleep(300) // Allow gossip propagation
    println("  ✓ node-3 joined cluster")
    println()

    println("--- Phase 3: Cluster State ---")
    println()
    println("All nodes discover each other via gossip propagation:")
    println()
    println(s"  node-1 sees: ${runtime1.aliveMembers.map(_.id).mkString(", ")}")
    println(s"  node-2 sees: ${runtime2.aliveMembers.map(_.id).mkString(", ")}")
    println(s"  node-3 sees: ${runtime3.aliveMembers.map(_.id).mkString(", ")}")
    println()

    // Show cluster statistics
    val stats1 = runtime1.stats
    val stats2 = runtime2.stats
    val stats3 = runtime3.stats
    println("Cluster statistics:")
    println(s"  node-1: ${stats1.aliveNodes} alive, ${stats1.ringNodes} in hash ring")
    println(s"  node-2: ${stats2.aliveNodes} alive, ${stats2.ringNodes} in hash ring")
    println(s"  node-3: ${stats3.aliveNodes} alive, ${stats3.ringNodes} in hash ring")
    println()

    println("--- Phase 4: Workflow Routing Preview ---")
    println()
    println("Consistent hashing routes workflows to specific nodes:")
    println()
    for i <- 1 to 6 do
      val workflowId = s"workflow-$i"
      val owner = runtime1.getOwnerNode(workflowId).get
      val isLocal = runtime1.isLocalOwner(workflowId)
      val localMarker = if isLocal then " (local to node-1)" else ""
      println(s"  $workflowId → $owner$localMarker")
    println()

    println("--- Phase 5: Graceful Shutdown ---")
    println()
    println("Stopping all nodes...")
    Await.result(runtime3.stop(), 5.seconds)
    Await.result(runtime2.stop(), 5.seconds)
    Await.result(runtime1.stop(), 5.seconds)
    println("  ✓ All nodes stopped")
    println()
    println("=" * 60)
    println("Demo 11 Complete")
    println("=" * 60)
