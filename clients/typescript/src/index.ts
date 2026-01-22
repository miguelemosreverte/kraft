/**
 * Kraft TypeScript Client
 *
 * A clean, type-safe client for Kraft distributed workflows.
 *
 * @example Simple usage
 * ```typescript
 * const kraft = new Kraft('localhost:9000');
 * const result = await kraft.run('process-order', { orderId: '123' });
 * ```
 *
 * @example With types
 * ```typescript
 * const processOrder = kraft.workflow<OrderInput, OrderResult>('process-order');
 * const result = await processOrder.run({ orderId: '123' });
 * ```
 *
 * @example Long-running with handle
 * ```typescript
 * const handle = await kraft.start('process-order', { orderId: '123' });
 * handle.on('step', (e) => console.log(e));
 * const result = await handle.result();
 * ```
 */

// ============================================================================
// Types
// ============================================================================

export type WorkflowStatus = 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';

export interface WorkflowEvent {
  workflowId: string;
  step: string;
  status: 'started' | 'completed' | 'failed';
  timestamp: Date;
  data?: unknown;
}

export interface WorkflowInfo {
  workflowId: string;
  name: string;
  status: WorkflowStatus;
  createdAt: Date;
  completedAt?: Date;
  input: unknown;
  output?: unknown;
  error?: string;
}

export interface KraftOptions {
  /** Connection timeout in milliseconds */
  timeout?: number;
  /** Retry attempts for failed requests */
  retries?: number;
  /** Custom headers for all requests */
  headers?: Record<string, string>;
}

// ============================================================================
// Workflow Handle - for long-running workflows
// ============================================================================

export class WorkflowHandle<TOutput = unknown> {
  private eventListeners: Map<string, Set<(event: WorkflowEvent) => void>> = new Map();
  private completeListeners: Set<(result: TOutput) => void> = new Set();
  private errorListeners: Set<(error: Error) => void> = new Set();

  constructor(
    public readonly workflowId: string,
    private readonly client: Kraft
  ) {}

  /**
   * Get current workflow status
   */
  async status(): Promise<WorkflowStatus> {
    const info = await this.client.getWorkflow(this.workflowId);
    return info.status;
  }

  /**
   * Get full workflow info
   */
  async info(): Promise<WorkflowInfo> {
    return this.client.getWorkflow(this.workflowId);
  }

  /**
   * Wait for workflow to complete and return result
   */
  async result(): Promise<TOutput> {
    return this.client.waitForResult<TOutput>(this.workflowId);
  }

  /**
   * Query workflow state (if supported by workflow)
   */
  async query<T = unknown>(queryName: string): Promise<T> {
    return this.client.queryWorkflow<T>(this.workflowId, queryName);
  }

  /**
   * Send signal to running workflow
   */
  async signal(signalName: string, data?: unknown): Promise<void> {
    return this.client.signalWorkflow(this.workflowId, signalName, data);
  }

  /**
   * Cancel the workflow
   */
  async cancel(): Promise<void> {
    return this.client.cancelWorkflow(this.workflowId);
  }

  /**
   * Subscribe to workflow events
   */
  on(event: 'step', listener: (event: WorkflowEvent) => void): this;
  on(event: 'complete', listener: (result: TOutput) => void): this;
  on(event: 'error', listener: (error: Error) => void): this;
  on(event: string, listener: (...args: any[]) => void): this {
    if (event === 'complete') {
      this.completeListeners.add(listener as (result: TOutput) => void);
    } else if (event === 'error') {
      this.errorListeners.add(listener as (error: Error) => void);
    } else {
      if (!this.eventListeners.has(event)) {
        this.eventListeners.set(event, new Set());
      }
      this.eventListeners.get(event)!.add(listener);
    }
    return this;
  }

  /**
   * Async iterator for workflow events
   */
  async *events(): AsyncGenerator<WorkflowEvent> {
    yield* this.client.streamEvents(this.workflowId);
  }
}

