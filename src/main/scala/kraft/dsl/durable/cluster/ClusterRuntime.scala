package kraft.dsl.durable.cluster

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import kraft.dsl.durable.cluster.routing.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.durable.cluster.transport.*
import kraft.dsl.durable.cluster.rpc.*
import kraft.dsl.durable.runtime.NodeRuntime
import kraft.dsl.durable.storage.NodeStorage
import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*
import java.time.Instant
import java.util.UUID

/**
 * Cluster-aware workflow runtime.
 *
 * Coordinates workflow execution across a cluster of nodes using:
 * - SWIM gossip for membership
 * - Consistent hashing for workflow routing
 * - Remote execution for cross-node workflows
 *
 * Example:
 * {{{
 * val config = ClusterConfig(
 *   nodeId = NodeId("node-1"),
 *   bindAddress = NodeAddress("0.0.0.0", 7800),
 *   seedNodes = List(NodeAddress("seed1", 7800))
 * )
 *
 * val runtime = ClusterRuntime.make(config, storage)
 * runtime.start().foreach { _ =>
 *   // Submit workflow - automatically routes to correct node
 *   val handle = runtime.submit(myWorkflow, input)
 * }
 * }}}
 */
class ClusterRuntime private (
  config: ClusterConfig,
  storage: NodeStorage.Storage,
  transportFactory: NodeAddress => ClusterTransport
)(using ExecutionContext):

  // Core components
  private val localNodeInfo = NodeInfo(
    id = config.nodeId,
    address = config.bindAddress,
    state = NodeState.Alive,
    incarnation = 0,
    lastHeartbeat = Instant.now()
  )

  private val membership = Membership(localNodeInfo)
  private val hashRing = HashRing(config.virtualNodesPerNode)
  private val transport: ClusterTransport = transportFactory(config.bindAddress)
  private val gossip = GossipProtocol(config, membership, hashRing, transport)
  private val remoteExecutor = RemoteExecutor(config.nodeId, membership, transport, config.rpcTimeout)

  // Local runtime for executing workflows on this node
  private val localRuntime = NodeRuntime.make(
    storage = storage,
    config = NodeRuntime.NodeConfig(
      nodeId = config.nodeId.value,
      maxConcurrentWorkflows = 100
    )
  )

  // Track registered workflows for remote execution
  private val workflowRegistry = scala.collection.concurrent.TrieMap[String, WorkflowDef[?, ?]]()

  /**
   * Start the cluster runtime.
   */
  def start(): Future[Unit] =
    for
      _ <- gossip.start()
      joined <- gossip.join()
      _ = if !joined then throw new RuntimeException("Failed to join cluster")
    yield ()

  /**
   * Stop the cluster runtime gracefully.
   */
  def stop(): Future[Unit] =
    gossip.stop().map { _ =>
      localRuntime.shutdown()
    }

  /**
   * Register a function that can be called by workflows.
   */
  def registerFunction[Req: JsonValueCodec, Res: JsonValueCodec](name: String)(handler: Req => Res): ClusterRuntime =
    localRuntime.register(name)(handler)
    this

  /**
   * Register a workflow type for remote execution.
   */
  def registerWorkflow[In: JsonValueCodec, Out: JsonValueCodec](
    workflow: NodeRuntime.Workflow[In, Out]
  ): ClusterRuntime =
    workflowRegistry.put(workflow.name, WorkflowDef(workflow))
    this

  /**
   * Submit a workflow for execution.
   * Routes to the appropriate node based on consistent hashing.
   */
  def submit[In: JsonValueCodec, Out: JsonValueCodec](
    workflow: NodeRuntime.Workflow[In, Out],
    input: In,
    workflowId: String = UUID.randomUUID().toString
  ): Future[ClusterWorkflowHandle[Out]] =
    // Determine owner node via hash ring
    val ownerNode = hashRing.getNode(workflowId).getOrElse(config.nodeId)

    if ownerNode == config.nodeId then
      // Execute locally
      Future.successful {
        val handle = localRuntime.submit(workflow, input, workflowId)
        ClusterWorkflowHandle(
          workflowId = workflowId,
          ownerNode = ownerNode,
          localHandle = Some(handle),
          runtime = this
        )
      }
    else
      // Forward to remote node
      val inputJson = writeToString(input)
      remoteExecutor.submitWorkflow(ownerNode, workflowId, workflow.name, inputJson).map { response =>
        if response.success then
          ClusterWorkflowHandle[Out](
            workflowId = workflowId,
            ownerNode = ownerNode,
            localHandle = None,
            runtime = this
          )
        else
          throw new RuntimeException(s"Remote submit failed: ${response.errorMessage.getOrElse("unknown")}")
      }

  /**
   * Get workflow status (local or remote).
   */
  def getStatus(workflowId: String): Future[Option[WorkflowStatus]] =
    val ownerNode = hashRing.getNode(workflowId).getOrElse(config.nodeId)

    if ownerNode == config.nodeId then
      Future.successful(localRuntime.getStatus(workflowId).map(_.status))
    else
      remoteExecutor.getWorkflowStatus(ownerNode, workflowId).map { response =>
        if response.found then response.status else None
      }

  /**
   * Cancel a workflow (local or remote).
   */
  def cancel(workflowId: String): Future[Boolean] =
    val ownerNode = hashRing.getNode(workflowId).getOrElse(config.nodeId)

    if ownerNode == config.nodeId then
      Future.successful(localRuntime.cancel(workflowId))
    else
      remoteExecutor.cancelWorkflow(ownerNode, workflowId).map(_.success)

  /**
   * Get cluster membership info.
   */
  def clusterMembers: Seq[NodeInfo] = membership.all

  /**
   * Get alive cluster members.
   */
  def aliveMembers: Seq[NodeInfo] = membership.alive

  /**
   * Get this node's ID.
   */
  def nodeId: NodeId = config.nodeId

  /**
   * Get gossip statistics.
   */
  def stats: ClusterStats =
    val gossipStats = gossip.stats
    ClusterStats(
      nodeId = config.nodeId,
      aliveNodes = gossipStats.aliveNodes,
      suspectNodes = gossipStats.suspectNodes,
      ringNodes = gossipStats.ringNodes,
      localWorkflows = 0 // Would need to track
    )

  /**
   * Get the node responsible for a workflow.
   */
  def getOwnerNode(workflowId: String): Option[NodeId] =
    hashRing.getNode(workflowId)

  /**
   * Check if this node owns a workflow.
   */
  def isLocalOwner(workflowId: String): Boolean =
    hashRing.getNode(workflowId).contains(config.nodeId)

  // Internal: Get local runtime for RPC handler
  private[cluster] def getLocalRuntime: NodeRuntime.Runtime = localRuntime

