package kraft.dsl.durable.cluster

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.membership.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import scala.collection.mutable.ListBuffer

class MembershipSpec extends AnyFunSuite with Matchers:

  def makeNode(id: String, state: NodeState = NodeState.Alive, incarnation: Long = 0): NodeInfo =
    NodeInfo(
      id = NodeId(id),
      address = NodeAddress("127.0.0.1", 7800 + id.hashCode.abs % 1000),
      state = state,
      incarnation = incarnation,
      lastHeartbeat = Instant.now()
    )

  test("membership starts with local node"):
    val local = makeNode("local")
    val membership = Membership(local)

    membership.localId shouldBe local.id
    membership.all.size shouldBe 1
    membership.get(local.id) shouldBe Some(local)

  test("applyUpdate adds new nodes"):
    val local = makeNode("local")
    val membership = Membership(local)

    val remote = makeNode("remote")
    membership.applyUpdate(remote) shouldBe true
    membership.all.size shouldBe 2
    membership.get(remote.id) shouldBe Some(remote)

  test("applyUpdate with higher incarnation replaces node"):
    val local = makeNode("local")
    val membership = Membership(local)

    val node1 = makeNode("node", incarnation = 1)
    val node2 = makeNode("node", incarnation = 2)

    membership.applyUpdate(node1) shouldBe true
    membership.applyUpdate(node2) shouldBe true

    membership.get(NodeId("node")).get.incarnation shouldBe 2

  test("applyUpdate with lower incarnation is ignored"):
    val local = makeNode("local")
    val membership = Membership(local)

    val node2 = makeNode("node", incarnation = 2)
    val node1 = makeNode("node", incarnation = 1)

    membership.applyUpdate(node2) shouldBe true
    membership.applyUpdate(node1) shouldBe false

    membership.get(NodeId("node")).get.incarnation shouldBe 2

  test("state priority at same incarnation"):
    val local = makeNode("local")
    val membership = Membership(local)

    // Higher priority state wins at same incarnation
    val alive = makeNode("node", NodeState.Alive, incarnation = 1)
    val suspect = makeNode("node", NodeState.Suspect, incarnation = 1)
    val dead = makeNode("node", NodeState.Dead, incarnation = 1)

    membership.applyUpdate(alive) shouldBe true
    membership.applyUpdate(suspect) shouldBe true // suspect > alive
    membership.get(NodeId("node")).get.state shouldBe NodeState.Suspect

    membership.applyUpdate(dead) shouldBe true // dead > suspect
    membership.get(NodeId("node")).get.state shouldBe NodeState.Dead

    // Lower priority state is ignored at same incarnation
    membership.applyUpdate(alive) shouldBe false
    membership.get(NodeId("node")).get.state shouldBe NodeState.Dead

  test("alive and suspect filters"):
    val local = makeNode("local", NodeState.Alive)
    val membership = Membership(local)

    val alive1 = makeNode("alive1", NodeState.Alive)
    val alive2 = makeNode("alive2", NodeState.Alive)
    val suspect1 = makeNode("suspect1", NodeState.Suspect)
    val dead1 = makeNode("dead1", NodeState.Dead)

    Seq(alive1, alive2, suspect1, dead1).foreach(membership.applyUpdate)

    membership.alive.map(_.id.value).toSet shouldBe Set("local", "alive1", "alive2")
    membership.suspect.map(_.id.value).toSet shouldBe Set("suspect1")
    membership.aliveCount shouldBe 3

  test("markSuspect transitions alive to suspect"):
    val local = makeNode("local")
    val membership = Membership(local)

    val node = makeNode("node", NodeState.Alive, incarnation = 1)
    membership.applyUpdate(node)

    membership.markSuspect(NodeId("node")) shouldBe true
    val updated = membership.get(NodeId("node")).get

    updated.state shouldBe NodeState.Suspect
    updated.incarnation shouldBe 2 // incremented

  test("markSuspect on non-alive is no-op"):
    val local = makeNode("local")
    val membership = Membership(local)

    val suspect = makeNode("node", NodeState.Suspect)
    membership.applyUpdate(suspect)

    membership.markSuspect(NodeId("node")) shouldBe false

  test("markDead transitions to dead"):
    val local = makeNode("local")
    val membership = Membership(local)

    val node = makeNode("node", NodeState.Alive)
    membership.applyUpdate(node)

    membership.markDead(NodeId("node")) shouldBe true
    membership.get(NodeId("node")).get.state shouldBe NodeState.Dead

  test("markDead on already dead is no-op"):
    val local = makeNode("local")
    val membership = Membership(local)

    val dead = makeNode("node", NodeState.Dead)
    membership.applyUpdate(dead)

    membership.markDead(NodeId("node")) shouldBe false

  test("remove deletes node"):
    val local = makeNode("local")
    val membership = Membership(local)

    val node = makeNode("node")
    membership.applyUpdate(node)
    membership.contains(NodeId("node")) shouldBe true

    membership.remove(NodeId("node")) shouldBe true
    membership.contains(NodeId("node")) shouldBe false
    membership.remove(NodeId("node")) shouldBe false // already removed

  test("refute bumps local incarnation"):
    val local = makeNode("local", incarnation = 5)
    val membership = Membership(local)

    val refuted = membership.refute()
    refuted.incarnation shouldBe 6
    refuted.state shouldBe NodeState.Alive

  test("version increments on changes"):
    val local = makeNode("local")
    val membership = Membership(local)

    val v0 = membership.version

    membership.applyUpdate(makeNode("a"))
    membership.version should be > v0

    val v1 = membership.version
    membership.markSuspect(NodeId("a"))
    membership.version should be > v1

    val v2 = membership.version
    membership.markDead(NodeId("a"))
    membership.version should be > v2

    val v3 = membership.version
    membership.remove(NodeId("a"))
    membership.version should be > v3

  test("listeners receive events"):
    val local = makeNode("local")
    val membership = Membership(local)

    val events = ListBuffer[MembershipEvent]()
    membership.addListener("test", event => events += event)

    val node = makeNode("node")
    membership.applyUpdate(node) // MemberJoined

    val updated = node.copy(state = NodeState.Suspect, incarnation = 1)
    membership.applyUpdate(updated) // MemberStateChanged

    membership.remove(NodeId("node")) // MemberRemoved

    events.size shouldBe 3
    events(0) shouldBe a[MembershipEvent.MemberJoined]
    events(1) shouldBe a[MembershipEvent.MemberStateChanged]
    events(2) shouldBe a[MembershipEvent.MemberRemoved]

  test("listener removal stops events"):
    val local = makeNode("local")
    val membership = Membership(local)

    var count = 0
    membership.addListener("test", _ => count += 1)

    membership.applyUpdate(makeNode("a"))
    count shouldBe 1

    membership.removeListener("test")
    membership.applyUpdate(makeNode("b"))
    count shouldBe 1 // no increment

  test("listener errors don't break membership"):
    val local = makeNode("local")
    val membership = Membership(local)

    membership.addListener("broken", _ => throw new RuntimeException("boom"))

    // Should not throw
    membership.applyUpdate(makeNode("node"))
    membership.all.size shouldBe 2
