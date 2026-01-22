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

// macOS Finder Styles
function getFinderStyles(): string {
  return `
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', sans-serif;
      background: #1e1e1e;
      color: #e0e0e0;
      min-height: 100vh;
      font-size: 13px;
    }
    .window {
      background: #2d2d2d;
      border-radius: 10px;
      margin: 20px;
      box-shadow: 0 20px 60px rgba(0,0,0,0.5);
      overflow: hidden;
      border: 1px solid #3d3d3d;
    }
    .titlebar {
      background: linear-gradient(180deg, #3d3d3d 0%, #333 100%);
      padding: 12px 16px;
      display: flex;
      align-items: center;
      gap: 12px;
      border-bottom: 1px solid #1a1a1a;
    }
    .traffic-lights { display: flex; gap: 8px; }
    .traffic-light { width: 12px; height: 12px; border-radius: 50%; }
    .traffic-light.red { background: #ff5f57; }
    .traffic-light.yellow { background: #febc2e; }
    .traffic-light.green { background: #28c840; }
    .titlebar-title { flex: 1; text-align: center; font-weight: 500; color: #999; }
    .toolbar {
      background: #2d2d2d;
      padding: 8px 16px;
      display: flex;
      align-items: center;
      gap: 12px;
      border-bottom: 1px solid #3d3d3d;
    }
    .toolbar-group { display: flex; align-items: center; gap: 4px; background: #3d3d3d; border-radius: 6px; padding: 2px; }
    .toolbar-btn {
      padding: 6px 12px;
      border: none;
      background: transparent;
      color: #999;
      cursor: pointer;
      border-radius: 4px;
      font-size: 12px;
      transition: all 0.15s;
    }
    .toolbar-btn:hover { background: #4d4d4d; color: #fff; }
    .toolbar-btn.active { background: #0a84ff; color: #fff; }
    .toolbar-search { flex: 1; max-width: 300px; margin-left: auto; }
    .toolbar-search input {
      width: 100%;
      padding: 6px 12px;
      border: none;
      border-radius: 6px;
      background: #1a1a1a;
      color: #e0e0e0;
      font-size: 12px;
    }
    .toolbar-search input::placeholder { color: #666; }
    .toolbar-search input:focus { outline: 2px solid #0a84ff; }
    .finder-layout { display: flex; height: calc(100vh - 140px); }
    .finder-sidebar {
      width: 200px;
      background: #252525;
      padding: 12px 0;
      overflow-y: auto;
      border-right: 1px solid #3d3d3d;
    }
    .sidebar-section { margin-bottom: 20px; }
    .sidebar-title { font-size: 11px; font-weight: 600; color: #888; text-transform: uppercase; padding: 4px 16px; margin-bottom: 4px; }
    .sidebar-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 16px;
      cursor: pointer;
      color: #e0e0e0;
      transition: background 0.15s;
    }
    .sidebar-item:hover { background: #3d3d3d; }
    .sidebar-item.active { background: #0a84ff; color: #fff; }
    .sidebar-icon { font-size: 16px; }
    .finder-main { flex: 1; overflow-y: auto; background: #1e1e1e; }
    .pathbar {
      padding: 8px 16px;
      background: #252525;
      border-bottom: 1px solid #3d3d3d;
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
    }
    .pathbar-item { color: #0a84ff; text-decoration: none; padding: 4px 8px; border-radius: 4px; }
    .pathbar-item:hover { background: #3d3d3d; }
    .pathbar-sep { color: #666; }
    .list-view { padding: 0; }
    .list-header {
      display: grid;
      grid-template-columns: 40px 1fr 100px 120px 100px;
      padding: 8px 16px;
      background: #252525;
      border-bottom: 1px solid #3d3d3d;
      font-size: 11px;
      font-weight: 600;
      color: #888;
      position: sticky;
      top: 0;
    }
    .list-item {
      display: grid;
      grid-template-columns: 40px 1fr 100px 120px 100px;
      padding: 6px 16px;
      border-bottom: 1px solid #2d2d2d;
      cursor: pointer;
      transition: background 0.1s;
      align-items: center;
    }
    .list-item:hover { background: #2d2d2d; }
    .list-item.selected { background: #0a84ff; color: #fff; }
    .list-icon { font-size: 20px; }
    .list-name { font-size: 13px; }
    .list-size, .list-modified, .list-location { font-size: 12px; color: #888; }
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
    .grid-item:hover { background: #2d2d2d; }
    .grid-item.selected { background: #0a84ff; }
    .grid-icon { font-size: 48px; margin-bottom: 8px; }
    .grid-thumbnail {
      width: 64px;
      height: 64px;
      object-fit: cover;
      border-radius: 4px;
      margin-bottom: 8px;
      background: #3d3d3d;
    }
    .grid-name { font-size: 11px; text-align: center; word-break: break-word; max-width: 90px; line-height: 1.3; }
    .statusbar {
      padding: 6px 16px;
      background: #252525;
      border-top: 1px solid #3d3d3d;
      font-size: 11px;
      color: #888;
      display: flex;
      justify-content: space-between;
    }
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 300px;
      color: #666;
    }
    .empty-icon { font-size: 64px; margin-bottom: 16px; opacity: 0.5; }
    .toggle-label { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #999; cursor: pointer; }
    .toggle-switch {
      width: 36px;
      height: 20px;
      background: #3d3d3d;
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
    .toggle-input:checked + .toggle-switch { background: #0a84ff; }
    .toggle-input:checked + .toggle-switch::after { transform: translateX(16px); }

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

    /* Thumbnail styles */
    .grid-thumbnail {
      width: 80px;
      height: 80px;
      object-fit: cover;
      border-radius: 6px;
      margin-bottom: 8px;
      background: #3d3d3d;
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
    .list-thumbnail {
      width: 32px;
      height: 32px;
      object-fit: cover;
      border-radius: 4px;
    }
  `;
}

