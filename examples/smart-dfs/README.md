# Smart Distributed Filesystem

A distributed filesystem with **RAID-like replication** and **smart placement** - like having a home media server that automatically manages where your files go!

## Features

| Feature | Description |
|---------|-------------|
| 🎯 **Smart Placement** | Files automatically go to nodes with the most free space |
| 🔄 **RAID-like Replication** | Store copies on multiple nodes for fault tolerance |
| 🔍 **Global Search** | Find files across all storage nodes instantly |
| 🌐 **Web UI** | Beautiful interface for browsing, searching, and uploading |
| ✅ **Checksum Verification** | Ensure data integrity across replicas |
| 🔄 **Automatic Failover** | Read from any replica if one node is down |

---

## Quick Start

### 1. Start Storage Nodes

```bash
docker-compose up -d
```

This starts 3 storage nodes:
- `storage-alpha` (port 7801)
- `storage-beta` (port 7802)
- `storage-gamma` (port 7803)

### 2. Start the Web UI

```bash
npm install
npm run server:local
```

Open **http://localhost:3000** in your browser!

### 3. Or Use the CLI

```bash
npm run start:local
```

---

## Web UI Screenshots

### Dashboard
```
╔══════════════════════════════════════════════════════════════════════════════╗
║              Smart DFS - Distributed File System                              ║
╚══════════════════════════════════════════════════════════════════════════════╝

┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│     🖥️      │  │     📄      │  │     🔄      │
│      3      │  │     42      │  │     2x      │
│ Storage     │  │ Total       │  │ Replication │
│ Nodes       │  │ Files       │  │             │
└─────────────┘  └─────────────┘  └─────────────┘

Storage Nodes:
┌──────────────────────────────────────────────────────────────────────────────┐
│ 💾 storage-alpha                                                              │
│    [████████░░░░░░░░░░░░] 40% used                                           │
│    Free: 60 GB / Total: 100 GB                                               │
├──────────────────────────────────────────────────────────────────────────────┤
│ 💾 storage-beta                                                               │
│    [████████████░░░░░░░░] 60% used                                           │
│    Free: 40 GB / Total: 100 GB                                               │
├──────────────────────────────────────────────────────────────────────────────┤
│ 💾 storage-gamma                                                              │
│    [████████████████░░░░] 80% used  ⚠️                                       │
│    Free: 20 GB / Total: 100 GB                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Search
```
🔍 Search: "movie"

Results:
┌──────────────────────────────────────────────────────────────────────────────┐
│ 📄 vacation-movie.mp4                                                        │
│    /data/vacation-movie.mp4 • 2.4 GB                     [storage-alpha]     │
├──────────────────────────────────────────────────────────────────────────────┤
│ 📄 birthday-movie.mp4                                                        │
│    /data/birthday-movie.mp4 • 1.8 GB                     [storage-beta]      │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## CLI Commands

```
smart-dfs> save vacation.txt This is my vacation notes!
Saving "vacation.txt" with replication factor 2...

✓ File saved successfully!
  Size:     28 B
  Checksum: a1b2c3d4e5f6...
  Replicas: 2
    → storage-alpha (smart-fs-7801)
    → storage-beta (smart-fs-7802)

smart-dfs> search vacation
Searching for "vacation" across all nodes...
═══════════════════════════════════════════════════════════════════════════════
  📄 vacation.txt
     Path: /data/vacation.txt
     Size: 28 B
     Node: storage-alpha

  📄 vacation.txt
     Path: /data/vacation.txt
     Size: 28 B
     Node: storage-beta

  Found 2 file(s)
═══════════════════════════════════════════════════════════════════════════════

smart-dfs> read vacation.txt
[Read from storage-alpha]
──────────────────────────────────────────────────────
This is my vacation notes!
──────────────────────────────────────────────────────
```

---

## How It Works

### Smart Placement

When you save a file, the system:

