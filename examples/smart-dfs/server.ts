/**
 * Smart DFS Web Server
 *
 * Provides a web UI for:
 * - Visual file browser (like an OS)
 * - Drag & drop file upload
 * - Per-file replication settings
 * - Search across all nodes
 * - Disk space visualization
 */

import * as http from 'http';
import * as url from 'url';
import { SmartDFS, formatBytes, formatPercent, StoredFile } from './smart-dfs.js';

const PORT = parseInt(process.env.PORT || '3000');
const nodesEnv = process.env.KRAFT_NODES;
const endpoints = nodesEnv
  ? nodesEnv.split(',').map(n => n.trim())
  : ['http://localhost:7801', 'http://localhost:7802', 'http://localhost:7803'];

const defaultReplicationFactor = parseInt(process.env.REPLICATION_FACTOR || '2');

const dfs = new SmartDFS(endpoints, {
  replicationFactor: defaultReplicationFactor,
  minFreeSpacePercent: 10
});

// Store file metadata with replication info
const fileRegistry: Map<string, StoredFile> = new Map();

// HTML Template
const htmlTemplate = (content: string, currentPage: string) => `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Smart DFS - Distributed File System</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%);
      color: #eee;
      min-height: 100vh;
    }
    .container { max-width: 1400px; margin: 0 auto; padding: 20px; }

    header {
      background: rgba(255,255,255,0.05);
      backdrop-filter: blur(10px);
      padding: 20px 30px;
      border-radius: 16px;
      margin-bottom: 25px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border: 1px solid rgba(255,255,255,0.1);
    }
    .logo { font-size: 1.8em; font-weight: 700; }
    .logo span { color: #4ade80; }

    nav { display: flex; gap: 8px; }
    nav a {
      color: #888;
      text-decoration: none;
      padding: 10px 20px;
      border-radius: 10px;
      transition: all 0.2s;
      font-weight: 500;
    }
    nav a:hover { color: #fff; background: rgba(255,255,255,0.1); }
    nav a.active { color: #fff; background: rgba(74,222,128,0.2); }

    .stats-row {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      margin-bottom: 25px;
    }
    .stat-card {
      background: rgba(255,255,255,0.05);
      border-radius: 16px;
      padding: 25px;
      text-align: center;
      border: 1px solid rgba(255,255,255,0.1);
    }
    .stat-icon { font-size: 2.5em; margin-bottom: 10px; }
    .stat-value { font-size: 2.2em; font-weight: 700; }
    .stat-label { color: #888; margin-top: 5px; }

    .card {
      background: rgba(255,255,255,0.05);
      border-radius: 16px;
      padding: 25px;
      border: 1px solid rgba(255,255,255,0.1);
      margin-bottom: 25px;
    }
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 20px;
    }
    .card-title { font-size: 1.3em; font-weight: 600; display: flex; align-items: center; gap: 10px; }

    /* File Browser Styles */
    .file-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
      gap: 15px;
    }
    .file-item {
      background: rgba(255,255,255,0.03);
      border-radius: 12px;
      padding: 20px 15px;
      text-align: center;
      cursor: pointer;
      transition: all 0.2s;
      border: 2px solid transparent;
    }
    .file-item:hover {
      background: rgba(255,255,255,0.08);
      border-color: rgba(74,222,128,0.3);
      transform: translateY(-2px);
    }
    .file-item.selected {
      background: rgba(74,222,128,0.15);
      border-color: #4ade80;
    }
    .file-icon { font-size: 3em; margin-bottom: 10px; }
    .file-name {
      font-size: 0.9em;
      word-break: break-all;
      line-height: 1.3;
    }
    .file-size { font-size: 0.75em; color: #888; margin-top: 5px; }
    .file-replicas {
      display: flex;
      justify-content: center;
      gap: 3px;
      margin-top: 8px;
    }
    .replica-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #4ade80;
    }

    /* Drag & Drop Zone */
    .drop-zone {
      border: 3px dashed rgba(255,255,255,0.2);
      border-radius: 16px;
      padding: 60px 40px;
      text-align: center;
      transition: all 0.3s;
      margin-bottom: 25px;
    }
    .drop-zone.drag-over {
      border-color: #4ade80;
      background: rgba(74,222,128,0.1);
    }
    .drop-zone-icon { font-size: 4em; margin-bottom: 20px; opacity: 0.5; }
    .drop-zone-text { font-size: 1.2em; color: #888; margin-bottom: 15px; }
    .drop-zone-hint { font-size: 0.9em; color: #666; }

    /* Upload Form */
    .upload-options {
      display: flex;
      gap: 20px;
      align-items: center;
      justify-content: center;
      margin-top: 20px;
      padding-top: 20px;
      border-top: 1px solid rgba(255,255,255,0.1);
    }
    .option-group { display: flex; align-items: center; gap: 10px; }
    .option-label { color: #888; }
    select, input[type="text"] {
      padding: 10px 15px;
      border: none;
      border-radius: 8px;
      background: rgba(255,255,255,0.1);
      color: #fff;
      font-size: 1em;
    }
    select:focus, input:focus { outline: 2px solid #4ade80; }

    .btn {
      padding: 12px 30px;
      border: none;
      border-radius: 10px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
      font-size: 1em;
    }
    .btn-primary { background: #4ade80; color: #000; }
    .btn-primary:hover { background: #22c55e; transform: translateY(-1px); }
    .btn-secondary { background: rgba(255,255,255,0.1); color: #fff; }
    .btn-secondary:hover { background: rgba(255,255,255,0.2); }

    /* Node Cards */
    .nodes-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; }
    .node-card {
      background: rgba(255,255,255,0.03);
      border-radius: 12px;
      padding: 20px;
      border-left: 4px solid #4ade80;
    }
    .node-card.warning { border-left-color: #fbbf24; }
    .node-card.danger { border-left-color: #ef4444; }
    .node-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; }
    .node-name { font-weight: 600; font-size: 1.1em; }
    .node-status {
      padding: 4px 12px;
      border-radius: 20px;
      font-size: 0.8em;
      font-weight: 500;
    }
    .node-status.ok { background: rgba(74,222,128,0.2); color: #4ade80; }
    .node-status.warning { background: rgba(251,191,36,0.2); color: #fbbf24; }
    .node-status.danger { background: rgba(239,68,68,0.2); color: #ef4444; }

    .progress-bar {
      height: 10px;
      background: rgba(255,255,255,0.1);
      border-radius: 5px;
      overflow: hidden;
      margin: 12px 0;
    }
    .progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #4ade80, #22c55e);
      transition: width 0.3s;
    }
    .progress-fill.warning { background: linear-gradient(90deg, #fbbf24, #f59e0b); }
    .progress-fill.danger { background: linear-gradient(90deg, #ef4444, #dc2626); }
    .node-stats { display: flex; justify-content: space-between; color: #888; font-size: 0.85em; }

    /* Search */
    .search-box {
      position: relative;
      margin-bottom: 25px;
    }
    .search-input {
      width: 100%;
      padding: 18px 25px 18px 55px;
      font-size: 1.1em;
      border: none;
      border-radius: 14px;
      background: rgba(255,255,255,0.08);
      color: #fff;
    }
    .search-input::placeholder { color: #666; }
    .search-icon {
      position: absolute;
      left: 20px;
      top: 50%;
      transform: translateY(-50%);
      font-size: 1.3em;
      opacity: 0.5;
    }

    /* Alerts */
    .alert {
      padding: 16px 20px;
      border-radius: 12px;
      margin-bottom: 20px;
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .alert-success { background: rgba(74,222,128,0.15); border: 1px solid rgba(74,222,128,0.3); }
    .alert-error { background: rgba(239,68,68,0.15); border: 1px solid rgba(239,68,68,0.3); }

    /* Empty State */
    .empty-state {
      text-align: center;
      padding: 80px 40px;
      color: #666;
    }
    .empty-icon { font-size: 5em; margin-bottom: 20px; opacity: 0.3; }
    .empty-text { font-size: 1.2em; }

    /* File Details Modal */
    .file-details {
      background: rgba(0,0,0,0.3);
      padding: 20px;
      border-radius: 12px;
      margin-top: 15px;
    }
    .detail-row {
      display: flex;
      justify-content: space-between;
      padding: 8px 0;
      border-bottom: 1px solid rgba(255,255,255,0.1);
    }
    .detail-row:last-child { border-bottom: none; }
    .detail-label { color: #888; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo">📦 Smart <span>DFS</span></div>
      <nav>
        <a href="/" class="${currentPage === 'home' ? 'active' : ''}">🏠 Dashboard</a>
        <a href="/files" class="${currentPage === 'files' ? 'active' : ''}">📁 Files</a>
        <a href="/upload" class="${currentPage === 'upload' ? 'active' : ''}">📤 Upload</a>
        <a href="/search" class="${currentPage === 'search' ? 'active' : ''}">🔍 Search</a>
      </nav>
    </header>
    ${content}
  </div>

  <script>
    // Drag and drop handling
    const dropZone = document.querySelector('.drop-zone');
    if (dropZone) {
      ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(event => {
        dropZone.addEventListener(event, e => {
          e.preventDefault();
          e.stopPropagation();
        });
      });

      ['dragenter', 'dragover'].forEach(event => {
        dropZone.addEventListener(event, () => dropZone.classList.add('drag-over'));
      });

      ['dragleave', 'drop'].forEach(event => {
        dropZone.addEventListener(event, () => dropZone.classList.remove('drag-over'));
      });

      dropZone.addEventListener('drop', async (e) => {
        const files = e.dataTransfer.files;
        if (files.length > 0) {
          const file = files[0];
          const replicas = document.getElementById('replicas')?.value || '2';

          // Read file content
          const reader = new FileReader();
          reader.onload = async (event) => {
            const content = event.target.result;

            // Submit via form
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = '/upload';

            const filenameInput = document.createElement('input');
            filenameInput.type = 'hidden';
            filenameInput.name = 'filename';
            filenameInput.value = file.name;
            form.appendChild(filenameInput);

            const contentInput = document.createElement('input');
            contentInput.type = 'hidden';
            contentInput.name = 'content';
            contentInput.value = content;
            form.appendChild(contentInput);

            const replicasInput = document.createElement('input');
            replicasInput.type = 'hidden';
            replicasInput.name = 'replicas';
            replicasInput.value = replicas;
            form.appendChild(replicasInput);

            document.body.appendChild(form);
            form.submit();
          };
          reader.readAsText(file);
        }
      });
    }

    // File selection
    document.querySelectorAll('.file-item').forEach(item => {
      item.addEventListener('click', () => {
        document.querySelectorAll('.file-item').forEach(i => i.classList.remove('selected'));
        item.classList.add('selected');
      });
    });
  </script>
</body>
</html>
`;

