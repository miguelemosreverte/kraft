package kraft.dsl.durable.cluster.gossip

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Disseminator for piggybacking state updates on gossip messages.
 *
 * Implements infection-style dissemination where each update is propagated
 * a limited number of times to ensure eventual consistency while limiting
 * network overhead.
 *
 * Uses a bounded buffer with transmission counts to track which updates
 * still need propagation.
 */
class Disseminator(
  maxUpdates: Int = 10,
  maxTransmissions: Int = 3
):
  // Updates pending dissemination with transmission count
  private val pendingUpdates = TrieMap[NodeId, (GossipUpdate, Int)]()
  private val sequenceNum = new AtomicLong(0)

  /**
   * Add an update to be disseminated.
   * Replaces any existing update for the same node if newer.
   */
  def add(update: GossipUpdate): Unit =
    pendingUpdates.get(update.nodeId) match
      case Some((existing, _)) if existing.incarnation >= update.incarnation =>
        // Existing update is same or newer, ignore
        ()
      case _ =>
        // New or newer update
        pendingUpdates.put(update.nodeId, (update, 0))

  /**
   * Add updates from a NodeInfo.
   */
  def addFromNodeInfo(info: NodeInfo): Unit =
    add(GossipUpdate.fromNodeInfo(info))

  /**
   * Get updates to piggyback on an outgoing message.
   * Increments transmission count for returned updates.
   * Removes updates that have been transmitted enough times.
   */
  def getUpdates(): Seq[GossipUpdate] =
    val updates = pendingUpdates.toSeq
      .sortBy(-_._2._1.incarnation) // prioritize newer updates
      .take(maxUpdates)
      .map { case (nodeId, (update, count)) =>
        val newCount = count + 1
        if newCount >= maxTransmissions then
          pendingUpdates.remove(nodeId)
        else
          pendingUpdates.put(nodeId, (update, newCount))
        update
      }
    updates

  /**
   * Merge received updates into pending queue.
   * Only adds updates that are newer than what we have.
   */
  def merge(updates: Seq[GossipUpdate]): Seq[GossipUpdate] =
    updates.filter { update =>
      pendingUpdates.get(update.nodeId) match
        case Some((existing, _)) if existing.incarnation >= update.incarnation =>
          false // we already have same or newer
        case _ =>
          pendingUpdates.put(update.nodeId, (update, 0))
          true
    }

  /**
   * Get the number of pending updates.
   */
  def pendingCount: Int = pendingUpdates.size

  /**
   * Clear all pending updates.
   */
  def clear(): Unit = pendingUpdates.clear()

  /**
   * Check if there are any pending updates.
   */
  def hasPending: Boolean = pendingUpdates.nonEmpty

/**
 * Companion with factory methods.
 */
object Disseminator:
  /**
   * Create a disseminator tuned for cluster size.
   * Larger clusters need more transmissions for convergence.
   */
  def forClusterSize(size: Int): Disseminator =
    val transmissions = math.max(3, math.ceil(math.log(size.max(1)) * 2).toInt)
    Disseminator(maxUpdates = 10, maxTransmissions = transmissions)
