/**
 * Kraft Hello World Example
 *
 * This example shows how to:
 * 1. Connect to a Kraft cluster
 * 2. Check cluster health
 * 3. Submit a simple workflow
 *
 * Run with: npx ts-node index.ts
 * Or with Deno: deno run --allow-net index.ts
 */

// Simple Kraft client (inline to avoid build step)
class Kraft {
  constructor(private endpoint: string) {}

  async health(): Promise<{ status: string; nodeId: string; nodes: number; activeWorkflows: number }> {
    const res = await fetch(`${this.endpoint}/health`);
    return res.json();
  }

  async submitWorkflow(name: string, input: unknown, workflowId?: string): Promise<{ workflowId: string; status: string }> {
    const res = await fetch(`${this.endpoint}/workflows/submit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflowName: name,
        workflowId: workflowId ?? `wf-${Date.now()}`,
        input: JSON.stringify(input)
      })
    });
    return res.json();
  }

  async getWorkflow(workflowId: string): Promise<unknown> {
    const res = await fetch(`${this.endpoint}/workflows/${workflowId}`);
    return res.json();
  }
}

// Main
async function main() {
  // Default to local cluster, or use KRAFT_ENDPOINT env var
  const endpoint = process.env.KRAFT_ENDPOINT ?? 'http://localhost:8800';

  console.log('╔════════════════════════════════════════════════════════════╗');
  console.log('║              Kraft Hello World Demo                        ║');
  console.log('╚════════════════════════════════════════════════════════════╝');
  console.log();
  console.log(`Connecting to: ${endpoint}`);
  console.log();

  const kraft = new Kraft(endpoint);

  // Check cluster health
  try {
    const health = await kraft.health();
    console.log('✓ Cluster Health:');
    console.log(`  Status:     ${health.status}`);
    console.log(`  Node ID:    ${health.nodeId}`);
    console.log(`  Nodes:      ${health.nodes}`);
    console.log(`  Workflows:  ${health.activeWorkflows}`);
    console.log();
  } catch (e) {
    console.error('✗ Failed to connect to cluster:', (e as Error).message);
    console.log();
    console.log('Make sure the Kraft cluster is running:');
    console.log('  sbt "runMain kraft.demos.ClusterNode seed 7800"');
    process.exit(1);
  }

  // Submit a workflow
  console.log('Submitting workflow...');
  const result = await kraft.submitWorkflow('hello', { name: 'World' });
  console.log(`✓ Workflow submitted: ${result.workflowId}`);
  console.log(`  Status: ${result.status}`);
  console.log();

  // Get workflow info
  console.log('Getting workflow info...');
  const info = await kraft.getWorkflow(result.workflowId);
  console.log('✓ Workflow info:', JSON.stringify(info, null, 2));
  console.log();

  console.log('Done!');
}

main().catch(console.error);
