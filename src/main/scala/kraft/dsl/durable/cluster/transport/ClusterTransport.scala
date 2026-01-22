package kraft.dsl.durable.cluster.transport

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * Transport abstraction for cluster communication.
 *
 * Handles sending and receiving gossip messages between nodes.
 * Implementations may use different underlying protocols (TCP, UDP, in-memory).
 */
trait ClusterTransport:
  /**
   * Start the transport, binding to the configured address.
   */
  def start(): Future[Unit]

  /**
   * Stop the transport, releasing resources.
   */
  def stop(): Future[Unit]

  /**
   * Send a message to a specific node.
   * Returns true if the message was sent (not necessarily delivered).
   */
  def send(to: NodeAddress, message: GossipMessage): Future[Boolean]

  /**
   * Send a message and wait for a response with timeout.
   */
  def sendAndReceive(
    to: NodeAddress,
    message: GossipMessage,
    timeout: FiniteDuration
  ): Future[Option[GossipMessage]]

  /**
   * Register a handler for incoming messages.
   */
  def onMessage(handler: MessageHandler): Unit

  /**
   * Get the local bind address.
   */
  def localAddress: NodeAddress

/**
 * Handler for incoming cluster messages.
 */
trait MessageHandler:
  /**
   * Called when a message is received.
   * Returns an optional response message.
   */
  def handle(from: NodeAddress, message: GossipMessage): Option[GossipMessage]

/**
 * Transport statistics for monitoring.
 */
case class TransportStats(
  messagesSent: Long,
  messagesReceived: Long,
  bytesOut: Long,
  bytesIn: Long,
  sendFailures: Long,
  activeConnections: Int
)
