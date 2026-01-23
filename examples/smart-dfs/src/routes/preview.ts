/**
 * Preview route - serves file content for preview/download
 * Optimized for speed with direct filesystem access for local files
 */

import type { IncomingMessage, ServerResponse } from 'http';
import type { SmartDFS } from '../../smart-dfs.js';
import { getMimeType } from '../utils/mime-types.js';
import { isImageFile } from '../utils/file-types.js';
import * as fs from 'fs/promises';
import * as pathModule from 'path';

export interface PreviewQuery {
  path?: string;
  thumb?: string;
  node?: string;  // Node ID to fetch from
}

export async function handlePreview(
  req: IncomingMessage,
  res: ServerResponse,
  query: PreviewQuery,
  dfs: SmartDFS
): Promise<void> {
  const filePath = query.path;
  const isThumb = query.thumb === '1';
  const targetNodeId = query.node;

  if (!filePath) {
    res.statusCode = 400;
    res.end('Missing path parameter');
    return;
  }

  const startTime = Date.now();
  try {
    const filename = filePath.split('/').pop() || 'file';
    const mimeType = getMimeType(filename);

    // Set caching headers early
    if (isThumb && isImageFile(filename)) {
      res.setHeader('Cache-Control', 'public, max-age=3600');
    }

    // FAST PATH: Try direct filesystem read first for local files
    // This avoids the HTTP/JSON/base64 overhead entirely
    try {
      const stat = await fs.stat(filePath);
      if (stat.isFile()) {
        const fileBuffer = await fs.readFile(filePath);
        res.setHeader('Content-Type', mimeType);
        res.setHeader('Content-Length', fileBuffer.length);
        console.log(`[Preview] FAST PATH: ${filename} - ${Date.now() - startTime}ms`);
        res.end(fileBuffer);
        return;
      }
    } catch {
      // File not accessible locally, fall through to node API
      console.log(`[Preview] FAST PATH FAILED for: ${filePath}`);
    }

    // SLOW PATH: Fetch from node via API (for remote files)
    console.log(`[Preview] SLOW PATH: ${filename}, path=${filePath}`);
    // Use cached nodes list - don't rediscover every request
    const nodes = dfs.getNodes();

    // If no nodes cached, do a quick discovery
    if (nodes.length === 0) {
      console.log(`[Preview] No cached nodes, discovering...`);
      await dfs.discoverNodes();
    }

    // Find nodes to try - keep ALL matching nodes (may have duplicate IDs but different endpoints)
    let nodesToTry = dfs.getNodes();
    console.log(`[Preview] Nodes available: ${nodesToTry.map(n => `${n.info.hostname}(${n.endpoint})`).join(', ')}`);
    if (targetNodeId) {
      // Filter to nodes with matching ID, but keep ALL of them (different endpoints)
      const matchingNodes = nodesToTry.filter(n => n.info.nodeId === targetNodeId);
      if (matchingNodes.length > 0) {
        nodesToTry = matchingNodes;
        console.log(`[Preview] Trying ${matchingNodes.length} node(s) with ID ${targetNodeId}`);
      }
    }

    // Fetch from node(s) - try /fs/raw first, fall back to /fs/read
    for (const { endpoint } of nodesToTry) {
      const nodeUrl = endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;

      // Try /fs/raw first (direct binary, fastest)
      try {
        console.log(`[Preview] Trying ${nodeUrl}/fs/raw`);
        const rawRes = await fetch(`${nodeUrl}/fs/raw`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path: filePath })
        });

        if (rawRes.ok) {
          const contentType = rawRes.headers.get('content-type');
          // If we got JSON back, it's an error response
          if (contentType?.includes('application/json')) {
            const data = await rawRes.json() as { error?: string };
            if (data.error) {
              console.log(`[Preview] Raw endpoint error: ${data.error}`);
              continue;
            }
          }

          const buffer = Buffer.from(await rawRes.arrayBuffer());
          console.log(`[Preview] Got ${buffer.length} bytes from /fs/raw`);

          res.setHeader('Content-Type', contentType || mimeType);
          res.setHeader('Content-Length', buffer.length);
          res.end(buffer);
          return;
        }
      } catch {
        // /fs/raw not available, try /fs/read
      }

      // Fallback to /fs/read (base64 encoded for binary)
      try {
        console.log(`[Preview] Falling back to ${nodeUrl}/fs/read`);
        const readRes = await fetch(`${nodeUrl}/fs/read`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ path: filePath })
        });

        if (readRes.ok) {
          const data = await readRes.json() as { content?: string; error?: string };
          if (data.content && !data.error) {
            // Text content
            if (mimeType.startsWith('text/') || mimeType === 'application/json') {
              res.setHeader('Content-Type', mimeType);
              res.end(data.content);
              return;
            }

            // Binary content - try base64 decode
            const isBase64 = /^[A-Za-z0-9+/=]+$/.test(data.content.slice(0, 100).replace(/\s/g, ''));
            if (isBase64) {
              const buffer = Buffer.from(data.content, 'base64');
              console.log(`[Preview] Got ${buffer.length} bytes from /fs/read (base64)`);
              res.setHeader('Content-Type', mimeType);
              res.setHeader('Content-Length', buffer.length);
              res.end(buffer);
              return;
            } else {
              // Old node returning raw binary in JSON (corrupted, but try anyway)
              console.log(`[Preview] Warning: Node returned non-base64 binary, may be corrupted`);
              const buffer = Buffer.from(data.content, 'latin1');
              res.setHeader('Content-Type', mimeType);
              res.setHeader('Content-Length', buffer.length);
              res.end(buffer);
              return;
            }
          }
        }
      } catch (err) {
        console.log(`[Preview] Error with /fs/read: ${err}`);
      }
    }

    res.statusCode = 404;
    res.end('File not found');
  } catch (e) {
    res.statusCode = 500;
    res.end(`Error: ${(e as Error).message}`);
  }
}