async function renderDashboard(): Promise<string> {
  await dfs.discoverNodes();
  const diskInfo = await dfs.getDiskInfo();
  const allFiles = await dfs.listAll();

  let totalFiles = 0;
  let totalSize = 0;
  allFiles.forEach(n => {
    n.files.forEach(f => {
      if (!f.isDirectory) {
        totalFiles++;
        totalSize += f.size;
      }
    });
  });

  const totalFreeSpace = diskInfo.reduce((sum, d) => sum + d.disk.freeSpace, 0);

  let nodesHtml = '';
  for (const { disk } of diskInfo) {
    const freePercent = 100 - disk.usagePercent;
    const status = freePercent < 10 ? 'danger' : freePercent < 30 ? 'warning' : 'ok';

    nodesHtml += `
      <div class="node-card ${status === 'ok' ? '' : status}">
        <div class="node-header">
          <span class="node-name">💾 ${disk.hostname}</span>
          <span class="node-status ${status}">${status.toUpperCase()}</span>
        </div>
        <div class="progress-bar">
          <div class="progress-fill ${status === 'ok' ? '' : status}" style="width: ${disk.usagePercent}%"></div>
        </div>
        <div class="node-stats">
          <span>${formatBytes(disk.usedSpace)} used</span>
          <span>${formatBytes(disk.freeSpace)} free</span>
        </div>
      </div>
    `;
  }

  return `
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon">🖥️</div>
        <div class="stat-value">${diskInfo.length}</div>
        <div class="stat-label">Storage Nodes</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">📄</div>
        <div class="stat-value">${totalFiles}</div>
        <div class="stat-label">Total Files</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">💾</div>
        <div class="stat-value">${formatBytes(totalSize)}</div>
        <div class="stat-label">Total Data</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">📊</div>
        <div class="stat-value">${formatBytes(totalFreeSpace)}</div>
        <div class="stat-label">Free Space</div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <span class="card-title">🖥️ Storage Nodes</span>
      </div>
      <div class="nodes-grid">
        ${nodesHtml}
      </div>
    </div>
  `;
}

