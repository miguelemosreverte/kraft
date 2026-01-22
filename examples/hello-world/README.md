# Kraft Hello World Example

A simple example showing how to connect to a Kraft cluster and submit workflows.

## Prerequisites

1. A running Kraft cluster (see main README)
2. Node.js 18+ installed

## Running

### Quick Start (uses localhost:8800)

```bash
cd examples/hello-world
npm install
npm start
```

### Connect to Remote Cluster

```bash
# Connect to seed node
KRAFT_ENDPOINT=http://192.168.0.130:8800 npm start

# Connect to worker node
KRAFT_ENDPOINT=http://192.168.0.130:8801 npm start
```

### With Deno (no npm install needed)

```bash
deno run --allow-net --allow-env index.ts
```

## Expected Output

```
╔════════════════════════════════════════════════════════════╗
║              Kraft Hello World Demo                        ║
╚════════════════════════════════════════════════════════════╝

Connecting to: http://192.168.0.130:8800

✓ Cluster Health:
  Status:     healthy
  Node ID:    seed-7800
  Nodes:      2
  Workflows:  0

Submitting workflow...
✓ Workflow submitted: wf-1737507600000
  Status: pending

Getting workflow info...
✓ Workflow info: {
  "workflowId": "wf-1737507600000",
  "name": "hello",
  "status": "running",
  ...
}

Done!
```

## What This Shows

1. **Cluster Connection** - Connect to any node in the cluster
2. **Health Check** - See cluster status and node count
3. **Workflow Submission** - Submit a workflow to be executed
4. **Workflow Tracking** - Query workflow status
