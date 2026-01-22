package kraft.dsl.durable.cluster

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.durable.cluster.transport.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.compiletime.uninitialized

class TransportSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  var registry: InMemoryTransport.Registry = uninitialized

  override def beforeEach(): Unit =
    registry = InMemoryTransport.newRegistry()

  override def afterEach(): Unit =
    registry.all.foreach(t => Await.result(t.stop(), 1.second))
    registry.clear()

  def createTransport(port: Int): InMemoryTransport =
    val transport = InMemoryTransport(NodeAddress("127.0.0.1", port), registry)
    Await.result(transport.start(), 1.second)
    transport

  test("transport starts and stops"):
    val transport = createTransport(7800)
    transport.localAddress shouldBe NodeAddress("127.0.0.1", 7800)
    Await.result(transport.stop(), 1.second)

  test("send message between two transports"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    var received: Option[GossipMessage] = None
    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        received = Some(msg)
        None
    )

    val ping = GossipMessage.Ping(NodeId("node-1"), 1)
    val result = Await.result(t1.send(t2.localAddress, ping), 1.second)

    result shouldBe true
    Thread.sleep(50) // allow async delivery
    received shouldBe Some(ping)

  test("sendAndReceive returns response"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        msg match
          case GossipMessage.Ping(senderId, seq, _) =>
            Some(GossipMessage.Ack(NodeId("node-2"), seq))
          case _ => None
    )

    val ping = GossipMessage.Ping(NodeId("node-1"), 42)
    val response = Await.result(t1.sendAndReceive(t2.localAddress, ping, 1.second), 2.seconds)

    response shouldBe defined
    response.get shouldBe a[GossipMessage.Ack]
    response.get.asInstanceOf[GossipMessage.Ack].sequenceNum shouldBe 42

  test("send to non-existent node returns false"):
    val t1 = createTransport(7800)
    val result = Await.result(t1.send(NodeAddress("127.0.0.1", 9999), GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    result shouldBe false

  test("sendAndReceive timeout returns None"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    // No handler registered - no response
    val ping = GossipMessage.Ping(NodeId("node-1"), 1)
    val response = Await.result(t1.sendAndReceive(t2.localAddress, ping, 100.millis), 1.second)

    response shouldBe None

  test("network partition prevents communication"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    var received = false
    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        received = true
        None
    )

    // Create partition
    t1.partition(Set(t2.localAddress))

    val result = Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    result shouldBe false
    Thread.sleep(50)
    received shouldBe false

    // Heal partition
    t1.healPartition()
    val result2 = Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    result2 shouldBe true
    Thread.sleep(50)
    received shouldBe true

  test("message delay works"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    var receiveTime: Long = 0
    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        receiveTime = System.currentTimeMillis()
        None
    )

    t1.setDelay(100.millis)

    val sendTime = System.currentTimeMillis()
    Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)

    Thread.sleep(150) // wait for delayed delivery
    (receiveTime - sendTime) should be >= 100L

  test("drop probability loses messages"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    var receiveCount = 0
    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        receiveCount += 1
        None
    )

    t1.setDropProbability(1.0) // drop everything

    // Send many messages
    for i <- 1 to 10 do
      Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), i)), 1.second)

    Thread.sleep(50)
    receiveCount shouldBe 0

  test("registry partition between groups"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)
    val t3 = createTransport(7802)

    // Create partition: [t1, t2] | [t3]
    registry.createPartition(
      Set(t1.localAddress, t2.localAddress),
      Set(t3.localAddress)
    )

    // t1 can reach t2
    val r1 = Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    r1 shouldBe true

    // t1 cannot reach t3
    val r2 = Await.result(t1.send(t3.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    r2 shouldBe false

    // Heal
    registry.healAllPartitions()
    val r3 = Await.result(t1.send(t3.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    r3 shouldBe true

  test("multiple message handlers not overwritten"):
    val t1 = createTransport(7800)
    val t2 = createTransport(7801)

    // Second handler replaces first
    var h1Called = false
    var h2Called = false

    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        h1Called = true
        None
    )

    t2.onMessage(new MessageHandler:
      def handle(from: NodeAddress, msg: GossipMessage): Option[GossipMessage] =
        h2Called = true
        None
    )

    Await.result(t1.send(t2.localAddress, GossipMessage.Ping(NodeId("x"), 1)), 1.second)
    Thread.sleep(50)

    h1Called shouldBe false
    h2Called shouldBe true // latest handler wins