1. **Queries all nodes** for their disk space
2. **Sorts by free space** (most free first)
3. **Filters out full nodes** (< 10% free space)
4. **Writes to the top N nodes** (where N = replication factor)

```
File: movie.mp4 (2 GB)
Replication: 2

Node Status:
  storage-alpha: 60 GB free  ← Selected (most space)
  storage-beta:  40 GB free  ← Selected (second most)
  storage-gamma: 5 GB free   ✗ Skipped (< 10% free)

Result: File stored on alpha + beta
```

### RAID-like Replication

Similar to RAID-1 mirroring:
- Files are stored on multiple nodes
- Any replica can serve reads
- If one node dies, data is still available

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   alpha     │     │    beta     │     │   gamma     │
│ ┌─────────┐ │     │ ┌─────────┐ │     │             │
│ │movie.mp4│ │     │ │movie.mp4│ │     │  (no copy)  │
│ └─────────┘ │     │ └─────────┘ │     │             │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │
       └─────────┬─────────┘
                 │
         Both are identical
          (checksum verified)
```

### Automatic Failover

```
Read Request: movie.mp4

1. Try replica 1 (alpha) → ✓ Success? Return content
                          ✗ Failed? Continue to step 2

2. Try replica 2 (beta)  → ✓ Success? Return content
                          ✗ Failed? Continue to step 3

3. Search all nodes      → Find any copy and return
```

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KRAFT_NODES` | localhost:7801,7802,7803 | Comma-separated list of storage nodes |
| `REPLICATION_FACTOR` | 2 | How many copies of each file |
| `PORT` | 3000 | Web UI port |

### Replication Strategies

```bash
# No replication (fast, risky)
REPLICATION_FACTOR=1 npm run server:local

# Mirror (balanced)
REPLICATION_FACTOR=2 npm run server:local

# Triple redundancy (safest)
REPLICATION_FACTOR=3 npm run server:local
```

---

## Use Cases

### 🎬 Home Media Server
Store your movies and photos across multiple drives. If one drive fails, your memories are safe on another.

### 💾 Backup System
Automatically distribute backups across multiple machines. Search and retrieve any file instantly.

### 📊 Distributed Data Lake
Store large datasets across nodes. The system automatically balances storage usage.

### 🖥️ Multi-Machine Development
Share files across your development machines. Access the same files from any computer.

---

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │           Smart DFS Client          │
                    │  ┌──────────┐  ┌──────────────────┐ │
                    │  │ CLI/REPL │  │     Web UI       │ │
                    │  └──────────┘  └──────────────────┘ │
                    │         │              │            │
                    │         └──────┬───────┘            │
                    │                │                    │
                    │         ┌──────▼──────┐             │
                    │         │ SmartDFS    │             │
                    │         │ ─────────── │             │
                    │         │ • Placement │             │
                    │         │ • Replicate │             │
                    │         │ • Search    │             │
                    │         │ • Failover  │             │
                    │         └──────┬──────┘             │
                    └────────────────┼────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │  storage-alpha  │    │  storage-beta   │    │  storage-gamma  │
    │  ─────────────  │    │  ─────────────  │    │  ─────────────  │
    │  /fs/disk-info  │    │  /fs/disk-info  │    │  /fs/disk-info  │
    │  /fs/search     │    │  /fs/search     │    │  /fs/search     │
    │  /fs/write      │    │  /fs/write      │    │  /fs/write      │
    │  /fs/read       │    │  /fs/read       │    │  /fs/read       │
    │  /fs/delete     │    │  /fs/delete     │    │  /fs/delete     │
    │                 │    │                 │    │                 │
    │  ┌───────────┐  │    │  ┌───────────┐  │    │  ┌───────────┐  │
    │  │  /data    │  │    │  │  /data    │  │    │  │  /data    │  │
    │  │ (volume)  │  │    │  │ (volume)  │  │    │  │ (volume)  │  │
    │  └───────────┘  │    │  └───────────┘  │    │  └───────────┘  │
    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

---

## Cleanup

```bash
docker-compose down -v
```
