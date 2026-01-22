#!/usr/bin/env npx tsx
/**
 * Smart DFS CLI Tool
 *
 * A command-line tool for interacting with the Smart Distributed Filesystem.
 * Can be used in scripts or by AI assistants.
 *
 * Usage:
 *   npx tsx cli.ts <command> [options]
 *
 * Commands:
 *   save <file> [--replicas=N]   - Save a file to the DFS
 *   read <filename>              - Read a file from the DFS
 *   delete <filename>            - Delete a file from all nodes
 *   search <pattern>             - Search for files across all nodes
 *   ls [node]                    - List files (all nodes or specific node)
 *   disk                         - Show disk space on all nodes
 *   nodes                        - List available nodes
 *   upload <local-path> [--replicas=N] [--name=<name>] - Upload a local file
 *
 * Examples:
 *   npx tsx cli.ts nodes
 *   npx tsx cli.ts disk
 *   npx tsx cli.ts save myfile.txt "Hello World" --replicas=3
 *   npx tsx cli.ts upload ./movie.mp4 --replicas=2
 *   npx tsx cli.ts search movie
 *   npx tsx cli.ts read myfile.txt
 *   npx tsx cli.ts ls
 *   npx tsx cli.ts delete myfile.txt
 */

import * as fs from 'fs';
import * as path from 'path';
import { SmartDFS, formatBytes, formatPercent } from './smart-dfs.js';

// Parse arguments
const args = process.argv.slice(2);
const command = args[0];

// Parse options
function parseOptions(args: string[]): Record<string, string> {
  const options: Record<string, string> = {};
  for (const arg of args) {
    if (arg.startsWith('--')) {
      const [key, value] = arg.slice(2).split('=');
      options[key] = value || 'true';
    }
  }
  return options;
}

// Get positional arguments (non-option arguments)
function getPositionalArgs(args: string[]): string[] {
  return args.filter(arg => !arg.startsWith('--'));
}

const options = parseOptions(args);
const positionalArgs = getPositionalArgs(args);

// Environment configuration
const nodesEnv = process.env.KRAFT_NODES;
const endpoints = nodesEnv
  ? nodesEnv.split(',').map(n => n.trim())
  : ['http://localhost:7801', 'http://localhost:7802', 'http://localhost:7803'];

const defaultReplicas = parseInt(process.env.REPLICATION_FACTOR || '2');

