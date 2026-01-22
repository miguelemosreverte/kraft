/**
 * File type detection utilities
 */

const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp', 'bmp', 'ico', 'heic', 'heif'];
const VIDEO_EXTENSIONS = ['mp4', 'mov', 'avi', 'mkv', 'webm', 'm4v', 'wmv'];
const AUDIO_EXTENSIONS = ['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg', 'wma'];

function getExtension(filename: string): string {
  return filename.split('.').pop()?.toLowerCase() || '';
}

export function isImageFile(filename: string): boolean {
  return IMAGE_EXTENSIONS.includes(getExtension(filename));
}

export function isVideoFile(filename: string): boolean {
  return VIDEO_EXTENSIONS.includes(getExtension(filename));
}

export function isAudioFile(filename: string): boolean {
  return AUDIO_EXTENSIONS.includes(getExtension(filename));
}

export function isPdfFile(filename: string): boolean {
  return getExtension(filename) === 'pdf';
}

export function isPreviewable(filename: string): boolean {
  return isImageFile(filename) || isVideoFile(filename);
}

const TEXT_EXTENSIONS = [
  'txt', 'md', 'markdown', 'log', 'csv',
  'js', 'ts', 'jsx', 'tsx', 'mjs', 'cjs',
  'py', 'rb', 'php', 'java', 'scala', 'kt', 'go', 'rs', 'c', 'cpp', 'h', 'hpp',
  'html', 'htm', 'css', 'scss', 'sass', 'less',
  'json', 'xml', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg',
  'sh', 'bash', 'zsh', 'fish', 'ps1', 'bat', 'cmd',
  'sql', 'graphql', 'gql',
  'dockerfile', 'makefile', 'gitignore', 'env', 'editorconfig',
  'swift', 'dart', 'lua', 'r', 'pl', 'pm', 'ex', 'exs', 'elm', 'clj', 'hs'
];

export function isTextFile(filename: string): boolean {
  const ext = getExtension(filename);
  const name = filename.toLowerCase();

  // Check extension
  if (TEXT_EXTENSIONS.includes(ext)) return true;

  // Check common config files without extensions
  const configFiles = ['dockerfile', 'makefile', 'gemfile', 'rakefile', 'procfile', 'readme', 'license', 'changelog'];
  if (configFiles.includes(name)) return true;

  return false;
}

export function getLanguage(filename: string): string {
  const ext = getExtension(filename);
  const langMap: Record<string, string> = {
    'js': 'javascript', 'jsx': 'javascript', 'mjs': 'javascript',
    'ts': 'typescript', 'tsx': 'typescript',
    'py': 'python',
    'rb': 'ruby',
    'java': 'java', 'scala': 'scala', 'kt': 'kotlin',
    'go': 'go',
    'rs': 'rust',
    'c': 'c', 'h': 'c', 'cpp': 'cpp', 'hpp': 'cpp',
    'html': 'html', 'htm': 'html',
    'css': 'css', 'scss': 'scss', 'sass': 'sass',
    'json': 'json',
    'xml': 'xml',
    'yaml': 'yaml', 'yml': 'yaml',
    'md': 'markdown', 'markdown': 'markdown',
    'sh': 'bash', 'bash': 'bash', 'zsh': 'bash',
    'sql': 'sql',
    'php': 'php',
    'swift': 'swift',
    'dart': 'dart',
  };
  return langMap[ext] || 'plaintext';
}
