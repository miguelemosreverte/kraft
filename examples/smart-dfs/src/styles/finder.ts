/**
 * macOS Finder-style UI styles
 */

export const finderStyles = `
  /* Window Chrome */
  .window {
    background: var(--bg-secondary);
    border-radius: 10px;
    margin: 20px;
    box-shadow: 0 20px 60px rgba(0,0,0,0.3);
    overflow: hidden;
    border: 1px solid var(--border-color);
  }

  /* Title Bar */
  .titlebar {
    background: var(--bg-tertiary);
    padding: 12px 16px;
    display: flex;
    align-items: center;
    gap: 12px;
    border-bottom: 1px solid var(--border-color);
  }

  .traffic-lights {
    display: flex;
    gap: 8px;
  }

  .traffic-light {
    width: 12px;
    height: 12px;
    border-radius: 50%;
  }

  .traffic-light.red { background: #ff5f57; }
  .traffic-light.yellow { background: #febc2e; }
  .traffic-light.green { background: #28c840; }

  .titlebar-title {
    flex: 1;
    text-align: center;
    font-weight: 500;
    color: var(--text-secondary);
  }

  /* Toolbar */
  .toolbar {
    background: var(--bg-secondary);
    padding: 8px 16px;
    display: flex;
    align-items: center;
    gap: 12px;
    border-bottom: 1px solid var(--border-color);
  }

  .toolbar-group {
    display: flex;
    align-items: center;
    gap: 4px;
    background: var(--bg-hover);
    border-radius: 6px;
    padding: 2px;
  }

  .toolbar-btn {
    padding: 6px 12px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: 4px;
    font-size: 12px;
    transition: all 0.15s;
  }

  .toolbar-btn:hover {
    background: var(--bg-tertiary);
    color: var(--text-primary);
  }

  .toolbar-btn.active {
    background: var(--accent-color);
    color: #fff;
  }

  .toolbar-search {
    flex: 1;
    max-width: 300px;
    margin-left: auto;
  }

  .toolbar-search input {
    width: 100%;
    padding: 6px 12px;
    border: none;
    border-radius: 6px;
    background: var(--bg-primary);
    color: var(--text-primary);
    font-size: 12px;
  }

  .toolbar-search input::placeholder { color: var(--text-muted); }
  .toolbar-search input:focus { outline: 2px solid var(--accent-color); }

  /* Layout */
  .finder-layout {
    display: flex;
    height: calc(100vh - 140px);
  }

  /* Sidebar */
  .finder-sidebar {
    width: 200px;
    background: var(--bg-tertiary);
    padding: 12px 0;
    overflow-y: auto;
    border-right: 1px solid var(--border-color);
  }

  .sidebar-section {
    margin-bottom: 20px;
  }

  .sidebar-title {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-secondary);
    text-transform: uppercase;
    padding: 4px 16px;
    margin-bottom: 4px;
  }

  .sidebar-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
    cursor: pointer;
    color: var(--text-primary);
    transition: background 0.15s;
  }

  .sidebar-item:hover { background: var(--bg-hover); }
  .sidebar-item.active { background: var(--accent-color); color: #fff; }
  .sidebar-item.disabled { opacity: 0.5; cursor: not-allowed; }
  .sidebar-item.disabled:hover { background: transparent; }
  .sidebar-item.offline { opacity: 0.6; }
  .sidebar-item.offline:hover { opacity: 0.8; }
  .sidebar-icon { font-size: 16px; }

  .node-status-dot {
    margin-left: auto;
    font-size: 8px;
    line-height: 1;
  }

  /* Main Content */
  .finder-main {
    flex: 1;
    overflow-y: auto;
    background: var(--bg-primary);
  }

  /* Path Bar */
  .pathbar {
    padding: 8px 16px;
    background: var(--bg-tertiary);
    border-bottom: 1px solid var(--border-color);
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
  }

  .pathbar-item {
    color: var(--accent-color);
    text-decoration: none;
    padding: 4px 8px;
    border-radius: 4px;
  }

  .pathbar-item:hover { background: var(--bg-hover); }
  .pathbar-sep { color: var(--text-muted); }

  /* Status Bar */
  .statusbar {
    padding: 6px 16px;
    background: var(--bg-tertiary);
    border-top: 1px solid var(--border-color);
    font-size: 11px;
    color: var(--text-secondary);
    display: flex;
    justify-content: space-between;
  }

  /* Toggle Switch */
  .toggle-label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--text-secondary);
    cursor: pointer;
  }

  .toggle-switch {
    width: 36px;
    height: 20px;
    background: var(--bg-hover);
    border-radius: 10px;
    position: relative;
    transition: background 0.2s;
  }

  .toggle-switch::after {
    content: '';
    position: absolute;
    width: 16px;
    height: 16px;
    background: #fff;
    border-radius: 50%;
    top: 2px;
    left: 2px;
    transition: transform 0.2s;
  }

  .toggle-input { display: none; }
  .toggle-input:checked + .toggle-switch { background: var(--accent-color); }
  .toggle-input:checked + .toggle-switch::after { transform: translateX(16px); }
`;

