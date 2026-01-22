/**
 * Kraft Filesystem Demo
 *
 * This example demonstrates distributed filesystem operations:
 * - Each node has access to its LOCAL filesystem
 * - See which node handles your request based on consistent hashing
 * - Run `ls`, `read`, `write` operations across the cluster
 *
 * Run with: npx tsx index.ts
 */

import * as readline from 'readline';

interface FileInfo {
  name: string;
  isDirectory: boolean;
  size: number;
}

interface LsResponse {
  nodeId: string;
  hostname: string;
  path: string;
  files: FileInfo[];
  error?: string;
}

interface ReadResponse {
  nodeId: string;
  hostname: string;
  path: string;
  content: string;
  error?: string;
}

interface WriteResponse {
  nodeId: string;
  hostname: string;
  path: string;
  bytesWritten: number;
  error?: string;
}

interface ExecResponse {
  nodeId: string;
  hostname: string;
  command: string;
  output: string;
  exitCode: number;
  error?: string;
}

interface NodeInfo {
  nodeId: string;
  hostname: string;
  cwd: string;
  home: string;
}

class FileSystemClient {
  constructor(private endpoints: string[]) {}

  private async request<T>(endpoint: string, path: string, body?: unknown): Promise<T> {
    const url = endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;
    const res = await fetch(`${url}${path}`, {
      method: body ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    return res.json();
  }

  async getInfo(endpoint: string): Promise<NodeInfo | null> {
    try {
      return await this.request<NodeInfo>(endpoint, '/fs/info');
    } catch {
      return null;
    }
  }

  async ls(endpoint: string, path: string): Promise<LsResponse> {
    return this.request<LsResponse>(endpoint, '/fs/ls', { path });
  }

  async read(endpoint: string, path: string): Promise<ReadResponse> {
    return this.request<ReadResponse>(endpoint, '/fs/read', { path });
  }

  async write(endpoint: string, path: string, content: string): Promise<WriteResponse> {
    return this.request<WriteResponse>(endpoint, '/fs/write', { path, content });
  }

  async exec(endpoint: string, command: string): Promise<ExecResponse> {
    return this.request<ExecResponse>(endpoint, '/fs/exec', { command });
  }

  // Get all available nodes
  async discoverNodes(): Promise<{ endpoint: string; info: NodeInfo }[]> {
    const results: { endpoint: string; info: NodeInfo }[] = [];
    for (const endpoint of this.endpoints) {
      const info = await this.getInfo(endpoint);
      if (info) {
        results.push({ endpoint, info });
      }
    }
    return results;
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}K`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}M`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)}G`;
}

async function main() {
  const nodesEnv = process.env.KRAFT_NODES;
  const endpoints = nodesEnv
    ? nodesEnv.split(',').map(n => n.trim())
    : ['http://192.168.0.130:7800', 'http://192.168.0.130:7801'];

  console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
  console.log('║                    Kraft Distributed Filesystem Demo                         ║');
  console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  console.log();

  const client = new FileSystemClient(endpoints);

  // Discover nodes
  console.log('Discovering nodes...');
  const nodes = await client.discoverNodes();

  if (nodes.length === 0) {
    console.log('No nodes found! Make sure the cluster is running:');
    console.log('  sbt "runMain kraft.demos.FileSystemNode seed 7800"');
    process.exit(1);
  }

  console.log();
  console.log('Available Nodes:');
  console.log('─'.repeat(78));
  for (const { endpoint, info } of nodes) {
    console.log(`  [${info.nodeId}] ${info.hostname}`);
    console.log(`    Endpoint: ${endpoint}`);
    console.log(`    Home:     ${info.home}`);
    console.log(`    CWD:      ${info.cwd}`);
  }
  console.log('─'.repeat(78));
  console.log();

  // Interactive REPL
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
  });

  console.log('Commands:');
  console.log('  ls <node#> <path>              - List directory on specific node');
  console.log('  read <node#> <path>            - Read file from specific node');
  console.log('  write <node#> <path> <content> - Write file to specific node');
  console.log('  exec <node#> <command>         - Execute command on specific node');
  console.log('  all-ls <path>                  - List directory on ALL nodes');
  console.log('  all-exec <command>             - Execute command on ALL nodes');
  console.log('  nodes                          - Show available nodes');
  console.log('  exit                           - Quit');
  console.log();

  const prompt = () => {
    rl.question('kraft-fs> ', async (input) => {
      const parts = input.trim().split(' ');
      const cmd = parts[0];

      try {
        switch (cmd) {
          case 'ls': {
            const nodeIdx = parseInt(parts[1]);
            const path = parts.slice(2).join(' ') || '.';
            if (isNaN(nodeIdx) || nodeIdx >= nodes.length) {
              console.log(`Invalid node index. Use 0-${nodes.length - 1}`);
              break;
            }
            const result = await client.ls(nodes[nodeIdx].endpoint, path);
            console.log();
            console.log(`[${result.nodeId}@${result.hostname}] ls ${result.path}`);
            if (result.error) {
              console.log(`  Error: ${result.error}`);
            } else {
              console.log('─'.repeat(50));
              for (const f of result.files) {
                const icon = f.isDirectory ? '📁' : '📄';
                const size = f.isDirectory ? '<DIR>' : formatSize(f.size);
                console.log(`  ${icon} ${f.name.padEnd(30)} ${size}`);
              }
              console.log('─'.repeat(50));
              console.log(`  ${result.files.length} items`);
            }
            console.log();
            break;
          }

          case 'read': {
            const nodeIdx = parseInt(parts[1]);
            const path = parts.slice(2).join(' ');
            if (isNaN(nodeIdx) || nodeIdx >= nodes.length) {
              console.log(`Invalid node index. Use 0-${nodes.length - 1}`);
              break;
            }
            const result = await client.read(nodes[nodeIdx].endpoint, path);
            console.log();
            console.log(`[${result.nodeId}@${result.hostname}] cat ${result.path}`);
            if (result.error) {
              console.log(`  Error: ${result.error}`);
            } else {
              console.log('─'.repeat(50));
              console.log(result.content);
              console.log('─'.repeat(50));
            }
            console.log();
            break;
          }

          case 'write': {
            const nodeIdx = parseInt(parts[1]);
            const path = parts[2];
            const content = parts.slice(3).join(' ');
            if (isNaN(nodeIdx) || nodeIdx >= nodes.length) {
              console.log(`Invalid node index. Use 0-${nodes.length - 1}`);
              break;
            }
            const result = await client.write(nodes[nodeIdx].endpoint, path, content);
            console.log();
            console.log(`[${result.nodeId}@${result.hostname}] write ${result.path}`);
            if (result.error) {
              console.log(`  Error: ${result.error}`);
            } else {
              console.log(`  ✓ Wrote ${result.bytesWritten} bytes`);
            }
            console.log();
            break;
          }

          case 'exec': {
            const nodeIdx = parseInt(parts[1]);
            const command = parts.slice(2).join(' ');
            if (isNaN(nodeIdx) || nodeIdx >= nodes.length) {
              console.log(`Invalid node index. Use 0-${nodes.length - 1}`);
              break;
            }
            const result = await client.exec(nodes[nodeIdx].endpoint, command);
            console.log();
            console.log(`[${result.nodeId}@${result.hostname}] $ ${result.command}`);
            if (result.error) {
              console.log(`  Error: ${result.error}`);
            } else {
              console.log(result.output);
              if (result.exitCode !== 0) {
                console.log(`  (exit code: ${result.exitCode})`);
              }
            }
            console.log();
            break;
          }

          case 'all-ls': {
            const path = parts.slice(1).join(' ') || '.';
            console.log();
            console.log(`Listing "${path}" on all nodes:`);
            console.log('═'.repeat(78));
            for (const { endpoint } of nodes) {
              const result = await client.ls(endpoint, path);
              console.log();
              console.log(`[${result.nodeId}@${result.hostname}]`);
              if (result.error) {
                console.log(`  Error: ${result.error}`);
              } else {
                for (const f of result.files.slice(0, 10)) {
                  const icon = f.isDirectory ? '📁' : '📄';
                  console.log(`  ${icon} ${f.name}`);
                }
                if (result.files.length > 10) {
                  console.log(`  ... and ${result.files.length - 10} more`);
                }
              }
            }
            console.log('═'.repeat(78));
            console.log();
            break;
          }

          case 'all-exec': {
            const command = parts.slice(1).join(' ');
            console.log();
            console.log(`Executing "${command}" on all nodes:`);
            console.log('═'.repeat(78));
            for (const { endpoint } of nodes) {
              const result = await client.exec(endpoint, command);
              console.log();
              console.log(`[${result.nodeId}@${result.hostname}]`);
              if (result.error) {
                console.log(`  Error: ${result.error}`);
              } else {
                console.log(result.output.trim());
              }
            }
            console.log('═'.repeat(78));
            console.log();
            break;
          }

          case 'nodes': {
            console.log();
            console.log('Available Nodes:');
            for (let i = 0; i < nodes.length; i++) {
              const { info } = nodes[i];
              console.log(`  [${i}] ${info.nodeId} @ ${info.hostname}`);
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