async function renderFiles(): Promise<string> {
  await dfs.discoverNodes();
  const allFiles = await dfs.listAll();

  // Collect all files with their locations
  const fileMap = new Map<string, { name: string; size: number; nodes: string[] }>();

  for (const { node, files } of allFiles) {
    for (const file of files) {
      if (!file.isDirectory) {
        const existing = fileMap.get(file.name);
        if (existing) {
          existing.nodes.push(node);
        } else {
          fileMap.set(file.name, { name: file.name, size: file.size, nodes: [node] });
        }
      }
    }
  }

  let filesHtml = '';
  for (const [name, file] of fileMap) {
    const icon = getFileIcon(name);
    const replicaDots = file.nodes.map(() => '<div class="replica-dot"></div>').join('');

    filesHtml += `
      <div class="file-item" title="${file.nodes.join(', ')}">
        <div class="file-icon">${icon}</div>
        <div class="file-name">${name}</div>
        <div class="file-size">${formatBytes(file.size)}</div>
        <div class="file-replicas" title="${file.nodes.length} replica(s): ${file.nodes.join(', ')}">${replicaDots}</div>
      </div>
    `;
  }

  return `
    <div class="card">
      <div class="card-header">
        <span class="card-title">📁 All Files</span>
        <a href="/upload" class="btn btn-primary">+ Upload File</a>
      </div>
      ${fileMap.size > 0 ? `
        <div class="file-grid">${filesHtml}</div>
      ` : `
        <div class="empty-state">
          <div class="empty-icon">📭</div>
          <div class="empty-text">No files yet</div>
          <p style="margin-top: 15px;">
            <a href="/upload" class="btn btn-primary">Upload your first file</a>
          </p>
        </div>
      `}
    </div>
  `;
}