async function main() {
  const dfs = new SmartDFS(endpoints, {
    replicationFactor: defaultReplicas,
    minFreeSpacePercent: 10
  });

  // Discover nodes first
  await dfs.discoverNodes();
  const nodes = dfs.getNodes();

  if (nodes.length === 0 && command !== 'help') {
    console.error('ERROR: No storage nodes available');
    console.error('Make sure the cluster is running:');
    console.error('  docker-compose up -d');
    process.exit(1);
  }

  switch (command) {
    case 'nodes': {
      console.log('NODES:');
      for (let i = 0; i < nodes.length; i++) {
        const { endpoint, info } = nodes[i];
        console.log(`  [${i}] ${info.nodeId} @ ${info.hostname}`);
        console.log(`      endpoint: ${endpoint}`);
        console.log(`      storage: ${info.storagePath}`);
      }
      break;
    }

    case 'disk': {
      console.log('DISK SPACE:');
      const diskInfo = await dfs.getDiskInfo();
      for (const { disk } of diskInfo) {
        const freePercent = 100 - disk.usagePercent;
        const status = freePercent < 10 ? 'CRITICAL' : freePercent < 30 ? 'WARNING' : 'OK';
        console.log(`  ${disk.hostname}:`);
        console.log(`    used: ${formatPercent(disk.usagePercent)} (${formatBytes(disk.usedSpace)})`);
        console.log(`    free: ${formatBytes(disk.freeSpace)} / ${formatBytes(disk.totalSpace)}`);
        console.log(`    status: ${status}`);
      }
      break;
    }

    case 'save': {
      const filename = positionalArgs[1];
      const content = positionalArgs.slice(2).join(' ');
      const replicas = parseInt(options.replicas || String(defaultReplicas));

      if (!filename || !content) {
        console.error('Usage: save <filename> <content> [--replicas=N]');
        process.exit(1);
      }

      // Temporarily override replication factor
      const customDfs = new SmartDFS(endpoints, {
        replicationFactor: replicas,
        minFreeSpacePercent: 10
      });
      await customDfs.discoverNodes();

      const stored = await customDfs.save(filename, content);
      console.log('SAVED:');
      console.log(`  filename: ${stored.filename}`);
      console.log(`  size: ${formatBytes(stored.size)}`);
      console.log(`  checksum: ${stored.checksum}`);
      console.log(`  replicas: ${stored.replicas.length}`);
      for (const replica of stored.replicas) {
        console.log(`    - ${replica.hostname}`);
      }
      break;
    }

    case 'upload': {
      const localPath = positionalArgs[1];
      const replicas = parseInt(options.replicas || String(defaultReplicas));
      const customName = options.name;

      if (!localPath) {
        console.error('Usage: upload <local-path> [--replicas=N] [--name=<name>]');
        process.exit(1);
      }

      if (!fs.existsSync(localPath)) {
        console.error(`ERROR: File not found: ${localPath}`);
        process.exit(1);
      }

      const filename = customName || path.basename(localPath);
      const content = fs.readFileSync(localPath, 'utf-8');

      const customDfs = new SmartDFS(endpoints, {
        replicationFactor: replicas,
        minFreeSpacePercent: 10
      });
      await customDfs.discoverNodes();

      const stored = await customDfs.save(filename, content);
      console.log('UPLOADED:');
      console.log(`  local: ${localPath}`);
      console.log(`  remote: ${stored.filename}`);
      console.log(`  size: ${formatBytes(stored.size)}`);
      console.log(`  checksum: ${stored.checksum}`);
      console.log(`  replicas: ${stored.replicas.length}`);
      for (const replica of stored.replicas) {
        console.log(`    - ${replica.hostname}`);
      }
      break;
    }

    case 'read': {
      const filename = positionalArgs[1];

      if (!filename) {
        console.error('Usage: read <filename>');
        process.exit(1);
      }

      try {
        const { content, fromNode } = await dfs.read(filename);
        console.log(`READ FROM: ${fromNode}`);
        console.log('---');
        console.log(content);
      } catch (e) {
        console.error(`ERROR: ${(e as Error).message}`);
        process.exit(1);
      }
      break;
    }

    case 'delete': {
      const filename = positionalArgs[1];

      if (!filename) {
        console.error('Usage: delete <filename>');
        process.exit(1);
      }

      const { deleted, failed } = await dfs.delete(filename);
      console.log('DELETED:');
      if (deleted.length > 0) {
        console.log(`  success: ${deleted.join(', ')}`);
      }
      if (failed.length > 0) {
        console.log(`  failed: ${failed.join(', ')}`);
      }
      if (deleted.length === 0 && failed.length === 0) {
        console.log('  (file not found on any node)');
      }
      break;
    }

    case 'search': {
      const pattern = positionalArgs.slice(1).join(' ');

      if (!pattern) {
        console.error('Usage: search <pattern>');
        process.exit(1);
      }

      const results = await dfs.search(pattern);
      console.log(`SEARCH RESULTS FOR: "${pattern}"`);
      console.log(`FOUND: ${results.length} file(s)`);
      if (results.length > 0) {
        console.log('---');
        for (const file of results) {
          console.log(`  ${file.name}`);
          console.log(`    path: ${file.path}`);
          console.log(`    size: ${formatBytes(file.size)}`);
          console.log(`    node: ${file.hostname}`);
        }
      }
      break;
    }

    case 'ls': {
      const nodeArg = positionalArgs[1];

      if (nodeArg !== undefined) {
        const nodeIdx = parseInt(nodeArg);
        if (isNaN(nodeIdx) || nodeIdx >= nodes.length) {
          console.error(`ERROR: Invalid node index. Use 0-${nodes.length - 1}`);
          process.exit(1);
        }
        const result = await dfs.listNode(nodeIdx);
        console.log(`FILES ON ${result.hostname}:`);
        console.log(`PATH: ${result.path}`);
        console.log('---');
        for (const file of result.files) {
          const type = file.isDirectory ? 'DIR' : 'FILE';
          console.log(`  [${type}] ${file.name} (${formatBytes(file.size)})`);
        }
        console.log(`TOTAL: ${result.files.length} items`);
      } else {
        const allFiles = await dfs.listAll();
        console.log('FILES ACROSS ALL NODES:');
        for (const { node, files } of allFiles) {
          console.log(`\n  [${node}]`);
          if (files.length === 0) {
            console.log('    (empty)');
          } else {
            for (const file of files) {
              if (!file.isDirectory) {
                console.log(`    ${file.name} (${formatBytes(file.size)})`);
              }
            }
          }
        }
      }
      break;
    }

    case 'help':
    case '--help':
    case '-h':
    case undefined: {
      console.log(`
Smart DFS CLI - Distributed Filesystem with RAID-like Replication

USAGE:
  npx tsx cli.ts <command> [options]

COMMANDS:
  nodes                              List available storage nodes
  disk                               Show disk space on all nodes
  save <name> <content> [--replicas=N]   Save text content as a file
  upload <path> [--replicas=N] [--name=X]  Upload a local file
  read <filename>                    Read a file from the DFS
  delete <filename>                  Delete a file from all nodes
  search <pattern>                   Search for files across all nodes
  ls [node-index]                    List files (all nodes or specific)
  help                               Show this help message

OPTIONS:
  --replicas=N    Number of copies to store (default: ${defaultReplicas})
  --name=X        Custom filename for upload

ENVIRONMENT:
  KRAFT_NODES         Comma-separated list of node URLs
  REPLICATION_FACTOR  Default replication factor

EXAMPLES:
  npx tsx cli.ts nodes
  npx tsx cli.ts disk
  npx tsx cli.ts save notes.txt "My important notes" --replicas=3
  npx tsx cli.ts upload ./photo.jpg --replicas=2
  npx tsx cli.ts search photo
  npx tsx cli.ts read notes.txt
  npx tsx cli.ts ls
  npx tsx cli.ts ls 0
  npx tsx cli.ts delete notes.txt
`);
      break;
    }

    default:
      console.error(`Unknown command: ${command}`);
      console.error('Run with --help for usage information');
      process.exit(1);
  }
}

main().catch(e => {
  console.error(`ERROR: ${e.message}`);
  process.exit(1);
});
