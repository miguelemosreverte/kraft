package kraft.dsl.durable.cluster.membership

import kraft.dsl.durable.cluster.model.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Thread-safe membership list with versioned updates.
 *
 * The membership list is the source of truth for which nodes are
 * in the cluster. It uses incarnation numbers to handle stale updates.
 */
class Membership(localNode: NodeInfo):
  private val members = new ConcurrentHashMap[NodeId, NodeInfo]()
  members.put(localNode.id, localNode)

  @volatile private var _version: Long = 0
  private val listeners = new ConcurrentHashMap[String, MembershipListener]()

  def localId: NodeId = localNode.id
  def version: Long = _version

  /**
   * Get all members (any state).
   */
  def all: Seq[NodeInfo] = members.values().asScala.toSeq

  /**
   * Get only routable (alive) members.
   */
  def alive: Seq[NodeInfo] = all.filter(_.state == NodeState.Alive)

  /**
   * Get suspect members.
   */
  def suspect: Seq[NodeInfo] = all.filter(_.state == NodeState.Suspect)

  /**
   * Get a specific member.
   */
  def get(id: NodeId): Option[NodeInfo] = Option(members.get(id))

  /**
   * Check if a node is in the membership.
   */
  def contains(id: NodeId): Boolean = members.containsKey(id)

  /**
   * Get count of alive members.
   */
  def aliveCount: Int = alive.size

  /**
   * Apply an update if it's newer than existing state.
   * Returns true if the update was applied.
   */
  def applyUpdate(update: NodeInfo): Boolean =
    var applied = false
    members.compute(update.id, (_, existing) =>
      if existing == null then
        _version += 1
        applied = true
        notifyListeners(MembershipEvent.MemberJoined(update))
        update
      else if update.incarnation > existing.incarnation then
        _version += 1
        applied = true
        if existing.state != update.state then
          notifyListeners(MembershipEvent.MemberStateChanged(existing, update))
        update
      else if update.incarnation == existing.incarnation &&
              stateOutranks(update.state, existing.state) then
        _version += 1
        applied = true
        notifyListeners(MembershipEvent.MemberStateChanged(existing, update))
        update
      else
        existing
    )
    applied

  /**
   * Mark a node as suspect.
   */
  def markSuspect(id: NodeId): Boolean =
    var changed = false
    members.computeIfPresent(id, (_, node) =>
      if node.state == NodeState.Alive then
        _version += 1
        changed = true
        val updated = node.copy(state = NodeState.Suspect, incarnation = node.incarnation + 1)
        notifyListeners(MembershipEvent.MemberStateChanged(node, updated))
        updated
      else node
    )
    changed

  /**
   * Mark a node as dead.
   */
  def markDead(id: NodeId): Boolean =
    var changed = false
    members.computeIfPresent(id, (_, node) =>
      if node.state != NodeState.Dead && node.state != NodeState.Left then
        _version += 1
        changed = true
        val updated = node.copy(state = NodeState.Dead, incarnation = node.incarnation + 1)
        notifyListeners(MembershipEvent.MemberStateChanged(node, updated))
        updated
      else node
    )
    changed

  /**
   * Remove a dead node from the list.
   */
  def remove(id: NodeId): Boolean =
    val removed = members.remove(id)
    if removed != null then
      _version += 1
      notifyListeners(MembershipEvent.MemberRemoved(removed))
      true
    else false

  /**
   * Bump local incarnation (used to refute suspicion).
   */
  def refute(): NodeInfo =
    val updated = members.compute(localNode.id, (_, node) =>
      _version += 1
      node.copy(incarnation = node.incarnation + 1, state = NodeState.Alive, lastHeartbeat = Instant.now())
    )
    updated

  /**
   * Update local node's heartbeat timestamp.
   */
  def touchLocal(): Unit =
    members.computeIfPresent(localNode.id, (_, node) =>
      node.copy(lastHeartbeat = Instant.now())
    )

  /**
   * Register a membership event listener.
   */
  def addListener(id: String, listener: MembershipListener): Unit =
    listeners.put(id, listener)

  /**
   * Remove a membership event listener.
   */
  def removeListener(id: String): Unit =
    listeners.remove(id)

  /**
   * State priority for conflict resolution.
   * Higher-priority states override lower ones at same incarnation.
   */
  private def stateOutranks(newState: NodeState, oldState: NodeState): Boolean =
    val priority = Map(
      NodeState.Alive -> 0,
      NodeState.Suspect -> 1,
      NodeState.Dead -> 2,
      NodeState.Left -> 3
    )
    priority.getOrElse(newState, 0) > priority.getOrElse(oldState, 0)

  private def notifyListeners(event: MembershipEvent): Unit =
    listeners.values().asScala.foreach { listener =>
      try listener.onEvent(event)
      catch case e: Exception =>
        System.err.println(s"Membership listener error: ${e.getMessage}")
    }

/**
 * Events emitted by membership changes.
 */
enum MembershipEvent:
  case MemberJoined(node: NodeInfo)
  case MemberStateChanged(oldState: NodeInfo, newState: NodeInfo)
  case MemberRemoved(node: NodeInfo)

/**
 * Listener for membership events.
 */
trait MembershipListener:
  def onEvent(event: MembershipEvent): Unit
