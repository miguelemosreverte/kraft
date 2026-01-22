/**
 * Component styles - cards, nodes, file items, etc.
 */

export const headerStyles = `
  header {
    background: var(--bg-secondary);
    padding: 20px 30px;
    border-radius: 10px;
    margin-bottom: 25px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border: 1px solid var(--border-color);
  }

  .logo {
    font-size: 1.8em;
    font-weight: 700;
  }

  .logo span { color: var(--accent-color); }

  nav {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  nav a {
    color: var(--text-secondary);
    text-decoration: none;
    padding: 10px 20px;
    border-radius: 8px;
    transition: all 0.2s;
    font-weight: 500;
  }

  nav a:hover {
    color: var(--text-primary);
    background: var(--bg-hover);
  }

  nav a.active {
    color: #fff;
    background: var(--accent-color);
  }
`;

export const cardStyles = `
  .card {
    background: var(--bg-secondary);
    border-radius: 16px;
    padding: 25px;
    border: 1px solid var(--border-color);
    margin-bottom: 25px;
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
  }

  .card-title {
    font-size: 1.3em;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 10px;
  }
`;

export const statStyles = `
  .stats-row {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 20px;
    margin-bottom: 25px;
  }

  @media (max-width: 900px) {
    .stats-row {
      grid-template-columns: repeat(2, 1fr);
    }
  }

  .stat-card {
    background: var(--bg-secondary);
    border-radius: 16px;
    padding: 25px;
    text-align: center;
    border: 1px solid var(--border-color);
  }

  .stat-icon {
    font-size: 2.5em;
    margin-bottom: 10px;
  }

  .stat-value {
    font-size: 2.2em;
    font-weight: 700;
  }

  .stat-label {
    color: var(--text-secondary);
    margin-top: 5px;
  }
`;

export const nodeStyles = `
  .nodes-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 20px;
  }

  .node-card {
    background: var(--bg-tertiary);
    border-radius: 12px;
    padding: 20px;
    border-left: 4px solid var(--success-color);
  }

  .node-card.warning { border-left-color: #fbbf24; }
  .node-card.danger { border-left-color: #ef4444; }

  .node-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 15px;
  }

  .node-name {
    font-weight: 600;
    font-size: 1.1em;
  }

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
    background: var(--bg-hover);
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

  .node-stats {
    display: flex;
    justify-content: space-between;
    color: var(--text-secondary);
    font-size: 0.85em;
  }
`;

export const uploadStyles = `
  .drop-zone {
    border: 3px dashed var(--border-color);
    border-radius: 16px;
    padding: 60px 40px;
    text-align: center;
    transition: all 0.3s;
    margin-bottom: 25px;
  }

  .drop-zone.drag-over {
    border-color: var(--success-color);
    background: rgba(74,222,128,0.1);
  }

  .drop-zone-icon {
    font-size: 4em;
    margin-bottom: 20px;
    opacity: 0.5;
  }

  .drop-zone-text {
    font-size: 1.2em;
    color: var(--text-secondary);
    margin-bottom: 15px;
  }

  .drop-zone-hint {
    font-size: 0.9em;
    color: var(--text-muted);
  }

  .upload-options {
    display: flex;
    gap: 20px;
    align-items: center;
    justify-content: center;
    margin-top: 20px;
    padding-top: 20px;
    border-top: 1px solid var(--border-color);
  }

  .option-group {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .option-label { color: var(--text-secondary); }

  textarea {
    background: var(--bg-input);
    color: var(--text-primary);
    border: 1px solid var(--border-color);
  }

  textarea:focus {
    outline: 2px solid var(--accent-color);
  }
`;

export const searchStyles = `
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
    background: var(--bg-secondary);
    color: var(--text-primary);
  }

  .search-input::placeholder { color: var(--text-muted); }

  .search-icon {
    position: absolute;
    left: 20px;
    top: 50%;
    transform: translateY(-50%);
    font-size: 1.3em;
    opacity: 0.5;
  }
`;
