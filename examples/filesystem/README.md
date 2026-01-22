# Kraft Distributed Filesystem Demo

A powerful demonstration of distributed execution - each node accesses its **local** filesystem!

## The Key Insight

When you run commands:
- `ls 0 /home` - Lists files on Node 0's machine
- `ls 1 /home` - Lists files on Node 1's machine

Each node sees **different files** because they're on different computers!

## Setup

### 1. Start Filesystem Nodes

On **Machine A** (192.168.0.48):
```bash
sbt "runMain kraft.demos.FileSystemNode seed 7800"
```

On **Machine B** (192.168.0.130):
```bash
sbt "runMain kraft.demos.FileSystemNode join 7800 192.168.0.48:7800"
```

### 2. Run the Client

```bash
cd examples/filesystem
npm install
KRAFT_NODES=http://192.168.0.48:7800,http://192.168.0.130:7800 npm start
```

## Commands

| Command | Description |
|---------|-------------|
| `ls <node#> <path>` | List directory on specific node |
| `read <node#> <path>` | Read file from specific node |
| `write <node#> <path> <text>` | Write file to specific node |
| `exec <node#> <cmd>` | Execute shell command on node |
| `all-ls <path>` | List directory on ALL nodes |
| `all-exec <cmd>` | Execute command on ALL nodes |
| `nodes` | Show available nodes |

## Example Session

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                    Kraft Distributed Filesystem Demo                         ║
╚══════════════════════════════════════════════════════════════════════════════╝

Discovering nodes...

Available Nodes:
──────────────────────────────────────────────────────────────────────────────
  [fs-node-7800] MacBook-Pro.local
    Endpoint: http://192.168.0.48:7800
    Home:     /Users/miguel_lemos
  [fs-node-7800] MacBook-Air.local
    Endpoint: http://192.168.0.130:7800
    Home:     /Users/miguel_lemos
──────────────────────────────────────────────────────────────────────────────

kraft-fs> all-exec hostname
═══════════════════════════════════════════════════════════════════════════════

[fs-node-7800@MacBook-Pro.local]
MacBook-Pro.local

[fs-node-7800@MacBook-Air.local]
Miguels-MacBook-Air.local

═══════════════════════════════════════════════════════════════════════════════

kraft-fs> ls 0 /tmp
[fs-node-7800@MacBook-Pro.local] ls /tmp
──────────────────────────────────────────────────────────────────────────────
  📁 com.apple.launchd.xxx
  📄 kraft-seed.log
  ...
──────────────────────────────────────────────────────────────────────────────

kraft-fs> ls 1 /tmp
[fs-node-7800@MacBook-Air.local] ls /tmp
──────────────────────────────────────────────────────────────────────────────
  📁 com.apple.launchd.yyy
  📄 different-files-here.txt
  ...
──────────────────────────────────────────────────────────────────────────────
```

## What This Demonstrates

1. **True Distribution** - Each node runs on a different machine
2. **Local Execution** - Commands run where the node is, not where you are
3. **Network Transparency** - Same API regardless of which node handles it
4. **Use Cases**:
   - Distributed log collection
   - Multi-machine deployments
   - Cross-datacenter operations
   - Edge computing scenarios
