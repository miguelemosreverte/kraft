# Durable Execution Book

## Table of Contents

1. [Introduction](#chapter-1-introduction)
2. [Core Concepts](#chapter-2-core-concepts)
3. [Per-Node Architecture](#chapter-3-per-node-architecture)
4. [Cluster Protocol](#chapter-4-cluster-protocol)
5. [Workflow Routing](#chapter-5-workflow-routing)
6. [Failover and Recovery](#chapter-6-failover-and-recovery)
7. [Demonstrations](#chapter-7-demonstrations)

---

## Chapter 1: Introduction

Durable execution is a programming model that makes distributed systems reliable by automatically handling failures, retries, and recovery. Instead of manually coding failure handling, developers write normal code that the runtime makes durable.

### What is Durable Execution?

When a workflow fails—whether from network issues, process crashes, or server restarts—durable execution automatically resumes from where it left off. This is possible because:

1. **Journaling**: Every operation is recorded to persistent storage before execution
2. **Deterministic Replay**: On recovery, the journal is replayed to reconstruct state
3. **Idempotent Operations**: Side effects are executed exactly once

### Why Durable Execution?

Traditional approaches to reliability require:
- Manual retry logic
- Complex state machines
- Distributed transactions
- Compensating transactions (sagas)

Durable execution handles all of this automatically. You write:

```scala
def processOrder(ctx: Context, order: Order): Receipt = {
  val payment = ctx.call("processPayment", order.paymentInfo)
  val shipment = ctx.call("scheduleShipment", order.address)
  Receipt(payment.id, shipment.trackingNumber)
}
```

And the runtime ensures this completes exactly once, even across failures.

---

## Chapter 2: Core Concepts

### Workflows

A workflow is a long-running business process that survives failures. Workflows are:

- **Stateful**: Maintain state across calls and restarts
- **Durable**: Persist progress to storage
- **Deterministic**: Same inputs produce same outputs on replay

```scala
val orderWorkflow = Workflow[OrderInput, OrderOutput]("process-order") {
  (ctx, input) =>
    // Durable operations
    val validated = ctx.call("validateOrder", input)
    ctx.sleep(24.hours) // Durable timer!
    val shipped = ctx.call("shipOrder", validated)
    OrderOutput(shipped.trackingNumber)
}
```

### Journal

The journal is an append-only log of all operations a workflow has performed. Each entry includes:

| Field | Description |
|-------|-------------|
| `sequenceNumber` | Monotonically increasing position |
| `entryType` | Call, SideEffect, Sleep, State |
| `name` | Operation identifier |
| `inputJson` | Serialized input (if any) |
| `outputJson` | Serialized result (when complete) |
| `timestamp` | When the entry was created |
| `completed` | Whether the operation finished |

### Side Effects

Side effects are operations that interact with the outside world. They're recorded once and replayed from the journal:

```scala
ctx.sideEffect("sendEmail") {
  emailService.send(to = user.email, subject = "Order Confirmed")
}
```

On replay, the side effect isn't re-executed—the stored result is returned.

### Durable Timers

Timers that survive process restarts:

```scala
ctx.sleep(30.minutes)  // Process can crash and resume after 30min
```

The timer is recorded to storage. If the process crashes, recovery checks for pending timers and resumes workflows when timers expire.

---

## Chapter 3: Per-Node Architecture

### Embedded Storage (Restate-style)

Unlike traditional approaches with a central database, each node maintains its own embedded storage:

```
┌─────────────────────────────────────────────────┐
│                    Node 1                        │
│  ┌────────────┐  ┌────────────┐  ┌───────────┐  │
│  │ Workflows  │  │  Runtime   │  │  Storage  │  │
│  │  (active)  │──│            │──│ (RocksDB) │  │
│  └────────────┘  └────────────┘  └───────────┘  │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│                    Node 2                        │
│  ┌────────────┐  ┌────────────┐  ┌───────────┐  │
│  │ Workflows  │  │  Runtime   │  │  Storage  │  │
│  │  (active)  │──│            │──│ (LevelDB) │  │
│  └────────────┘  └────────────┘  └───────────┘  │
└─────────────────────────────────────────────────┘
```

Benefits:
- **No single point of failure**: Each node is independent
- **Low latency**: Local storage access
- **Horizontal scaling**: Add nodes without bottlenecks

### Storage Backends

The system supports multiple embedded storage backends:

| Backend | Use Case |
|---------|----------|
| RocksDB | Production - high performance |
| LevelDB | Lighter footprint |
| In-Memory | Testing |

```scala
// Production
val store = RocksDBStore.open("/data/node-1/durable")

// Testing
val store = InMemoryStore.open()

// Create storage abstraction
val storage = NodeStorage.make(store)
```

### NodeRuntime

The `NodeRuntime` manages workflow execution on a single node:

```scala
val runtime = NodeRuntime.make(
  storage = storage,
  config = NodeConfig(
    nodeId = "node-1",
    maxConcurrentWorkflows = 100
  )
)

// Register functions
runtime.register("processPayment") { (req: PaymentRequest) =>
  paymentService.charge(req)
}

// Submit workflows
val handle = runtime.submit(orderWorkflow, OrderInput("order-123"))
```

---

## Chapter 4: Cluster Protocol

### SWIM Gossip Protocol

The cluster uses SWIM (Scalable Weakly-consistent Infection-style Membership) for:

- **Membership detection**: Which nodes are in the cluster
- **Failure detection**: Identifying dead nodes
- **Information dissemination**: Spreading updates

#### How SWIM Works

1. Each node periodically pings a random member
2. If no response, indirect ping through other nodes
3. If still no response, mark as **Suspect**
4. After timeout, mark as **Dead**

```
Node A              Node B              Node C
   │                  │                   │
   │──── PING ───────→│                   │
   │                  │                   │
   │←─── ACK ─────────│                   │
   │                  │                   │
   │──── PING ───────────────────────────→│
   │                  │         (no ack)  │
   │──── PING-REQ ───→│                   │
   │                  │──── PING ────────→│
   │                  │         (no ack)  │
   │                  │──── NACK ────────→│
   │                  │                   │
   │  (mark C suspect)│                   │
```

### Seed Nodes

New nodes bootstrap by connecting to seed nodes:

```scala
val config = ClusterConfig(
  nodeId = NodeId("node-3"),
  bindAddress = NodeAddress("0.0.0.0", 7802),
  seedNodes = List(
    NodeAddress("seed1.cluster", 7800),
    NodeAddress("seed2.cluster", 7800)
  )
)
```

The join process:
1. Connect to seed node
2. Send `Join` message with node info
3. Receive `JoinAck` with full membership list
4. Add self to hash ring
5. Gossip propagates to other nodes

### Node States

| State | Meaning |
|-------|---------|
| `Alive` | Healthy and responsive |
| `Suspect` | Missed heartbeats, may be failing |
| `Dead` | Confirmed dead, removed from routing |
| `Left` | Graceful departure |

### Incarnation Numbers

To handle stale updates, each node has an incarnation number:
- Starts at 0
- Incremented when refuting suspicion
- Higher incarnation always wins in conflicts

---

## Chapter 5: Workflow Routing

### Consistent Hashing

Workflows are assigned to nodes using consistent hashing:

```
Hash Ring:
      ┌─────────────────────────────────────┐
      │                                     │
   node-1                               node-2
      │                                     │
      │     workflow-123 hashes here        │
      │            ↓                        │
      └────────────●────────────────────────┘
                   │
                node-3

workflow-123 → node-3 (first node clockwise)
```

### Virtual Nodes

Each physical node creates multiple virtual nodes (default: 150) for uniform distribution:

```scala
val ring = HashRing(virtualNodesPerNode = 150)
ring.addNode(NodeId("node-1"))  // Creates 150 positions
ring.addNode(NodeId("node-2"))  // Creates 150 more
ring.addNode(NodeId("node-3"))  // Creates 150 more
// Total: 450 virtual nodes on ring
```

### Replication

For critical workflows, multiple replicas can be assigned:

```scala
val replicas = ring.getNodes("critical-workflow", count = 3)
// Returns: [node-2, node-3, node-1] in ring order
```

### Routing Logic

```scala
def submit(workflowId: String, input: Input): Handle = {
  val owner = hashRing.getNode(workflowId)

  if (owner == localNodeId) {
    // Execute locally
    localRuntime.submit(workflow, input, workflowId)
  } else {
    // Forward to remote node
    remoteExecutor.submitWorkflow(owner, workflowId, input)
  }
}
```

---

## Chapter 6: Failover and Recovery

### Node Failure Detection

When a node fails:
1. Gossip protocol detects missed heartbeats
2. Node marked as Suspect
3. After timeout, marked as Dead
4. Dead node removed from hash ring
5. Workflows re-routed to new owners

### Workflow Migration

When the hash ring changes, workflows may need to migrate:

```
Before: workflow-123 → node-2
After:  workflow-123 → node-3 (node-2 died)

Migration:
1. node-3 notices it now owns workflow-123
2. Requests journal from replicas
3. Replays journal to reconstruct state
4. Resumes execution
```

### Recovery Process

```
1. Load WorkflowMetadata from storage
2. Load JournalEntries for workflow
3. Create new Context with replay mode
4. Execute workflow handler
   - For each operation:
     - If in journal: return stored result
     - If not in journal: execute and record
5. Continue from where it left off
```

### Exactly-Once Semantics

The combination of journaling and replay ensures:
- Operations execute exactly once
- Side effects are recorded and replayed
- External calls use idempotency keys

---

## Chapter 7: Demonstrations

The following demonstrations showcase durable execution patterns.

### Demo 1: Ping-Pong

Two workflows exchanging messages back and forth.

<!-- @demo:num="1" -->

---

### Demo 2: Chat Session

A conversation with multiple participants exchanging messages.

<!-- @demo:num="2" -->

---

### Demo 3: Remote Commands

Executing commands on remote services with results.

<!-- @demo:num="3" -->

---

### Demo 4: Durable Counter

A counter that survives crashes, demonstrating durable state.

<!-- @demo:num="4" -->

---

### Demo 5: Saga Pattern

Multi-step transactions with automatic compensation on failure.

<!-- @demo:num="5" -->

---

### Demo 6: Durable Timer

Timers that survive process restarts.

<!-- @demo:num="6" -->

---

### Demo 7: State Machine

Order processing with state transitions.

<!-- @demo:num="7" -->

---

### Demo 8: Parallel Fan-Out

Distributing work to parallel workers and collecting results.

<!-- @demo:num="8" -->

---

### Demo 9: Automatic Retry

Retrying on transient failures with exponential backoff.

<!-- @demo:num="9" -->

---

### Demo 10: Checkpoint Recovery

Checkpointing progress and resuming after crashes.

<!-- @demo:num="10" -->

---

### Demo 11: Multi-Node Cluster Formation

Cluster formation using SWIM gossip, seed nodes, and automatic node discovery.

<!-- @demo:num="11" -->

---

### Demo 12: Distributed Workflow Execution

Workflows routed to and executed on specific nodes via consistent hashing.

<!-- @demo:num="12" -->

---

### Demo 13: Node Failure and Rebalancing

What happens when a node fails: detection, rerouting, and recovery.

<!-- @demo:num="13" -->

---

## Appendix: Configuration Reference

### Cluster Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `gossipInterval` | 1s | How often to ping random nodes |
| `suspectTimeout` | 5s | How long before suspect becomes dead |
| `deadTimeout` | 30s | How long to keep dead nodes in list |
| `virtualNodesPerNode` | 150 | Virtual nodes per physical node |
| `rpcTimeout` | 30s | Timeout for RPC calls |

### Environment Variables

```bash
CLUSTER_NODE_ID=node-1
CLUSTER_BIND_HOST=0.0.0.0
CLUSTER_BIND_PORT=7800
CLUSTER_SEED_NODES=seed1:7800,seed2:7800
```

---

*This book was auto-generated. Demo outputs are captured from actual program execution.*
