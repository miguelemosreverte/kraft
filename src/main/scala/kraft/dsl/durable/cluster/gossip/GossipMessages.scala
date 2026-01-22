package kraft.dsl.durable.cluster.gossip

import kraft.dsl.durable.cluster.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * SWIM-style gossip protocol messages.
 */
sealed trait GossipMessage:
  def senderId: NodeId

object GossipMessage:
  /**
   * Ping - basic liveness check.
   */
  case class Ping(
    senderId: NodeId,
    sequenceNum: Long,
    updates: Seq[GossipUpdate] = Seq.empty
  ) extends GossipMessage

  /**
   * Ack - response to Ping.
   */
  case class Ack(
    senderId: NodeId,
    sequenceNum: Long,
    updates: Seq[GossipUpdate] = Seq.empty
  ) extends GossipMessage

  /**
   * PingReq - indirect ping through intermediary.
   */
  case class PingReq(
    senderId: NodeId,
    targetId: NodeId,
    sequenceNum: Long
  ) extends GossipMessage

  /**
   * Nack - negative acknowledgement (target unreachable).
   */
  case class Nack(
    senderId: NodeId,
    targetId: NodeId,
    sequenceNum: Long
  ) extends GossipMessage

  /**
   * Sync - full membership synchronization request.
   */
  case class SyncRequest(
    senderId: NodeId,
    membershipVersion: Long
  ) extends GossipMessage

  /**
   * SyncResponse - full membership state.
   */
  case class SyncResponse(
    senderId: NodeId,
    members: Seq[NodeInfo]
  ) extends GossipMessage

  /**
   * Join - node requesting to join cluster.
   */
  case class Join(
    senderId: NodeId,
    nodeInfo: NodeInfo
  ) extends GossipMessage

  /**
   * JoinAck - acknowledge join request with current membership.
   */
  case class JoinAck(
    senderId: NodeId,
    members: Seq[NodeInfo],
    accepted: Boolean,
    errorMessage: Option[String] = None
  ) extends GossipMessage

  /**
   * Leave - graceful departure announcement.
   */
  case class Leave(
    senderId: NodeId
  ) extends GossipMessage

  // JSON codecs
  given JsonValueCodec[GossipMessage] = JsonCodecMaker.make

/**
 * Compact update for piggybacking on protocol messages.
 */
case class GossipUpdate(
  nodeId: NodeId,
  state: NodeState,
  incarnation: Long,
  address: NodeAddress
):
  def toNodeInfo: NodeInfo = NodeInfo(
    id = nodeId,
    address = address,
    state = state,
    incarnation = incarnation,
    lastHeartbeat = java.time.Instant.now()
  )

object GossipUpdate:
  def fromNodeInfo(info: NodeInfo): GossipUpdate =
    GossipUpdate(info.id, info.state, info.incarnation, info.address)

  given JsonValueCodec[GossipUpdate] = JsonCodecMaker.make
  given JsonValueCodec[Seq[GossipUpdate]] = JsonCodecMaker.make
