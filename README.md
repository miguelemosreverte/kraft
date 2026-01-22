# Kraft

Durable workflow execution with clustering support. Like Temporal/Restate, but in Scala.

## Features

- **Durable Workflows** - Automatic journaling, exactly-once execution, crash recovery
- **Cluster Support** - SWIM gossip protocol, consistent hashing, automatic failover
- **HTTP DSL** - Type-safe routing with WebSocket, GraphQL, and gRPC support
- **High Performance** - Netty with io_uring support, 500K+ RPS

## Quick Start

```bash
# Run a single node
sbt "runMain kraft.Main"

# Run cluster demos
sbt "runMain kraft.demos.Demo11"  # Cluster formation
sbt "runMain kraft.demos.Demo12"  # Distributed workflows
sbt "runMain kraft.demos.Demo13"  # Failover demo
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Kraft Cluster                        │
├─────────────────┬─────────────────┬─────────────────────┤
│     Node 1      │     Node 2      │      Node 3         │
│  ┌───────────┐  │  ┌───────────┐  │  ┌───────────┐      │
│  │ Workflows │  │  │ Workflows │  │  │ Workflows │      │
│  │ (local)   │  │  │ (local)   │  │  │ (local)   │      │
│  └─────┬─────┘  │  └─────┬─────┘  │  └─────┬─────┘      │
│        │        │        │        │        │            │
│  ┌─────┴─────┐  │  ┌─────┴─────┐  │  ┌─────┴─────┐      │
│  │  Runtime  │  │  │  Runtime  │  │  │  Runtime  │      │
│  └─────┬─────┘  │  └─────┬─────┘  │  └─────┬─────┘      │
│        │        │        │        │        │            │
│  ┌─────┴─────┐  │  ┌─────┴─────┐  │  ┌─────┴─────┐      │
│  │  Storage  │  │  │  Storage  │  │  │  Storage  │      │
│  │ (RocksDB) │  │  │ (RocksDB) │  │  │ (RocksDB) │      │
│  └───────────┘  │  └───────────┘  │  └───────────┘      │
└────────┬────────┴────────┬────────┴────────┬────────────┘
         │                 │                 │
         └────── SWIM Gossip + HTTP RPC ─────┘
```

## Example Workflow

```scala
import kraft.dsl.durable.runtime.NodeRuntime.*

val orderWorkflow = Workflow[OrderInput, OrderResult]("process-order") { (ctx, order) =>
  // Durable call - survives crashes
  val validated = ctx.call("validateOrder", order)

  // Durable sleep - process can restart
  ctx.sleep(1.hour)

  // Side effect - exactly-once execution
  ctx.sideEffect("sendEmail") {
    emailService.send(order.customerEmail)
  }

  OrderResult(order.id, "COMPLETED")
}
```

## Cluster Configuration

```scala
val config = ClusterConfig(
  nodeId = NodeId("node-1"),
  bindAddress = NodeAddress("0.0.0.0", 7800),
  seedNodes = List(
    NodeAddress("seed1.cluster", 7800),
    NodeAddress("seed2.cluster", 7800)
  )
)

val runtime = ClusterRuntime(config, storage)
runtime.start()
```

## Documentation

- [Durable Execution Book](docs/durable/book.md) - Workflows, journaling, clustering
- [DSL Book](docs/dsl/book.md) - HTTP routing, WebSocket, GraphQL, gRPC

## License

MIT
