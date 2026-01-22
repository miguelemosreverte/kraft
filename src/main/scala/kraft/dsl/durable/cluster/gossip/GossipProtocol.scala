package kraft.dsl.durable.cluster.gossip

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import kraft.dsl.durable.cluster.routing.HashRing
import kraft.dsl.durable.cluster.transport.*
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration.*
import scala.util.{Random, Success, Failure}
import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.time.Instant
import scala.compiletime.uninitialized

/**
 * SWIM-style gossip protocol implementation.
 *
 * Implements:
 * - Periodic random ping for failure detection
 * - Indirect ping (ping-req) through other nodes
 * - Suspicion mechanism before declaring death
 * - Piggybacked membership updates
 * - Join/leave protocol
 */
class GossipProtocol(
  config: ClusterConfig,
  membership: Membership,
  hashRing: HashRing,
  transport: ClusterTransport
)(using ExecutionContext):

  private val running = new AtomicBoolean(false)
  private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
  private val disseminator = Disseminator.forClusterSize(10) // will grow
  private val random = new Random()

  // Ping tracking
  private val sequenceNum = new AtomicLong(0)
  private val pendingPings = scala.collection.concurrent.TrieMap[Long, PingState]()

  // Scheduled tasks
  private var gossipTask: ScheduledFuture[?] = uninitialized
  private var suspectTask: ScheduledFuture[?] = uninitialized

  /**
   * Start the gossip protocol.
   */
  def start(): Future[Unit] =
    if !running.compareAndSet(false, true) then
      return Future.failed(new IllegalStateException("Gossip already started"))

    // Register message handler
    transport.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        handleMessage(from, msg)
    )

    // Add local node to hash ring
    hashRing.addNode(membership.localId)

    // Start periodic gossip
    gossipTask = scheduler.scheduleAtFixedRate(
      () => gossipRound(),
      config.gossipInterval.toMillis,
      config.gossipInterval.toMillis,
      TimeUnit.MILLISECONDS
    )

    // Start suspect timeout checker
    suspectTask = scheduler.scheduleAtFixedRate(
      () => checkSuspects(),
      config.suspectTimeout.toMillis,
      config.suspectTimeout.toMillis / 2,
      TimeUnit.MILLISECONDS
    )

    transport.start()

  /**
   * Stop the gossip protocol.
   */
  def stop(): Future[Unit] =
    if !running.compareAndSet(true, false) then
      return Future.successful(())

    // Cancel scheduled tasks
    if gossipTask != null then gossipTask.cancel(false)
    if suspectTask != null then suspectTask.cancel(false)
    scheduler.shutdown()

    // Send leave message to all alive members
    val leave = GossipMessage.Leave(membership.localId)
    val futures = membership.alive.filter(_.id != membership.localId).map { node =>
      transport.send(node.address, leave)
    }

    Future.sequence(futures).flatMap(_ => transport.stop())

  /**
   * Join the cluster through seed nodes.
   */
  def join(): Future[Boolean] =
    if config.seedNodes.isEmpty then
      // No seeds - we're the first node
      Future.successful(true)
    else
      joinViaSeed(config.seedNodes)

  private def joinViaSeed(seeds: List[NodeAddress]): Future[Boolean] =
    if seeds.isEmpty then
      Future.successful(false)
    else
      val seed = seeds.head
      val localInfo = membership.get(membership.localId).get
      val joinMsg = GossipMessage.Join(membership.localId, localInfo)

      transport.sendAndReceive(seed, joinMsg, config.rpcTimeout).flatMap {
        case Some(GossipMessage.JoinAck(_, members, true, _)) =>
          // Apply membership state from seed
          members.foreach { info =>
            if membership.applyUpdate(info) then
              hashRing.addNode(info.id)
              disseminator.addFromNodeInfo(info)
          }
          Future.successful(true)

        case Some(GossipMessage.JoinAck(_, _, false, errorMsg)) =>
          System.err.println(s"Join rejected: ${errorMsg.getOrElse("unknown reason")}")
          joinViaSeed(seeds.tail)

        case _ =>
          // Timeout or error, try next seed
          joinViaSeed(seeds.tail)
      }

  /**
   * Handle incoming gossip message.
   */
  private def handleMessage(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
    msg match
      case ping: GossipMessage.Ping =>
        handlePing(from, ping)

      case ack: GossipMessage.Ack =>
        handleAck(from, ack)
        None

      case pingReq: GossipMessage.PingReq =>
        handlePingReq(from, pingReq)
        None

      case nack: GossipMessage.Nack =>
        handleNack(from, nack)
        None

      case join: GossipMessage.Join =>
        handleJoin(from, join)

      case leave: GossipMessage.Leave =>
        handleLeave(from, leave)
        None

      case sync: GossipMessage.SyncRequest =>
        handleSyncRequest(from, sync)

      case _ => None

  private def handlePing(from: NodeAddress, ping: GossipMessage.Ping): Option[GossipMessage] =
    // Merge piggybacked updates
    mergeUpdates(ping.updates)

    // Update sender info
    updateNodeFromMessage(ping.senderId, from)

    // Respond with ack + our updates
    Some(GossipMessage.Ack(
      senderId = membership.localId,
      sequenceNum = ping.sequenceNum,
      updates = disseminator.getUpdates()
    ))

  private def handleAck(from: NodeAddress, ack: GossipMessage.Ack): Unit =
    // Merge piggybacked updates
    mergeUpdates(ack.updates)

    // Complete pending ping
    pendingPings.remove(ack.sequenceNum).foreach { state =>
      state.promise.trySuccess(true)

      // Node is alive - update state if suspect
      membership.get(ack.senderId).foreach { info =>
        if info.state == NodeState.Suspect then
          membership.applyUpdate(info.copy(
            state = NodeState.Alive,
            incarnation = info.incarnation + 1,
            lastHeartbeat = Instant.now()
          ))
      }
    }

  private def handlePingReq(from: NodeAddress, pingReq: GossipMessage.PingReq): Unit =
    // Forward ping to target and relay response
    membership.get(pingReq.targetId).foreach { target =>
      val ping = GossipMessage.Ping(
        senderId = membership.localId,
        sequenceNum = pingReq.sequenceNum,
        updates = disseminator.getUpdates()
      )

      transport.sendAndReceive(target.address, ping, config.suspectTimeout / 2).onComplete {
        case Success(Some(_: GossipMessage.Ack)) =>
          // Target responded - send ack back to requester
          transport.send(from, GossipMessage.Ack(membership.localId, pingReq.sequenceNum))
        case _ =>
          // Target didn't respond - send nack
          transport.send(from, GossipMessage.Nack(membership.localId, pingReq.targetId, pingReq.sequenceNum))
      }
    }

  private def handleNack(from: NodeAddress, nack: GossipMessage.Nack): Unit =
    // Indirect ping failed - count as failure
    pendingPings.get(nack.sequenceNum).foreach { state =>
      state.nackCount.incrementAndGet()
      if state.nackCount.get() >= state.expectedAcks then
        // All indirect pings failed
        state.promise.trySuccess(false)
    }

  private def handleJoin(from: NodeAddress, join: GossipMessage.Join): Option[GossipMessage] =
    // Add new node to membership
    if membership.applyUpdate(join.nodeInfo) then
      hashRing.addNode(join.nodeInfo.id)
      disseminator.addFromNodeInfo(join.nodeInfo)

    // Respond with current membership
    Some(GossipMessage.JoinAck(
      senderId = membership.localId,
      members = membership.alive,
      accepted = true
    ))

  private def handleLeave(from: NodeAddress, leave: GossipMessage.Leave): Unit =
    membership.get(leave.senderId).foreach { info =>
      val left = info.copy(state = NodeState.Left, incarnation = info.incarnation + 1)
      membership.applyUpdate(left)
      disseminator.addFromNodeInfo(left)
      hashRing.removeNode(leave.senderId)
    }

  private def handleSyncRequest(from: NodeAddress, sync: GossipMessage.SyncRequest): Option[GossipMessage] =
    Some(GossipMessage.SyncResponse(
      senderId = membership.localId,
      members = membership.all
    ))

  /**
   * Periodic gossip round - ping random nodes.
   */
  private def gossipRound(): Unit =
    if !running.get() then return

    val alive = membership.alive.filter(_.id != membership.localId)
    if alive.isEmpty then return

    // Pick random node to ping
    val target = alive(random.nextInt(alive.size))
    ping(target)

  /**
   * Ping a node and handle failure detection.
   */
  private def ping(target: NodeInfo): Unit =
    val seq = sequenceNum.incrementAndGet()
    val promise = Promise[Boolean]()
    val state = PingState(target.id, promise, System.currentTimeMillis())
    pendingPings.put(seq, state)

    val pingMsg = GossipMessage.Ping(
      senderId = membership.localId,
      sequenceNum = seq,
      updates = disseminator.getUpdates()
    )

    // Direct ping with timeout
    transport.sendAndReceive(target.address, pingMsg, config.gossipInterval * 2).onComplete {
      case Success(Some(_: GossipMessage.Ack)) =>
        // Already handled in handleAck
        ()
      case _ if running.get() =>
        // No direct response - try indirect ping
        indirectPing(target, seq, state)
      case _ => ()
    }

    // Timeout handling
    scheduler.schedule(
      new Runnable:
        def run(): Unit =
          if !promise.isCompleted then
            pendingPings.remove(seq)
            handlePingFailure(target)
      ,
      config.suspectTimeout.toMillis,
      TimeUnit.MILLISECONDS
    )

  /**
   * Try indirect ping through other nodes.
   */
  private def indirectPing(target: NodeInfo, seq: Long, state: PingState): Unit =
    val others = membership.alive.filter(n => n.id != membership.localId && n.id != target.id)
    if others.isEmpty then
      pendingPings.remove(seq)
      handlePingFailure(target)
      return

    // Pick up to 3 nodes for indirect ping
    val delegates = random.shuffle(others).take(3)
    state.expectedAcks = delegates.size

    delegates.foreach { delegate =>
      val pingReq = GossipMessage.PingReq(
        senderId = membership.localId,
        targetId = target.id,
        sequenceNum = seq
      )
      transport.send(delegate.address, pingReq)
    }

  /**
   * Handle ping failure - mark node as suspect.
   */
  private def handlePingFailure(target: NodeInfo): Unit =
    if membership.markSuspect(target.id) then
      membership.get(target.id).foreach(disseminator.addFromNodeInfo)

  /**
   * Check suspect nodes for timeout and mark as dead.
   */
  private def checkSuspects(): Unit =
    if !running.get() then return

    val now = Instant.now()
    val deadTimeout = config.deadTimeout.toMillis

    membership.suspect.foreach { suspect =>
      val suspectDuration = java.time.Duration.between(suspect.lastHeartbeat, now).toMillis
      if suspectDuration > deadTimeout then
        if membership.markDead(suspect.id) then
          hashRing.removeNode(suspect.id)
          membership.get(suspect.id).foreach(disseminator.addFromNodeInfo)
    }

  /**
   * Merge received updates into membership.
   */
  private def mergeUpdates(updates: Seq[GossipUpdate]): Unit =
    updates.foreach { update =>
      val info = update.toNodeInfo
      if membership.applyUpdate(info) then
        if info.state == NodeState.Alive || info.state == NodeState.Suspect then
          if !hashRing.contains(info.id) then
            hashRing.addNode(info.id)
        else
          hashRing.removeNode(info.id)
        disseminator.add(update)
    }

  /**
   * Update node info from message.
   */
  private def updateNodeFromMessage(nodeId: NodeId, address: NodeAddress): Unit =
    membership.get(nodeId) match
      case Some(info) =>
        membership.applyUpdate(info.copy(lastHeartbeat = Instant.now()))
      case None =>
        // New node - add it
        val info = NodeInfo(nodeId, address, NodeState.Alive, 0, Instant.now())
        if membership.applyUpdate(info) then
          hashRing.addNode(nodeId)
          disseminator.addFromNodeInfo(info)

  /**
   * Get protocol statistics.
   */
  def stats: GossipStats =
    GossipStats(
      aliveNodes = membership.aliveCount,
      suspectNodes = membership.suspect.size,
      pendingPings = pendingPings.size,
      pendingUpdates = disseminator.pendingCount,
      ringNodes = hashRing.size
    )

/**
 * State for tracking pending pings.
 */
private case class PingState(
  targetId: NodeId,
  promise: Promise[Boolean],
  startTime: Long,
  nackCount: AtomicLong = new AtomicLong(0),
  var expectedAcks: Int = 0
)

/**
 * Gossip protocol statistics.
 */
case class GossipStats(
  aliveNodes: Int,
  suspectNodes: Int,
  pendingPings: Int,
  pendingUpdates: Int,
  ringNodes: Int
)
