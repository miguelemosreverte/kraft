# Kraft Workflow Routing Demo

Demonstrates how consistent hashing distributes workflows across cluster nodes.

## Concepts

Kraft uses **consistent hashing** with virtual nodes to route workflows:

- Each workflow ID is hashed to determine its "owner" node
- Same ID always routes to the same node (deterministic)
- Adding/removing nodes only moves ~1/N of workflows (minimal disruption)
- Virtual nodes ensure even distribution

## Running

```bash
cd examples/workflow-routing
npm install
npm run start:remote
```

## Expected Output

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                   Kraft Workflow Routing Demo                                ║
╚══════════════════════════════════════════════════════════════════════════════╝

Checking cluster health...
  ✓ 8800: seed-7800 (2 nodes in cluster)
  ✓ 8801: worker-7801 (2 nodes in cluster)

Submitting workflows to see routing distribution...
──────────────────────────────────────────────────────────────────────────────────
  Workflow ID                    Submitted Via    Hash         Predicted Node
──────────────────────────────────────────────────────────────────────────────────
  order-001                      8800             1234567890   node-0
  order-002                      8800             1234567891   node-1
  user-signup-abc                8800             987654321    node-1
  ...
──────────────────────────────────────────────────────────────────────────────────

Routing Distribution (based on consistent hashing):
──────────────────────────────────────────────────────────────────────────────────
  Node 0: 5 workflows
    - order-001
    - payment-100
    ...
  Node 1: 5 workflows
    - order-002
    - user-signup-abc
    ...
──────────────────────────────────────────────────────────────────────────────────
```

## Try It

1. **Submit same workflow ID multiple times** - It always goes to the same node
2. **Add a third node** - Only ~1/3 of workflows would move
3. **Use different ID patterns** - See how hashing distributes them

## Why Consistent Hashing?

| Approach | Node Addition | Node Removal |
|----------|---------------|--------------|
| Simple modulo | All workflows move | All workflows move |
| Consistent hash | ~1/N workflows move | ~1/N workflows move |

This enables:
- **Caching efficiency** - State stays local
- **Scalability** - Add nodes without full rebalance
- **Predictability** - Same input → same node
