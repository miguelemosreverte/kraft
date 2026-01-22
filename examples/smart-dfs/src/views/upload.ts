/**
 * Upload view - file upload form
 */

import type { SmartDFS } from '../../smart-dfs.js';

export interface UploadViewOptions {
  message?: string;
  isError?: boolean;
}

export async function renderUpload(dfs: SmartDFS, options: UploadViewOptions = {}): Promise<string> {
  const { message, isError } = options;
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
