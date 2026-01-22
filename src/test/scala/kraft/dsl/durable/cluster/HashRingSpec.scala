package kraft.dsl.durable.cluster

import kraft.dsl.durable.cluster.model.*
import kraft.dsl.durable.cluster.routing.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class HashRingSpec extends AnyFunSuite with Matchers:

  test("empty ring returns None for any key"):
    val ring = HashRing()
    ring.getNode("any-key") shouldBe None
    ring.getNodes("any-key", 3) shouldBe empty

  test("single node handles all keys"):
    val ring = HashRing()
    val node = NodeId("node-1")
    ring.addNode(node)

    ring.getNode("key-1") shouldBe Some(node)
    ring.getNode("key-2") shouldBe Some(node)
    ring.getNode("any-random-key") shouldBe Some(node)

  test("adding node increases size"):
    val ring = HashRing()
    ring.size shouldBe 0
    ring.isEmpty shouldBe true

    ring.addNode(NodeId("node-1"))
    ring.size shouldBe 1
    ring.isEmpty shouldBe false

    ring.addNode(NodeId("node-2"))
    ring.size shouldBe 2

  test("removing node decreases size"):
    val ring = HashRing()
    val node1 = NodeId("node-1")
    val node2 = NodeId("node-2")

    ring.addNode(node1)
    ring.addNode(node2)
    ring.size shouldBe 2

    ring.removeNode(node1)
    ring.size shouldBe 1
    ring.contains(node1) shouldBe false
    ring.contains(node2) shouldBe true

  test("consistent hashing - same key maps to same node"):
    val ring = HashRing()
    ring.addNode(NodeId("node-1"))
    ring.addNode(NodeId("node-2"))
    ring.addNode(NodeId("node-3"))

    val key = "consistent-key"
    val node1 = ring.getNode(key)
    val node2 = ring.getNode(key)
    val node3 = ring.getNode(key)

    node1 shouldBe node2
    node2 shouldBe node3

  test("getNodes returns requested number of unique nodes"):
    val ring = HashRing()
    ring.addNode(NodeId("node-1"))
    ring.addNode(NodeId("node-2"))
    ring.addNode(NodeId("node-3"))

    val nodes = ring.getNodes("replication-key", 3)
    nodes.size shouldBe 3
    nodes.distinct.size shouldBe 3 // all unique

  test("getNodes returns all available if fewer than requested"):
    val ring = HashRing()
    ring.addNode(NodeId("node-1"))
    ring.addNode(NodeId("node-2"))

    val nodes = ring.getNodes("key", 5)
    nodes.size shouldBe 2

  test("virtual nodes provide uniform distribution"):
    val ring = HashRing(virtualNodesPerNode = 150)
    val nodes = (1 to 5).map(i => NodeId(s"node-$i"))
    nodes.foreach(ring.addNode)

    // Count key assignments
    val assignments = (1 to 1000).map(i => ring.getNode(s"key-$i").get)
    val counts = assignments.groupBy(identity).view.mapValues(_.size).toMap

    // Each node should get roughly 200 keys (1000/5)
    // Allow 50% variance for statistical distribution
    counts.values.foreach { count =>
      count should be > 100
      count should be < 300
    }

  test("adding node redistributes some keys"):
    val ring = HashRing()
    ring.addNode(NodeId("node-1"))
    ring.addNode(NodeId("node-2"))

    // Record initial assignments
    val keys = (1 to 100).map(i => s"key-$i")
    val before = keys.map(k => k -> ring.getNode(k).get).toMap

    // Add new node
    ring.addNode(NodeId("node-3"))
    val after = keys.map(k => k -> ring.getNode(k).get).toMap

    // Some keys should have moved to the new node
    val movedToNew = keys.count(k => after(k) == NodeId("node-3"))
    movedToNew should be > 0
    movedToNew should be < 100 // not all

  test("removing node redistributes its keys"):
    val ring = HashRing()
    val node1 = NodeId("node-1")
    val node2 = NodeId("node-2")
    val node3 = NodeId("node-3")

    ring.addNode(node1)
    ring.addNode(node2)
    ring.addNode(node3)

    // Record assignments before removal
    val keys = (1 to 100).map(i => s"key-$i")
    val before = keys.map(k => k -> ring.getNode(k).get).toMap
    val keysOnNode2 = keys.filter(k => before(k) == node2)

    // Remove node2
    ring.removeNode(node2)

    // Keys that were on node2 should now be on node1 or node3
    keysOnNode2.foreach { k =>
      val newNode = ring.getNode(k).get
      newNode should not be node2
      Set(node1, node3) should contain(newNode)
    }

  test("allNodes returns all added nodes"):
    val ring = HashRing()
    ring.allNodes shouldBe empty

    val nodes = Set(NodeId("a"), NodeId("b"), NodeId("c"))
    nodes.foreach(ring.addNode)

    ring.allNodes shouldBe nodes

  test("getPositions returns virtual node positions"):
    val ring = HashRing(virtualNodesPerNode = 10)
    val node = NodeId("node-1")

    ring.getPositions(node) shouldBe empty

    ring.addNode(node)
    ring.getPositions(node).size shouldBe 10

  test("stats reflect ring state"):
    val ring = HashRing(virtualNodesPerNode = 100)

    val emptyStats = ring.stats
    emptyStats.nodeCount shouldBe 0
    emptyStats.totalVirtualNodes shouldBe 0

    ring.addNode(NodeId("node-1"))
    ring.addNode(NodeId("node-2"))

    val stats = ring.stats
    stats.nodeCount shouldBe 2
    stats.totalVirtualNodes shouldBe 200
    stats.avgVirtualNodesPerNode shouldBe 100.0
    stats.stdDevVirtualNodes shouldBe 0.0 // all same size
