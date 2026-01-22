/**
 * Files view - macOS Finder-style file browser
 */

import { formatBytes } from '../../smart-dfs.js';
import type { SmartDFS } from '../../smart-dfs.js';
import { isImageFile, isVideoFile, isTextFile, getLanguage } from '../utils/index.js';
import { getFileIcon, FOLDER_ICON } from '../utils/icons.js';
import { baseStyles, themeScript } from '../styles/base.js';
import { getFinderStyles } from '../styles/finder.js';

export interface ConnectionStateInfo {
  nodeId: string;
  status: 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'failed';
  hostname?: string;
  endpoint?: string;
  wsEndpoint?: string;
  lastConnected?: number;
  lastHeartbeat?: number;
  reconnectAttempts?: number;
}

export interface FilesViewOptions {
  searchQuery?: string;
  selectedNode?: string;
  currentPath?: string;
  viewMode?: string;
  showHidden?: boolean;
  connectionStates?: Record<string, ConnectionStateInfo>;
}

interface FileEntry {
  name: string;
  size: number;
  nodes: string[];
  nodeIds: string[];  // Node IDs for routing
  path: string;
  isDirectory: boolean;
  modified?: number;
  primaryNodeId?: string;  // Primary node to fetch from
}

export async function renderFiles(dfs: SmartDFS, options: FilesViewOptions = {}): Promise<string> {
  const {
    searchQuery,
    selectedNode,
    currentPath,
    viewMode,
    showHidden,
    connectionStates = {}
  } = options;

  await dfs.discoverNodes();
  const discoveredNodes = dfs.getNodes();
  const view = viewMode || 'list';
  const showHiddenFiles = showHidden === true;

  // Merge discovered nodes with persisted connection states
  // This ensures we show nodes that were previously connected but may be offline now
  const allNodes = new Map<string, {
    info: { nodeId: string; hostname: string; storagePath?: string };
    endpoint: string;
    wsEndpoint?: string;
    status: string;
    storagePath: string;
  }>();

  // Add discovered nodes (these are online)
  for (const node of discoveredNodes) {
    const storagePath = node.info.storagePath || '/';
    allNodes.set(node.info.nodeId, {
      info: { ...node.info, storagePath },
      endpoint: node.endpoint,
      wsEndpoint: (node as any).wsEndpoint,
      status: 'connected',
      storagePath
    });
  }

  // Add persisted nodes that weren't discovered (these may be offline)
  for (const [nodeId, state] of Object.entries(connectionStates)) {
    if (!allNodes.has(nodeId)) {
      allNodes.set(nodeId, {
        info: { nodeId, hostname: state.hostname || nodeId },
        endpoint: state.endpoint || '',
        wsEndpoint: state.wsEndpoint,
        status: state.status || 'disconnected',
        storagePath: '/'  // Unknown for offline nodes
      });
    } else {
      // Update status from connection manager for discovered nodes
      const existing = allNodes.get(nodeId)!;
      existing.status = state.status || 'connected';
    }
  }

  const nodes = Array.from(allNodes.values());

  // Determine the root path - use selected node's storagePath or first available
  let rootPath = '/';
  if (selectedNode) {
    const selectedNodeData = allNodes.get(selectedNode);
    if (selectedNodeData) {
      rootPath = selectedNodeData.storagePath;
    }
  } else if (nodes.length > 0) {
    // Use first node's storage path as default
    rootPath = nodes[0].storagePath;
  }

  // Use provided path or default to root
  const path = currentPath || rootPath;

  // Get files - either via search or listing
  let files: FileEntry[] = [];
  let searchInfo = '';

  // Helper to find nodeId from hostname
  const hostnameToNodeId = new Map<string, string>();
  for (const node of nodes) {
    hostnameToNodeId.set(node.info.hostname, node.info.nodeId);
  }

  if (searchQuery) {
    const searchResults = await dfs.search(searchQuery);
    const fileMap = new Map<string, FileEntry>();

    for (const result of searchResults) {
      if (selectedNode && result.nodeId !== selectedNode) continue;
      if (!showHiddenFiles && result.name.startsWith('.')) continue;

      const existing = fileMap.get(result.name);
      if (existing) {
        if (!existing.nodes.includes(result.hostname)) {
          existing.nodes.push(result.hostname);
          existing.nodeIds.push(result.nodeId);
        }
      } else {
        fileMap.set(result.name, {
          name: result.name,
          size: result.size,
          nodes: [result.hostname],
          nodeIds: [result.nodeId],
          path: result.path,
          isDirectory: result.isDirectory,
          primaryNodeId: result.nodeId
        });
      }
    }
    files = Array.from(fileMap.values());
    searchInfo = `${files.length} item(s) matching "${searchQuery}"`;
  } else {
    const allFiles = await dfs.listPath(path) || [];
    const fileMap = new Map<string, FileEntry>();

    for (const { node, files: nodeFiles } of allFiles) {
      // Find the nodeId for this hostname
      const nodeId = hostnameToNodeId.get(node) || selectedNode || '';

      if (selectedNode) {
        const nodeInfo = nodes.find(n => n.info.nodeId === selectedNode);
        if (nodeInfo && node !== nodeInfo.info.hostname) continue;
      }

      for (const file of (nodeFiles || [])) {
        if (!showHiddenFiles && file.name.startsWith('.')) continue;

        const existing = fileMap.get(file.name);
        if (existing) {
          if (!existing.nodes.includes(node)) {
            existing.nodes.push(node);
            existing.nodeIds.push(nodeId);
          }
        } else {
          fileMap.set(file.name, {
            name: file.name,
            size: file.size,
            nodes: [node],
            nodeIds: [nodeId],
            path: file.path,
            isDirectory: file.isDirectory,
            modified: file.modified,
            primaryNodeId: nodeId
          });
        }
      }
    }
    files = Array.from(fileMap.values());
  }

  // Sort: directories first, then by name
  files.sort((a, b) => {
    if (a.isDirectory && !b.isDirectory) return -1;
    if (!a.isDirectory && b.isDirectory) return 1;
    return a.name.localeCompare(b.name);
  });

  // Build UI components
  const pathBarHtml = buildPathBar(path, rootPath, view, showHiddenFiles, selectedNode);
  const sidebarHtml = buildSidebar(nodes, path, view, showHiddenFiles, selectedNode);
  const { listHtml, gridHtml, previewableFiles } = buildFileList(files, path, view, showHiddenFiles, selectedNode);

  // Get current folder name - relative to root
  const relativePath = path.startsWith(rootPath)
    ? path.slice(rootPath.length).replace(/^\//, '')
    : path.replace(/^\//, '');
  const pathParts = relativePath.split('/').filter(p => p);
  const currentFolder = pathParts.length > 0 ? pathParts[pathParts.length - 1] : 'Home';

  // Get selected node's hostname
  const selectedNodeInfo = selectedNode ? nodes.find(n => n.info.nodeId === selectedNode) : undefined;
  const selectedNodeHostname = selectedNodeInfo?.info.hostname;

  return renderFinderLayout({
    currentFolder,
    path,
    rootPath,
    view,
    showHiddenFiles,
    selectedNode,
    selectedNodeHostname,
    searchQuery,
    nodes,
    files,
    pathBarHtml,
    sidebarHtml,
    listHtml,
    gridHtml,
    searchInfo,
    previewableFiles
  });
}

function buildPathBar(
  path: string,
  rootPath: string,
  view: string,
  showHiddenFiles: boolean,
  selectedNode?: string
): string {
  // Calculate relative path from root
  const relativePath = path.startsWith(rootPath)
    ? path.slice(rootPath.length).replace(/^\//, '')
    : path.replace(/^\//, '');

  const pathParts = relativePath.split('/').filter(p => p);

  // Home link goes to the root path
  let pathBarHtml = `<a href="/files?path=${encodeURIComponent(rootPath)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}" class="pathbar-item">🏠</a>`;

  let buildPath = rootPath;
  for (const part of pathParts) {
    buildPath = buildPath.endsWith('/') ? buildPath + part : buildPath + '/' + part;
    pathBarHtml += `<span class="pathbar-sep">›</span><a href="/files?path=${encodeURIComponent(buildPath)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}" class="pathbar-item">${part}</a>`;
  }

  return pathBarHtml;
}

function buildSidebar(
  nodes: { endpoint: string; info: { nodeId: string; hostname: string }; status?: string; storagePath: string }[],
  path: string,
  view: string,
  showHiddenFiles: boolean,
  selectedNode?: string
): string {
  let sidebarHtml = '';
  for (const node of nodes) {
    const { info, status = 'connected', storagePath } = node;
    const isActive = selectedNode === info.nodeId;
    const isOffline = status !== 'connected';
    const statusIcon = status === 'connected' ? '🟢' :
                       status === 'reconnecting' || status === 'connecting' ? '🟡' :
                       status === 'failed' ? '🔴' : '⚪';
    const statusClass = isOffline ? 'offline' : '';

    // Use node's storagePath when switching, keep current path if already on this node
    const nodePath = isActive ? path : storagePath;

    sidebarHtml += `
      <div class="sidebar-item ${isActive ? 'active' : ''} ${statusClass}" onclick="location.href='/files?path=${encodeURIComponent(nodePath)}&view=${view}&hidden=${showHiddenFiles}&node=${info.nodeId}'" title="${info.hostname} - ${status} (${storagePath})">
        <span class="sidebar-icon">💻</span>
        <span>${info.hostname}</span>
        <span class="node-status-dot" title="${status}">${statusIcon}</span>
      </div>`;
  }
  return sidebarHtml;
}

function buildFileList(
  files: FileEntry[],
  path: string,
  view: string,
  showHiddenFiles: boolean,
  selectedNode?: string
): { listHtml: string; gridHtml: string; previewableFiles: { path: string; name: string; type: string; nodeId?: string }[] } {
  let listHtml = '';
  let gridHtml = '';
  const previewableFiles: { path: string; name: string; type: string; nodeId?: string }[] = [];
  let fileIndex = 0;

  for (const file of files) {
    const icon = file.isDirectory ? FOLDER_ICON : getFileIcon(file.name);
    const isImage = isImageFile(file.name);
    const isVideo = isVideoFile(file.name);
    const isText = isTextFile(file.name);
    const isPreviewable = isImage || isVideo;

    // Use the file's primary node, or fall back to selectedNode
    const fileNodeId = file.primaryNodeId || selectedNode || '';
    const nodeParam = fileNodeId ? `&node=${fileNodeId}` : '';

    // Track previewable files
    if (!file.isDirectory && isPreviewable) {
      previewableFiles.push({
        path: file.path,
        name: file.name,
        type: isImage ? 'image' : 'video',
        nodeId: fileNodeId
      });
    }

    // Click action
    let clickAction: string;
    if (file.isDirectory) {
      clickAction = `onclick="location.href='/files?path=${encodeURIComponent(file.path)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'"`;
    } else if (isPreviewable) {
      clickAction = `onclick="openPreview(${fileIndex})" data-index="${fileIndex}"`;
    } else if (isText) {
      clickAction = `onclick="openEditor('${encodeURIComponent(file.path)}', '${file.name.replace(/'/g, "\\'")}', '${getLanguage(file.name)}', '${fileNodeId}')"`;
    } else {
      clickAction = `onclick="window.open('/preview?path=${encodeURIComponent(file.path)}${nodeParam}', '_blank')"`;
    }

    const nodeList = file.nodes.join(', ');
    const modifiedDate = file.modified ? new Date(file.modified).toLocaleDateString() : '--';

    // List view item - include node in thumbnail URL (no lazy loading for instant display)
    const listIconHtml = isImage
      ? `<img class="list-thumbnail" src="/preview?path=${encodeURIComponent(file.path)}&thumb=1${nodeParam}" alt="">`
      : `<span class="list-icon">${icon}</span>`;

    listHtml += `
      <div class="list-item" ${clickAction} data-path="${file.path}" data-node="${fileNodeId}" data-type="${isImage ? 'image' : isVideo ? 'video' : 'file'}">
        ${listIconHtml}
        <span class="list-name">${file.name}</span>
        <span class="list-size">${file.isDirectory ? '--' : formatBytes(file.size)}</span>
        <span class="list-modified">${modifiedDate}</span>
        <span class="list-location">${nodeList}</span>
      </div>`;

    // Grid view item - include node in thumbnail URL (no lazy loading for instant display)
    let gridIconHtml: string;
    if (isImage) {
      gridIconHtml = `<div class="grid-thumbnail"><img src="/preview?path=${encodeURIComponent(file.path)}&thumb=1${nodeParam}" alt="${file.name}"></div>`;
    } else if (isVideo) {
      gridIconHtml = `<div class="grid-thumbnail" style="display:flex;align-items:center;justify-content:center;font-size:32px;background:#1a1a1a;">🎬</div>`;
    } else {
      gridIconHtml = `<span class="grid-icon">${icon}</span>`;
    }

    gridHtml += `
      <div class="grid-item" ${clickAction} data-path="${file.path}" data-node="${fileNodeId}" data-type="${isImage ? 'image' : isVideo ? 'video' : 'file'}">
        ${gridIconHtml}
        <span class="grid-name">${file.name.length > 20 ? file.name.substring(0, 18) + '...' : file.name}</span>
      </div>`;

    if (!file.isDirectory) fileIndex++;
  }

  return { listHtml, gridHtml, previewableFiles };
}

interface FinderLayoutOptions {
  currentFolder: string;
  path: string;
  rootPath: string;
  view: string;
  showHiddenFiles: boolean;
  selectedNode?: string;
  selectedNodeHostname?: string;
  searchQuery?: string;
  nodes: { endpoint: string; info: { nodeId: string; hostname: string }; status?: string; storagePath: string }[];
  files: FileEntry[];
  pathBarHtml: string;
  sidebarHtml: string;
  listHtml: string;
  gridHtml: string;
  searchInfo: string;
  previewableFiles: { path: string; name: string; type: string; nodeId?: string }[];
}

function renderFinderLayout(opts: FinderLayoutOptions): string {
  const {
    currentFolder, path, view, showHiddenFiles, selectedNode, selectedNodeHostname, searchQuery,
    nodes, files, pathBarHtml, sidebarHtml, listHtml, gridHtml, searchInfo,
    previewableFiles
  } = opts;

  const titlebarText = selectedNodeHostname
    ? `${currentFolder} — ${selectedNodeHostname}`
    : `${currentFolder} — All Nodes`;

  const terminalEnabled = !!selectedNode;
  const terminalTitle = selectedNodeHostname || 'No node selected';

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Smart DFS - Files</title>
  <style>${baseStyles}${getFinderStyles()}</style>
</head>
<body>
  <div class="window">
    <div class="titlebar">
      <div class="traffic-lights">
        <div class="traffic-light red"></div>
        <div class="traffic-light yellow"></div>
        <div class="traffic-light green"></div>
      </div>
      <div class="titlebar-title">${titlebarText}</div>
      <button class="theme-toggle" onclick="toggleTheme()" title="Toggle theme">
        <span class="icon">🌙</span>
      </button>
    </div>

    <div class="toolbar">
      <div class="toolbar-group">
        <button class="toolbar-btn ${view === 'list' ? 'active' : ''}" onclick="location.href='/files?path=${encodeURIComponent(path)}&view=list&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}${searchQuery ? '&q=' + encodeURIComponent(searchQuery) : ''}'">☰ List</button>
        <button class="toolbar-btn ${view === 'grid' ? 'active' : ''}" onclick="location.href='/files?path=${encodeURIComponent(path)}&view=grid&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}${searchQuery ? '&q=' + encodeURIComponent(searchQuery) : ''}'">⊞ Icons</button>
      </div>

      <label class="toggle-label">
        <input type="checkbox" class="toggle-input" ${showHiddenFiles ? 'checked' : ''} onchange="location.href='/files?path=${encodeURIComponent(path)}&view=${view}&hidden=' + this.checked + '${selectedNode ? '&node=' + selectedNode : ''}${searchQuery ? '&q=' + encodeURIComponent(searchQuery) : ''}'">
        <span class="toggle-switch"></span>
        Show Hidden
      </label>

      <div class="toolbar-search">
        <form action="/files" method="GET">
          <input type="hidden" name="path" value="${path}">
          <input type="hidden" name="view" value="${view}">
          <input type="hidden" name="hidden" value="${showHiddenFiles}">
          ${selectedNode ? `<input type="hidden" name="node" value="${selectedNode}">` : ''}
          <input type="text" name="q" placeholder="Search..." value="${searchQuery || ''}">
        </form>
      </div>
    </div>

    <div class="finder-layout">
      <div class="finder-sidebar">
        <div class="sidebar-section">
          <div class="sidebar-title">Locations</div>
          <div class="sidebar-item ${!selectedNode ? 'active' : ''}" onclick="location.href='/files?path=${encodeURIComponent(path)}&view=${view}&hidden=${showHiddenFiles}'">
            <span class="sidebar-icon">🌐</span>
            <span>All Nodes</span>
          </div>
          ${sidebarHtml}
        </div>

        <div class="sidebar-section">
          <div class="sidebar-title">Favorites</div>
          <div class="sidebar-item" onclick="location.href='/files?path=/data/Desktop&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'">
            <span class="sidebar-icon">🖥️</span>
            <span>Desktop</span>
          </div>
          <div class="sidebar-item" onclick="location.href='/files?path=/data/Documents&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'">
            <span class="sidebar-icon">📄</span>
            <span>Documents</span>
          </div>
          <div class="sidebar-item" onclick="location.href='/files?path=/data/Downloads&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'">
            <span class="sidebar-icon">⬇️</span>
            <span>Downloads</span>
          </div>
          <div class="sidebar-item" onclick="location.href='/files?path=/data/Pictures&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'">
            <span class="sidebar-icon">🖼️</span>
            <span>Pictures</span>
          </div>
        </div>

        <div class="sidebar-section">
          <div class="sidebar-title">Tools</div>
          <div class="sidebar-item ${terminalEnabled ? '' : 'disabled'}" onclick="${terminalEnabled ? 'toggleTerminal()' : 'showTerminalDisabledMessage()'}" title="${terminalEnabled ? 'Open terminal' : 'Select a node first'}">
            <span class="sidebar-icon">💻</span>
            <span>Terminal${terminalEnabled ? '' : ' (select node)'}</span>
          </div>
        </div>
      </div>

      <div class="finder-main">
        <div class="pathbar">${pathBarHtml}</div>

        ${searchInfo ? `<div style="padding: 8px 16px; background: #252525; color: #888; font-size: 12px;">${searchInfo}</div>` : ''}

        ${files.length > 0 ? (view === 'grid' ? `
          <div class="grid-view">${gridHtml}</div>
        ` : `
          <div class="list-view">
            <div class="list-header">
              <span></span>
              <span>Name</span>
              <span>Size</span>
              <span>Date Modified</span>
              <span>Location</span>
            </div>
            ${listHtml}
          </div>
        `) : `
          <div class="empty-state">
            <div class="empty-icon">${searchQuery ? '🔍' : '📁'}</div>
            <div>${searchQuery ? 'No items match your search' : 'This folder is empty'}</div>
          </div>
        `}
      </div>
    </div>

    <div class="statusbar">
      <span>${files.length} item${files.length !== 1 ? 's' : ''}${showHiddenFiles ? ' (showing hidden files)' : ''}</span>
      <span>${nodes.length} connected node${nodes.length !== 1 ? 's' : ''}</span>
    </div>
  </div>

  <!-- Terminal Panel -->
  <div id="terminalPanel" class="terminal-panel" style="display:none;">
    <div class="terminal-container">
      <div class="terminal-header">
        <div class="traffic-lights">
          <div class="traffic-light red" onclick="toggleTerminal()"></div>
          <div class="traffic-light yellow" onclick="clearTerminal()"></div>
          <div class="traffic-light green" onclick="toggleTerminal()"></div>
        </div>
        <span class="terminal-title">Terminal — ${terminalTitle}</span>
        <span id="connectionStatus" class="connection-status disconnected">Disconnected</span>
        <button class="terminal-btn" onclick="clearTerminal()" title="Clear">Clear</button>
      </div>
      <div id="terminalOutput" class="terminal-output" onclick="focusTerminalInput()">
        <div class="info">Smart DFS Terminal — ${terminalTitle}</div>
        <div class="info">Type below. Note: streaming commands (ping, tail -f) will show output after completion.</div>
        <div id="terminalInputWrapper" class="terminal-input-wrapper">
          <span class="terminal-prompt-inline">${selectedNodeHostname || 'local'}:~$</span>
          <input type="text" id="terminalInput" class="terminal-input-inline" autocomplete="off" spellcheck="false" onkeydown="handleTerminalKey(event)">
        </div>
      </div>
    </div>
  </div>

  <!-- Preview Modal -->
  <div id="previewOverlay" class="preview-overlay" onclick="if(event.target===this)closePreview()">
    <button class="preview-nav prev" onclick="navigatePreview(-1)">‹</button>
    <div class="preview-content">
      <button class="preview-close" onclick="closePreview()">×</button>
      <div id="previewMedia"></div>
      <div id="previewFilename" class="preview-filename"></div>
    </div>
    <button class="preview-nav next" onclick="navigatePreview(1)">›</button>
  </div>

  <!-- Editor Modal -->
  <div id="editorOverlay" class="editor-overlay" onclick="if(event.target===this)closeEditor()">
    <div class="editor-container">
      <div class="editor-header">
        <span id="editorFilename" class="editor-filename">filename.txt</span>
        <span id="editorLang" class="editor-lang">plaintext</span>
        <button class="editor-close" onclick="closeEditor()">✕</button>
      </div>
      <div class="editor-content">
        <div id="editorLoading" class="editor-loading">Loading...</div>
        <div id="editorCode" class="line-numbers" style="display:none;">
          <pre class="line-nums" id="lineNums"></pre>
          <pre class="code" id="codeContent"></pre>
        </div>
      </div>
    </div>
  </div>

  <script>
    ${themeScript}

    const previewFiles = ${JSON.stringify(previewableFiles)};
    let currentPreviewIndex = 0;

    function openPreview(index) {
      if (index < 0 || index >= previewFiles.length) return;
      currentPreviewIndex = index;
      showPreview();
    }

    function showPreview() {
      const file = previewFiles[currentPreviewIndex];
      if (!file) return;

      const overlay = document.getElementById('previewOverlay');
      const mediaContainer = document.getElementById('previewMedia');
      const filenameEl = document.getElementById('previewFilename');

      // Include nodeId in preview URL
      const nodeParam = file.nodeId ? '&node=' + file.nodeId : '';

      let mediaHtml = '';
      if (file.type === 'image') {
        mediaHtml = '<img src="/preview?path=' + encodeURIComponent(file.path) + nodeParam + '" alt="' + file.name + '">';
      } else if (file.type === 'video') {
        mediaHtml = '<video controls autoplay><source src="/preview?path=' + encodeURIComponent(file.path) + nodeParam + '"></video>';
      }

      mediaContainer.innerHTML = mediaHtml;
      filenameEl.textContent = file.name + ' (' + (currentPreviewIndex + 1) + ' of ' + previewFiles.length + ')';
      overlay.classList.add('active');

      // Preload adjacent images with nodeId
      if (currentPreviewIndex > 0 && previewFiles[currentPreviewIndex - 1].type === 'image') {
        const prevFile = previewFiles[currentPreviewIndex - 1];
        const prevNodeParam = prevFile.nodeId ? '&node=' + prevFile.nodeId : '';
        new Image().src = '/preview?path=' + encodeURIComponent(prevFile.path) + prevNodeParam;
      }
      if (currentPreviewIndex < previewFiles.length - 1 && previewFiles[currentPreviewIndex + 1].type === 'image') {
        const nextFile = previewFiles[currentPreviewIndex + 1];
        const nextNodeParam = nextFile.nodeId ? '&node=' + nextFile.nodeId : '';
        new Image().src = '/preview?path=' + encodeURIComponent(nextFile.path) + nextNodeParam;
      }
    }

    function closePreview() {
      document.getElementById('previewOverlay').classList.remove('active');
      document.getElementById('previewMedia').innerHTML = '';
    }

    function navigatePreview(direction) {
      const newIndex = currentPreviewIndex + direction;
      if (newIndex >= 0 && newIndex < previewFiles.length) {
        currentPreviewIndex = newIndex;
        showPreview();
      }
    }

    document.addEventListener('keydown', (e) => {
      const previewOverlay = document.getElementById('previewOverlay');
      const editorOverlay = document.getElementById('editorOverlay');

      if (previewOverlay.classList.contains('active')) {
        if (e.key === 'Escape') closePreview();
        else if (e.key === 'ArrowLeft') navigatePreview(-1);
        else if (e.key === 'ArrowRight') navigatePreview(1);
      }

      if (editorOverlay.classList.contains('active')) {
        if (e.key === 'Escape') closeEditor();
      }
    });

    // Editor functions
    async function openEditor(encodedPath, filename, lang, nodeId) {
      const overlay = document.getElementById('editorOverlay');
      const filenameEl = document.getElementById('editorFilename');
      const langEl = document.getElementById('editorLang');
      const loadingEl = document.getElementById('editorLoading');
      const codeEl = document.getElementById('editorCode');
      const lineNumsEl = document.getElementById('lineNums');
      const codeContentEl = document.getElementById('codeContent');

      filenameEl.textContent = filename;
      langEl.textContent = lang;
      loadingEl.style.display = 'flex';
      codeEl.style.display = 'none';
      overlay.classList.add('active');

      try {
        const nodeParam = nodeId ? '&node=' + nodeId : '';
        const res = await fetch('/preview?path=' + encodedPath + nodeParam);
        const text = await res.text();

        const lines = text.split('\\n');
        lineNumsEl.textContent = lines.map((_, i) => i + 1).join('\\n');
        codeContentEl.textContent = text;

        loadingEl.style.display = 'none';
        codeEl.style.display = 'flex';
      } catch (err) {
        loadingEl.textContent = 'Error loading file: ' + err.message;
      }
    }

    function closeEditor() {
      document.getElementById('editorOverlay').classList.remove('active');
    }

    // Terminal functions with WebSocket streaming
    const currentNodeId = ${selectedNode ? `'${selectedNode}'` : 'null'};
    const currentNodeHostname = ${selectedNodeHostname ? `'${selectedNodeHostname}'` : 'null'};
    const terminalEnabled = ${terminalEnabled};
    const terminalPrompt = (currentNodeHostname || 'local') + ':~$ ';

    // WebSocket connection
    let ws = null;
    let wsConnected = false;
    let commandRunning = false;

    // State management per node
    function getNodeStateKey(nodeId) {
      return 'dfs-terminal-' + (nodeId || 'global');
    }

    function loadNodeState(nodeId) {
      try {
        const key = getNodeStateKey(nodeId);
        const saved = localStorage.getItem(key);
        if (saved) return JSON.parse(saved);
      } catch (e) {}
      return { history: [], outputHtml: '' };
    }

    function saveNodeState(nodeId, state) {
      try {
        const key = getNodeStateKey(nodeId);
        localStorage.setItem(key, JSON.stringify(state));
      } catch (e) {}
    }

    // Load state for current node
    let nodeState = loadNodeState(currentNodeId);
    let terminalHistory = nodeState.history || [];
    let historyIndex = terminalHistory.length;

    function showTerminalDisabledMessage() {
      alert('Please select a specific node from the sidebar to use the terminal.');
    }

    function focusTerminalInput() {
      document.getElementById('terminalInput').focus();
    }

    function connectWebSocket() {
      if (ws && ws.readyState === WebSocket.OPEN) return;

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = protocol + '//' + window.location.host + '/ws/terminal';

      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        appendLine('Connecting to ' + currentNodeHostname + '...', 'info');
        // Send connect message with nodeId
        ws.send(JSON.stringify({ type: 'connect', nodeId: currentNodeId }));
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          handleWsMessage(msg);
        } catch (e) {
          appendLine('WS parse error: ' + e.message, 'error');
        }
      };

      ws.onclose = () => {
        wsConnected = false;
        commandRunning = false;
        appendLine('Disconnected from node', 'info');
      };

      ws.onerror = (err) => {
        appendLine('WebSocket error', 'error');
      };
    }

    function handleWsMessage(msg) {
      switch (msg.type) {
        case 'connected':
          wsConnected = true;
          updateConnectionStatus('connected');
          if (msg.reconnected) {
            appendLine('Reconnected to ' + msg.nodeId, 'info');
          } else {
            appendLine('Connected to ' + msg.nodeId, 'info');
          }
          break;

        case 'start':
          commandRunning = true;
          appendLine('$ ' + msg.command, 'command');
          break;

        case 'output':
          appendLine(msg.line, 'stdout');
          break;

        case 'exit':
          commandRunning = false;
          if (msg.exitCode !== 0) {
            appendLine('Exit code: ' + msg.exitCode, 'error');
          }
          saveCurrentState();
          break;

        case 'error':
          commandRunning = false;
          appendLine('Error: ' + msg.message, 'error');
          if (msg.nodeStatus) {
            updateConnectionStatus(msg.nodeStatus);
          }
          saveCurrentState();
          break;

        case 'disconnected':
          wsConnected = false;
          commandRunning = false;
          updateConnectionStatus('disconnected');
          if (msg.willReconnect) {
            appendLine('Node disconnected - attempting to reconnect...', 'info');
          } else {
            appendLine('Node disconnected', 'info');
          }
          break;

        case 'reconnecting':
          updateConnectionStatus('reconnecting');
          appendLine('Reconnecting to ' + msg.nodeId + ' (attempt ' + msg.attempt + ')...', 'info');
          break;

        case 'status':
          updateConnectionStatus(msg.nodeStatus);
          appendLine('Node status: ' + msg.nodeStatus + ', WS: ' + (msg.wsConnected ? 'connected' : 'disconnected'), 'info');
          break;
      }
      scrollToBottom();
    }

    function updateConnectionStatus(status) {
      const indicator = document.getElementById('connectionStatus');
      if (!indicator) return;

      indicator.className = 'connection-status ' + status;
      indicator.textContent = status.charAt(0).toUpperCase() + status.slice(1);
    }

    function disconnectWebSocket() {
      if (ws) {
        ws.close();
        ws = null;
        wsConnected = false;
      }
    }

    function toggleTerminal() {
      if (!terminalEnabled) {
        showTerminalDisabledMessage();
        return;
      }

      const panel = document.getElementById('terminalPanel');
      const isVisible = panel.style.display !== 'none';
      panel.style.display = isVisible ? 'none' : 'flex';

      if (!isVisible) {
        restoreTerminalOutput();
        connectWebSocket();
        focusTerminalInput();
        scrollToBottom();
      } else {
        saveCurrentState();
        // Keep connection open for quick re-open
      }
    }

    function getOutputContainer() {
      return document.getElementById('terminalOutput');
    }

    function getInputWrapper() {
      return document.getElementById('terminalInputWrapper');
    }

    function restoreTerminalOutput() {
      if (nodeState.outputHtml) {
        const output = getOutputContainer();
        const inputWrapper = getInputWrapper();
        inputWrapper.remove();
        output.innerHTML = nodeState.outputHtml;
        output.appendChild(inputWrapper);
      }
    }

    function saveCurrentState() {
      const output = getOutputContainer();
      const inputWrapper = getInputWrapper();
      inputWrapper.remove();
      const html = output.innerHTML;
      output.appendChild(inputWrapper);

      saveNodeState(currentNodeId, {
        history: terminalHistory.slice(-100),
        outputHtml: html
      });
    }

    function scrollToBottom() {
      const output = getOutputContainer();
      output.scrollTop = output.scrollHeight;
    }

    function clearTerminal() {
      const output = getOutputContainer();
      const inputWrapper = getInputWrapper();
      output.innerHTML = '';
      output.appendChild(document.createElement('div')).className = 'info';
      output.lastChild.textContent = 'Terminal cleared';
      output.appendChild(inputWrapper);
      scrollToBottom();
      saveCurrentState();
    }

    function appendLine(text, className = 'stdout') {
      const output = getOutputContainer();
      const inputWrapper = getInputWrapper();
      const div = document.createElement('div');
      div.className = className;
      div.textContent = text;
      output.insertBefore(div, inputWrapper);
      scrollToBottom();
    }

    function executeTerminalCommand(command) {
      // Handle built-in commands
      if (command === 'help') {
        appendLine(terminalPrompt + command, 'command');
        appendLine('Connected to: ' + (currentNodeHostname || 'local'), 'info');
        appendLine('Commands execute on remote node via WebSocket streaming', 'info');
        appendLine('Ctrl+C: kill running command | clear: clear screen', 'info');
        appendLine('Up/Down arrows: navigate history', 'info');
        saveCurrentState();
        return;
      }

      if (command === 'clear') {
        clearTerminal();
        return;
      }

      // Check WebSocket connection
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        appendLine('Not connected. Reconnecting...', 'error');
        connectWebSocket();
        setTimeout(() => {
          if (wsConnected) {
            ws.send(JSON.stringify({ type: 'exec', command }));
          }
        }, 500);
        return;
      }

      if (!wsConnected) {
        appendLine('Waiting for node connection...', 'info');
        return;
      }

      // Send command via WebSocket
      ws.send(JSON.stringify({ type: 'exec', command }));
    }

    function killRunningCommand() {
      if (ws && ws.readyState === WebSocket.OPEN && commandRunning) {
        ws.send(JSON.stringify({ type: 'kill' }));
        appendLine('^C', 'error');
        commandRunning = false;
      }
    }

    function handleTerminalKey(event) {
      const input = document.getElementById('terminalInput');

      if (event.key === 'Enter') {
        event.preventDefault();
        const command = input.value.trim();
        if (command && !commandRunning) {
          terminalHistory.push(command);
          historyIndex = terminalHistory.length;
          executeTerminalCommand(command);
        }
        input.value = '';
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        if (historyIndex > 0) {
          historyIndex--;
          input.value = terminalHistory[historyIndex];
        }
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        if (historyIndex < terminalHistory.length - 1) {
          historyIndex++;
          input.value = terminalHistory[historyIndex];
        } else {
          historyIndex = terminalHistory.length;
          input.value = '';
        }
      } else if (event.key === 'Escape') {
        toggleTerminal();
      } else if (event.key === 'c' && event.ctrlKey) {
        event.preventDefault();
        killRunningCommand();
      } else if (event.key === 'l' && event.ctrlKey) {
        event.preventDefault();
        clearTerminal();
      }
    }

    // Save state before leaving page
    window.addEventListener('beforeunload', () => {
      saveCurrentState();
      disconnectWebSocket();
    });
  </script>
</body>
</html>`;
}
