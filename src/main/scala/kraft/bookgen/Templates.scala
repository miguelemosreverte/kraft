package kraft.bookgen

import java.sql.Connection

/**
 * HTML templates for BookGen output.
 */
object Templates:

  def wrapHTML(content: String): String =
    s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Fever Event Search API - Performance Journey</title>
  <script>
    (function() {
      var savedTheme = localStorage.getItem('theme');
      var savedStyle = localStorage.getItem('style');
      var theme = savedTheme;
      if (!theme) {
        theme = (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
      }
      var style = savedStyle || 'editorial';
      document.documentElement.setAttribute('data-theme', theme);
      document.documentElement.setAttribute('data-style', style);
    })();
  </script>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
  <script>
    mermaid.initialize({
      startOnLoad: true,
      theme: document.documentElement.getAttribute('data-theme') === 'light' ? 'default' : 'dark',
      flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' }
    });
  </script>
  <script src="c4-renderer.js"></script>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css" id="hljs-theme-dark">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css" id="hljs-theme-light">
  <script>
    (function() {
      var theme = document.documentElement.getAttribute('data-theme');
      document.getElementById('hljs-theme-dark').disabled = (theme === 'light');
      document.getElementById('hljs-theme-light').disabled = (theme !== 'light');
    })();
  </script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/go.min.js"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/scala.min.js"></script>
  <style>
    :root, [data-theme="dark"] {
      --bg-primary: #0d1117;
      --bg-secondary: #161b22;
      --bg-tertiary: #21262d;
      --text-primary: #c9d1d9;
      --text-secondary: #8b949e;
      --gray-50: #f9fafb;
      --gray-100: #f3f4f6;
      --gray-200: #e5e7eb;
      --gray-300: #d1d5db;
      --gray-400: #9ca3af;
      --gray-500: #6b7280;
      --gray-600: #4b5563;
      --gray-700: #374151;
      --gray-800: #1f2937;
      --gray-900: #111827;
      --accent-primary: #c9d1d9;
      --accent-green: #c9d1d9;
      --accent-blue: #58a6ff;
      --accent-orange: #d29922;
      --accent-red: #f85149;
      --accent-purple: #a371f7;
      --border-color: #30363d;
      --code-bg: #21262d;
      --heading-font: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
      --body-font: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
      --panel-radius: 8px;
      --panel-border: 1px solid var(--border-color);
      --panel-bg: var(--bg-secondary);
      --panel-padding: 1.5rem;
    }
    [data-theme="light"] {
      --bg-primary: #ffffff;
      --bg-secondary: #f6f8fa;
      --bg-tertiary: #eaeef2;
      --text-primary: #1f2328;
      --text-secondary: #656d76;
      --accent-primary: #374151;
      --accent-green: #374151;
      --accent-blue: #0969da;
      --accent-orange: #9a6700;
      --accent-red: #cf222e;
      --accent-purple: #8250df;
      --border-color: #d0d7de;
      --code-bg: #eaeef2;
    }
    [data-style="editorial"] {
      --heading-font: 'Georgia', 'Times New Roman', serif;
      --body-font: 'Georgia', 'Times New Roman', serif;
      --panel-radius: 0;
      --panel-border: none;
      --panel-bg: transparent;
      --panel-padding: 0;
    }
    [data-style="editorial"][data-theme="dark"] {
      --bg-primary: #1a1a1a;
      --bg-secondary: #1a1a1a;
      --bg-tertiary: #2a2a2a;
      --text-primary: #e8e8e8;
      --text-secondary: #999999;
      --border-color: #333333;
    }
    [data-style="editorial"][data-theme="light"] {
      --bg-primary: #faf9f6;
      --bg-secondary: #faf9f6;
      --bg-tertiary: #f0efe9;
      --text-primary: #222222;
      --text-secondary: #666666;
      --border-color: #dddddd;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html { background: var(--bg-primary); }
    body {
      font-family: var(--body-font);
      background: var(--bg-primary);
      color: var(--text-primary);
      line-height: 1.6;
      max-width: 900px;
      margin: 0 auto;
      padding: 2rem;
    }
    canvas { background: transparent !important; }
    h1, h2, h3, h4 {
      font-family: var(--heading-font);
      color: var(--text-primary);
      margin: 2rem 0 1rem;
      font-weight: 600;
    }
    h1 { font-size: 2.5rem; border-bottom: 1px solid var(--border-color); padding-bottom: 0.5rem; }
    h2 { font-size: 1.8rem; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3rem; }
    h3 { font-size: 1.4rem; }
    h4 { font-size: 1.1rem; color: var(--text-secondary); }
    [data-style="editorial"] h1 { font-size: 2.8rem; font-weight: 700; letter-spacing: -0.02em; border-bottom: 2px solid var(--text-primary); }
    [data-style="editorial"] h2 { font-size: 1.6rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; margin-top: 3rem; }
    [data-style="editorial"] h3 { font-size: 1.3rem; font-weight: 600; font-style: italic; }
    [data-style="editorial"] p { font-size: 1.05rem; line-height: 1.75; }
    p { margin: 1rem 0; }
    em { color: var(--text-secondary); font-style: italic; }
    strong { color: var(--accent-green); font-weight: 600; }
    a { color: var(--accent-blue); text-decoration: none; }
    a:hover { text-decoration: underline; }
    code {
      background: var(--bg-tertiary);
      padding: 0.2rem 0.4rem;
      border-radius: 4px;
      font-family: 'SFMono-Regular', Consolas, monospace;
      font-size: 0.9em;
    }
    pre {
      background: var(--bg-secondary) !important;
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 1rem;
      overflow-x: auto;
      margin: 1rem 0;
    }
    pre code { background: transparent !important; padding: 0; }
    pre code.hljs, .hljs { background: transparent !important; }
    hr { border: none; border-top: 1px solid var(--border-color); margin: 3rem 0; }
    table { width: 100%; border-collapse: collapse; margin: 1rem 0; }
    th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid var(--border-color); }
    th { background: var(--bg-secondary); font-weight: 600; color: var(--text-secondary); }
    tr:hover { background: var(--bg-secondary); }
    ul, ol { margin: 1rem 0; padding-left: 2rem; }
    li { margin: 0.5rem 0; }
    .rps { font-weight: 600; color: var(--accent-blue); }
    .improvement { color: var(--accent-green); }
    .chart-container {
      background: var(--panel-bg);
      border: var(--panel-border);
      border-radius: var(--panel-radius);
      padding: var(--panel-padding);
      margin: 2rem 0;
    }
    .chart-container h4 { margin: 0 0 1rem; color: var(--text-primary); }
    [data-style="editorial"] .chart-container { border-bottom: 1px solid var(--border-color); padding-bottom: 2rem; margin-bottom: 2rem; }
    [data-style="editorial"] .chart-container h4 { font-family: var(--heading-font); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); margin-bottom: 1.5rem; }
    .git-visualization { background: var(--panel-bg); border: var(--panel-border); border-radius: var(--panel-radius); padding: var(--panel-padding); margin: 2rem 0; }
    [data-style="editorial"] .git-visualization { border-left: 3px solid var(--accent-blue); padding-left: 1.5rem; }
    .branch-label { display: inline-block; background: var(--accent-green); color: var(--bg-primary); padding: 0.2rem 0.6rem; border-radius: 4px; font-size: 0.85rem; font-weight: 600; margin-bottom: 1rem; }
    .commits { position: relative; padding-left: 2rem; }
    .commits::before { content: ''; position: absolute; left: 0.5rem; top: 0; bottom: 0; width: 2px; background: var(--accent-green); }
    .git-commit { position: relative; display: flex; align-items: flex-start; margin-bottom: 1rem; padding: 0.5rem 0; }
    .git-commit .node { position: absolute; left: -1.65rem; width: 12px; height: 12px; background: var(--accent-green); border-radius: 50%; border: 2px solid var(--bg-secondary); }
    .git-commit .node.final { width: 16px; height: 16px; left: -1.75rem; background: linear-gradient(135deg, var(--accent-green), var(--accent-blue)); box-shadow: 0 0 10px rgba(63, 185, 80, 0.5); }
    .commit-info { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
    .commit-info .tag { background: var(--bg-tertiary); color: var(--accent-blue); padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.8rem; }
    .commit-info .message { color: var(--text-primary); }
    .commit-info .improvement { color: var(--accent-green); font-weight: 600; }
    .table-container { background: var(--panel-bg); border: var(--panel-border); border-radius: var(--panel-radius); padding: var(--panel-padding); margin: 2rem 0; overflow-x: auto; }
    .table-container h4 { margin: 0 0 1rem; }
    [data-style="editorial"] .table-container { padding: 0; }
    [data-style="editorial"] .table-container h4 { font-family: var(--heading-font); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); padding-bottom: 0.5rem; border-bottom: 1px solid var(--border-color); }
    .generated-footer { margin-top: 4rem; padding-top: 2rem; border-top: 1px solid var(--border-color); text-align: center; color: var(--text-secondary); font-size: 0.9rem; }
    .c4-diagram, .mermaid-diagram { background: var(--panel-bg); border: var(--panel-border); border-radius: var(--panel-radius); padding: var(--panel-padding); margin: 2rem 0; overflow: visible; }
    .c4-diagram h4, .mermaid-diagram h4 { margin: 0 0 1rem; color: var(--text-primary); }
    .c4-diagram .mermaid, .mermaid-diagram .mermaid { background: transparent; min-height: 400px; }
    .c4-diagram svg, .mermaid-diagram svg { max-width: 100%; height: auto !important; overflow: visible; }
    .c4-diagram .relation { stroke-width: 1.5px; }
    .c4-diagram .messageText { font-size: 12px; fill: var(--text-secondary); }
    .c4-diagram .boundary { overflow: visible; }
    .mermaid-diagram .node rect, .mermaid-diagram .node polygon { fill: #438dd5 !important; stroke: #1168bd !important; }
    .mermaid-diagram .node .label { color: #fff !important; }
    .mermaid-diagram .cluster rect { fill: transparent !important; stroke: #444 !important; stroke-dasharray: 5,5; }
    .mermaid-diagram .cluster span { color: var(--text-primary) !important; }
    .mermaid-diagram .edgePath path { stroke: #666 !important; }
    .mermaid-diagram .edgeLabel { background: var(--panel-bg) !important; }
    [data-theme="light"] .mermaid-diagram .node rect, [data-theme="light"] .mermaid-diagram .node polygon { fill: #438dd5 !important; stroke: #1168bd !important; }
    [data-theme="light"] .mermaid-diagram .cluster rect { stroke: #999 !important; }
    .controls { position: fixed; top: 1.5rem; right: 1.5rem; display: flex; gap: 0.75rem; z-index: 1000; }
    .control-btn { width: 44px; height: 44px; border-radius: 50%; border: 1px solid var(--border-color); background: var(--bg-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.3s ease; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2); }
    .control-btn:hover { transform: scale(1.1); border-color: var(--accent-blue); }
    .control-btn svg { width: 20px; height: 20px; fill: var(--text-primary); }
    .theme-toggle .sun-icon { display: none; }
    .theme-toggle .moon-icon { display: block; }
    [data-theme="light"] .theme-toggle .sun-icon { display: block; }
    [data-theme="light"] .theme-toggle .moon-icon { display: none; }
    .style-toggle { font-family: var(--heading-font); font-size: 11px; font-weight: 700; }
    .style-toggle .style-label { color: var(--text-primary); }
    @media (max-width: 768px) { body { padding: 1rem; } h1 { font-size: 1.8rem; } }
  </style>
</head>
<body>
  <div class="controls">
    <button class="control-btn style-toggle" onclick="cycleStyle()" title="Switch style"><span class="style-label">Aa</span></button>
    <button class="control-btn theme-toggle" onclick="toggleTheme()" title="Toggle theme">
      <svg class="sun-icon" viewBox="0 0 24 24"><path d="M12 17.5a5.5 5.5 0 1 0 0-11 5.5 5.5 0 0 0 0 11zm0 1.5a7 7 0 1 1 0-14 7 7 0 0 1 0 14zm0-17a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5A.75.75 0 0 1 12 2z"/></svg>
      <svg class="moon-icon" viewBox="0 0 24 24"><path d="M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49z"/></svg>
    </button>
  </div>
  $content
  <script>
    const STYLES = ['modern', 'editorial'];
    function cycleStyle() {
      const html = document.documentElement;
      const current = html.getAttribute('data-style') || 'modern';
      const next = STYLES[(STYLES.indexOf(current) + 1) % STYLES.length];
      html.setAttribute('data-style', next);
      localStorage.setItem('style', next);
      document.querySelector('.style-label').textContent = next === 'modern' ? 'Aa' : 'Ed';
    }
    function toggleTheme() {
      const html = document.documentElement;
      const current = html.getAttribute('data-theme');
      const next = current === 'dark' ? 'light' : 'dark';
      html.setAttribute('data-theme', next);
      localStorage.setItem('theme', next);
      document.getElementById('hljs-theme-dark').disabled = (next === 'light');
      document.getElementById('hljs-theme-light').disabled = (next !== 'light');
    }
    (function() {
      const style = document.documentElement.getAttribute('data-style') || 'editorial';
      document.querySelector('.style-label').textContent = style === 'modern' ? 'Aa' : 'Ed';
      if (typeof hljs !== 'undefined') hljs.highlightAll();
    })();
  </script>
</body>
</html>"""

  def wrapLandingHTML(content: String, extractMetric: (String, String, String) => String, conn: Option[Connection]): String =
    val maxRPS = extractMetric("v5-response-cache", "", "short")
    val p99Latency = extractMetric("v4-observability", "p99_latency_us", "comma")
    val improvement = extractMetric("v5-response-cache", "improvement_percent", "decimal")

    s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Fever Documentation</title>
  <script>
    (function() {
      var savedTheme = localStorage.getItem('theme');
      var savedStyle = localStorage.getItem('style');
      var theme = savedTheme;
      if (!theme) {
        theme = (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
      }
      var style = savedStyle || 'editorial';
      document.documentElement.setAttribute('data-theme', theme);
      document.documentElement.setAttribute('data-style', style);
    })();
  </script>
  <style>
    :root, [data-theme="dark"] {
      --bg: #0d1117;
      --bg-secondary: #161b22;
      --text: #c9d1d9;
      --accent: #58a6ff;
      --card-bg: #161b22;
      --border: #30363d;
      --muted: #8b949e;
    }
    [data-theme="light"] {
      --bg: #ffffff;
      --bg-secondary: #f6f8fa;
      --text: #1f2328;
      --accent: #0969da;
      --card-bg: #f6f8fa;
      --border: #d0d7de;
      --muted: #656d76;
    }
    [data-style="editorial"] {
      --heading-font: 'Georgia', 'Times New Roman', serif;
      --body-font: 'Georgia', 'Times New Roman', serif;
    }
    [data-style="editorial"][data-theme="dark"] {
      --bg: #1a1a1a;
      --bg-secondary: #1a1a1a;
      --card-bg: #252525;
      --text: #e8e8e8;
      --muted: #999999;
      --border: #333333;
    }
    [data-style="editorial"][data-theme="light"] {
      --bg: #faf9f6;
      --bg-secondary: #faf9f6;
      --card-bg: #f0efe9;
      --text: #222222;
      --muted: #666666;
      --border: #dddddd;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html { background: var(--bg); }
    body { font-family: var(--body-font, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif); background: var(--bg); color: var(--text); min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 2rem; }
    h1 { font-family: var(--heading-font, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif); font-size: 2.5rem; margin-bottom: 0.5rem; }
    [data-style="editorial"] h1 { font-size: 2.8rem; font-weight: 700; letter-spacing: -0.02em; }
    .subtitle { color: var(--muted); margin-bottom: 2rem; }
    [data-style="editorial"] .subtitle { font-style: italic; }
    .metrics { display: flex; gap: 2rem; margin-bottom: 3rem; flex-wrap: wrap; justify-content: center; }
    .metric { text-align: center; padding: 1rem 2rem; background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; min-width: 140px; }
    [data-style="editorial"] .metric { border-radius: 0; border: none; border-bottom: 2px solid var(--accent); }
    .metric-value { font-size: 2rem; font-weight: 700; color: var(--accent); }
    .metric-label { font-size: 0.85rem; color: var(--muted); margin-top: 0.25rem; }
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; max-width: 800px; width: 100%; }
    .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; padding: 2rem; text-decoration: none; color: var(--text); transition: transform 0.2s, box-shadow 0.2s; }
    .card:hover { transform: translateY(-4px); box-shadow: 0 8px 24px rgba(0,0,0,0.2); }
    [data-style="editorial"] .card { border-radius: 0; border: none; border-left: 3px solid var(--accent); }
    [data-style="editorial"] .card:hover { transform: translateX(4px); box-shadow: none; }
    .card h2 { font-family: var(--heading-font, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif); color: var(--accent); margin-bottom: 0.5rem; font-size: 1.4rem; }
    .card p { color: var(--muted); line-height: 1.5; }
    .card .arrow { float: right; font-size: 1.5rem; color: var(--accent); }
    .tag { display: inline-block; background: var(--accent); color: var(--bg); font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; margin-left: 0.5rem; vertical-align: middle; }
    [data-style="editorial"] .tag { border-radius: 0; text-transform: uppercase; letter-spacing: 0.05em; }
    footer { margin-top: 3rem; color: var(--muted); font-size: 0.9rem; }
    .controls { position: fixed; top: 1.5rem; right: 1.5rem; display: flex; gap: 0.75rem; z-index: 1000; }
    .control-btn { width: 44px; height: 44px; border-radius: 50%; border: 1px solid var(--border); background: var(--bg-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.3s ease; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2); }
    .control-btn:hover { transform: scale(1.1); border-color: var(--accent); }
    .control-btn svg { width: 20px; height: 20px; fill: var(--text); }
    .theme-toggle .sun-icon { display: none; }
    .theme-toggle .moon-icon { display: block; }
    [data-theme="light"] .theme-toggle .sun-icon { display: block; }
    [data-theme="light"] .theme-toggle .moon-icon { display: none; }
    .style-toggle { font-family: var(--heading-font, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif); font-size: 11px; font-weight: 700; }
    .style-toggle .style-label { color: var(--text); }
    @media (max-width: 768px) { body { padding: 1rem; } h1 { font-size: 1.8rem; } .controls { top: 1rem; right: 1rem; } }
  </style>
</head>
<body>
  <div class="controls">
    <button class="control-btn style-toggle" onclick="cycleStyle()" title="Switch style"><span class="style-label">Aa</span></button>
    <button class="control-btn theme-toggle" onclick="toggleTheme()" title="Toggle theme">
      <svg class="sun-icon" viewBox="0 0 24 24"><path d="M12 17.5a5.5 5.5 0 1 0 0-11 5.5 5.5 0 0 0 0 11zm0 1.5a7 7 0 1 1 0-14 7 7 0 0 1 0 14zm0-17a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5A.75.75 0 0 1 12 2z"/></svg>
      <svg class="moon-icon" viewBox="0 0 24 24"><path d="M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49z"/></svg>
    </button>
  </div>
  <h1>Fever Documentation</h1>
  <p class="subtitle">High-Performance Event Search API - Scala Edition</p>
  <div class="metrics">
    <div class="metric">
      <div class="metric-value">$maxRPS</div>
      <div class="metric-label">Requests/sec</div>
    </div>
    <div class="metric">
      <div class="metric-value">${p99Latency}µs</div>
      <div class="metric-label">P99 Latency</div>
    </div>
    <div class="metric">
      <div class="metric-value">${improvement}%</div>
      <div class="metric-label">vs Gin Baseline</div>
    </div>
  </div>
  <div class="cards">
    <a href="app-book.html" class="card">
      <span class="arrow">-></span>
      <h2>Application Book <span class="tag">ARCHITECTURE</span></h2>
      <p>Clean architecture and domain-driven design. Covers domain modeling, use cases, ports &amp; adapters, and the dependency rule.</p>
    </a>
    <a href="server-book.html" class="card">
      <span class="arrow">-></span>
      <h2>Server Library <span class="tag">PERFORMANCE</span></h2>
      <p>The journey to 500K+ RPS. Covers io_uring, load shedding, adaptive limits, scaling signals, and benchmark data.</p>
    </a>
    <a href="bookgen-book.html" class="card">
      <span class="arrow">-></span>
      <h2>BookGen <span class="tag">DOCUMENTATION</span></h2>
      <p>Living documentation generator. Embed metrics, charts, and diagrams directly in Markdown with real benchmark data.</p>
    </a>
    <a href="dsl-book.html" class="card">
      <span class="arrow">-></span>
      <h2>DSL Book <span class="tag">HTTP DSL</span></h2>
      <p>HTTP DSL implementation. Covers the domain-specific language for defining routes, handlers, and middleware.</p>
    </a>
    <a href="durable-book.html" class="card">
      <span class="arrow">-></span>
      <h2>Durable Execution <span class="tag">CLUSTER</span></h2>
      <p>Durable workflows with cluster support. Covers journaling, replay, SWIM gossip, consistent hashing, and failover.</p>
    </a>
  </div>
  <footer>Fever Code Challenge - Clean Architecture - Scala Edition</footer>
  <script>
    const STYLES = ['modern', 'editorial'];
    function cycleStyle() {
      const html = document.documentElement;
      const current = html.getAttribute('data-style') || 'modern';
      const next = STYLES[(STYLES.indexOf(current) + 1) % STYLES.length];
      html.setAttribute('data-style', next);
      localStorage.setItem('style', next);
      document.querySelector('.style-label').textContent = next === 'modern' ? 'Aa' : 'Ed';
    }
    function toggleTheme() {
      const html = document.documentElement;
      const current = html.getAttribute('data-theme');
      const next = current === 'dark' ? 'light' : 'dark';
      html.setAttribute('data-theme', next);
      localStorage.setItem('theme', next);
    }
    (function() {
      const style = document.documentElement.getAttribute('data-style') || 'editorial';
      document.querySelector('.style-label').textContent = style === 'modern' ? 'Aa' : 'Ed';
    })();
  </script>
</body>
</html>"""
