/**
 * Upload route - handles file uploads
 */

import type { IncomingMessage, ServerResponse } from 'http';
import { SmartDFS } from '../../smart-dfs.js';
import { renderLayout } from '../views/layout.js';
import { renderUpload } from '../views/upload.js';

export async function handleUploadGet(
  res: ServerResponse,
  dfs: SmartDFS
): Promise<void> {
  const content = await renderUpload(dfs);
  res.end(renderLayout({ content, currentPage: 'upload' }));
}

export async function handleUploadPost(
  req: IncomingMessage,
  res: ServerResponse,
  dfs: SmartDFS,
  endpoints: string[]
): Promise<void> {
  let body = '';

  req.on('data', chunk => body += chunk);

  req.on('end', async () => {
    try {
      const params = new URLSearchParams(body);
      const filename = params.get('filename');
      const content = params.get('content');
      const replicas = parseInt(params.get('replicas') || '2');

      if (filename && content) {
        // Create DFS with custom replication
        const customDfs = new SmartDFS(endpoints, {
          replicationFactor: replicas,
          minFreeSpacePercent: 10
        });
        await customDfs.discoverNodes();

        const stored = await customDfs.save(filename, content);
        const nodeNames = stored.replicas.map(r => r.hostname).join(', ');

        const html = await renderUpload(dfs, {
          message: `File "${filename}" saved to ${stored.replicas.length} node(s): ${nodeNames}`,
          isError: false
        });
        res.end(renderLayout({ content: html, currentPage: 'upload' }));
      } else {
        const html = await renderUpload(dfs, {
          message: 'Missing filename or content',
          isError: true
        });
        res.end(renderLayout({ content: html, currentPage: 'upload' }));
      }
    } catch (e) {
      const html = await renderUpload(dfs, {
        message: `Error: ${(e as Error).message}`,
        isError: true
      });
      res.end(renderLayout({ content: html, currentPage: 'upload' }));
    }
  });
}
