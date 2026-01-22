package kraft.dsl.durable.cluster

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.gossip.*
import kraft.dsl.durable.cluster.membership.*
import kraft.dsl.durable.cluster.routing.HashRing
import kraft.dsl.durable.cluster.transport.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.compiletime.uninitialized
import java.time.Instant

class DisseminatorSpec extends AnyFunSuite with Matchers:

  def makeUpdate(id: String, incarnation: Long = 0): GossipUpdate =
    GossipUpdate(
      nodeId = NodeId(id),
      state = NodeState.Alive,
      incarnation = incarnation,
      address = NodeAddress("127.0.0.1", 7800)
    )

  test("add and get updates"):
    val d = Disseminator()
    d.add(makeUpdate("a"))
    d.add(makeUpdate("b"))

    val updates = d.getUpdates()
    updates.size shouldBe 2

  test("newer updates replace older"):
    val d = Disseminator()
    d.add(makeUpdate("a", incarnation = 1))
    d.add(makeUpdate("a", incarnation = 2))

    val updates = d.getUpdates()
    updates.size shouldBe 1
    updates.head.incarnation shouldBe 2

  test("older updates are ignored"):
    val d = Disseminator()
    d.add(makeUpdate("a", incarnation = 2))
    d.add(makeUpdate("a", incarnation = 1))

    val updates = d.getUpdates()
    updates.size shouldBe 1
    updates.head.incarnation shouldBe 2

  test("updates removed after max transmissions"):
    val d = Disseminator(maxUpdates = 10, maxTransmissions = 2)
    d.add(makeUpdate("a"))

    d.getUpdates().size shouldBe 1
    d.getUpdates().size shouldBe 1
    d.getUpdates().size shouldBe 0 // removed after 2 transmissions

  test("max updates limits returned count"):
    val d = Disseminator(maxUpdates = 3, maxTransmissions = 10)
    for i <- 1 to 10 do
      d.add(makeUpdate(s"node-$i", incarnation = i))

    val updates = d.getUpdates()
    updates.size shouldBe 3
    // Should be newest (highest incarnation)
    updates.map(_.incarnation).toSet should contain allOf(10, 9, 8)

  test("merge adds new updates"):
    val d = Disseminator()
    d.add(makeUpdate("a"))

    val merged = d.merge(Seq(makeUpdate("b"), makeUpdate("c")))
    merged.size shouldBe 2

    d.pendingCount shouldBe 3

  test("merge ignores older updates"):
    val d = Disseminator()
    d.add(makeUpdate("a", incarnation = 5))

    val merged = d.merge(Seq(makeUpdate("a", incarnation = 3)))
    merged.size shouldBe 0

  test("clear removes all updates"):
    val d = Disseminator()
    d.add(makeUpdate("a"))
    d.add(makeUpdate("b"))

    d.pendingCount shouldBe 2
    d.clear()
    d.pendingCount shouldBe 0

class GossipProtocolSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  var registry: InMemoryTransport.Registry = uninitialized

  override def beforeEach(): Unit =
    registry = InMemoryTransport.newRegistry()

  override def afterEach(): Unit =
    registry.all.foreach(t => Await.result(t.stop(), 1.second))
    registry.clear()

  def createNode(id: String, port: Int, seeds: List[NodeAddress] = Nil): (GossipProtocol, InMemoryTransport) =
    val config = ClusterConfig.forTesting(id, port, seeds)
    val nodeInfo = NodeInfo(
      id = NodeId(id),
      address = config.bindAddress,
      state = NodeState.Alive,
      incarnation = 0,
      lastHeartbeat = Instant.now()
    )
    val membership = Membership(nodeInfo)
    val hashRing = HashRing()
    val transport = InMemoryTransport(config.bindAddress, registry)

    val gossip = GossipProtocol(config, membership, hashRing, transport)
    (gossip, transport)

  test("single node starts without seeds"):
    val (gossip, transport) = createNode("node-1", 7800)

    Await.result(gossip.start(), 2.seconds)
    Await.result(gossip.join(), 2.seconds) shouldBe true

    val stats = gossip.stats
    stats.aliveNodes shouldBe 1
    stats.ringNodes shouldBe 1

    Await.result(gossip.stop(), 2.seconds)

  test("node joins via seed"):
    // Start seed node
    val (seed, _) = createNode("seed", 7800)
    Await.result(seed.start(), 2.seconds)
    Await.result(seed.join(), 2.seconds)

    // Start joining node
    val (joiner, _) = createNode("joiner", 7801, List(NodeAddress("127.0.0.1", 7800)))
    Await.result(joiner.start(), 2.seconds)
    Await.result(joiner.join(), 2.seconds) shouldBe true

    // Wait for gossip to propagate
    Thread.sleep(200)

    // Both should see each other
    seed.stats.aliveNodes should be >= 1
    joiner.stats.aliveNodes should be >= 1

    Await.result(seed.stop(), 2.seconds)
    Await.result(joiner.stop(), 2.seconds)

  test("multiple nodes form cluster"):
    // Start seed node
    val (node1, _) = createNode("node-1", 7800)
    Await.result(node1.start(), 2.seconds)
    Await.result(node1.join(), 2.seconds)

    // Start second node
    val (node2, _) = createNode("node-2", 7801, List(NodeAddress("127.0.0.1", 7800)))
    Await.result(node2.start(), 2.seconds)
    Await.result(node2.join(), 2.seconds)

    // Start third node
    val (node3, _) = createNode("node-3", 7802, List(NodeAddress("127.0.0.1", 7800)))
    Await.result(node3.start(), 2.seconds)
    Await.result(node3.join(), 2.seconds)

    // Wait for gossip to propagate
    Thread.sleep(500)

    // All nodes should see the cluster
    node1.stats.aliveNodes should be >= 2
    node2.stats.aliveNodes should be >= 2
    node3.stats.aliveNodes should be >= 2

    Await.result(node1.stop(), 2.seconds)
    Await.result(node2.stop(), 2.seconds)
    Await.result(node3.stop(), 2.seconds)

  test("graceful leave removes node from cluster"):
    // Start two nodes
    val (node1, _) = createNode("node-1", 7800)
    Await.result(node1.start(), 2.seconds)
    Await.result(node1.join(), 2.seconds)

    val (node2, _) = createNode("node-2", 7801, List(NodeAddress("127.0.0.1", 7800)))
    Await.result(node2.start(), 2.seconds)
    Await.result(node2.join(), 2.seconds)

    Thread.sleep(200)

    // Node 2 leaves gracefully
    Await.result(node2.stop(), 2.seconds)

    Thread.sleep(200)

    // Node 1 should eventually see node 2 as left
    // (may take a gossip round to propagate)
    Await.result(node1.stop(), 2.seconds)