async function renderUpload(message?: string, isError?: boolean): Promise<string> {
  const nodes = dfs.getNodes();

  return `
    ${message ? `
      <div class="alert ${isError ? 'alert-error' : 'alert-success'}">
        ${isError ? '❌' : '✅'} ${message}
      </div>
    ` : ''}

    <div class="drop-zone" id="dropZone">
      <div class="drop-zone-icon">📥</div>
      <div class="drop-zone-text">Drag & drop files here</div>
      <div class="drop-zone-hint">or click to browse</div>

      <div class="upload-options">
        <div class="option-group">
          <span class="option-label">Replicas:</span>
          <select id="replicas" name="replicas">
            <option value="1">1 (No redundancy)</option>
            <option value="2" selected>2 (Recommended)</option>
            <option value="3" ${nodes.length >= 3 ? '' : 'disabled'}>3 (High availability)</option>
          </select>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <span class="card-title">📝 Or paste content directly</span>
      </div>
      <form action="/upload" method="POST" style="display: flex; flex-direction: column; gap: 15px;">
        <input type="text" name="filename" placeholder="filename.txt" required style="width: 100%;">
        <textarea name="content" placeholder="Paste your content here..."
                  style="width: 100%; height: 200px; padding: 15px; border: none; border-radius: 10px;
                         background: rgba(255,255,255,0.08); color: #fff; resize: vertical;" required></textarea>
        <div style="display: flex; gap: 15px; align-items: center;">
          <div class="option-group">
            <span class="option-label">Replicas:</span>
            <select name="replicas">
              <option value="1">1</option>
              <option value="2" selected>2</option>
              <option value="3" ${nodes.length >= 3 ? '' : 'disabled'}>3</option>
            </select>
          </div>
          <button type="submit" class="btn btn-primary">Upload File</button>
        </div>
      </form>
    </div>
  `;
}

