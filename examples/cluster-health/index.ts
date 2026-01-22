/**
 * Kraft Cluster Health Monitor
 *
 * This example shows how to:
 * 1. Connect to multiple nodes in a cluster
 * 2. Monitor cluster health in real-time
 * 3. Detect node failures and recovery
 *
 * Run with: npx tsx index.ts
 */

interface NodeHealth {
  status: string;
  nodeId: string;
  nodes: number;
  activeWorkflows: number;
}

interface NodeStatus {
  endpoint: string;
  health?: NodeHealth;
  error?: string;
  latencyMs: number;
  lastCheck: Date;
}

class ClusterMonitor {
  private nodes: string[];
  private statuses: Map<string, NodeStatus> = new Map();

  constructor(nodes: string[]) {
    this.nodes = nodes;
  }

  async checkNode(endpoint: string): Promise<NodeStatus> {
    const start = Date.now();
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);

      const res = await fetch(`${endpoint}/health`, { signal: controller.signal });
      clearTimeout(timeout);

      const health = await res.json() as NodeHealth;
      return {
        endpoint,
        health,
        latencyMs: Date.now() - start,
        lastCheck: new Date()
      };
    } catch (e) {
      return {
        endpoint,
        error: (e as Error).message,
        latencyMs: Date.now() - start,
        lastCheck: new Date()
      };
    }
  }

  async checkAll(): Promise<void> {
    const results = await Promise.all(this.nodes.map(node => this.checkNode(node)));
    results.forEach(status => this.statuses.set(status.endpoint, status));
  }

  printStatus(): void {
    console.clear();
    console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
    console.log('║                        Kraft Cluster Health Monitor                          ║');
    console.log('╠══════════════════════════════════════════════════════════════════════════════╣');

    const timestamp = new Date().toLocaleTimeString();
    console.log(`║  Last Update: ${timestamp.padEnd(62)}║`);
    console.log('╠══════════════════════════════════════════════════════════════════════════════╣');

    // Calculate cluster summary
    const healthy = Array.from(this.statuses.values()).filter(s => s.health?.status === 'healthy');
    const failed = Array.from(this.statuses.values()).filter(s => s.error);
    const totalNodes = healthy.length > 0 ? healthy[0].health!.nodes : 0;

    console.log(`║  Cluster: ${healthy.length}/${this.nodes.length} endpoints reachable, ${totalNodes} gossip members`.padEnd(77) + '║');
    console.log('╠══════════════════════════════════════════════════════════════════════════════╣');

    // Print each node status
    for (const [endpoint, status] of this.statuses) {
      const icon = status.health ? '✓' : '✗';
      const state = status.health ? `${status.health.status}`.toUpperCase() : 'UNREACHABLE';
      const nodeId = status.health?.nodeId ?? 'unknown';
      const latency = `${status.latencyMs}ms`;
      const workflows = status.health?.activeWorkflows ?? 0;

      console.log(`║  ${icon} ${endpoint.padEnd(30)} ${state.padEnd(12)} ${nodeId.padEnd(15)} ${latency.padStart(6)} ${workflows} workflows`.padEnd(77) + '║');
    }

    console.log('╠══════════════════════════════════════════════════════════════════════════════╣');
    console.log('║  Press Ctrl+C to exit                                                        ║');
    console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  }

  async monitor(intervalMs: number = 2000): Promise<void> {
    console.log('Starting cluster monitor...');
    console.log(`Monitoring nodes: ${this.nodes.join(', ')}`);
    console.log();

    // Initial check
    await this.checkAll();
    this.printStatus();

    // Continuous monitoring
    setInterval(async () => {
      await this.checkAll();
      this.printStatus();
    }, intervalMs);
  }
}

// Main
async function main() {
  // Get nodes from env or use defaults
  const nodesEnv = process.env.KRAFT_NODES;
  const nodes = nodesEnv
    ? nodesEnv.split(',').map(n => n.trim())
    : ['http://192.168.0.130:8800', 'http://192.168.0.130:8801'];

  console.log('╔════════════════════════════════════════════════════════════╗');
  console.log('║           Kraft Cluster Health Monitor                     ║');
  console.log('╚════════════════════════════════════════════════════════════╝');
  console.log();
  console.log('Usage:');
  console.log('  KRAFT_NODES="http://host1:8800,http://host2:8801" npx tsx index.ts');
  console.log();

  const monitor = new ClusterMonitor(nodes);
  await monitor.monitor(2000);
}

main().catch(console.error);