/**
 * Wrapper to store workflow with type info.
 */
private case class WorkflowDef[In, Out](
  workflow: NodeRuntime.Workflow[In, Out]
)(using val inCodec: JsonValueCodec[In], val outCodec: JsonValueCodec[Out])

/**
 * Handle to a workflow that may be running on any cluster node.
 */
class ClusterWorkflowHandle[Out](
  val workflowId: String,
  val ownerNode: NodeId,
  private val localHandle: Option[NodeRuntime.WorkflowHandle[Out]],
  private val runtime: ClusterRuntime
)(using ExecutionContext):

  /**
   * Check if workflow is running on local node.
   */
  def isLocal: Boolean = localHandle.isDefined

  /**
   * Get current status.
   */
  def status: Future[Option[WorkflowStatus]] =
    runtime.getStatus(workflowId)

  /**
   * Cancel the workflow.
   */
  def cancel(): Future[Boolean] =
    runtime.cancel(workflowId)

  /**
   * Get the result if completed locally.
   */
  def localResult: Option[WorkflowResult[Out]] =
    localHandle.flatMap(_.result)

/**
 * Cluster statistics.
 */
case class ClusterStats(
  nodeId: NodeId,
  aliveNodes: Int,
  suspectNodes: Int,
  ringNodes: Int,
  localWorkflows: Int
)

object ClusterRuntime:
  /**
   * Create a production cluster runtime using HTTP transport.
   * This uses the high-performance HTTP DSL foundation (Netty + io_uring).
   */
  def apply(
    config: ClusterConfig,
    storage: NodeStorage.Storage
  )(using ExecutionContext): ClusterRuntime =
    new ClusterRuntime(config, storage, addr => HttpTransport(addr))

  /**
   * Create a cluster runtime with in-memory transport (for testing).
   */
  def forTesting(
    nodeId: String,
    port: Int,
    seeds: List[NodeAddress],
    storage: NodeStorage.Storage
  )(using ExecutionContext): ClusterRuntime =
    val registry = InMemoryTransport.newRegistry()
    forTestingWithRegistry(nodeId, port, seeds, storage, registry)

  /**
   * Create a cluster runtime with shared in-memory registry (for multi-node tests).
   */
  def forTestingWithRegistry(
    nodeId: String,
    port: Int,
    seeds: List[NodeAddress],
    storage: NodeStorage.Storage,
    registry: InMemoryTransport.Registry
  )(using ExecutionContext): ClusterRuntime =
    val config = ClusterConfig.forTesting(nodeId, port, seeds)
    new ClusterRuntime(config, storage, addr => InMemoryTransport(addr, registry))
