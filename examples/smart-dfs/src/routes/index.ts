/**
 * Route handlers
 */

import type { IncomingMessage, ServerResponse } from 'http';
import type { ParsedUrlQuery } from 'querystring';
import type { SmartDFS } from '../../smart-dfs.js';
import type { DurableConnectionManager } from '../services/connection-manager.js';

import { renderLayout } from '../views/layout.js';
import { renderDashboard } from '../views/dashboard.js';
import { renderFiles } from '../views/files.js';
import { handleUploadGet, handleUploadPost } from './upload.js';
import { handlePreview } from './preview.js';
import { handleTerminal } from './terminal.js';

export interface RouterContext {
  dfs: SmartDFS;
  endpoints: string[];
  connectionManager?: DurableConnectionManager;
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

export async function handleRequest(
  req: IncomingMessage,
  res: ServerResponse,
  pathname: string,
  query: ParsedUrlQuery,
  ctx: RouterContext
): Promise<void> {
  const { dfs, endpoints, connectionManager } = ctx;

  res.setHeader('Content-Type', 'text/html; charset=utf-8');

  try {
    switch (pathname) {
      case '/':
        await handleDashboard(res, dfs);
        break;

      case '/files':
        await handleFiles(res, query, dfs, connectionManager);
        break;

      case '/upload':
        if (req.method === 'POST') {
          await handleUploadPost(req, res, dfs, endpoints);
        } else {
          await handleUploadGet(res, dfs);
        }
        break;

      case '/preview':
        await handlePreview(req, res, {
          path: query.path as string | undefined,
          thumb: query.thumb as string | undefined,
          node: query.node as string | undefined
        }, dfs);
        break;

      case '/terminal':
        if (req.method === 'POST') {
          const body = await readBody(req);
          await handleTerminal(req, res, body, dfs);
        } else {
          res.statusCode = 405;
          res.end(JSON.stringify({ error: 'Method not allowed' }));
        }
        break;

      default:
        res.statusCode = 404;
        res.end(renderLayout({
          content: '<h1>404 Not Found</h1>',
          currentPage: ''
        }));
    }
  } catch (e) {
    res.statusCode = 500;
    res.end(renderLayout({
      content: `<div class="alert alert-error">Error: ${(e as Error).message}</div>`,
      currentPage: ''
    }));
  }
}

async function handleDashboard(res: ServerResponse, dfs: SmartDFS): Promise<void> {
  const content = await renderDashboard(dfs);
  res.end(renderLayout({ content, currentPage: 'home' }));
}

async function handleFiles(
  res: ServerResponse,
  query: ParsedUrlQuery,
  dfs: SmartDFS,
  connectionManager?: DurableConnectionManager
): Promise<void> {
  // Get connection states for all known nodes
  const connectionStates = connectionManager ?
    Object.fromEntries(
      Array.from(connectionManager.getAllStates().entries()).map(([nodeId, state]) => {
        const creds = connectionManager.getCredentials(nodeId);
        return [nodeId, { ...state, ...creds }];
      })
    ) : {};

  const html = await renderFiles(dfs, {
    searchQuery: query.q as string | undefined,
    selectedNode: query.node as string | undefined,
    currentPath: query.path as string | undefined,
    viewMode: query.view as string | undefined,
    showHidden: query.hidden === 'true',
    connectionStates
  });
  // Files view renders its own complete HTML
  res.end(html);
}
