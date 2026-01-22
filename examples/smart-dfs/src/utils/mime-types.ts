/**
 * MIME type mapping utilities
 */

const MIME_TYPES: Record<string, string> = {
  // Images
  'jpg': 'image/jpeg',
  'jpeg': 'image/jpeg',
  'png': 'image/png',
  'gif': 'image/gif',
  'svg': 'image/svg+xml',
  'webp': 'image/webp',
  'bmp': 'image/bmp',
  'ico': 'image/x-icon',
  'heic': 'image/heic',
  'heif': 'image/heif',

  // Videos
  'mp4': 'video/mp4',
  'mov': 'video/quicktime',
  'avi': 'video/x-msvideo',
  'mkv': 'video/x-matroska',
  'webm': 'video/webm',
  'm4v': 'video/x-m4v',
  'wmv': 'video/x-ms-wmv',

  // Audio
  'mp3': 'audio/mpeg',
  'wav': 'audio/wav',
  'flac': 'audio/flac',
  'aac': 'audio/aac',
  'm4a': 'audio/mp4',
  'ogg': 'audio/ogg',
  'wma': 'audio/x-ms-wma',

  // Documents
  'pdf': 'application/pdf',
  'doc': 'application/msword',
  'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'xls': 'application/vnd.ms-excel',
  'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'ppt': 'application/vnd.ms-powerpoint',
  'pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',

  // Text/Code
  'txt': 'text/plain',
  'html': 'text/html',
  'css': 'text/css',
  'js': 'text/javascript',
  'ts': 'text/typescript',
  'json': 'application/json',
  'xml': 'application/xml',
  'md': 'text/markdown',

  // Archives
  'zip': 'application/zip',
  'tar': 'application/x-tar',
  'gz': 'application/gzip',
  'rar': 'application/vnd.rar',
  '7z': 'application/x-7z-compressed',
};

export function getMimeType(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return MIME_TYPES[ext] || 'application/octet-stream';
}
