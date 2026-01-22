/**
 * Dashboard view - shows system overview
 */

import { formatBytes } from '../../smart-dfs.js';
import type { SmartDFS } from '../../smart-dfs.js';

export async function renderDashboard(dfs: SmartDFS): Promise<string> {
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
