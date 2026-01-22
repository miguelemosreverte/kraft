package kraft.dsl.durable.cluster.transport

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import scala.collection.concurrent.TrieMap
import scala.compiletime.uninitialized
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/**
 * Netty-based transport for production cluster communication.
 *
 * Uses TCP with length-prefixed framing and JSON serialization.
 * Maintains connection pools to peer nodes.
 */
class NettyTransport(
  val localAddress: NodeAddress,
  connectionPoolSize: Int = 2
)(using ExecutionContext) extends ClusterTransport:

  private val running = new AtomicBoolean(false)
  private var handler: Option[MessageHandler] = None

  // Netty components
  private var bossGroup: NioEventLoopGroup = uninitialized
  private var workerGroup: NioEventLoopGroup = uninitialized
  private var serverChannel: Channel = uninitialized
  private var clientBootstrap: Bootstrap = uninitialized

  // Connection pool
  private val connections = TrieMap[NodeAddress, ConnectionPool]()

  // Pending responses for request-response pattern
  private val pendingResponses = TrieMap[Long, Promise[Option[GossipMessage]]]()
  private val requestIdGen = new AtomicLong(0)

  // Stats
  private val messagesSent = new AtomicLong(0)
  private val messagesReceived = new AtomicLong(0)
  private val sendFailures = new AtomicLong(0)

  override def start(): Future[Unit] =
    if !running.compareAndSet(false, true) then
      return Future.failed(new IllegalStateException("Transport already started"))

    val promise = Promise[Unit]()

    bossGroup = new NioEventLoopGroup(1)
    workerGroup = new NioEventLoopGroup()

    // Server bootstrap
    val serverBootstrap = new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
      .childHandler(new ChannelInitializer[SocketChannel]:
        override def initChannel(ch: SocketChannel): Unit =
          ch.pipeline()
            .addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
            .addLast(new LengthFieldPrepender(4))
            .addLast(new ServerHandler())
      )
      .option(ChannelOption.SO_BACKLOG, Integer.valueOf(128))
      .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)

    // Client bootstrap
    clientBootstrap = new Bootstrap()
      .group(workerGroup)
      .channel(classOf[NioSocketChannel])
      .handler(new ChannelInitializer[SocketChannel]:
        override def initChannel(ch: SocketChannel): Unit =
          ch.pipeline()
            .addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
            .addLast(new LengthFieldPrepender(4))
            .addLast(new ClientHandler())
      )
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(5000))
      .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)

    // Bind server
    serverBootstrap.bind(localAddress.host, localAddress.port)
      .addListener((future: ChannelFuture) =>
        if future.isSuccess then
          serverChannel = future.channel()
          promise.success(())
        else
          running.set(false)
          promise.failure(future.cause())
      )

    promise.future

  override def stop(): Future[Unit] =
    if !running.compareAndSet(true, false) then
      return Future.successful(())

    val promise = Promise[Unit]()

    // Close all connections
    connections.values.foreach(_.close())
    connections.clear()

    // Close server channel
    if serverChannel != null then
      serverChannel.close()

    // Shutdown event loops
    val bossFuture = bossGroup.shutdownGracefully()
    val workerFuture = workerGroup.shutdownGracefully()

    workerFuture.addListener((_: io.netty.util.concurrent.Future[?]) =>
      bossFuture.addListener((_: io.netty.util.concurrent.Future[?]) =>
        promise.success(())
      )
    )

    promise.future

  override def send(to: NodeAddress, message: GossipMessage): Future[Boolean] =
    if !running.get() then
      return Future.successful(false)

    getConnection(to).flatMap { channel =>
      if channel == null || !channel.isActive then
        sendFailures.incrementAndGet()
        Future.successful(false)
      else
        val envelope = MessageEnvelope(
          requestId = 0, // fire-and-forget
          senderAddress = localAddress,
          message = message,
          isResponse = false
        )
        val bytes = writeToArray(envelope)
        val buf = Unpooled.copiedBuffer(bytes)

        val promise = Promise[Boolean]()
        channel.writeAndFlush(buf).addListener((f: ChannelFuture) =>
          if f.isSuccess then
            messagesSent.incrementAndGet()
            promise.success(true)
          else
            sendFailures.incrementAndGet()
            promise.success(false)
        )
        promise.future
    }.recover { case _ =>
      sendFailures.incrementAndGet()
      false
    }

  override def sendAndReceive(
    to: NodeAddress,
    message: GossipMessage,
    timeout: FiniteDuration
  ): Future[Option[GossipMessage]] =
    if !running.get() then
      return Future.successful(None)

    val requestId = requestIdGen.incrementAndGet()
    val responsePromise = Promise[Option[GossipMessage]]()
    pendingResponses.put(requestId, responsePromise)

    // Timeout handler
    workerGroup.schedule(
      new Runnable:
        def run(): Unit =
          pendingResponses.remove(requestId).foreach(_.trySuccess(None))
      ,
      timeout.toMillis,
      TimeUnit.MILLISECONDS
    )

    getConnection(to).flatMap { channel =>
      if channel == null || !channel.isActive then
        pendingResponses.remove(requestId)
        sendFailures.incrementAndGet()
        Future.successful(None)
      else
        val envelope = MessageEnvelope(
          requestId = requestId,
          senderAddress = localAddress,
          message = message,
          isResponse = false
        )
        val bytes = writeToArray(envelope)
        val buf = Unpooled.copiedBuffer(bytes)

        channel.writeAndFlush(buf).addListener((f: ChannelFuture) =>
          if f.isSuccess then
            messagesSent.incrementAndGet()
          else
            pendingResponses.remove(requestId).foreach(_.trySuccess(None))
            sendFailures.incrementAndGet()
        )

        responsePromise.future
    }.recover { case _ =>
      pendingResponses.remove(requestId)
      sendFailures.incrementAndGet()
      None
    }

  override def onMessage(h: MessageHandler): Unit =
    handler = Some(h)

  def stats: TransportStats =
    TransportStats(
      messagesSent = messagesSent.get(),
      messagesReceived = messagesReceived.get(),
      bytesOut = 0, // Could track if needed
      bytesIn = 0,
      sendFailures = sendFailures.get(),
      activeConnections = connections.values.map(_.activeCount).sum
    )

  private def getConnection(address: NodeAddress): Future[Channel] =
    connections.getOrElseUpdate(address, new ConnectionPool(address, connectionPoolSize))
      .getChannel()

  private def handleIncomingMessage(envelope: MessageEnvelope): Unit =
    messagesReceived.incrementAndGet()

    if envelope.isResponse then
      // This is a response to a request we sent
      pendingResponses.remove(envelope.requestId).foreach { promise =>
        promise.trySuccess(Some(envelope.message))
      }
    else
      // This is a request - process and optionally respond
      handler.foreach { h =>
        val response = h.handle(envelope.senderAddress, envelope.message)
        if envelope.requestId != 0 then
          // Send response
          response.foreach { respMsg =>
            send(envelope.senderAddress, respMsg) // TODO: proper response routing
          }
      }

  // Connection pool for a single remote node
  private class ConnectionPool(address: NodeAddress, size: Int):
    private val channels = new java.util.concurrent.CopyOnWriteArrayList[Channel]()
    private val connecting = new AtomicBoolean(false)

    def getChannel(): Future[Channel] =
      // Find active channel
      val iter = channels.iterator()
      while iter.hasNext do
        val ch = iter.next()
        if ch.isActive then return Future.successful(ch)
        else channels.remove(ch)

      // Create new connection
      connect()

    def activeCount: Int =
      var count = 0
      val iter = channels.iterator()
      while iter.hasNext do
        if iter.next().isActive then count += 1
      count

    def close(): Unit =
      val iter = channels.iterator()
      while iter.hasNext do
        iter.next().close()
      channels.clear()

    private def connect(): Future[Channel] =
      val promise = Promise[Channel]()
      clientBootstrap.connect(address.host, address.port)
        .addListener((f: ChannelFuture) =>
          if f.isSuccess then
            channels.add(f.channel())
            promise.success(f.channel())
          else
            promise.failure(f.cause())
        )
      promise.future

  // Server-side handler
  private class ServerHandler extends SimpleChannelInboundHandler[ByteBuf]:
    override def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf): Unit =
      val bytes = new Array[Byte](msg.readableBytes())
      msg.readBytes(bytes)

      try
        val envelope = readFromArray[MessageEnvelope](bytes)
        messagesReceived.incrementAndGet()

        if envelope.isResponse then
          pendingResponses.remove(envelope.requestId).foreach { promise =>
            promise.trySuccess(Some(envelope.message))
          }
        else
          handler.foreach { h =>
            val response = h.handle(envelope.senderAddress, envelope.message)
            if envelope.requestId != 0 then
              response.foreach { respMsg =>
                val respEnvelope = MessageEnvelope(
                  requestId = envelope.requestId,
                  senderAddress = localAddress,
                  message = respMsg,
                  isResponse = true
                )
                val respBytes = writeToArray(respEnvelope)
                ctx.writeAndFlush(Unpooled.copiedBuffer(respBytes))
                messagesSent.incrementAndGet()
              }
          }
      catch
        case e: Exception =>
          System.err.println(s"Failed to process message: ${e.getMessage}")

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
      System.err.println(s"Server handler error: ${cause.getMessage}")
      ctx.close()

  // Client-side handler
  private class ClientHandler extends SimpleChannelInboundHandler[ByteBuf]:
    override def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf): Unit =
      val bytes = new Array[Byte](msg.readableBytes())
      msg.readBytes(bytes)

      try
        val envelope = readFromArray[MessageEnvelope](bytes)
        handleIncomingMessage(envelope)
      catch
        case e: Exception =>
          System.err.println(s"Failed to process response: ${e.getMessage}")

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
      System.err.println(s"Client handler error: ${cause.getMessage}")
      ctx.close()

/**
 * Wire format for messages.
 */
case class MessageEnvelope(
  requestId: Long,
  senderAddress: NodeAddress,
  message: GossipMessage,
  isResponse: Boolean
)

object MessageEnvelope:
  import com.github.plokhotnyuk.jsoniter_scala.macros.*
  given JsonValueCodec[MessageEnvelope] = JsonCodecMaker.make
