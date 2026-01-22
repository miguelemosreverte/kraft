# Kraft Cluster Health Monitor

A real-time dashboard that monitors multiple Kraft cluster nodes.

## Features

- Live health status from all nodes
- Response latency tracking
- Automatic failure detection
- Gossip member count

## Running

### Monitor Remote Cluster

```bash
cd examples/cluster-health
npm install
npm run start:remote
```

### Monitor Custom Nodes

```bash
KRAFT_NODES="http://node1:8800,http://node2:8801,http://node3:8802" npm start
```

### Monitor Local Cluster

```bash
npm run start:local
```

## Expected Output

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                        Kraft Cluster Health Monitor                          ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  Last Update: 12:34:56 AM                                                    ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  Cluster: 2/2 endpoints reachable, 2 gossip members                          ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  ✓ http://192.168.0.130:8800     HEALTHY      seed-7800         12ms 0 workflows║
║  ✓ http://192.168.0.130:8801     HEALTHY      worker-7801        8ms 0 workflows║
╠══════════════════════════════════════════════════════════════════════════════╣
║  Press Ctrl+C to exit                                                        ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## Try It

1. Run this monitor on one machine
2. Start/stop cluster nodes on another machine
3. Watch the monitor detect changes in real-time!

## What This Shows

- **Multi-node Monitoring** - Watch all cluster endpoints simultaneously
- **Failure Detection** - See when nodes become unreachable
- **Gossip Membership** - Track how many nodes are in the gossip network
- **Latency Tracking** - Monitor response times across nodes
