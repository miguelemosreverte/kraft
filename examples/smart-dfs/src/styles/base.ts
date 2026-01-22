/**
 * Base styles - common across all pages
 */

export const baseStyles = `
  :root {
    /* Dark theme (default) */
    --bg-primary: #1e1e1e;
    --bg-secondary: #2d2d2d;
    --bg-tertiary: #252525;
    --bg-hover: #3d3d3d;
    --bg-input: rgba(255,255,255,0.1);
    --text-primary: #e0e0e0;
    --text-secondary: #888;
    --text-muted: #666;
    --border-color: #3d3d3d;
    --accent-color: #0a84ff;
    --success-color: #4ade80;
  }

  body.light-theme {
    /* Light theme */
    --bg-primary: #f5f5f7;
    --bg-secondary: #ffffff;
    --bg-tertiary: #e8e8ed;
    --bg-hover: #d1d1d6;
    --bg-input: rgba(0,0,0,0.05);
    --text-primary: #1d1d1f;
    --text-secondary: #6e6e73;
    --text-muted: #86868b;
    --border-color: #d2d2d7;
    --accent-color: #0071e3;
    --success-color: #34c759;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', sans-serif;
    background: var(--bg-primary);
    color: var(--text-primary);
    min-height: 100vh;
    font-size: 13px;
    transition: background 0.3s, color 0.3s;
  }

  .container {
    max-width: 1400px;
    margin: 0 auto;
    padding: 20px;
  }

  /* Theme Toggle */
  .theme-toggle {
    background: var(--bg-hover);
    border: none;
    border-radius: 20px;
    padding: 8px 12px;
    cursor: pointer;
    font-size: 18px;
    transition: all 0.2s;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .theme-toggle:hover {
    transform: scale(1.05);
  }

  .theme-toggle .icon {
    transition: transform 0.3s;
  }

  body.light-theme .theme-toggle .icon {
    transform: rotate(180deg);
  }

  /* Buttons */
  .btn {
    padding: 12px 30px;
    border: none;
    border-radius: 10px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
    font-size: 1em;
  }

  .btn-primary {
    background: var(--success-color);
    color: #000;
  }

  .btn-primary:hover {
    filter: brightness(0.9);
    transform: translateY(-1px);
  }

  .btn-secondary {
    background: var(--bg-input);
    color: var(--text-primary);
  }

  .btn-secondary:hover {
    background: var(--bg-hover);
  }

  /* Form Elements */
  select, input[type="text"] {
    padding: 10px 15px;
    border: none;
    border-radius: 8px;
    background: var(--bg-input);
    color: var(--text-primary);
    font-size: 1em;
  }

  select:focus, input:focus {
    outline: 2px solid var(--accent-color);
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

  .alert-success {
    background: rgba(74,222,128,0.15);
    border: 1px solid rgba(74,222,128,0.3);
  }

  .alert-error {
    background: rgba(239,68,68,0.15);
    border: 1px solid rgba(239,68,68,0.3);
  }

  /* Empty State */
  .empty-state {
    text-align: center;
    padding: 80px 40px;
    color: var(--text-muted);
  }

  .empty-icon {
    font-size: 5em;
    margin-bottom: 20px;
    opacity: 0.3;
  }

  .empty-text {
    font-size: 1.2em;
  }
`;

export const themeScript = `
  // Theme toggle functionality
  function initTheme() {
    const savedTheme = localStorage.getItem('dfs-theme');
    if (savedTheme === 'light') {
      document.body.classList.add('light-theme');
    }
    updateThemeIcon();
  }

  function toggleTheme() {
    document.body.classList.toggle('light-theme');
    const isLight = document.body.classList.contains('light-theme');
    localStorage.setItem('dfs-theme', isLight ? 'light' : 'dark');
    updateThemeIcon();
  }

  function updateThemeIcon() {
    const btn = document.querySelector('.theme-toggle .icon');
    if (btn) {
      const isLight = document.body.classList.contains('light-theme');
      btn.textContent = isLight ? '☀️' : '🌙';
    }
  }

  // Initialize on load
  initTheme();
`;
