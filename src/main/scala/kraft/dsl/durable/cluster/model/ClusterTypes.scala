package kraft.dsl.durable.cluster.model

import java.time.Instant
import scala.concurrent.duration.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * Unique identifier for a cluster node.
 */
case class NodeId(value: String):
  override def toString: String = value

object NodeId:
  def generate(): NodeId = NodeId(s"node-${java.util.UUID.randomUUID().toString.take(8)}")
  given JsonValueCodec[NodeId] = JsonCodecMaker.make

/**
 * Network address for a node.
 */
case class NodeAddress(host: String, port: Int):
  def toEndpoint: String = s"$host:$port"

object NodeAddress:
  def parse(endpoint: String): Option[NodeAddress] =
    endpoint.split(':') match
      case Array(host, port) => port.toIntOption.map(NodeAddress(host, _))
      case _ => None

  given JsonValueCodec[NodeAddress] = JsonCodecMaker.make

/**
 * Node lifecycle state in the cluster.
 */
enum NodeState:
  case Alive     // Node is healthy and responsive
  case Suspect   // Missed heartbeats, may be failing
  case Dead      // Confirmed dead, removed from routing
  case Left      // Graceful departure

object NodeState:
  given JsonValueCodec[NodeState] = JsonCodecMaker.make

/**
 * Full node information including state and metadata.
 */
case class NodeInfo(
  id: NodeId,
  address: NodeAddress,
  state: NodeState,
  incarnation: Long,
  lastHeartbeat: Instant,
  metadata: Map[String, String] = Map.empty
):
  def isRoutable: Boolean = state == NodeState.Alive

object NodeInfo:
  given instantCodec: JsonValueCodec[Instant] = new JsonValueCodec[Instant]:
    def decodeValue(in: JsonReader, default: Instant): Instant =
      Instant.ofEpochMilli(in.readLong())
    def encodeValue(x: Instant, out: JsonWriter): Unit =
      out.writeVal(x.toEpochMilli)
    def nullValue: Instant = null

  given JsonValueCodec[NodeInfo] = JsonCodecMaker.make

/**
 * Configuration for a cluster node.
 */
case class ClusterConfig(
  nodeId: NodeId,
  bindAddress: NodeAddress,
  seedNodes: List[NodeAddress],
  gossipInterval: FiniteDuration = 1.second,
  suspectTimeout: FiniteDuration = 5.seconds,
  deadTimeout: FiniteDuration = 30.seconds,
  virtualNodesPerNode: Int = 150,
  rpcTimeout: FiniteDuration = 30.seconds
)

object ClusterConfig:
  def fromEnv(): ClusterConfig =
    val nodeId = sys.env.get("CLUSTER_NODE_ID")
      .map(NodeId(_))
      .getOrElse(NodeId.generate())
    val bindHost = sys.env.getOrElse("CLUSTER_BIND_HOST", "0.0.0.0")
    val bindPort = sys.env.getOrElse("CLUSTER_BIND_PORT", "7800").toInt
    val seeds = sys.env.get("CLUSTER_SEED_NODES")
      .map(_.split(',').toList.flatMap(NodeAddress.parse))
      .getOrElse(Nil)

    ClusterConfig(
      nodeId = nodeId,
      bindAddress = NodeAddress(bindHost, bindPort),
      seedNodes = seeds
    )

  def forTesting(nodeId: String, port: Int, seeds: List[NodeAddress] = Nil): ClusterConfig =
    ClusterConfig(
      nodeId = NodeId(nodeId),
      bindAddress = NodeAddress("127.0.0.1", port),
      seedNodes = seeds,
      gossipInterval = 100.millis,
      suspectTimeout = 500.millis,
      deadTimeout = 2.seconds
    )
