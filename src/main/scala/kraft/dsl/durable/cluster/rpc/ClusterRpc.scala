package kraft.dsl.durable.cluster.rpc

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * RPC messages for cluster workflow operations.
 */
sealed trait RpcMessage:
  def requestId: String

object RpcMessage:

  /**
   * Submit a workflow for execution on the target node.
   */
  case class SubmitWorkflow(
    requestId: String,
    workflowId: String,
    workflowType: String,
    inputJson: String,
    originNode: NodeId
  ) extends RpcMessage

  /**
   * Response to workflow submission.
   */
  case class SubmitWorkflowResponse(
    requestId: String,
    success: Boolean,
    errorMessage: Option[String] = None
  ) extends RpcMessage

  /**
   * Query workflow status.
   */
  case class GetWorkflowStatus(
    requestId: String,
    workflowId: String
  ) extends RpcMessage

  /**
   * Response to status query.
   */
  case class WorkflowStatusResponse(
    requestId: String,
    found: Boolean,
    status: Option[WorkflowStatus] = None,
    outputJson: Option[String] = None,
    errorMessage: Option[String] = None
  ) extends RpcMessage

  /**
   * Cancel a running workflow.
   */
  case class CancelWorkflow(
    requestId: String,
    workflowId: String
  ) extends RpcMessage

  /**
   * Response to cancel request.
   */
  case class CancelWorkflowResponse(
    requestId: String,
    success: Boolean
  ) extends RpcMessage

  /**
   * Request to migrate a workflow to this node (for rebalancing).
   */
  case class MigrateWorkflow(
    requestId: String,
    workflowId: String,
    metadata: WorkflowMetadata,
    journalEntries: Seq[JournalEntry]
  ) extends RpcMessage

  /**
   * Response to migration request.
   */
  case class MigrateWorkflowResponse(
    requestId: String,
    success: Boolean,
    errorMessage: Option[String] = None
  ) extends RpcMessage

  // JSON codecs
  given workflowStatusCodec: JsonValueCodec[WorkflowStatus] = JsonCodecMaker.make
  given journalEntryTypeCodec: JsonValueCodec[JournalEntryType] = JsonCodecMaker.make
  given journalEntryCodec: JsonValueCodec[JournalEntry] = JsonCodecMaker.make
  given journalSeqCodec: JsonValueCodec[Seq[JournalEntry]] = JsonCodecMaker.make
  given workflowMetadataCodec: JsonValueCodec[WorkflowMetadata] = JsonCodecMaker.make
  given JsonValueCodec[RpcMessage] = JsonCodecMaker.make

/**
 * Wrapper for RPC request/response with routing info.
 */
case class RpcEnvelope(
  senderNode: NodeId,
  targetNode: NodeId,
  message: RpcMessage
)

object RpcEnvelope:
  given JsonValueCodec[RpcEnvelope] = JsonCodecMaker.make