export const listViewStyles = `
  /* List View */
  .list-view { padding: 0; }

  .list-header {
    display: grid;
    grid-template-columns: 40px 1fr 100px 120px 100px;
    padding: 8px 16px;
    background: var(--bg-tertiary);
    border-bottom: 1px solid var(--border-color);
    font-size: 11px;
    font-weight: 600;
    color: var(--text-secondary);
    position: sticky;
    top: 0;
  }

  .list-item {
    display: grid;
    grid-template-columns: 40px 1fr 100px 120px 100px;
    padding: 6px 16px;
    border-bottom: 1px solid var(--bg-secondary);
    cursor: pointer;
    transition: background 0.1s;
    align-items: center;
  }

  .list-item:hover { background: var(--bg-secondary); }
  .list-item.selected { background: var(--accent-color); color: #fff; }

  .list-icon { font-size: 20px; }
  .list-name { font-size: 13px; }
  .list-size, .list-modified, .list-location { font-size: 12px; color: var(--text-secondary); }

  .list-item.selected .list-size,
  .list-item.selected .list-modified,
  .list-item.selected .list-location { color: rgba(255,255,255,0.7); }

  .list-thumbnail {
    width: 32px;
    height: 32px;
    object-fit: cover;
    border-radius: 4px;
  }
`;

export const gridViewStyles = `
  /* Grid View */
  .grid-view {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 16px;
    padding: 20px;
  }

  .grid-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 12px 8px;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.1s;
  }

  .grid-item:hover { background: var(--bg-secondary); }
  .grid-item.selected { background: var(--accent-color); }

  .grid-icon {
    font-size: 48px;
    margin-bottom: 8px;
  }

  .grid-thumbnail {
    width: 80px;
    height: 80px;
    object-fit: cover;
    border-radius: 6px;
    margin-bottom: 8px;
    background: var(--bg-hover);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .grid-thumbnail img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: 6px;
  }

  .grid-name {
    font-size: 11px;
    text-align: center;
    word-break: break-word;
    max-width: 90px;
    line-height: 1.3;
  }
`;

export const previewModalStyles = `
  /* Preview Modal */
  .preview-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.9);
    display: none;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .preview-overlay.active { display: flex; }

  .preview-content {
    max-width: 90vw;
    max-height: 90vh;
    position: relative;
  }

  .preview-content img, .preview-content video {
    max-width: 90vw;
    max-height: 85vh;
    object-fit: contain;
    border-radius: 8px;
  }

  .preview-close {
    position: absolute;
    top: -40px;
    right: 0;
    background: none;
    border: none;
    color: #fff;
    font-size: 32px;
    cursor: pointer;
    opacity: 0.7;
  }

  .preview-close:hover { opacity: 1; }

  .preview-filename {
    color: #fff;
    text-align: center;
    margin-top: 12px;
    font-size: 14px;
  }

  .preview-nav {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    background: rgba(255,255,255,0.1);
    border: none;
    color: #fff;
    font-size: 24px;
    padding: 20px 15px;
    cursor: pointer;
    border-radius: 8px;
  }

  .preview-nav:hover { background: rgba(255,255,255,0.2); }
  .preview-nav.prev { left: 20px; }
  .preview-nav.next { right: 20px; }
`;

