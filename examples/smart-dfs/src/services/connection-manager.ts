/**
 * Durable Connection Manager
 *
 * Manages persistent connections to DFS nodes with:
 * - Auto-reconnect with exponential backoff
 * - Health monitoring via heartbeats
 * - Connection state persistence
 * - Credential storage
 */

import { EventEmitter } from 'events';
import * as fs from 'fs';

export interface NodeCredentials {
  nodeId: string;
  endpoint: string;
  wsEndpoint: string;
  hostname: string;
  // Future: auth tokens, certificates, etc.
  authToken?: string;
}

export interface ConnectionState {
  nodeId: string;
  status: 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'failed';
  lastConnected?: number;
  lastHeartbeat?: number;
  reconnectAttempts: number;
  error?: string;
}

export interface ConnectionManagerConfig {
  heartbeatInterval: number;      // ms between heartbeats (default: 5000)
  heartbeatTimeout: number;       // ms to wait for heartbeat response (default: 3000)
  reconnectBaseDelay: number;     // initial reconnect delay (default: 1000)
  reconnectMaxDelay: number;      // max reconnect delay (default: 30000)
  reconnectMaxAttempts: number;   // max attempts before giving up (default: Infinity)
  persistStatePath?: string;      // path to persist state (server-side)
}

const DEFAULT_CONFIG: ConnectionManagerConfig = {
  heartbeatInterval: 5000,
  heartbeatTimeout: 3000,
  reconnectBaseDelay: 1000,
  reconnectMaxDelay: 30000,
  reconnectMaxAttempts: Infinity
};

export class DurableConnectionManager extends EventEmitter {
  private connections: Map<string, ConnectionState> = new Map();
  private credentials: Map<string, NodeCredentials> = new Map();
  private heartbeatTimers: Map<string, NodeJS.Timeout> = new Map();
  private reconnectTimers: Map<string, NodeJS.Timeout> = new Map();
  private config: ConnectionManagerConfig;

  constructor(config: Partial<ConnectionManagerConfig> = {}) {
    super();
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.loadPersistedState();
  }

  /**
   * Register a node and attempt to establish connection
   */
  async registerNode(creds: NodeCredentials): Promise<void> {
    this.credentials.set(creds.nodeId, creds);

    const state: ConnectionState = {
      nodeId: creds.nodeId,
      status: 'disconnected',
      reconnectAttempts: 0
    };
    this.connections.set(creds.nodeId, state);

    await this.connect(creds.nodeId);
    this.persistState();
  }

  /**
   * Connect to a node
   */
  async connect(nodeId: string): Promise<boolean> {
    const creds = this.credentials.get(nodeId);
    if (!creds) {
      this.emit('error', { nodeId, error: 'No credentials for node' });
      return false;
    }

    const state = this.connections.get(nodeId)!;
    state.status = 'connecting';
    this.emit('connecting', { nodeId });

    try {
      // Test connection with health check
      const response = await this.healthCheck(creds.endpoint);

      if (response.ok) {
        state.status = 'connected';
        state.lastConnected = Date.now();
        state.lastHeartbeat = Date.now();
        state.reconnectAttempts = 0;
        state.error = undefined;

        this.startHeartbeat(nodeId);
        this.emit('connected', { nodeId, hostname: creds.hostname });
        this.persistState();
        return true;
      } else {
        throw new Error(`Health check failed: ${response.status}`);
      }
    } catch (error) {
      state.status = 'disconnected';
      state.error = (error as Error).message;
      this.emit('error', { nodeId, error: state.error });
      this.scheduleReconnect(nodeId);
      return false;
    }
  }