// ============================================================================
// Typed Workflow - for type-safe workflow calls
// ============================================================================

export class TypedWorkflow<TInput, TOutput> {
  constructor(
    private readonly name: string,
    private readonly client: Kraft
  ) {}

  /**
   * Run workflow and wait for result
   */
  async run(input: TInput, workflowId?: string): Promise<TOutput> {
    return this.client.run<TInput, TOutput>(this.name, input, workflowId);
  }

  /**
   * Start workflow and return handle (don't wait)
   */
  async start(input: TInput, workflowId?: string): Promise<WorkflowHandle<TOutput>> {
    return this.client.start<TInput, TOutput>(this.name, input, workflowId);
  }
}

// ============================================================================
// Main Client
// ============================================================================

export class Kraft {
  private readonly endpoints: string[];
  private currentEndpoint: number = 0;
  private readonly options: Required<KraftOptions>;

  constructor(
    endpoint: string | string[],
    options: KraftOptions = {}
  ) {
    this.endpoints = Array.isArray(endpoint) ? endpoint : [endpoint];
    this.options = {
      timeout: options.timeout ?? 30000,
      retries: options.retries ?? 3,
      headers: options.headers ?? {}
    };
  }

  /**
   * Create a typed workflow reference
   *
   * @example
   * ```typescript
   * const processOrder = kraft.workflow<OrderInput, OrderResult>('process-order');
   * const result = await processOrder.run({ orderId: '123' });
   * ```
   */
  workflow<TInput, TOutput>(name: string): TypedWorkflow<TInput, TOutput> {
    return new TypedWorkflow<TInput, TOutput>(name, this);
  }

  /**
   * Run a workflow and wait for the result
   *
   * @example
   * ```typescript
   * const result = await kraft.run('process-order', { orderId: '123' });
   * ```
   */
  async run<TInput = unknown, TOutput = unknown>(
    workflowName: string,
    input: TInput,
    workflowId?: string
  ): Promise<TOutput> {
    const handle = await this.start<TInput, TOutput>(workflowName, input, workflowId);
    return handle.result();
  }

  /**
   * Start a workflow and return a handle (don't wait for completion)
   *
   * @example
   * ```typescript
   * const handle = await kraft.start('process-order', { orderId: '123' });
   * console.log(`Started: ${handle.workflowId}`);
   * const result = await handle.result();
   * ```
   */
  async start<TInput = unknown, TOutput = unknown>(
    workflowName: string,
    input: TInput,
    workflowId?: string
  ): Promise<WorkflowHandle<TOutput>> {
    const response = await this.request<{ workflowId: string }>('/workflows/submit', {
      method: 'POST',
      body: {
        workflowName,
        workflowId: workflowId ?? this.generateId(),
        input
      }
    });
    return new WorkflowHandle<TOutput>(response.workflowId, this);
  }

  /**
   * Get workflow information
   */
  async getWorkflow(workflowId: string): Promise<WorkflowInfo> {
    return this.request<WorkflowInfo>(`/workflows/${workflowId}`);
  }

  /**
   * Wait for a workflow to complete
   */
  async waitForResult<TOutput = unknown>(workflowId: string): Promise<TOutput> {
    const response = await this.request<{ output: TOutput }>(`/workflows/${workflowId}/result`, {
      timeout: 0 // No timeout for long-polling
    });
    return response.output;
  }

  /**
   * Query workflow state
   */
  async queryWorkflow<T = unknown>(workflowId: string, queryName: string): Promise<T> {
    return this.request<T>(`/workflows/${workflowId}/query/${queryName}`);
  }

  /**
   * Send signal to workflow
   */
  async signalWorkflow(workflowId: string, signalName: string, data?: unknown): Promise<void> {
    await this.request(`/workflows/${workflowId}/signal/${signalName}`, {
      method: 'POST',
      body: data
    });
  }

