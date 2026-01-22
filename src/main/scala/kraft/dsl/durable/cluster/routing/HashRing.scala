package kraft.dsl.durable.cluster.routing

import kraft.dsl.durable.cluster.model.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.TreeMap

/**
 * Consistent hash ring with virtual nodes.
 *
 * Uses MD5 hashing for uniform distribution. Each physical node
 * is represented by multiple virtual nodes on the ring for better
 * load distribution.
 */
class HashRing(virtualNodesPerNode: Int = 150):

  // Immutable ring structure, updated atomically
  private val ring = new AtomicReference[TreeMap[Long, NodeId]](TreeMap.empty)
  private val nodePositions = new AtomicReference[Map[NodeId, Set[Long]]](Map.empty)

  /**
   * Add a node to the ring.
   */
  def addNode(nodeId: NodeId): Unit =
    val positions = (0 until virtualNodesPerNode).map { i =>
      hash(s"${nodeId.value}#$i")
    }.toSet

    ring.updateAndGet { r =>
      positions.foldLeft(r)((acc, pos) => acc + (pos -> nodeId))
    }
    nodePositions.updateAndGet(_ + (nodeId -> positions))

  /**
   * Remove a node from the ring.
   */
  def removeNode(nodeId: NodeId): Unit =
    nodePositions.get().get(nodeId).foreach { positions =>
      ring.updateAndGet { r =>
        positions.foldLeft(r)((acc, pos) => acc - pos)
      }
    }
    nodePositions.updateAndGet(_ - nodeId)

  /**
   * Get the node responsible for a key.
   */
  def getNode(key: String): Option[NodeId] =
    val r = ring.get()
    if r.isEmpty then None
    else
      val keyHash = hash(key)
      // Find first node at or after key position, wrap around if needed
      r.rangeFrom(keyHash).headOption
        .orElse(r.headOption)
        .map(_._2)

  /**
   * Get N nodes for a key (for replication).
   * Returns unique nodes in ring order starting from the key's position.
   */
  def getNodes(key: String, count: Int): Seq[NodeId] =
    val r = ring.get()
    if r.isEmpty then Seq.empty
    else
      val keyHash = hash(key)
      val iter = r.iteratorFrom(keyHash) ++ r.iterator
      iter.map(_._2).distinct.take(count).toSeq

  /**
   * Check if the ring contains a node.
   */
  def contains(nodeId: NodeId): Boolean =
    nodePositions.get().contains(nodeId)

  /**
   * Get all positions for a node.
   */
  def getPositions(nodeId: NodeId): Set[Long] =
    nodePositions.get().getOrElse(nodeId, Set.empty)

  /**
   * Get all nodes in the ring.
   */
  def allNodes: Set[NodeId] = nodePositions.get().keySet

  /**
   * Get the number of nodes in the ring.
   */
  def size: Int = nodePositions.get().size

  /**
   * Check if the ring is empty.
   */
  def isEmpty: Boolean = ring.get().isEmpty

  /**
   * Get the key ranges that would be affected when a node joins/leaves.
   * Returns (predecessor_position, node_position) pairs.
   */
  def getAffectedRanges(nodeId: NodeId): Seq[(Long, Long)] =
    val r = ring.get()
    val positions = nodePositions.get().getOrElse(nodeId, Set.empty)

    positions.toSeq.sorted.map { pos =>
      // Find predecessor position
      val pred = r.until(pos).lastOption.map(_._1).getOrElse(r.lastOption.map(_._1).getOrElse(pos))
      (pred, pos)
    }

  /**
   * Get statistics about ring distribution.
   */
  def stats: HashRingStats =
    val positions = nodePositions.get()
    val nodeCount = positions.size
    val totalVnodes = ring.get().size

    if nodeCount == 0 then
      HashRingStats(0, 0, 0.0, 0.0)
    else
      val vnodesPerNode = positions.values.map(_.size).toSeq
      val avgVnodes = vnodesPerNode.sum.toDouble / nodeCount
      val variance = vnodesPerNode.map(v => math.pow(v - avgVnodes, 2)).sum / nodeCount

      HashRingStats(nodeCount, totalVnodes, avgVnodes, math.sqrt(variance))

  /**
   * MD5-based hash function.
   */
  private def hash(key: String): Long =
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(key.getBytes(StandardCharsets.UTF_8))
    // Use first 8 bytes for long
    var h: Long = 0
    for i <- 0 until 8 do
      h = (h << 8) | (bytes(i) & 0xFF)
    h

/**
 * Statistics about the hash ring distribution.
 */
case class HashRingStats(
  nodeCount: Int,
  totalVirtualNodes: Int,
  avgVirtualNodesPerNode: Double,
  stdDevVirtualNodes: Double
)
