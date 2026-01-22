/**
 * Base HTML layout template
 */

import { getMainStyles, themeScript } from '../styles/index.js';

export interface LayoutOptions {
  title?: string;
  currentPage: string;
  content: string;
}

export function renderLayout({ title = 'Smart DFS', currentPage, content }: LayoutOptions): string {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${title} - Distributed File System</title>
  <style>${getMainStyles()}</style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo">📦 Smart <span>DFS</span></div>
      <nav>
        <a href="/" class="${currentPage === 'home' ? 'active' : ''}">🏠 Dashboard</a>
        <a href="/files" class="${currentPage === 'files' ? 'active' : ''}">📁 Files</a>
        <a href="/upload" class="${currentPage === 'upload' ? 'active' : ''}">📤 Upload</a>
        <button class="theme-toggle" onclick="toggleTheme()" title="Toggle theme">
          <span class="icon">🌙</span>
        </button>
      </nav>
    </header>
    ${content}
  </div>

  <script>
    ${themeScript}

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

          const reader = new FileReader();
          reader.onload = async (event) => {
            const content = event.target.result;

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
</html>`;
}
