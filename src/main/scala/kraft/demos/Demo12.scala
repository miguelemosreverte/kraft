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

/**
 * Demo 12: Distributed Workflow Execution
 *
 * Demonstrates workflows executing across multiple cluster nodes:
 * - Consistent hashing routes workflows to specific nodes
 * - Each node executes its own workflows locally
 * - The cluster acts as a unified distributed runtime
 *
 * This shows the power of having multiple computers working together.
 */
object Demo12:
  // Workflow types
  case class ComputeTask(id: String, operation: String, value: Int)
  case class ComputeResult(id: String, result: Int, executedOnNode: String)

  given JsonValueCodec[ComputeTask] = JsonCodecMaker.make
  given JsonValueCodec[ComputeResult] = JsonCodecMaker.make
  given JsonValueCodec[String] = JsonCodecMaker.make
  given JsonValueCodec[Int] = JsonCodecMaker.make

  def main(args: Array[String]): Unit =
    println("=" * 60)
    println("Demo 12: Distributed Workflow Execution")
    println("=" * 60)
    println()
    println("This demo shows workflows being routed to and executed on")
    println("specific nodes based on consistent hashing.")
    println()

    // Create shared transport registry
    val registry = InMemoryTransport.newRegistry()

    // Create separate storage for each node (in-memory for demo)
    val storage1 = NodeStorage.make(InMemoryStore.open())
    val storage2 = NodeStorage.make(InMemoryStore.open())
    val storage3 = NodeStorage.make(InMemoryStore.open())

    println("--- Setting Up 3-Node Cluster ---")
    println()

    // Create workflow definition
    def createComputeWorkflow(nodeId: String) = Workflow[ComputeTask, ComputeResult]("Compute") { (ctx, task) =>
      // Log which node is executing (side effect returns a string)
      ctx.sideEffect[String](s"log-execution-${task.id}") {
        println(s"    [${nodeId}] Executing task: ${task.id}")
        "logged"
      }

      // Perform the computation
      val computed = task.operation match
        case "square" => task.value * task.value
        case "double" => task.value * 2
        case "triple" => task.value * 3
        case _ => task.value

      ComputeResult(task.id, computed, nodeId)
    }

    // Start node 1 (seed)
    println("Starting node-1 (seed)...")
    val runtime1 = ClusterRuntime.forTestingWithRegistry("node-1", 7800, Nil, storage1, registry)
    runtime1.registerFunction[ComputeTask, ComputeResult]("ComputeTask") { task =>
      val computed = task.operation match
        case "square" => task.value * task.value
        case "double" => task.value * 2
        case "triple" => task.value * 3
        case _ => task.value
      ComputeResult(task.id, computed, "node-1")
    }
    Await.result(runtime1.start(), 5.seconds)

    // Start node 2
    println("Starting node-2...")
    val runtime2 = ClusterRuntime.forTestingWithRegistry("node-2", 7801, List(NodeAddress("127.0.0.1", 7800)), storage2, registry)
    runtime2.registerFunction[ComputeTask, ComputeResult]("ComputeTask") { task =>
      val computed = task.operation match
        case "square" => task.value * task.value
        case "double" => task.value * 2
        case "triple" => task.value * 3
        case _ => task.value
      ComputeResult(task.id, computed, "node-2")
    }
    Await.result(runtime2.start(), 5.seconds)

    // Start node 3
    println("Starting node-3...")
    val runtime3 = ClusterRuntime.forTestingWithRegistry("node-3", 7802, List(NodeAddress("127.0.0.1", 7800)), storage3, registry)
    runtime3.registerFunction[ComputeTask, ComputeResult]("ComputeTask") { task =>
      val computed = task.operation match
        case "square" => task.value * task.value
        case "double" => task.value * 2
        case "triple" => task.value * 3
        case _ => task.value
      ComputeResult(task.id, computed, "node-3")
    }
    Await.result(runtime3.start(), 5.seconds)

    Thread.sleep(300) // Allow gossip to propagate
    println()
    println(s"Cluster ready: ${runtime1.aliveMembers.size} nodes")
    println()

    println("--- Submitting Workflows to Cluster ---")
    println()
    println("Each workflow ID is hashed to determine its owner node.")
    println("Workflows are executed locally on their assigned node.")
    println()

    // Define a workflow that computes on the local node
    val computeWorkflow = Workflow[ComputeTask, ComputeResult]("Compute") { (ctx, task) =>
      val computed = task.operation match
        case "square" => task.value * task.value
        case "double" => task.value * 2
        case "triple" => task.value * 3
        case _ => task.value
      // In a real distributed system, ctx.nodeId would identify the executing node
      ComputeResult(task.id, computed, ctx.workflowId.take(10))
    }

    // Submit workflows with specific IDs that will route to different nodes
    val tasks = Seq(
      ("task-alpha", ComputeTask("alpha", "square", 5)),
      ("task-beta", ComputeTask("beta", "double", 7)),
      ("task-gamma", ComputeTask("gamma", "triple", 3)),
      ("task-delta", ComputeTask("delta", "square", 4)),
      ("task-epsilon", ComputeTask("epsilon", "double", 10)),
      ("task-zeta", ComputeTask("zeta", "triple", 6))
    )

    println("Workflow routing based on consistent hashing:")
    println()
    val nodeWorkflowCounts = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)

    for (workflowId, task) <- tasks do
      // Check which node owns this workflow
      val owner = runtime1.getOwnerNode(workflowId).get
      nodeWorkflowCounts(owner.value) += 1

      // Submit to the appropriate node based on ownership
      val runtimeToUse = owner.value match
        case "node-1" => runtime1
        case "node-2" => runtime2
        case "node-3" => runtime3

      // Submit locally on the owning node
      val handle = Await.result(
        runtimeToUse.submit(computeWorkflow, task, workflowId),
        5.seconds
      )

      println(s"  $workflowId → $owner (${task.operation}(${task.value}))")

    println()
    println("--- Workflow Distribution ---")
    println()
    println("Tasks distributed across nodes:")
    nodeWorkflowCounts.toSeq.sortBy(_._1).foreach { case (node, count) =>
      val bar = "█" * count + "░" * (6 - count)
      println(s"  $node: $bar ($count tasks)")
    }
    println()
    println("Note: Consistent hashing ensures workflows route to the same")
    println("      node every time, enabling local state and durability.")
    println()

    println("--- Cleanup ---")
    println()
    Await.result(runtime3.stop(), 5.seconds)
    Await.result(runtime2.stop(), 5.seconds)
    Await.result(runtime1.stop(), 5.seconds)
    println("All nodes stopped.")
    println()
    println("=" * 60)
    println("Demo 12 Complete")
    println("=" * 60)
