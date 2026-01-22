/**
 * Smart DFS Web Server
 *
 * A distributed file system web interface with:
 * - macOS Finder-style file browser
 * - Drag & drop file upload
 * - Per-file replication settings
 * - Search across all nodes
 * - Image/video preview
 * - WebSocket terminal with streaming output
 * - Durable connections with auto-reconnect
 */

import * as http from 'http';
import * as path from 'path';
import { URL } from 'url';
import { WebSocketServer, WebSocket } from 'ws';
import { SmartDFS } from './smart-dfs.js';
import { handleRequest } from './src/routes/index.js';
import { DurableConnectionManager, NodeCredentials } from './src/services/connection-manager.js';

// Configuration
const PORT = parseInt(process.env.PORT || '3000');
const nodesEnv = process.env.KRAFT_NODES;
const endpoints = nodesEnv
  ? nodesEnv.split(',').map(n => n.trim())
  : ['http://localhost:7801', 'http://localhost:7802', 'http://localhost:7803'];

const defaultReplicationFactor = parseInt(process.env.REPLICATION_FACTOR || '2');

// Initialize DFS client
const dfs = new SmartDFS(endpoints, {
  replicationFactor: defaultReplicationFactor,
  minFreeSpacePercent: 10
});

// Initialize Durable Connection Manager
const connectionManager = new DurableConnectionManager({
  heartbeatInterval: 5000,
  heartbeatTimeout: 3000,
  reconnectBaseDelay: 1000,
  reconnectMaxDelay: 30000,
  reconnectMaxAttempts: Infinity,
  persistStatePath: path.join(process.cwd(), '.dfs-connections.json')
});

// Connection manager event handlers
connectionManager.on('connected', ({ nodeId, hostname }) => {
  console.log(`[Connection] Connected to ${hostname} (${nodeId})`);
});

connectionManager.on('disconnected', ({ nodeId, reason }) => {
  console.log(`[Connection] Disconnected from ${nodeId}: ${reason}`);
});

connectionManager.on('reconnecting', ({ nodeId, attempt, delay }) => {
  console.log(`[Connection] Reconnecting to ${nodeId} (attempt ${attempt}) in ${delay}ms`);
});

connectionManager.on('failed', ({ nodeId, attempts }) => {
  console.error(`[Connection] Failed to reconnect to ${nodeId} after ${attempts} attempts`);
});

connectionManager.on('heartbeat', ({ nodeId }) => {
  // Quiet heartbeat logging - uncomment for debugging
  // console.log(`[Connection] Heartbeat OK: ${nodeId}`);
});

connectionManager.on('error', ({ nodeId, error }) => {
  console.error(`[Connection] Error for ${nodeId}: ${error}`);
});