export const editorModalStyles = `
  /* Editor Modal */
  .editor-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.85);
    display: none;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .editor-overlay.active { display: flex; }

  .editor-container {
    width: 90vw;
    height: 90vh;
    background: var(--bg-secondary);
    border-radius: 12px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    box-shadow: 0 20px 60px rgba(0,0,0,0.5);
  }

  .editor-header {
    display: flex;
    align-items: center;
    padding: 12px 16px;
    background: var(--bg-tertiary);
    border-bottom: 1px solid var(--border-color);
    gap: 12px;
  }

  .editor-filename {
    flex: 1;
    font-weight: 500;
    font-size: 14px;
  }

  .editor-lang {
    font-size: 12px;
    color: var(--text-secondary);
    background: var(--bg-hover);
    padding: 4px 8px;
    border-radius: 4px;
  }

  .editor-close {
    background: none;
    border: none;
    color: var(--text-secondary);
    font-size: 20px;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;
  }

  .editor-close:hover {
    background: var(--bg-hover);
    color: var(--text-primary);
  }

  .editor-content {
    flex: 1;
    overflow: auto;
    background: #1e1e1e;
  }

  .editor-content pre {
    margin: 0;
    padding: 16px;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', 'Roboto Mono', Consolas, monospace;
    font-size: 13px;
    line-height: 1.5;
    color: #d4d4d4;
    white-space: pre-wrap;
    word-wrap: break-word;
  }

  .editor-content .line-numbers {
    display: flex;
  }

  .editor-content .line-nums {
    padding: 16px 12px 16px 16px;
    text-align: right;
    color: #858585;
    user-select: none;
    border-right: 1px solid #333;
    background: #1a1a1a;
  }

  .editor-content .code {
    flex: 1;
    padding: 16px;
    overflow-x: auto;
  }

  .editor-loading {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--text-secondary);
  }
`;

export const terminalStyles = `
  /* Terminal Panel */
  .terminal-panel {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 300px;
    z-index: 100;
    display: flex;
    box-shadow: 0 -4px 20px rgba(0,0,0,0.3);
  }

  /* Terminal */
  .terminal-container {
    background: #1a1a1a;
    border-radius: 8px 8px 0 0;
    overflow: hidden;
    height: 100%;
    width: 100%;
    display: flex;
    flex-direction: column;
  }

  .terminal-header {
    display: flex;
    align-items: center;
    padding: 8px 12px;
    background: #2d2d2d;
    border-bottom: 1px solid #3d3d3d;
    gap: 8px;
  }

  .terminal-title {
    flex: 1;
    font-size: 12px;
    color: #888;
  }

  .connection-status {
    font-size: 10px;
    padding: 2px 8px;
    border-radius: 10px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    font-weight: 600;
  }

  .connection-status.connected {
    background: rgba(40, 200, 64, 0.2);
    color: #28c840;
  }

  .connection-status.disconnected {
    background: rgba(255, 95, 87, 0.2);
    color: #ff5f57;
  }

  .connection-status.connecting,
  .connection-status.reconnecting {
    background: rgba(254, 188, 46, 0.2);
    color: #febc2e;
    animation: pulse 1.5s ease-in-out infinite;
  }

  .connection-status.failed {
    background: rgba(255, 95, 87, 0.3);
    color: #ff5f57;
    font-weight: 700;
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.6; }
  }

  .terminal-btn {
    background: #3d3d3d;
    border: none;
    color: #888;
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 11px;
    cursor: pointer;
  }

  .terminal-btn:hover {
    background: #4d4d4d;
    color: #fff;
  }

  .terminal-output {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', Consolas, monospace;
    font-size: 13px;
    line-height: 1.5;
    color: #e0e0e0;
    white-space: pre-wrap;
    word-wrap: break-word;
    background: #0d0d0d;
  }

  .terminal-output .prompt {
    color: #4ade80;
  }

  .terminal-output .command {
    color: #4ade80;
  }

  .terminal-output .stdout {
    color: #e0e0e0;
  }

  .terminal-output .error {
    color: #f87171;
  }

  .terminal-output .info {
    color: #60a5fa;
  }

  .terminal-input-wrapper {
    display: flex;
    align-items: center;
    margin-top: 4px;
  }

  .terminal-prompt-inline {
    color: #4ade80;
    margin-right: 8px;
    white-space: nowrap;
  }

  .terminal-input-inline {
    flex: 1;
    background: transparent;
    border: none;
    color: #e0e0e0;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', Consolas, monospace;
    font-size: 13px;
    outline: none;
    caret-color: #4ade80;
  }

  .terminal-input-inline::placeholder {
    color: #555;
  }

  .terminal-cursor {
    display: inline-block;
    width: 8px;
    height: 15px;
    background: #4ade80;
    animation: blink 1s step-end infinite;
  }

  @keyframes blink {
    50% { opacity: 0; }
  }
`;

export function getFinderStyles(): string {
  return finderStyles + listViewStyles + gridViewStyles + previewModalStyles + editorModalStyles + terminalStyles;
}
