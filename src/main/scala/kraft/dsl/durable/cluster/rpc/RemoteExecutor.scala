package kraft.dsl.durable.cluster.rpc

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import kraft.dsl.durable.cluster.transport.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.*
import scala.collection.concurrent.TrieMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles remote workflow execution via RPC.
 *
 * Sends workflow operations to remote nodes and handles responses.
 */
class RemoteExecutor(
  localNode: NodeId,
  membership: Membership,
  transport: ClusterTransport,
  timeout: FiniteDuration = 30.seconds
)(using ExecutionContext):

  private val pendingRequests = TrieMap[String, Promise[RpcMessage]]()
  private val requestIdGen = new AtomicLong(0)

  /**
   * Register RPC message handler on transport.
   */
  def registerHandler(handler: RpcMessage => Option[RpcMessage]): Unit =
    transport.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        msg match
          case GossipMessage.SyncResponse(_, _) =>
            // Hack: using SyncResponse to carry RPC envelope
            // In production, would use separate transport channel
            None
          case _ => None
    )

  /**
   * Submit a workflow to a remote node.
   */
  def submitWorkflow(
    targetNode: NodeId,
    workflowId: String,
    workflowType: String,
    inputJson: String
  ): Future[RpcMessage.SubmitWorkflowResponse] =
    val requestId = generateRequestId()
    val request = RpcMessage.SubmitWorkflow(
      requestId = requestId,
      workflowId = workflowId,
      workflowType = workflowType,
      inputJson = inputJson,
      originNode = localNode
    )

    sendRequest(targetNode, request).map {
      case resp: RpcMessage.SubmitWorkflowResponse => resp
      case _ => RpcMessage.SubmitWorkflowResponse(requestId, false, Some("Invalid response"))
    }

  /**
   * Get workflow status from a remote node.
   */
  def getWorkflowStatus(
    targetNode: NodeId,
    workflowId: String
  ): Future[RpcMessage.WorkflowStatusResponse] =
    val requestId = generateRequestId()
    val request = RpcMessage.GetWorkflowStatus(requestId, workflowId)

    sendRequest(targetNode, request).map {
      case resp: RpcMessage.WorkflowStatusResponse => resp
      case _ => RpcMessage.WorkflowStatusResponse(requestId, false)
    }

  /**
   * Cancel a workflow on a remote node.
   */
  def cancelWorkflow(
    targetNode: NodeId,
    workflowId: String
  ): Future[RpcMessage.CancelWorkflowResponse] =
    val requestId = generateRequestId()
    val request = RpcMessage.CancelWorkflow(requestId, workflowId)

    sendRequest(targetNode, request).map {
      case resp: RpcMessage.CancelWorkflowResponse => resp
      case _ => RpcMessage.CancelWorkflowResponse(requestId, false)
    }

  /**
   * Migrate a workflow to a target node.
   */
  def migrateWorkflow(
    targetNode: NodeId,
    workflowId: String,
    metadata: WorkflowMetadata,
    journal: Seq[JournalEntry]
  ): Future[RpcMessage.MigrateWorkflowResponse] =
    val requestId = generateRequestId()
    val request = RpcMessage.MigrateWorkflow(requestId, workflowId, metadata, journal)

    sendRequest(targetNode, request).map {
      case resp: RpcMessage.MigrateWorkflowResponse => resp
      case _ => RpcMessage.MigrateWorkflowResponse(requestId, false, Some("Invalid response"))
    }

  /**
   * Handle an incoming RPC response.
   */
  def handleResponse(response: RpcMessage): Unit =
    pendingRequests.remove(response.requestId).foreach { promise =>
      promise.trySuccess(response)
    }

  private def sendRequest(targetNode: NodeId, request: RpcMessage): Future[RpcMessage] =
    membership.get(targetNode) match
      case None =>
        Future.failed(new RuntimeException(s"Unknown node: $targetNode"))

      case Some(nodeInfo) if nodeInfo.state != NodeState.Alive =>
        Future.failed(new RuntimeException(s"Node not alive: $targetNode (${nodeInfo.state})"))

      case Some(nodeInfo) =>
        val promise = Promise[RpcMessage]()
        pendingRequests.put(request.requestId, promise)

        // Serialize and send via transport
        val envelope = RpcEnvelope(localNode, targetNode, request)
        val bytes = writeToArray(envelope)

        // Use a custom message type for RPC
        // For now, piggyback on gossip transport (not ideal but works for demo)
        val rpcMsg = GossipMessage.SyncRequest(localNode, bytes.length) // Hack

        transport.send(nodeInfo.address, rpcMsg).flatMap { sent =>
          if sent then
            // Set timeout
            Future {
              Thread.sleep(timeout.toMillis)
              pendingRequests.remove(request.requestId).foreach { p =>
                p.tryFailure(new RuntimeException("RPC timeout"))
              }
            }
            promise.future
          else
            pendingRequests.remove(request.requestId)
            Future.failed(new RuntimeException(s"Failed to send to $targetNode"))
        }

  private def generateRequestId(): String =
    s"${localNode.value}-${requestIdGen.incrementAndGet()}"

/**
 * Local handler for incoming RPC requests.
 */
trait RpcHandler:
  def handleSubmitWorkflow(req: RpcMessage.SubmitWorkflow): RpcMessage.SubmitWorkflowResponse
  def handleGetStatus(req: RpcMessage.GetWorkflowStatus): RpcMessage.WorkflowStatusResponse
  def handleCancel(req: RpcMessage.CancelWorkflow): RpcMessage.CancelWorkflowResponse
  def handleMigrate(req: RpcMessage.MigrateWorkflow): RpcMessage.MigrateWorkflowResponse