  /**
   * Cancel a workflow
   */
  async cancelWorkflow(workflowId: string): Promise<void> {
    await this.request(`/workflows/${workflowId}/cancel`, { method: 'POST' });
  }

  /**
   * List workflows with optional filters
   */
  async listWorkflows(filters?: {
    status?: WorkflowStatus;
    name?: string;
    limit?: number;
    offset?: number;
  }): Promise<WorkflowInfo[]> {
    const params = new URLSearchParams();
    if (filters?.status) params.set('status', filters.status);
    if (filters?.name) params.set('name', filters.name);
    if (filters?.limit) params.set('limit', String(filters.limit));
    if (filters?.offset) params.set('offset', String(filters.offset));

    const query = params.toString();
    return this.request<WorkflowInfo[]>(`/workflows${query ? `?${query}` : ''}`);
  }

  /**
   * Stream workflow events
   */
  async *streamEvents(workflowId: string): AsyncGenerator<WorkflowEvent> {
    // Implementation would use SSE or WebSocket
    // For now, poll-based fallback
    let lastSeq = 0;
    while (true) {
      const events = await this.request<WorkflowEvent[]>(
        `/workflows/${workflowId}/events?after=${lastSeq}`
      );

      for (const event of events) {
        yield event;
        lastSeq++;
      }

      // Check if workflow is done
      const info = await this.getWorkflow(workflowId);
      if (info.status === 'completed' || info.status === 'failed' || info.status === 'cancelled') {
        break;
      }

      // Wait before next poll
      await this.sleep(100);
    }
  }

  /**
   * Get cluster health/status
   */
  async health(): Promise<{
    status: 'healthy' | 'degraded' | 'unhealthy';
    nodes: number;
    activeWorkflows: number;
  }> {
    return this.request('/health');
  }

  // ==========================================================================
  // Internal
  // ==========================================================================

  private async request<T>(
    path: string,
    options: {
      method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
      body?: unknown;
      timeout?: number;
    } = {}
  ): Promise<T> {
    const { method = 'GET', body, timeout = this.options.timeout } = options;

    let lastError: Error | null = null;

    for (let attempt = 0; attempt <= this.options.retries; attempt++) {
      try {
        const endpoint = this.getEndpoint();
        const url = `${endpoint}${path}`;

        const controller = new AbortController();
        const timeoutId = timeout > 0
          ? setTimeout(() => controller.abort(), timeout)
          : null;

        try {
          const response = await fetch(url, {
            method,
            headers: {
              'Content-Type': 'application/json',
              ...this.options.headers
            },
            body: body ? JSON.stringify(body) : undefined,
            signal: controller.signal
          });

          if (!response.ok) {
            const error = await response.json().catch(() => ({ message: response.statusText }));
            throw new KraftError(error.message || 'Request failed', response.status);
          }

          return await response.json() as T;
        } finally {
          if (timeoutId) clearTimeout(timeoutId);
        }
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));

        // Rotate to next endpoint on failure
        this.rotateEndpoint();

        // Don't retry on client errors (4xx)
        if (error instanceof KraftError && error.status >= 400 && error.status < 500) {
          throw error;
        }

        // Wait before retry
        if (attempt < this.options.retries) {
          await this.sleep(Math.min(1000 * Math.pow(2, attempt), 10000));
        }
      }
    }

    throw lastError ?? new Error('Request failed');
  }

  private getEndpoint(): string {
    const endpoint = this.endpoints[this.currentEndpoint];
    // Ensure http:// prefix
    return endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;
  }

  private rotateEndpoint(): void {
    this.currentEndpoint = (this.currentEndpoint + 1) % this.endpoints.length;
  }

  private generateId(): string {
    return `wf-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

// ============================================================================
// Errors
// ============================================================================

export class KraftError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string
  ) {
    super(message);
    this.name = 'KraftError';
  }
}

// ============================================================================
// Export default
// ============================================================================

export default Kraft;