// HTML Template - macOS Finder Style
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
      font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', sans-serif;
      background: #1e1e1e;
      color: #e0e0e0;
      min-height: 100vh;
      font-size: 13px;
    }

    /* macOS Window Chrome */
    .window {
      background: #2d2d2d;
      border-radius: 10px;
      margin: 20px;
      box-shadow: 0 20px 60px rgba(0,0,0,0.5);
      overflow: hidden;
      border: 1px solid #3d3d3d;
    }

    /* Title Bar */
    .titlebar {
      background: linear-gradient(180deg, #3d3d3d 0%, #333 100%);
      padding: 12px 16px;
      display: flex;
      align-items: center;
      gap: 12px;
      border-bottom: 1px solid #1a1a1a;
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
      color: #999;
    }

    /* Toolbar */
    .toolbar {
      background: #2d2d2d;
      padding: 8px 16px;
      display: flex;
      align-items: center;
      gap: 12px;
      border-bottom: 1px solid #3d3d3d;
    }
    .toolbar-group {
      display: flex;
      align-items: center;
      gap: 4px;
      background: #3d3d3d;
      border-radius: 6px;
      padding: 2px;
    }
    .toolbar-btn {
      padding: 6px 12px;
      border: none;
      background: transparent;
      color: #999;
      cursor: pointer;
      border-radius: 4px;
      font-size: 12px;
      transition: all 0.15s;
    }
    .toolbar-btn:hover { background: #4d4d4d; color: #fff; }
    .toolbar-btn.active { background: #0a84ff; color: #fff; }
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
      background: #1a1a1a;
      color: #e0e0e0;
      font-size: 12px;
    }
    .toolbar-search input::placeholder { color: #666; }
    .toolbar-search input:focus { outline: 2px solid #0a84ff; }

    /* Main Layout */
    .finder-layout {
      display: flex;
      height: calc(100vh - 140px);
    }

    /* Sidebar */
    .finder-sidebar {
      width: 200px;
      background: #252525;
      padding: 12px 0;
      overflow-y: auto;
      border-right: 1px solid #3d3d3d;
    }
    .sidebar-section { margin-bottom: 20px; }
    .sidebar-title {
      font-size: 11px;
      font-weight: 600;
      color: #888;
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
      color: #e0e0e0;
      transition: background 0.15s;
    }
    .sidebar-item:hover { background: #3d3d3d; }
    .sidebar-item.active { background: #0a84ff; color: #fff; }
    .sidebar-icon { font-size: 16px; }

    /* Main Content */
    .finder-main {
      flex: 1;
      overflow-y: auto;
      background: #1e1e1e;
    }

    /* Path Bar */
    .pathbar {
      padding: 8px 16px;
      background: #252525;
      border-bottom: 1px solid #3d3d3d;
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
    }
    .pathbar-item {
      color: #0a84ff;
      text-decoration: none;
      padding: 4px 8px;
      border-radius: 4px;
    }
    .pathbar-item:hover { background: #3d3d3d; }
    .pathbar-sep { color: #666; }

    /* List View */
    .list-view { padding: 0; }
    .list-header {
      display: grid;
      grid-template-columns: 40px 1fr 100px 120px 100px;
      padding: 8px 16px;
      background: #252525;
      border-bottom: 1px solid #3d3d3d;
      font-size: 11px;
      font-weight: 600;
      color: #888;
      position: sticky;
      top: 0;
    }
    .list-item {
      display: grid;
      grid-template-columns: 40px 1fr 100px 120px 100px;
      padding: 6px 16px;
      border-bottom: 1px solid #2d2d2d;
      cursor: pointer;
      transition: background 0.1s;
      align-items: center;
    }
    .list-item:hover { background: #2d2d2d; }
    .list-item.selected { background: #0a84ff; color: #fff; }
    .list-icon { font-size: 20px; }
    .list-name { font-size: 13px; }
    .list-size, .list-modified, .list-location { font-size: 12px; color: #888; }
    .list-item.selected .list-size,
    .list-item.selected .list-modified,
    .list-item.selected .list-location { color: rgba(255,255,255,0.7); }

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
    .grid-item:hover { background: #2d2d2d; }
    .grid-item.selected { background: #0a84ff; }
    .grid-icon {
      font-size: 48px;
      margin-bottom: 8px;
    }
    .grid-thumbnail {
      width: 64px;
      height: 64px;
      object-fit: cover;
      border-radius: 4px;
      margin-bottom: 8px;
      background: #3d3d3d;
    }
    .grid-name {
      font-size: 11px;
      text-align: center;
      word-break: break-word;
      max-width: 90px;
      line-height: 1.3;
    }

    /* Status Bar */
    .statusbar {
      padding: 6px 16px;
      background: #252525;
      border-top: 1px solid #3d3d3d;
      font-size: 11px;
      color: #888;
      display: flex;
      justify-content: space-between;
    }

    /* Empty State */
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 300px;
      color: #666;
    }
    .empty-icon { font-size: 64px; margin-bottom: 16px; opacity: 0.5; }

    /* Toggle Switch */
    .toggle-label {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: #999;
      cursor: pointer;
    }
    .toggle-switch {
      width: 36px;
      height: 20px;
      background: #3d3d3d;
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
    .toggle-input:checked + .toggle-switch { background: #0a84ff; }
    .toggle-input:checked + .toggle-switch::after { transform: translateX(16px); }

    /* Legacy styles for other pages */
    .container { max-width: 1400px; margin: 0 auto; padding: 20px; }
    header {
      background: #2d2d2d;
      padding: 20px 30px;
      border-radius: 10px;
      margin-bottom: 25px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border: 1px solid #3d3d3d;
    }
    .logo { font-size: 1.8em; font-weight: 700; }
    .logo span { color: #0a84ff; }
    nav { display: flex; gap: 8px; }
    nav a {
      color: #888;
      text-decoration: none;
      padding: 10px 20px;
      border-radius: 8px;
      transition: all 0.2s;
      font-weight: 500;
    }
    nav a:hover { color: #fff; background: #3d3d3d; }
    nav a.active { color: #fff; background: #0a84ff; }

    /* Breadcrumb (legacy) */
    .breadcrumb { margin-bottom: 15px; font-size: 0.95em; }
    .breadcrumb-item {
      color: #0a84ff;
      text-decoration: none;
      transition: color 0.2s;
    }
    .breadcrumb-item:hover { color: #22c55e; text-decoration: underline; }

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
  const diskInfo = await dfs.getDiskInfo() || [];
  const allFiles = await dfs.listAll() || [];

  let totalFiles = 0;
  let totalSize = 0;
  allFiles.forEach(n => {
    const files = n.files || [];
    files.forEach(f => {
      if (!f.isDirectory) {
        totalFiles++;
        totalSize += f.size;
      }
    });
  });

  const totalFreeSpace = diskInfo.reduce((sum, d) => sum + (d.disk?.freeSpace || 0), 0);

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

async function renderFiles(searchQuery?: string, selectedNode?: string, currentPath?: string, viewMode?: string, showHidden?: boolean): Promise<string> {
  await dfs.discoverNodes();
  const nodes = dfs.getNodes();
  const path = currentPath || '/data';
  const view = viewMode || 'list';
  const showHiddenFiles = showHidden === true;

  // Get files - either via search or listing
  let files: { name: string; size: number; nodes: string[]; path: string; isDirectory: boolean; modified?: number }[] = [];
  let searchInfo = '';

  if (searchQuery) {
    const searchResults = await dfs.search(searchQuery);
    const fileMap = new Map<string, { name: string; size: number; nodes: string[]; path: string; isDirectory: boolean }>();

    for (const result of searchResults) {
      if (selectedNode && result.nodeId !== selectedNode) continue;
      // Filter hidden files
      if (!showHiddenFiles && result.name.startsWith('.')) continue;

      const existing = fileMap.get(result.name);
      if (existing) {
        if (!existing.nodes.includes(result.hostname)) {
          existing.nodes.push(result.hostname);
        }
      } else {
        fileMap.set(result.name, {
          name: result.name,
          size: result.size,
          nodes: [result.hostname],
          path: result.path,
          isDirectory: result.isDirectory
        });
      }
    }
    files = Array.from(fileMap.values());
    searchInfo = `${files.length} item(s) matching "${searchQuery}"`;
  } else {
    const allFiles = await dfs.listPath(path) || [];
    const fileMap = new Map<string, { name: string; size: number; nodes: string[]; path: string; isDirectory: boolean; modified?: number }>();

    for (const { node, files: nodeFiles } of allFiles) {
      if (selectedNode) {
        const nodeInfo = nodes.find(n => n.info.nodeId === selectedNode);
        if (nodeInfo && node !== nodeInfo.info.hostname) continue;
      }

      for (const file of (nodeFiles || [])) {
        // Filter hidden files (dotfiles)
        if (!showHiddenFiles && file.name.startsWith('.')) continue;

        const existing = fileMap.get(file.name);
        if (existing) {
          if (!existing.nodes.includes(node)) {
            existing.nodes.push(node);
          }
        } else {
          fileMap.set(file.name, {
            name: file.name,
            size: file.size,
            nodes: [node],
            path: file.path,
            isDirectory: file.isDirectory,
            modified: file.modified
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

  // Build path bar
  const pathParts = path.split('/').filter(p => p && p !== 'data');
  const currentFolder = pathParts.length > 0 ? pathParts[pathParts.length - 1] : 'Home';
  let pathBarHtml = `<a href="/files?view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}" class="pathbar-item">🏠</a>`;
  let buildPath = '/data';
  for (const part of pathParts) {
    buildPath += '/' + part;
    pathBarHtml += `<span class="pathbar-sep">›</span><a href="/files?path=${encodeURIComponent(buildPath)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}" class="pathbar-item">${part}</a>`;
  }

  // Build sidebar
  let sidebarHtml = '';
  for (const { info } of nodes) {
    const isActive = selectedNode === info.nodeId;
    sidebarHtml += `
      <div class="sidebar-item ${isActive ? 'active' : ''}" onclick="location.href='/files?path=${encodeURIComponent(path)}&view=${view}&hidden=${showHiddenFiles}&node=${info.nodeId}'">
        <span class="sidebar-icon">💻</span>
        <span>${info.hostname}</span>
      </div>`;
  }

  // Build file list (List View)
  let listHtml = '';
  let fileIndex = 0;
  for (const file of files) {
    const icon = file.isDirectory ? '📁' : getFileIcon(file.name);
    const isImage = isImageFile(file.name);
    const isVideo = isVideoFile(file.name);
    const isPreviewable = isImage || isVideo;

    let clickAction: string;
    if (file.isDirectory) {
      clickAction = `onclick="location.href='/files?path=${encodeURIComponent(file.path)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'"`;
    } else if (isPreviewable) {
      clickAction = `onclick="openPreview(${fileIndex})" data-index="${fileIndex}"`;
    } else {
      clickAction = `onclick="window.open('/preview?path=${encodeURIComponent(file.path)}', '_blank')"`;
    }

    const nodeList = file.nodes.join(', ');
    const modifiedDate = file.modified ? new Date(file.modified).toLocaleDateString() : '--';

    // Show thumbnail for images in list view
    const iconHtml = isImage
      ? `<img class="list-thumbnail" src="/preview?path=${encodeURIComponent(file.path)}&thumb=1" alt="" loading="lazy">`
      : `<span class="list-icon">${icon}</span>`;

    listHtml += `
      <div class="list-item" ${clickAction} data-path="${file.path}" data-type="${isImage ? 'image' : isVideo ? 'video' : 'file'}">
        ${iconHtml}
        <span class="list-name">${file.name}</span>
        <span class="list-size">${file.isDirectory ? '--' : formatBytes(file.size)}</span>
        <span class="list-modified">${modifiedDate}</span>
        <span class="list-location">${nodeList}</span>
      </div>`;
    if (!file.isDirectory) fileIndex++;
  }

  // Build file grid (Grid View)
  let gridHtml = '';
  fileIndex = 0;
  for (const file of files) {
    const icon = file.isDirectory ? '📁' : getFileIcon(file.name);
    const isImage = isImageFile(file.name);
    const isVideo = isVideoFile(file.name);
    const isPreviewable = isImage || isVideo;

    let clickAction: string;
    if (file.isDirectory) {
      clickAction = `onclick="location.href='/files?path=${encodeURIComponent(file.path)}&view=${view}&hidden=${showHiddenFiles}${selectedNode ? '&node=' + selectedNode : ''}'"`;
    } else if (isPreviewable) {
      clickAction = `onclick="openPreview(${fileIndex})" data-index="${fileIndex}"`;
    } else {
      clickAction = `onclick="window.open('/preview?path=${encodeURIComponent(file.path)}', '_blank')"`;
    }

    // Show actual thumbnails for images
    let iconHtml: string;
    if (isImage) {
      iconHtml = `<div class="grid-thumbnail"><img src="/preview?path=${encodeURIComponent(file.path)}&thumb=1" alt="${file.name}" loading="lazy"></div>`;
    } else if (isVideo) {
      iconHtml = `<div class="grid-thumbnail" style="display:flex;align-items:center;justify-content:center;font-size:32px;background:#1a1a1a;">🎬</div>`;
    } else {
      iconHtml = `<span class="grid-icon">${icon}</span>`;
    }

    gridHtml += `
      <div class="grid-item" ${clickAction} data-path="${file.path}" data-type="${isImage ? 'image' : isVideo ? 'video' : 'file'}">
        ${iconHtml}
        <span class="grid-name">${file.name.length > 20 ? file.name.substring(0, 18) + '...' : file.name}</span>
      </div>`;
    if (!file.isDirectory) fileIndex++;
  }

  // Build previewable files array for JavaScript
  const previewableFiles = files
    .filter(f => !f.isDirectory && (isImageFile(f.name) || isVideoFile(f.name)))
    .map(f => ({ path: f.path, name: f.name, type: isImageFile(f.name) ? 'image' : 'video' }));

  // Use Finder-style window layout (skip the normal template)
  return `
  <div class="window">
    <div class="titlebar">
      <div class="traffic-lights">
        <div class="traffic-light red"></div>
        <div class="traffic-light yellow"></div>
        <div class="traffic-light green"></div>
      </div>
      <div class="titlebar-title">${currentFolder} — Smart DFS</div>
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

  <script>
    // Previewable files data
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

      let mediaHtml = '';
      if (file.type === 'image') {
        mediaHtml = '<img src="/preview?path=' + encodeURIComponent(file.path) + '" alt="' + file.name + '">';
      } else if (file.type === 'video') {
        mediaHtml = '<video controls autoplay><source src="/preview?path=' + encodeURIComponent(file.path) + '"></video>';
      }

      mediaContainer.innerHTML = mediaHtml;
      filenameEl.textContent = file.name + ' (' + (currentPreviewIndex + 1) + ' of ' + previewFiles.length + ')';
      overlay.classList.add('active');

      // Preload adjacent images
      if (currentPreviewIndex > 0 && previewFiles[currentPreviewIndex - 1].type === 'image') {
        new Image().src = '/preview?path=' + encodeURIComponent(previewFiles[currentPreviewIndex - 1].path);
      }
      if (currentPreviewIndex < previewFiles.length - 1 && previewFiles[currentPreviewIndex + 1].type === 'image') {
        new Image().src = '/preview?path=' + encodeURIComponent(previewFiles[currentPreviewIndex + 1].path);
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

    // Keyboard navigation
    document.addEventListener('keydown', (e) => {
      const overlay = document.getElementById('previewOverlay');
      if (!overlay.classList.contains('active')) return;

      if (e.key === 'Escape') closePreview();
      else if (e.key === 'ArrowLeft') navigatePreview(-1);
      else if (e.key === 'ArrowRight') navigatePreview(1);
    });
  </script>`;
}

function isImageFile(filename: string): boolean {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return ['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp', 'bmp', 'ico', 'heic', 'heif'].includes(ext);
}

function isVideoFile(filename: string): boolean {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return ['mp4', 'mov', 'avi', 'mkv', 'webm', 'm4v', 'wmv'].includes(ext);
}

function isAudioFile(filename: string): boolean {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return ['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg', 'wma'].includes(ext);
}

function isPdfFile(filename: string): boolean {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return ext === 'pdf';
}

function getMimeType(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  const mimeTypes: Record<string, string> = {
    'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'png': 'image/png', 'gif': 'image/gif',
    'svg': 'image/svg+xml', 'webp': 'image/webp', 'bmp': 'image/bmp', 'ico': 'image/x-icon',
    'mp4': 'video/mp4', 'mov': 'video/quicktime', 'avi': 'video/x-msvideo', 'mkv': 'video/x-matroska',
    'webm': 'video/webm', 'm4v': 'video/x-m4v',
    'mp3': 'audio/mpeg', 'wav': 'audio/wav', 'flac': 'audio/flac', 'aac': 'audio/aac',
    'm4a': 'audio/mp4', 'ogg': 'audio/ogg',
    'pdf': 'application/pdf',
    'txt': 'text/plain', 'html': 'text/html', 'css': 'text/css', 'js': 'text/javascript',
    'json': 'application/json', 'xml': 'application/xml',
  };
  return mimeTypes[ext] || 'application/octet-stream';
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
      const searchQuery = parsedUrl.query.q as string | undefined;
      const selectedNode = parsedUrl.query.node as string | undefined;
      const currentPath = parsedUrl.query.path as string | undefined;
      const viewMode = parsedUrl.query.view as string | undefined;
      const showHidden = parsedUrl.query.hidden === 'true';
      const content = await renderFiles(searchQuery, selectedNode, currentPath, viewMode, showHidden);
      // Files page has its own full HTML structure (Finder-style)
      res.end(`<!DOCTYPE html><html><head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Smart DFS - Files</title>
        <style>${getFinderStyles()}</style>
      </head><body>${content}</body></html>`);
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
    } else if (pathname === '/preview') {
      // Serve file preview/download
      const filePath = parsedUrl.query.path as string;
      const isThumb = parsedUrl.query.thumb === '1';

      if (!filePath) {
        res.statusCode = 400;
        res.end('Missing path parameter');
        return;
      }

      try {
        // Try to read the file directly from the filesystem
        const fs = await import('fs/promises');
        const path = await import('path');

        // The path from nodes is like /data/... which maps to user home directories
        // Try reading from local filesystem first
        let localPath = filePath;

        // Map /data to actual user directory if on local machine
        if (filePath.startsWith('/data/')) {
          const homeDir = process.env.HOME || '/Users/miguel_lemos';
          localPath = filePath.replace('/data', homeDir);
        }

        try {
          const stat = await fs.stat(localPath);
          if (stat.isFile()) {
            const mimeType = getMimeType(path.basename(localPath));
            const fileBuffer = await fs.readFile(localPath);

            res.setHeader('Content-Type', mimeType);
            res.setHeader('Content-Length', fileBuffer.length);

            // For thumbnails, we could resize but for now just serve the original
            if (isThumb && isImageFile(path.basename(localPath))) {
              res.setHeader('Cache-Control', 'public, max-age=3600');
            }

            res.end(fileBuffer);
            return;
          }
        } catch {
          // Local file not accessible, try via node API
        }

        // Try to fetch from nodes
        await dfs.discoverNodes();
        const nodes = dfs.getNodes();

        for (const { endpoint } of nodes) {
          try {
            // Try to read via node API
            const nodeUrl = endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;
            const readRes = await fetch(`${nodeUrl}/fs/read`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ path: filePath })
            });

            if (readRes.ok) {
              const data = await readRes.json() as { content?: string; error?: string };
              if (data.content && !data.error) {
                const filename = filePath.split('/').pop() || 'file';
                const mimeType = getMimeType(filename);

                // If it's text content, serve it
                if (mimeType.startsWith('text/') || mimeType === 'application/json') {
                  res.setHeader('Content-Type', mimeType);
                  res.end(data.content);
                  return;
                }

                // For binary content, try base64 decode if it looks like base64
                try {
                  const buffer = Buffer.from(data.content, 'base64');
                  res.setHeader('Content-Type', mimeType);
                  res.end(buffer);
                  return;
                } catch {
                  res.setHeader('Content-Type', 'text/plain');
                  res.end(data.content);
                  return;
                }
              }
            }
          } catch {
            // Try next node
          }
        }

        res.statusCode = 404;
        res.end('File not found');
      } catch (e) {
        res.statusCode = 500;
        res.end(`Error: ${(e as Error).message}`);
      }
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
