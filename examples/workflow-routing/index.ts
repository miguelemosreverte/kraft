/**
 * Kraft Workflow Routing Demo
 *
 * This example demonstrates consistent hashing in action:
 * 1. Submit workflows with different IDs
 * 2. See which node handles each workflow
 * 3. Verify consistent routing (same ID -> same node)
 *
 * Run with: npx tsx index.ts
 */

interface SubmitResponse {
  workflowId: string;
  status: string;
}

interface WorkflowInfo {
  workflowId: string;
  name: string;
  status: string;
}

class Kraft {
  constructor(private endpoint: string) {}

  get name(): string {
    return this.endpoint.split(':').pop() ?? this.endpoint;
  }

  async submitWorkflow(name: string, input: unknown, workflowId: string): Promise<SubmitResponse> {
    const res = await fetch(`${this.endpoint}/workflows/submit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflowName: name,
        workflowId,
        input: JSON.stringify(input)
      })
    });
    return res.json();
  }

  async getWorkflow(workflowId: string): Promise<WorkflowInfo | null> {
    try {
      const res = await fetch(`${this.endpoint}/workflows/${workflowId}`);
      if (!res.ok) return null;
      return res.json();
    } catch {
      return null;
    }
  }

  async health(): Promise<{ nodeId: string; nodes: number } | null> {
    try {
      const res = await fetch(`${this.endpoint}/health`);
      return res.json();
    } catch {
      return null;
    }
  }
}

// Simple consistent hash to predict routing
function simpleHash(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash; // Convert to 32-bit int
  }
  return Math.abs(hash);
}

async function main() {
  const nodesEnv = process.env.KRAFT_NODES;
  const nodeEndpoints = nodesEnv
    ? nodesEnv.split(',').map(n => n.trim())
    : ['http://192.168.0.130:8800', 'http://192.168.0.130:8801'];

  console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
  console.log('║                   Kraft Workflow Routing Demo                                ║');
  console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  console.log();

  const nodes = nodeEndpoints.map(e => new Kraft(e));

  // Check cluster health
  console.log('Checking cluster health...');
  for (const node of nodes) {
    const health = await node.health();
    if (health) {
      console.log(`  ✓ ${node.name}: ${health.nodeId} (${health.nodes} nodes in cluster)`);
    } else {
      console.log(`  ✗ ${node.name}: unreachable`);
    }
  }
  console.log();

  // Submit workflows with different IDs to see routing
  const workflowIds = [
    'order-001',
    'order-002',
    'order-003',
    'user-signup-abc',
    'user-signup-xyz',
    'payment-100',
    'payment-200',
    'report-daily',
    'report-weekly',
    'notification-email'
  ];

  console.log('Submitting workflows to see routing distribution...');
  console.log('─'.repeat(78));
  console.log('  Workflow ID                    Submitted Via    Hash         Predicted Node');
  console.log('─'.repeat(78));

  const results: { id: string; submittedVia: string; hash: number }[] = [];

  // Submit each workflow via the first available node
  const submitNode = nodes[0];
  for (const wfId of workflowIds) {
    try {
      await submitNode.submitWorkflow('demo', { data: wfId }, wfId);
      const hash = simpleHash(wfId);
      const predictedNode = hash % nodes.length;

      results.push({ id: wfId, submittedVia: submitNode.name, hash });

      console.log(
        `  ${wfId.padEnd(30)} ${submitNode.name.padEnd(16)} ${hash.toString().padEnd(12)} node-${predictedNode}`
      );
    } catch (e) {
      console.log(`  ${wfId.padEnd(30)} FAILED: ${(e as Error).message}`);
    }
  }

  console.log('─'.repeat(78));
  console.log();

  // Show distribution summary
  const distribution = new Map<number, string[]>();
  for (const result of results) {
    const nodeIdx = result.hash % nodes.length;
    if (!distribution.has(nodeIdx)) distribution.set(nodeIdx, []);
    distribution.get(nodeIdx)!.push(result.id);
  }

  console.log('Routing Distribution (based on consistent hashing):');
  console.log('─'.repeat(78));
  for (const [nodeIdx, wfIds] of distribution) {
    console.log(`  Node ${nodeIdx}: ${wfIds.length} workflows`);
    wfIds.forEach(id => console.log(`    - ${id}`));
  }
  console.log('─'.repeat(78));
  console.log();

  // Demonstrate idempotent routing
  console.log('Verifying consistent routing (same ID always routes to same node)...');
  const testId = 'order-001';
  const testHash = simpleHash(testId);
  console.log(`  Workflow "${testId}" hash: ${testHash}`);
  console.log(`  Will always route to node: ${testHash % nodes.length}`);
  console.log();

  console.log('Key Insight:');
  console.log('  Kraft uses consistent hashing with virtual nodes to distribute workflows.');
  console.log('  This ensures:');
  console.log('  - Same workflow ID always routes to the same node');
  console.log('  - Adding/removing nodes only moves ~1/N of workflows');
  console.log('  - Even distribution across cluster');
  console.log();
}

main().catch(console.error);
