/**
 * Smart DFS Web Server
 *
 * Provides a web UI for:
 * - Browsing files across all nodes
 * - Searching files
 * - Uploading/downloading files
 * - Viewing disk space
 */

import * as http from 'http';
import * as url from 'url';
import { SmartDFS, formatBytes, formatPercent } from './smart-dfs.js';

const PORT = parseInt(process.env.PORT || '3000');
const nodesEnv = process.env.KRAFT_NODES;
const endpoints = nodesEnv
  ? nodesEnv.split(',').map(n => n.trim())
  : ['http://localhost:7801', 'http://localhost:7802', 'http://localhost:7803'];

const replicationFactor = parseInt(process.env.REPLICATION_FACTOR || '2');

const dfs = new SmartDFS(endpoints, {
  replicationFactor,
  minFreeSpacePercent: 10
});

// HTML Templates
const htmlHead = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Smart DFS - Distributed File System</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      color: #eee;
      min-height: 100vh;
    }
    .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
    header {
      background: rgba(255,255,255,0.1);
      padding: 20px;
      border-radius: 10px;
      margin-bottom: 20px;
    }
    h1 { font-size: 2em; margin-bottom: 10px; }
    h1 span { color: #4ade80; }
    .subtitle { color: #888; }

    .search-box {
      background: rgba(255,255,255,0.1);
      padding: 20px;
      border-radius: 10px;
      margin-bottom: 20px;
    }
    .search-input {
      width: 100%;
      padding: 15px 20px;
      font-size: 1.1em;
      border: none;
      border-radius: 8px;
      background: rgba(255,255,255,0.1);
      color: #fff;
      outline: none;
    }
    .search-input::placeholder { color: #666; }
    .search-input:focus { background: rgba(255,255,255,0.15); }

    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; }

    .card {
      background: rgba(255,255,255,0.05);
      border-radius: 10px;
      padding: 20px;
      border: 1px solid rgba(255,255,255,0.1);
    }
    .card h3 { margin-bottom: 15px; display: flex; align-items: center; gap: 10px; }
    .card h3 .icon { font-size: 1.5em; }

    .node-card { border-left: 4px solid #4ade80; }
    .node-card.warning { border-left-color: #fbbf24; }
    .node-card.danger { border-left-color: #ef4444; }

    .progress-bar {
      height: 8px;
      background: rgba(255,255,255,0.1);
      border-radius: 4px;
      overflow: hidden;
      margin: 10px 0;
    }
    .progress-bar .fill {
      height: 100%;
      background: linear-gradient(90deg, #4ade80, #22c55e);
      transition: width 0.3s;
    }
    .progress-bar .fill.warning { background: linear-gradient(90deg, #fbbf24, #f59e0b); }
    .progress-bar .fill.danger { background: linear-gradient(90deg, #ef4444, #dc2626); }

    .stats { display: flex; justify-content: space-between; color: #888; font-size: 0.9em; }

    .file-list { list-style: none; }
    .file-item {
      display: flex;
      align-items: center;
      padding: 12px;
      border-bottom: 1px solid rgba(255,255,255,0.1);
      gap: 15px;
    }
    .file-item:last-child { border-bottom: none; }
    .file-item:hover { background: rgba(255,255,255,0.05); }
    .file-icon { font-size: 1.5em; }
    .file-info { flex: 1; }
    .file-name { font-weight: 500; }
    .file-meta { color: #888; font-size: 0.85em; }
    .file-node {
      background: rgba(74, 222, 128, 0.2);
      color: #4ade80;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 0.8em;
    }

    .upload-form {
      background: rgba(255,255,255,0.05);
      border: 2px dashed rgba(255,255,255,0.2);
      border-radius: 10px;
      padding: 40px;
      text-align: center;
      margin-bottom: 20px;
    }
    .upload-form input[type="text"] {
      padding: 10px 15px;
      margin: 10px;
      border: none;
      border-radius: 6px;
      background: rgba(255,255,255,0.1);
      color: #fff;
    }
    .upload-form button {
      padding: 12px 30px;
      background: #4ade80;
      color: #000;
      border: none;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
    }
    .upload-form button:hover { background: #22c55e; }

    .badge {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 0.8em;
      margin-left: 10px;
    }
    .badge.success { background: rgba(74, 222, 128, 0.2); color: #4ade80; }
    .badge.warning { background: rgba(251, 191, 36, 0.2); color: #fbbf24; }

    .replicas { margin-top: 10px; }
    .replica-badge {
      display: inline-block;
      background: rgba(255,255,255,0.1);
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 0.8em;
      margin: 2px;
    }

    nav {
      display: flex;
      gap: 15px;
      margin-bottom: 20px;
    }
    nav a {
      color: #888;
      text-decoration: none;
      padding: 10px 20px;
      border-radius: 8px;
      background: rgba(255,255,255,0.05);
    }
    nav a:hover, nav a.active { color: #fff; background: rgba(255,255,255,0.1); }

    .empty-state {
      text-align: center;
      padding: 60px;
      color: #666;
    }
    .empty-state .icon { font-size: 4em; margin-bottom: 20px; }

    .alert {
      padding: 15px 20px;
      border-radius: 8px;
      margin-bottom: 20px;
    }
    .alert.success { background: rgba(74, 222, 128, 0.2); border: 1px solid #4ade80; }
    .alert.error { background: rgba(239, 68, 68, 0.2); border: 1px solid #ef4444; }
  </style>
</head>
<body>
  <div class="container">
`;

const htmlFoot = `
  </div>
</body>
</html>
`;

function renderHeader(currentPage: string) {
  return `
    <header>
      <h1>📦 Smart <span>DFS</span></h1>
      <p class="subtitle">Distributed File System with RAID-like Replication</p>
    </header>
    <nav>
      <a href="/" class="${currentPage === 'home' ? 'active' : ''}">🏠 Dashboard</a>
      <a href="/files" class="${currentPage === 'files' ? 'active' : ''}">📁 Files</a>
      <a href="/search" class="${currentPage === 'search' ? 'active' : ''}">🔍 Search</a>
      <a href="/upload" class="${currentPage === 'upload' ? 'active' : ''}">📤 Upload</a>
    </nav>
  `;
}

async function renderDashboard(): Promise<string> {
  await dfs.discoverNodes();
  const diskInfo = await dfs.getDiskInfo();
  const allFiles = await dfs.listAll();

  let totalFiles = 0;
  allFiles.forEach(n => totalFiles += n.files.length);

  let nodesHtml = '';
  for (const { disk } of diskInfo) {
    const freePercent = 100 - disk.usagePercent;
    const status = freePercent < 10 ? 'danger' : freePercent < 30 ? 'warning' : '';
    const fillClass = freePercent < 10 ? 'danger' : freePercent < 30 ? 'warning' : '';

    nodesHtml += `
      <div class="card node-card ${status}">
        <h3><span class="icon">💾</span> ${disk.hostname}</h3>
        <p style="color: #888; margin-bottom: 10px;">${disk.nodeId}</p>
        <div class="progress-bar">
          <div class="fill ${fillClass}" style="width: ${disk.usagePercent}%"></div>
        </div>
        <div class="stats">
          <span>${formatBytes(disk.usedSpace)} used</span>
          <span>${formatBytes(disk.freeSpace)} free</span>
        </div>
        <p style="margin-top: 10px; color: #888; font-size: 0.85em;">
          Total: ${formatBytes(disk.totalSpace)}
        </p>
      </div>
    `;
  }

  return `
    ${renderHeader('home')}

    <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-bottom: 20px;">
      <div class="card" style="text-align: center;">
        <div style="font-size: 3em;">🖥️</div>
        <div style="font-size: 2em; font-weight: bold;">${diskInfo.length}</div>
        <div style="color: #888;">Storage Nodes</div>
      </div>
      <div class="card" style="text-align: center;">
        <div style="font-size: 3em;">📄</div>
        <div style="font-size: 2em; font-weight: bold;">${totalFiles}</div>
        <div style="color: #888;">Total Files</div>
      </div>
      <div class="card" style="text-align: center;">
        <div style="font-size: 3em;">🔄</div>
        <div style="font-size: 2em; font-weight: bold;">${replicationFactor}x</div>
        <div style="color: #888;">Replication</div>
      </div>
    </div>

    <h2 style="margin-bottom: 15px;">Storage Nodes</h2>
    <div class="grid">
      ${nodesHtml}
    </div>
  `;
}

async function renderFiles(): Promise<string> {
  await dfs.discoverNodes();
  const allFiles = await dfs.listAll();

  let filesHtml = '';
  for (const { node, files } of allFiles) {
    for (const file of files) {
      if (!file.isDirectory) {
        filesHtml += `
          <li class="file-item">
            <span class="file-icon">📄</span>
            <div class="file-info">
              <div class="file-name">${file.name}</div>
              <div class="file-meta">${formatBytes(file.size)} • ${new Date(file.modified).toLocaleDateString()}</div>
            </div>
            <span class="file-node">${node}</span>
          </li>
        `;
      }
    }
  }

  return `
    ${renderHeader('files')}

    <div class="card">
      <h3><span class="icon">📁</span> All Files</h3>
      ${filesHtml ? `<ul class="file-list">${filesHtml}</ul>` : `
        <div class="empty-state">
          <div class="icon">📭</div>
          <p>No files yet. Upload something!</p>
        </div>
      `}
    </div>
  `;
}

async function renderSearch(query?: string): Promise<string> {
  await dfs.discoverNodes();

  let resultsHtml = '';
  if (query) {
    const results = await dfs.search(query);
    if (results.length > 0) {
      for (const file of results) {
        resultsHtml += `
          <li class="file-item">
            <span class="file-icon">📄</span>
            <div class="file-info">
              <div class="file-name">${file.name}</div>
              <div class="file-meta">${file.path}</div>
              <div class="file-meta">${formatBytes(file.size)}</div>
            </div>
            <span class="file-node">${file.hostname}</span>
          </li>
        `;
      }
    } else {
      resultsHtml = `
        <div class="empty-state">
          <div class="icon">🔍</div>
          <p>No files found for "${query}"</p>
        </div>
      `;
    }
  }

  return `
    ${renderHeader('search')}

    <div class="search-box">
      <form action="/search" method="GET">
        <input type="text" name="q" class="search-input"
               placeholder="Search files across all nodes..."
               value="${query || ''}" autofocus>
      </form>
    </div>

    ${query ? `
      <div class="card">
        <h3><span class="icon">🔍</span> Search Results for "${query}"</h3>
        <ul class="file-list">${resultsHtml}</ul>
      </div>
    ` : `
      <div class="empty-state">
        <div class="icon">🔍</div>
        <p>Enter a search term to find files across all nodes</p>
      </div>
    `}
  `;
}

async function renderUpload(message?: string, isError?: boolean): Promise<string> {
  return `
    ${renderHeader('upload')}

    ${message ? `<div class="alert ${isError ? 'error' : 'success'}">${message}</div>` : ''}

    <div class="upload-form">
      <div style="font-size: 4em; margin-bottom: 20px;">📤</div>
      <h2>Upload a File</h2>
      <p style="color: #888; margin: 15px 0;">
        Files will be automatically stored on the ${replicationFactor} nodes with the most free space
      </p>
      <form action="/upload" method="POST">
        <div>
          <input type="text" name="filename" placeholder="filename.txt" required>
        </div>
        <div>
          <textarea name="content" placeholder="File content..."
                    style="width: 80%; height: 150px; padding: 15px; margin: 10px;
                           border: none; border-radius: 6px;
                           background: rgba(255,255,255,0.1); color: #fff;
                           resize: vertical;" required></textarea>
        </div>
        <button type="submit">Upload File</button>
      </form>
    </div>
  `;
}

// HTTP Server
const server = http.createServer(async (req, res) => {
  const parsedUrl = url.parse(req.url || '/', true);
  const pathname = parsedUrl.pathname || '/';

  res.setHeader('Content-Type', 'text/html; charset=utf-8');

  try {
    if (pathname === '/') {
      const html = await renderDashboard();
      res.end(htmlHead + html + htmlFoot);
    } else if (pathname === '/files') {
      const html = await renderFiles();
      res.end(htmlHead + html + htmlFoot);
    } else if (pathname === '/search') {
      const query = parsedUrl.query.q as string | undefined;
      const html = await renderSearch(query);
      res.end(htmlHead + html + htmlFoot);
    } else if (pathname === '/upload') {
      if (req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
          try {
            const params = new URLSearchParams(body);
            const filename = params.get('filename');
            const content = params.get('content');

            if (filename && content) {
              const stored = await dfs.save(filename, content);
              const html = await renderUpload(
                `✓ File "${filename}" saved to ${stored.replicas.map(r => r.hostname).join(', ')}`,
                false
              );
              res.end(htmlHead + html + htmlFoot);
            } else {
              const html = await renderUpload('Missing filename or content', true);
              res.end(htmlHead + html + htmlFoot);
            }
          } catch (e) {
            const html = await renderUpload(`Error: ${(e as Error).message}`, true);
            res.end(htmlHead + html + htmlFoot);
          }
        });
      } else {
        const html = await renderUpload();
        res.end(htmlHead + html + htmlFoot);
      }
    } else {
      res.statusCode = 404;
      res.end(htmlHead + '<h1>404 Not Found</h1>' + htmlFoot);
    }
  } catch (e) {
    res.statusCode = 500;
    res.end(htmlHead + `<h1>Error</h1><p>${(e as Error).message}</p>` + htmlFoot);
  }
});

async function main() {
  console.log('╔══════════════════════════════════════════════════════════════════════════════╗');
  console.log('║              Smart DFS Web Interface                                          ║');
  console.log('╚══════════════════════════════════════════════════════════════════════════════╝');
  console.log();

  console.log('Discovering nodes...');
  const nodes = await dfs.discoverNodes();
  console.log(`Found ${nodes.length} node(s)`);
  console.log();

  server.listen(PORT, () => {
    console.log(`🌐 Web UI running at http://localhost:${PORT}`);
    console.log();
    console.log('Pages:');
    console.log(`  http://localhost:${PORT}/        - Dashboard`);
    console.log(`  http://localhost:${PORT}/files   - Browse files`);
    console.log(`  http://localhost:${PORT}/search  - Search files`);
    console.log(`  http://localhost:${PORT}/upload  - Upload files`);
    console.log();
  });
}

main().catch(console.error);