  /**
   * Health check a node
   */
  private async healthCheck(endpoint: string): Promise<{ ok: boolean; status?: number }> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.heartbeatTimeout);

    try {
      const url = endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;
      const response = await fetch(`${url}/health`, { signal: controller.signal });
      clearTimeout(timeout);
      return { ok: response.ok, status: response.status };
    } catch (error) {
      clearTimeout(timeout);
      return { ok: false };
    }
  }

  /**
   * Start heartbeat monitoring for a node
   */
  private startHeartbeat(nodeId: string): void {
    this.stopHeartbeat(nodeId);

    const timer = setInterval(async () => {
      const creds = this.credentials.get(nodeId);
      const state = this.connections.get(nodeId);

      if (!creds || !state || state.status !== 'connected') {
        this.stopHeartbeat(nodeId);
        return;
      }

      try {
        const response = await this.healthCheck(creds.endpoint);

        if (response.ok) {
          state.lastHeartbeat = Date.now();
          this.emit('heartbeat', { nodeId, timestamp: state.lastHeartbeat });
        } else {
          throw new Error('Heartbeat failed');
        }
      } catch (error) {
        console.log(`[ConnectionManager] Heartbeat failed for ${nodeId}: ${(error as Error).message}`);
        state.status = 'reconnecting';
        this.stopHeartbeat(nodeId);
        this.emit('disconnected', { nodeId, reason: 'heartbeat_failed' });
        this.scheduleReconnect(nodeId);
      }
    }, this.config.heartbeatInterval);

    this.heartbeatTimers.set(nodeId, timer);
  }

  /**
   * Stop heartbeat monitoring
   */
  private stopHeartbeat(nodeId: string): void {
    const timer = this.heartbeatTimers.get(nodeId);
    if (timer) {
      clearInterval(timer);
      this.heartbeatTimers.delete(nodeId);
    }
  }

  /**
   * Schedule reconnection with exponential backoff
   */
  private scheduleReconnect(nodeId: string): void {
    const state = this.connections.get(nodeId);
    if (!state) return;

    // Check max attempts
    if (state.reconnectAttempts >= this.config.reconnectMaxAttempts) {
      state.status = 'failed';
      this.emit('failed', { nodeId, attempts: state.reconnectAttempts });
      return;
    }

    // Calculate backoff delay: baseDelay * 2^attempts, capped at maxDelay
    const delay = Math.min(
      this.config.reconnectBaseDelay * Math.pow(2, state.reconnectAttempts),
      this.config.reconnectMaxDelay
    );

    // Add jitter (±10%)
    const jitter = delay * 0.1 * (Math.random() * 2 - 1);
    const finalDelay = Math.round(delay + jitter);

    state.reconnectAttempts++;
    state.status = 'reconnecting';

    console.log(`[ConnectionManager] Scheduling reconnect for ${nodeId} in ${finalDelay}ms (attempt ${state.reconnectAttempts})`);
    this.emit('reconnecting', { nodeId, attempt: state.reconnectAttempts, delay: finalDelay });

    const timer = setTimeout(async () => {
      this.reconnectTimers.delete(nodeId);
      await this.connect(nodeId);
    }, finalDelay);

    this.reconnectTimers.set(nodeId, timer);
  }

  /**
   * Cancel scheduled reconnection
   */
  private cancelReconnect(nodeId: string): void {
    const timer = this.reconnectTimers.get(nodeId);
    if (timer) {
      clearTimeout(timer);
      this.reconnectTimers.delete(nodeId);
    }
  }

  /**
   * Disconnect from a node
   */
  disconnect(nodeId: string): void {
    this.stopHeartbeat(nodeId);
    this.cancelReconnect(nodeId);

    const state = this.connections.get(nodeId);
    if (state) {
      state.status = 'disconnected';
      state.reconnectAttempts = 0;
    }

    this.emit('disconnected', { nodeId, reason: 'manual' });
    this.persistState();
  }

  /**
   * Remove a node completely
   */
  removeNode(nodeId: string): void {
    this.disconnect(nodeId);
    this.connections.delete(nodeId);
    this.credentials.delete(nodeId);
    this.persistState();
  }

  /**
   * Get connection state for a node
   */
  getState(nodeId: string): ConnectionState | undefined {
    return this.connections.get(nodeId);
  }

  /**
   * Get all connection states
   */
  getAllStates(): Map<string, ConnectionState> {
    return new Map(this.connections);
  }

  /**
   * Get credentials for a node
   */
  getCredentials(nodeId: string): NodeCredentials | undefined {
    return this.credentials.get(nodeId);
  }

  /**
   * Check if node is connected
   */
  isConnected(nodeId: string): boolean {
    const state = this.connections.get(nodeId);
    return state?.status === 'connected';
  }

  /**
   * Persist state to storage
   */
  private persistState(): void {
    try {
      const state = {
        connections: Array.from(this.connections.entries()),
        credentials: Array.from(this.credentials.entries()).map(([id, creds]) => [id, {
          ...creds,
          // Don't persist auth tokens in plain text in production
          authToken: creds.authToken ? '[REDACTED]' : undefined
        }])
      };

      // In Node.js, write to file
      if (this.config.persistStatePath) {
        fs.writeFileSync(this.config.persistStatePath, JSON.stringify(state, null, 2));
      }
    } catch (error) {
      console.error('[ConnectionManager] Failed to persist state:', error);
    }
  }

  /**
   * Load persisted state
   */
  private loadPersistedState(): void {
    try {
      if (this.config.persistStatePath) {
        if (fs.existsSync(this.config.persistStatePath)) {
          const data = JSON.parse(fs.readFileSync(this.config.persistStatePath, 'utf-8'));

          // Restore connections (but set status to disconnected)
          for (const [id, state] of data.connections || []) {
            this.connections.set(id, { ...state, status: 'disconnected', reconnectAttempts: 0 });
          }

          // Restore credentials
          for (const [id, creds] of data.credentials || []) {
            this.credentials.set(id, creds);
          }

          console.log(`[ConnectionManager] Loaded ${this.connections.size} persisted connections`);
        }
      }
    } catch (error) {
      console.error('[ConnectionManager] Failed to load persisted state:', error);
    }
  }

  /**
   * Reconnect all previously known nodes
   */
  async reconnectAll(): Promise<void> {
    const nodeIds = Array.from(this.credentials.keys());
    console.log(`[ConnectionManager] Reconnecting to ${nodeIds.length} nodes...`);

    await Promise.all(nodeIds.map(nodeId => this.connect(nodeId)));
  }

  /**
   * Shutdown - disconnect all and cleanup
   */
  shutdown(): void {
    for (const nodeId of this.connections.keys()) {
      this.stopHeartbeat(nodeId);
      this.cancelReconnect(nodeId);
    }
    this.persistState();
  }
}

export default DurableConnectionManager;
