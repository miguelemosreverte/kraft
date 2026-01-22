/**
 * Terminal route - Execute shell commands on DFS nodes
 */

import type { IncomingMessage, ServerResponse } from 'http';
import type { SmartDFS, ExecResponse } from '../../smart-dfs.js';

interface TerminalRequest {
  command: string;
  nodeId: string;  // Which node to execute on
}

interface TerminalResponse {
  nodeId: string;
  hostname: string;
  output: string;
  exitCode: number;
  error?: string;
}

export async function handleTerminal(
  req: IncomingMessage,
  res: ServerResponse,
  body: string,
  dfs: SmartDFS
): Promise<void> {
  res.setHeader('Content-Type', 'application/json');

  try {
    const request: TerminalRequest = JSON.parse(body);
    const { command, nodeId } = request;

    if (!command || typeof command !== 'string') {
      res.statusCode = 400;
      res.end(JSON.stringify({
        nodeId: '',
        hostname: '',
        error: 'Command required',
        output: '',
        exitCode: 1
      }));
      return;
    }

    if (!nodeId) {
      res.statusCode = 400;
      res.end(JSON.stringify({
        nodeId: '',
        hostname: '',
        error: 'Node ID required. Select a node first.',
        output: '',
        exitCode: 1
      }));
      return;
    }

    // Execute command on the selected node via DFS
    const result = await dfs.exec(nodeId, command);

    const response: TerminalResponse = {
      nodeId: result.nodeId,
      hostname: result.hostname,
      output: result.output,
      exitCode: result.exitCode,
      error: result.error
    };

    res.end(JSON.stringify(response));
  } catch (e) {
    res.statusCode = 500;
    res.end(JSON.stringify({
      nodeId: '',
      hostname: '',
      error: (e as Error).message,
      output: '',
      exitCode: 1
    }));
  }
}
