/**
 * Smart DFS CLI
 *
 * Interactive command-line interface for the Smart Distributed Filesystem.
 */

import * as readline from 'readline';
import { SmartDFS, formatBytes, formatPercent } from './smart-dfs.js';

async function main() {
  const nodesEnv = process.env.KRAFT_NODES;
  const endpoints = nodesEnv
    ? nodesEnv.split(',').map(n => n.trim())
    : ['http://localhost:7801', 'http://localhost:7802', 'http://localhost:7803'];

  const replicationFactor = parseInt(process.env.REPLICATION_FACTOR || '2');

  console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
  console.log('║              Smart Distributed Filesystem (with RAID-like replication)       ║');
  console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  console.log();

  const dfs = new SmartDFS(endpoints, {
    replicationFactor,
    minFreeSpacePercent: 10
  });

  // Discover nodes
  console.log('Discovering nodes...');
  const nodes = await dfs.discoverNodes();

  if (nodes.length === 0) {
    console.log('No nodes found! Make sure the cluster is running.');
    process.exit(1);
  }

  // Show nodes with disk info
  console.log();
  console.log('Available Storage Nodes:');
  console.log('─'.repeat(78));
  const diskInfo = await dfs.getDiskInfo();
  for (const { endpoint, disk } of diskInfo) {
    const freePercent = 100 - disk.usagePercent;
    const bar = '█'.repeat(Math.floor(disk.usagePercent / 5)) + '░'.repeat(20 - Math.floor(disk.usagePercent / 5));
    console.log(`  [${disk.nodeId}] ${disk.hostname}`);
    console.log(`    Storage: ${disk.storagePath}`);
    console.log(`    Space:   [${bar}] ${formatPercent(disk.usagePercent)} used`);
    console.log(`    Free:    ${formatBytes(disk.freeSpace)} / ${formatBytes(disk.totalSpace)}`);
    console.log();
  }
  console.log('─'.repeat(78));
  console.log(`  Replication Factor: ${replicationFactor} (files stored on ${replicationFactor} nodes)`);
  console.log('─'.repeat(78));
  console.log();

  // Interactive REPL
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
  });

  console.log('Commands:');
  console.log('  save <filename> <content>  - Save file (auto-placed on best nodes)');
  console.log('  read <filename>            - Read file (auto-failover to replicas)');
  console.log('  delete <filename>          - Delete file from all replicas');
  console.log('  search <pattern>           - Search files across all nodes');
  console.log('  ls                         - List all files on all nodes');
  console.log('  ls <node#>                 - List files on specific node');
  console.log('  disk                       - Show disk space on all nodes');
  console.log('  files                      - Show tracked files with replicas');
  console.log('  nodes                      - Show available nodes');
  console.log('  exit                       - Quit');
  console.log();

  const prompt = () => {
    rl.question('smart-dfs> ', async (input) => {
      const parts = input.trim().split(' ');
      const cmd = parts[0];

      try {
        switch (cmd) {
          case 'save': {
            const filename = parts[1];
            const content = parts.slice(2).join(' ');
            if (!filename || !content) {
              console.log('Usage: save <filename> <content>');
              break;
            }

            console.log();
            console.log(`Saving "${filename}" with replication factor ${replicationFactor}...`);

            const stored = await dfs.save(filename, content);
            console.log();
            console.log('✓ File saved successfully!');
            console.log(`  Size:     ${formatBytes(stored.size)}`);
            console.log(`  Checksum: ${stored.checksum}`);
            console.log(`  Replicas: ${stored.replicas.length}`);
            for (const replica of stored.replicas) {
              console.log(`    → ${replica.hostname} (${replica.nodeId})`);
            }
            console.log();
            break;
          }

          case 'read': {
            const filename = parts[1];
            if (!filename) {
              console.log('Usage: read <filename>');
              break;
            }

            console.log();
            const { content, fromNode } = await dfs.read(filename);
            console.log(`[Read from ${fromNode}]`);
            console.log('─'.repeat(50));
            console.log(content);
            console.log('─'.repeat(50));
            console.log();
            break;
          }

          case 'delete': {
            const filename = parts[1];
            if (!filename) {
              console.log('Usage: delete <filename>');
              break;
            }

            console.log();
            const { deleted, failed } = await dfs.delete(filename);
            if (deleted.length > 0) {
              console.log(`✓ Deleted from: ${deleted.join(', ')}`);
            }
            if (failed.length > 0) {
              console.log(`✗ Failed on: ${failed.join(', ')}`);
            }
            if (deleted.length === 0 && failed.length === 0) {
              console.log('File not found on any node');
            }
            console.log();
            break;
          }

          case 'search': {
            const pattern = parts.slice(1).join(' ');
            if (!pattern) {
              console.log('Usage: search <pattern>');
              break;
            }

            console.log();
            console.log(`Searching for "${pattern}" across all nodes...`);
            console.log('═'.repeat(78));

            const results = await dfs.search(pattern);
            if (results.length === 0) {
              console.log('  No files found');
            } else {
              for (const file of results) {
                console.log(`  📄 ${file.name}`);
                console.log(`     Path: ${file.path}`);
                console.log(`     Size: ${formatBytes(file.size)}`);
                console.log(`     Node: ${file.hostname}`);
                console.log();
              }
              console.log(`  Found ${results.length} file(s)`);
            }
            console.log('═'.repeat(78));
            console.log();
            break;
          }

          case 'ls': {
            const nodeIdx = parts[1] ? parseInt(parts[1]) : undefined;

            console.log();
            if (nodeIdx !== undefined) {
              const result = await dfs.listNode(nodeIdx);
              console.log(`[${result.nodeId}@${result.hostname}] ${result.path}`);
              console.log('─'.repeat(50));
              for (const f of result.files) {
                const icon = f.isDirectory ? '📁' : '📄';
                console.log(`  ${icon} ${f.name.padEnd(30)} ${formatBytes(f.size)}`);
              }
              console.log('─'.repeat(50));
              console.log(`  ${result.files.length} items`);
            } else {
              const allFiles = await dfs.listAll();
              console.log('Files across all nodes:');
              console.log('═'.repeat(78));
              for (const { node, files } of allFiles) {
                console.log();
                console.log(`[${node}]`);
                if (files.length === 0) {
                  console.log('  (empty)');
                } else {
                  for (const f of files.slice(0, 10)) {
                    const icon = f.isDirectory ? '📁' : '📄';
                    console.log(`  ${icon} ${f.name}`);
                  }
                  if (files.length > 10) {
                    console.log(`  ... and ${files.length - 10} more`);
                  }
                }
              }
              console.log('═'.repeat(78));
            }
            console.log();
            break;
          }

          case 'disk': {
            console.log();
            console.log('Disk Space:');
            console.log('═'.repeat(78));
            const diskInfo = await dfs.getDiskInfo();
            for (const { disk } of diskInfo) {
              const bar = '█'.repeat(Math.floor(disk.usagePercent / 5)) + '░'.repeat(20 - Math.floor(disk.usagePercent / 5));
              console.log(`  ${disk.hostname}:`);
              console.log(`    [${bar}] ${formatPercent(disk.usagePercent)} used`);
              console.log(`    Free: ${formatBytes(disk.freeSpace)} / Total: ${formatBytes(disk.totalSpace)}`);
              console.log();
            }
            console.log('═'.repeat(78));
            console.log();
            break;
          }

          case 'files': {
            console.log();
            const fileIndex = dfs.getFileIndex();
            if (fileIndex.size === 0) {
              console.log('No tracked files (files saved in this session)');
            } else {
              console.log('Tracked Files:');
              console.log('═'.repeat(78));
              for (const [filename, stored] of fileIndex) {
                console.log(`  📄 ${filename}`);
                console.log(`     Size: ${formatBytes(stored.size)}, Checksum: ${stored.checksum}`);
                console.log(`     Replicas: ${stored.replicas.map(r => r.hostname).join(', ')}`);
                console.log();
              }
              console.log('═'.repeat(78));
            }
            console.log();
            break;
          }

          case 'nodes': {
            console.log();
            console.log('Available Nodes:');
            const nodes = dfs.getNodes();
            for (let i = 0; i < nodes.length; i++) {
              const { endpoint, info } = nodes[i];
              console.log(`  [${i}] ${info.nodeId} @ ${info.hostname}`);
              console.log(`      ${endpoint}`);
            }
            console.log();
            break;
          }

          case 'exit':
          case 'quit':
            rl.close();
            process.exit(0);

          case '':
            break;

          default:
            console.log(`Unknown command: ${cmd}`);
            console.log('Type "help" for available commands');
        }
      } catch (e) {
        console.log(`Error: ${(e as Error).message}`);
      }

      prompt();
    });
  };

  prompt();
}

main().catch(console.error);
