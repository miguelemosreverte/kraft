package kraft.dsl.durable.cluster.transport

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import scala.collection.concurrent.TrieMap
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory transport for testing.
 *
 * Maintains a registry of all transports to route messages directly
 * in memory. Supports simulating network conditions like delays,
 * partitions, and message loss.
 */
class InMemoryTransport(
  val localAddress: NodeAddress,
  registry: InMemoryTransport.Registry
)(using ExecutionContext) extends ClusterTransport:

  private val running = new AtomicBoolean(false)
  private var handler: Option[MessageHandler] = None
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  // Network simulation
  @volatile private var messageDelay: FiniteDuration = FiniteDuration(0, TimeUnit.MILLISECONDS)
  @volatile private var dropProbability: Double = 0.0
  @volatile private var partitioned: Set[NodeAddress] = Set.empty

  override def start(): Future[Unit] =
    if running.compareAndSet(false, true) then
      registry.register(localAddress, this)
      Future.successful(())
    else
      Future.failed(new IllegalStateException("Transport already started"))

  override def stop(): Future[Unit] =
    if running.compareAndSet(true, false) then
      registry.unregister(localAddress)
      scheduler.shutdown()
      Future.successful(())
    else
      Future.successful(())

  override def send(to: NodeAddress, message: GossipMessage): Future[Boolean] =
    if !running.get() then
      Future.successful(false)
    else if partitioned.contains(to) then
      Future.successful(false) // network partition
    else if scala.util.Random.nextDouble() < dropProbability then
      Future.successful(true) // dropped but "sent"
    else
      registry.get(to) match
        case Some(remote) =>
          val delay = messageDelay.toMillis
          if delay > 0 then
            val promise = Promise[Boolean]()
            scheduler.schedule(
              () => {
                remote.receive(localAddress, message)
                promise.success(true)
              },
              delay,
              TimeUnit.MILLISECONDS
            )
            promise.future
          else
            remote.receive(localAddress, message)
            Future.successful(true)
        case None =>
          Future.successful(false)

  override def sendAndReceive(
    to: NodeAddress,
    message: GossipMessage,
    timeout: FiniteDuration
  ): Future[Option[GossipMessage]] =
    if !running.get() then
      Future.successful(None)
    else if partitioned.contains(to) then
      // Simulate timeout for partitioned node
      val promise = Promise[Option[GossipMessage]]()
      scheduler.schedule(
        () => promise.success(None),
        timeout.toMillis,
        TimeUnit.MILLISECONDS
      )
      promise.future
    else
      registry.get(to) match
        case Some(remote) =>
          val promise = Promise[Option[GossipMessage]]()
          val delay = messageDelay.toMillis

          val deliverAndRespond = () => {
            val response = remote.receive(localAddress, message)
            promise.success(response)
          }

          if delay > 0 then
            scheduler.schedule(
              () => deliverAndRespond(),
              delay,
              TimeUnit.MILLISECONDS
            )
          else
            deliverAndRespond()

          // Add timeout
          scheduler.schedule(
            () => promise.trySuccess(None),
            timeout.toMillis,
            TimeUnit.MILLISECONDS
          )
          promise.future
        case None =>
          Future.successful(None)

  override def onMessage(h: MessageHandler): Unit =
    handler = Some(h)

  /**
   * Called by other transports to deliver a message.
   */
  def receive(from: NodeAddress, message: GossipMessage): Option[GossipMessage] =
    if running.get() then
      handler.flatMap(_.handle(from, message))
    else
      None

  // Test helpers

  /**
   * Set message delay for simulating network latency.
   */
  def setDelay(delay: FiniteDuration): Unit =
    messageDelay = delay

  /**
   * Set message drop probability (0.0 to 1.0).
   */
  def setDropProbability(probability: Double): Unit =
    dropProbability = probability.max(0.0).min(1.0)

  /**
   * Create a network partition with specified nodes.
   */
  def partition(nodes: Set[NodeAddress]): Unit =
    partitioned = nodes

  /**
   * Heal all network partitions.
   */
  def healPartition(): Unit =
    partitioned = Set.empty

object InMemoryTransport:
  /**
   * Registry of all in-memory transports for message routing.
   */
  class Registry:
    private val transports = TrieMap[NodeAddress, InMemoryTransport]()

    def register(address: NodeAddress, transport: InMemoryTransport): Unit =
      transports.put(address, transport)

    def unregister(address: NodeAddress): Unit =
      transports.remove(address)

    def get(address: NodeAddress): Option[InMemoryTransport] =
      transports.get(address)

    def all: Iterable[InMemoryTransport] =
      transports.values

    def clear(): Unit =
      transports.clear()

    /**
     * Create a partition between two groups of nodes.
     */
    def createPartition(group1: Set[NodeAddress], group2: Set[NodeAddress]): Unit =
      group1.foreach { addr =>
        transports.get(addr).foreach(_.partition(group2))
      }
      group2.foreach { addr =>
        transports.get(addr).foreach(_.partition(group1))
      }

    /**
     * Heal all partitions between nodes.
     */
    def healAllPartitions(): Unit =
      transports.values.foreach(_.healPartition())

  /**
   * Create a new registry for test isolation.
   */
  def newRegistry(): Registry = new Registry()
