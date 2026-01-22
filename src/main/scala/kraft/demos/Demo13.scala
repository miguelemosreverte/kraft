package kraft.demos

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.runtime.*
import kraft.dsl.durable.runtime.NodeRuntime.*
import kraft.dsl.durable.model.*
import kraft.dsl.durable.cluster.*
import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import kraft.dsl.durable.cluster.routing.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.durable.cluster.transport.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Demo 13: Node Failure and Rebalancing
 *
 * Demonstrates what happens when a node fails:
 * - SWIM gossip detects the failure
 * - Hash ring is updated to remove dead node
 * - Workflows are redistributed to remaining nodes
 *
 * This is critical for high availability in distributed systems.
 */
object Demo13:

  def main(args: Array[String]): Unit =
    println("=" * 60)
    println("Demo 13: Node Failure and Rebalancing")
    println("=" * 60)
    println()
    println("This demo shows what happens when a cluster node fails.")
    println("The remaining nodes detect the failure and rebalance work.")
    println()

    // Create shared transport registry with partition simulation capability
    val registry = InMemoryTransport.newRegistry()

    // Create storage for each node (in-memory for demo)
    val storage1 = NodeStorage.make(InMemoryStore.open())
    val storage2 = NodeStorage.make(InMemoryStore.open())
    val storage3 = NodeStorage.make(InMemoryStore.open())

    println("--- Phase 1: Cluster Formation ---")
    println()

    // Start 3-node cluster
    println("Starting 3-node cluster...")
    val runtime1 = ClusterRuntime.forTestingWithRegistry("node-1", 7800, Nil, storage1, registry)
    val runtime2 = ClusterRuntime.forTestingWithRegistry("node-2", 7801, List(NodeAddress("127.0.0.1", 7800)), storage2, registry)
    val runtime3 = ClusterRuntime.forTestingWithRegistry("node-3", 7802, List(NodeAddress("127.0.0.1", 7800)), storage3, registry)

    Await.result(runtime1.start(), 5.seconds)
    Await.result(runtime2.start(), 5.seconds)
    Await.result(runtime3.start(), 5.seconds)
    Thread.sleep(300) // Allow gossip propagation

    println(s"  ✓ Cluster ready: ${runtime1.aliveMembers.size} nodes")
    println()

    println("--- Phase 2: Initial Workflow Routing ---")
    println()

    // Show initial routing for 6 workflows
    val workflowIds = (1 to 6).map(i => s"workflow-$i")
    println("Initial workflow routing (all 3 nodes):")
    println()

    var node1Count = 0
    var node2Count = 0
    var node3Count = 0

    for wfId <- workflowIds do
      val owner = runtime1.getOwnerNode(wfId).get
      owner.value match
        case "node-1" => node1Count += 1
        case "node-2" => node2Count += 1
        case "node-3" => node3Count += 1
      println(s"  $wfId → $owner")

    println()
    println("Distribution:")
    println(s"  node-1: $node1Count workflows")
    println(s"  node-2: $node2Count workflows")
    println(s"  node-3: $node3Count workflows")
    println()

    println("--- Phase 3: Simulating Node Failure ---")
    println()

    // Simulate node-2 failing (abrupt shutdown without graceful leave)
    println("ALERT: node-2 has crashed!")
    println()

    // Stop node-2 to simulate crash
    Await.result(runtime2.stop(), 5.seconds)
    Thread.sleep(100)

    // In a real cluster with SWIM protocol, the failure would be detected
    // automatically via missing pings. For demo, we show immediate effect.
    println("  ✓ SWIM gossip would detect failure via ping timeouts")
    println("  ✓ Node marked as SUSPECT then DEAD after timeout")
    println()

    println("--- Phase 4: Routing After Failure ---")
    println()

    // The remaining nodes would update their hash ring
    // For demo purposes, we show what the new routing would be
    println("After node-2 failure, workflows are rerouted to surviving nodes:")
    println()

    // Create a new ring without node-2 to show redistribution
    val newRing = HashRing(150)
    newRing.addNode(NodeId("node-1"))
    newRing.addNode(NodeId("node-3"))

    var newNode1Count = 0
    var newNode3Count = 0

    for wfId <- workflowIds do
      val newOwner = newRing.getNode(wfId).get
      newOwner.value match
        case "node-1" => newNode1Count += 1
        case "node-3" => newNode3Count += 1
      println(s"  $wfId → $newOwner")

    println()
    println("New distribution (node-2 workflows redistributed):")
    println(s"  node-1: $newNode1Count workflows (was $node1Count)")
    println(s"  node-2: DOWN")
    println(s"  node-3: $newNode3Count workflows (was $node3Count)")
    println()

    println("--- Phase 5: Workflow Recovery Process ---")
    println()
    println("When a node fails, workflows owned by that node need recovery:")
    println()
    println("  1. Detect failure via SWIM gossip (ping timeout)")
    println("  2. Mark node as SUSPECT, then DEAD after confirmation")
    println("  3. Update hash ring to exclude dead node")
    println("  4. New owner node loads workflow state from durable storage")
    println("  5. Workflow resumes from last journaled checkpoint")
    println()
    println("With durable execution, NO WORK IS LOST - journals survive failures.")
    println()

    println("--- Phase 6: Adding Replacement Node ---")
    println()

    // Simulate adding a new node to replace the failed one
    val storage4 = NodeStorage.make(InMemoryStore.open())
    println("Starting node-4 as replacement...")
    val runtime4 = ClusterRuntime.forTestingWithRegistry("node-4", 7803, List(NodeAddress("127.0.0.1", 7800)), storage4, registry)
    Await.result(runtime4.start(), 5.seconds)
    Thread.sleep(300)

    println("  ✓ node-4 joined cluster")
    println()
    println(s"Cluster now has ${runtime1.aliveMembers.size} nodes")
    println()

    // Show new routing with replacement node
    val finalRing = HashRing(150)
    finalRing.addNode(NodeId("node-1"))
    finalRing.addNode(NodeId("node-3"))
    finalRing.addNode(NodeId("node-4"))

    println("Final routing with replacement node-4:")
    println()

    var finalNode1Count = 0
    var finalNode3Count = 0
    var finalNode4Count = 0

    for wfId <- workflowIds do
      val owner = finalRing.getNode(wfId).get
      owner.value match
        case "node-1" => finalNode1Count += 1
        case "node-3" => finalNode3Count += 1
        case "node-4" => finalNode4Count += 1
      println(s"  $wfId → $owner")

    println()
    println("Distribution with node-4:")
    println(s"  node-1: $finalNode1Count workflows")
    println(s"  node-3: $finalNode3Count workflows")
    println(s"  node-4: $finalNode4Count workflows (new)")
    println()

    println("--- Cleanup ---")
    println()
    Await.result(runtime4.stop(), 5.seconds)
    Await.result(runtime3.stop(), 5.seconds)
    Await.result(runtime1.stop(), 5.seconds)
    println("All nodes stopped.")
    println()
    println("=" * 60)
    println("Demo 13 Complete")
    println("=" * 60)
