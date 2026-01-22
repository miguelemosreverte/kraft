# @kraft/client

TypeScript client for [Kraft](https://github.com/miguelemosreverte/kraft) distributed workflows.

## Installation

```bash
npm install @kraft/client
```

## Quick Start

```typescript
import { Kraft } from '@kraft/client';

// Connect to a Kraft node
const kraft = new Kraft('localhost:9000');

// Run a workflow and wait for result
const result = await kraft.run('process-order', {
  orderId: '123',
  items: ['SKU-001', 'SKU-002']
});

console.log(result);
```

## Type-Safe Workflows

```typescript
import { Kraft } from '@kraft/client';

// Define your types
interface OrderInput {
  orderId: string;
  items: string[];
}

interface OrderResult {
  trackingNumber: string;
  estimatedDelivery: Date;
}

const kraft = new Kraft('localhost:9000');

// Create typed workflow reference
const processOrder = kraft.workflow<OrderInput, OrderResult>('process-order');

// Full type safety & IDE autocomplete
const result = await processOrder.run({
  orderId: '123',
  items: ['SKU-001']
});

console.log(result.trackingNumber);  // ✓ typed
```

## Long-Running Workflows

```typescript
// Start without waiting
const handle = await kraft.start('process-order', { orderId: '123' });

console.log(`Workflow started: ${handle.workflowId}`);

// Check status anytime
const status = await handle.status();  // 'running' | 'completed' | 'failed'

// Query internal state
const orderStatus = await handle.query('currentStep');

// Send signal to running workflow
await handle.signal('expedite', { priority: 'high' });

// Wait for result when ready
const result = await handle.result();
```

## Event Streaming

```typescript
const handle = await kraft.start('process-order', { orderId: '123' });

// Callback style
handle.on('step', (event) => {
  console.log(`Step ${event.step}: ${event.status}`);
});

handle.on('complete', (result) => {
  console.log('Done!', result);
});

// Or async iterator
for await (const event of handle.events()) {
  console.log(`${event.step}: ${event.status}`);
}
```

## Cluster Connection

```typescript
// Connect to multiple nodes (automatic failover)
const kraft = new Kraft([
  'node1.cluster:9000',
  'node2.cluster:9000',
  'node3.cluster:9000'
]);

// Requests automatically route to healthy nodes
const result = await kraft.run('process-order', input);
```

## Configuration

```typescript
const kraft = new Kraft('localhost:9000', {
  timeout: 30000,        // Request timeout (ms)
  retries: 3,            // Retry attempts
  headers: {             // Custom headers
    'Authorization': 'Bearer token'
  }
});
```

## API Reference

### `Kraft`

| Method | Description |
|--------|-------------|
| `run(name, input)` | Run workflow and wait for result |
| `start(name, input)` | Start workflow, return handle |
| `workflow<I, O>(name)` | Create typed workflow reference |
| `getWorkflow(id)` | Get workflow info |
| `listWorkflows(filters?)` | List workflows |
| `health()` | Check cluster health |

### `WorkflowHandle`

| Method | Description |
|--------|-------------|
| `status()` | Get current status |
| `info()` | Get full workflow info |
| `result()` | Wait for and return result |
| `query(name)` | Query workflow state |
| `signal(name, data?)` | Send signal to workflow |
| `cancel()` | Cancel the workflow |
| `on(event, listener)` | Subscribe to events |
| `events()` | Async iterator for events |

## License

MIT
