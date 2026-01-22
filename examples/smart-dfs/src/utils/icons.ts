/**
 * File icon mapping utilities
 */

const FILE_ICONS: Record<string, string> = {
  // Images
  'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️',
  'svg': '🖼️', 'webp': '🖼️', 'bmp': '🖼️', 'ico': '🖼️',
  'heic': '🖼️', 'heif': '🖼️',

  // Videos
  'mp4': '🎬', 'avi': '🎬', 'mov': '🎬', 'mkv': '🎬',
  'webm': '🎬', 'm4v': '🎬', 'wmv': '🎬',

  // Audio
  'mp3': '🎵', 'wav': '🎵', 'flac': '🎵', 'ogg': '🎵',
  'm4a': '🎵', 'aac': '🎵', 'wma': '🎵',

  // Documents
  'pdf': '📕',
  'doc': '📘', 'docx': '📘',
  'xls': '📗', 'xlsx': '📗',
  'ppt': '📙', 'pptx': '📙',

  // Code
  'js': '📜', 'ts': '📜', 'jsx': '📜', 'tsx': '📜',
  'py': '🐍',
  'java': '☕',
  'scala': '🔴',
  'go': '🔵',
  'rs': '🦀',
  'rb': '💎',
  'php': '🐘',
  'c': '⚙️', 'cpp': '⚙️', 'h': '⚙️',
  'html': '🌐',
  'css': '🎨',
  'json': '📋',
  'xml': '📋',
  'yaml': '📋', 'yml': '📋',
  'sh': '🖥️', 'bash': '🖥️', 'zsh': '🖥️',

  // Archives
  'zip': '📦', 'tar': '📦', 'gz': '📦', 'rar': '📦', '7z': '📦',

  // Text
  'txt': '📄',
  'md': '📝',
  'log': '📃',

  // Config
  'env': '🔐',
  'gitignore': '🙈',
  'dockerignore': '🐳',
};

export function getFileIcon(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return FILE_ICONS[ext] || '📄';
}

export const FOLDER_ICON = '📁';