async function renderSearch(query?: string): Promise<string> {
  let resultsHtml = '';

  if (query) {
    await dfs.discoverNodes();
    const results = await dfs.search(query);

    if (results.length > 0) {
      for (const file of results) {
        const icon = getFileIcon(file.name);
        resultsHtml += `
          <div class="file-item">
            <div class="file-icon">${icon}</div>
            <div class="file-name">${file.name}</div>
            <div class="file-size">${formatBytes(file.size)}</div>
            <div class="file-replicas">
              <div class="replica-dot" title="${file.hostname}"></div>
            </div>
          </div>
        `;
      }
    }
  }

  return `
    <div class="search-box">
      <span class="search-icon">🔍</span>
      <form action="/search" method="GET" style="width: 100%;">
        <input type="text" name="q" class="search-input"
               placeholder="Search files across all nodes..."
               value="${query || ''}" autofocus>
      </form>
    </div>

    ${query ? `
      <div class="card">
        <div class="card-header">
          <span class="card-title">Search Results for "${query}"</span>
        </div>
        ${resultsHtml ? `
          <div class="file-grid">${resultsHtml}</div>
        ` : `
          <div class="empty-state">
            <div class="empty-icon">🔍</div>
            <div class="empty-text">No files found</div>
          </div>
        `}
      </div>
    ` : `
      <div class="empty-state">
        <div class="empty-icon">🔍</div>
        <div class="empty-text">Enter a search term to find files</div>
      </div>
    `}
  `;
}

function getFileIcon(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  const icons: Record<string, string> = {
    // Images
    'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️', 'svg': '🖼️', 'webp': '🖼️',
    // Videos
    'mp4': '🎬', 'avi': '🎬', 'mov': '🎬', 'mkv': '🎬', 'webm': '🎬',
    // Audio
    'mp3': '🎵', 'wav': '🎵', 'flac': '🎵', 'ogg': '🎵', 'm4a': '🎵',
    // Documents
    'pdf': '📕', 'doc': '📘', 'docx': '📘', 'xls': '📗', 'xlsx': '📗',
    'ppt': '📙', 'pptx': '📙',
    // Code
    'js': '📜', 'ts': '📜', 'py': '🐍', 'java': '☕', 'scala': '🔴',
    'html': '🌐', 'css': '🎨', 'json': '📋', 'xml': '📋',
    // Archives
    'zip': '📦', 'tar': '📦', 'gz': '📦', 'rar': '📦', '7z': '📦',
    // Text
    'txt': '📄', 'md': '📝', 'log': '📃',
  };
  return icons[ext] || '📄';
}

// HTTP Server
const server = http.createServer(async (req, res) => {
  const parsedUrl = url.parse(req.url || '/', true);
  const pathname = parsedUrl.pathname || '/';

  res.setHeader('Content-Type', 'text/html; charset=utf-8');

  try {
    if (pathname === '/') {
      const content = await renderDashboard();
      res.end(htmlTemplate(content, 'home'));
    } else if (pathname === '/files') {
      const content = await renderFiles();
      res.end(htmlTemplate(content, 'files'));
    } else if (pathname === '/upload') {
      if (req.method === 'POST') {
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

              const html = await renderUpload(
                `File "${filename}" saved to ${stored.replicas.length} node(s): ${nodeNames}`,
                false
              );
              res.end(htmlTemplate(html, 'upload'));
            } else {
              const html = await renderUpload('Missing filename or content', true);
              res.end(htmlTemplate(html, 'upload'));
            }
          } catch (e) {
            const html = await renderUpload(`Error: ${(e as Error).message}`, true);
            res.end(htmlTemplate(html, 'upload'));
          }
        });
      } else {
        const html = await renderUpload();
        res.end(htmlTemplate(html, 'upload'));
      }
    } else if (pathname === '/search') {
      const query = parsedUrl.query.q as string | undefined;
      const content = await renderSearch(query);
      res.end(htmlTemplate(content, 'search'));
    } else {
      res.statusCode = 404;
      res.end(htmlTemplate('<h1>404 Not Found</h1>', ''));
    }
  } catch (e) {
    res.statusCode = 500;
    res.end(htmlTemplate(`<div class="alert alert-error">Error: ${(e as Error).message}</div>`, ''));
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
    console.log(`🌐 Web UI: http://localhost:${PORT}`);
    console.log();
    console.log('Features:');
    console.log('  • Drag & drop file upload');
    console.log('  • Per-file replication settings');
    console.log('  • Visual file browser');
    console.log('  • Search across all nodes');
    console.log();
  });
}

main().catch(console.error);