// Create HTTP server
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host}`);
  const pathname = url.pathname;
  const query = Object.fromEntries(url.searchParams.entries());

  // Connection status API endpoint
  if (pathname === '/api/connections') {
    const states = connectionManager.getAllStates();
    const connections: Record<string, any> = {};

    for (const [nodeId, state] of states) {
      const creds = connectionManager.getCredentials(nodeId);
      connections[nodeId] = {
        ...state,
        hostname: creds?.hostname,
        endpoint: creds?.endpoint,
        wsEndpoint: creds?.wsEndpoint
      };
    }

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ connections, timestamp: Date.now() }));
    return;
  }

  await handleRequest(req, res, pathname, query, { dfs, endpoints, connectionManager });
});

// Create WebSocket server for terminal streaming
const wss = new WebSocketServer({ server, path: '/ws/terminal' });

// Track active connections to node WebSockets
const nodeConnections = new Map<WebSocket, WebSocket>();

// Track client session state for reconnection
interface ClientSession {
  nodeId: string;
  nodeWs: WebSocket | null;
  reconnectTimer?: NodeJS.Timeout;
}

const clientSessions = new Map<WebSocket, ClientSession>();

wss.on('connection', (clientWs: WebSocket) => {
  console.log('[WS] Client connected to terminal');

  clientWs.on('message', (data: Buffer) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'connect') {
        // Connect to node's WebSocket
        const { nodeId } = msg;

        // Check if node is known and connected via durable connection manager
        const connState = connectionManager.getState(nodeId);
        if (connState && connState.status !== 'connected') {
          clientWs.send(JSON.stringify({
            type: 'error',
            message: `Node ${nodeId} is ${connState.status}. Waiting for reconnection...`,
            nodeStatus: connState.status,
            reconnectAttempts: connState.reconnectAttempts
          }));
          // Still try to connect - node might be available
        }

        const wsEndpoint = dfs.getWsEndpoint(nodeId);

        if (!wsEndpoint) {
          clientWs.send(JSON.stringify({ type: 'error', message: `Node not found: ${nodeId}` }));
          return;
        }

        // Clean up any existing session
        const existingSession = clientSessions.get(clientWs);
        if (existingSession?.nodeWs) {
          existingSession.nodeWs.close();
        }
        if (existingSession?.reconnectTimer) {
          clearTimeout(existingSession.reconnectTimer);
        }

        console.log(`[WS] Connecting to node WebSocket: ${wsEndpoint}`);

        const nodeWs = new WebSocket(wsEndpoint);
        const session: ClientSession = { nodeId, nodeWs };
        clientSessions.set(clientWs, session);

        nodeWs.on('open', () => {
          clientWs.send(JSON.stringify({ type: 'connected', nodeId }));
          nodeConnections.set(clientWs, nodeWs);
        });

        nodeWs.on('message', (nodeData: Buffer) => {
          // Relay message from node to client
          if (clientWs.readyState === WebSocket.OPEN) {
            clientWs.send(nodeData.toString());
          }
        });

        nodeWs.on('close', () => {
          console.log('[WS] Node connection closed');
          nodeConnections.delete(clientWs);

          if (clientWs.readyState === WebSocket.OPEN) {
            const currentSession = clientSessions.get(clientWs);
            if (currentSession) {
              // Notify client and attempt reconnect
              clientWs.send(JSON.stringify({
                type: 'disconnected',
                nodeId: currentSession.nodeId,
                willReconnect: true
              }));

              // Schedule reconnect attempt
              scheduleNodeReconnect(clientWs, currentSession.nodeId);
            }
          }
        });

        nodeWs.on('error', (err) => {
          console.error('[WS] Node connection error:', err.message);
          if (clientWs.readyState === WebSocket.OPEN) {
            clientWs.send(JSON.stringify({ type: 'error', message: err.message }));
          }
        });

      } else if (msg.type === 'exec') {
        // Send command to node
        const { command } = msg;
        const connectedNodeWs = nodeConnections.get(clientWs);

        if (connectedNodeWs && connectedNodeWs.readyState === WebSocket.OPEN) {
          connectedNodeWs.send(command);
        } else {
          clientWs.send(JSON.stringify({ type: 'error', message: 'Not connected to node' }));
        }

      } else if (msg.type === 'kill') {
        // Close the node connection to kill the process
        const connectedNodeWs = nodeConnections.get(clientWs);
        if (connectedNodeWs) {
          connectedNodeWs.close();
          nodeConnections.delete(clientWs);
        }

      } else if (msg.type === 'status') {
        // Return connection status for the current session
        const session = clientSessions.get(clientWs);
        if (session) {
          const connState = connectionManager.getState(session.nodeId);
          clientWs.send(JSON.stringify({
            type: 'status',
            nodeId: session.nodeId,
            nodeStatus: connState?.status || 'unknown',
            wsConnected: nodeConnections.has(clientWs)
          }));
        }
      }

    } catch (e) {
      console.error('[WS] Error processing message:', e);
      clientWs.send(JSON.stringify({ type: 'error', message: (e as Error).message }));
    }
  });

  clientWs.on('close', () => {
    console.log('[WS] Client disconnected');
    const session = clientSessions.get(clientWs);
    if (session) {
      if (session.nodeWs) {
        session.nodeWs.close();
      }
      if (session.reconnectTimer) {
        clearTimeout(session.reconnectTimer);
      }
      clientSessions.delete(clientWs);
    }
    nodeConnections.delete(clientWs);
  });

  clientWs.on('error', (err) => {
    console.error('[WS] Client error:', err.message);
  });
});

// Schedule WebSocket reconnection to a node
function scheduleNodeReconnect(clientWs: WebSocket, nodeId: string, attempt: number = 1): void {
  if (clientWs.readyState !== WebSocket.OPEN) return;

  const session = clientSessions.get(clientWs);
  if (!session || session.nodeId !== nodeId) return;

  // Exponential backoff: 1s, 2s, 4s, 8s... max 30s
  const delay = Math.min(1000 * Math.pow(2, attempt - 1), 30000);

  console.log(`[WS] Scheduling reconnect to ${nodeId} in ${delay}ms (attempt ${attempt})`);

  session.reconnectTimer = setTimeout(async () => {
    if (clientWs.readyState !== WebSocket.OPEN) return;

    // Check if node is connected via durable connection manager
    if (!connectionManager.isConnected(nodeId)) {
      clientWs.send(JSON.stringify({
        type: 'reconnecting',
        nodeId,
        attempt,
        message: `Node ${nodeId} not yet available, retrying...`
      }));
      scheduleNodeReconnect(clientWs, nodeId, attempt + 1);
      return;
    }

    const wsEndpoint = dfs.getWsEndpoint(nodeId);
    if (!wsEndpoint) {
      scheduleNodeReconnect(clientWs, nodeId, attempt + 1);
      return;
    }

    console.log(`[WS] Attempting reconnect to ${wsEndpoint}`);
    clientWs.send(JSON.stringify({ type: 'reconnecting', nodeId, attempt }));

    const nodeWs = new WebSocket(wsEndpoint);
    session.nodeWs = nodeWs;

    nodeWs.on('open', () => {
      console.log(`[WS] Reconnected to ${nodeId}`);
      clientWs.send(JSON.stringify({ type: 'connected', nodeId, reconnected: true }));
      nodeConnections.set(clientWs, nodeWs);
    });

    nodeWs.on('message', (nodeData: Buffer) => {
      if (clientWs.readyState === WebSocket.OPEN) {
        clientWs.send(nodeData.toString());
      }
    });

    nodeWs.on('close', () => {
      nodeConnections.delete(clientWs);
      if (clientWs.readyState === WebSocket.OPEN) {
        clientWs.send(JSON.stringify({ type: 'disconnected', nodeId, willReconnect: true }));
        scheduleNodeReconnect(clientWs, nodeId, 1);
      }
    });

    nodeWs.on('error', (err) => {
      console.error(`[WS] Reconnect error: ${err.message}`);
      scheduleNodeReconnect(clientWs, nodeId, attempt + 1);
    });

  }, delay);
}

// Start server
async function main() {
  console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
  console.log('║              Smart DFS Web Interface                                          ║');
  console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  console.log();

  console.log('Discovering nodes...');
  const nodes = await dfs.discoverNodes();
  console.log(`Found ${nodes.length} node(s)`);

  // Register discovered nodes with connection manager for durable connections
  console.log('\nEstablishing durable connections...');
  for (const node of nodes) {
    console.log(`  • ${node.info.hostname} (${node.info.nodeId})`);
    console.log(`    HTTP: ${node.endpoint}`);
    console.log(`    WS:   ${node.wsEndpoint}`);

    // Register node with connection manager
    await connectionManager.registerNode({
      nodeId: node.info.nodeId,
      endpoint: node.endpoint,
      wsEndpoint: node.wsEndpoint,
      hostname: node.info.hostname
    });
  }
  console.log();

  server.listen(PORT, () => {
    console.log(`🌐 Web UI: http://localhost:${PORT}`);
    console.log(`🔌 WebSocket Terminal: ws://localhost:${PORT}/ws/terminal`);
    console.log(`📊 Connection Status: http://localhost:${PORT}/api/connections`);
    console.log();
    console.log('Features:');
    console.log('  • Drag & drop file upload');
    console.log('  • Per-file replication settings');
    console.log('  • Visual file browser');
    console.log('  • Search across all nodes');
    console.log('  • Streaming terminal (WebSocket)');
    console.log('  • Durable connections with auto-reconnect');
    console.log();
  });
}

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('\nShutting down...');
  connectionManager.shutdown();
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  console.log('\nShutting down...');
  connectionManager.shutdown();
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});

main().catch(console.error);
