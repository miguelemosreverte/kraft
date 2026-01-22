/**
 * Smart Distributed Filesystem Client
 *
 * Features:
 * - Space-aware file placement (prefers nodes with more free space)
 * - RAID-like replication (store files on multiple nodes)
 * - Search across all nodes
 * - Automatic failover for reads
 */

export interface NodeInfo {
  nodeId: string;
  hostname: string;
  storagePath: string;
  totalSpace: number;
  freeSpace: number;
  usableSpace: number;
}

export interface DiskInfo {
  nodeId: string;
  hostname: string;
  totalSpace: number;
  freeSpace: number;
  usableSpace: number;
  usedSpace: number;
  usagePercent: number;
  storagePath: string;
}

export interface FileInfo {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  modified: number;
}

export interface WriteResponse {
  nodeId: string;
  hostname: string;
  path: string;
  bytesWritten: number;
  checksum: string;
  error?: string;
}

export interface ReadResponse {
  nodeId: string;
  hostname: string;
  path: string;
  content: string;
  size: number;
  checksum: string;
  error?: string;
}

export interface SearchResponse {
  nodeId: string;
  hostname: string;
  pattern: string;
  results: FileInfo[];
  totalFound: number;
  error?: string;
}

export interface ExistsResponse {
  nodeId: string;
  hostname: string;
  path: string;
  exists: boolean;
  isFile: boolean;
  size: number;
  checksum?: string;
}

export interface DeleteResponse {
  nodeId: string;
  hostname: string;
  path: string;
  deleted: boolean;
  error?: string;
}

export interface LsResponse {
  nodeId: string;
  hostname: string;
  path: string;
  files: FileInfo[];
  error?: string;
}

export interface StoredFile {
  filename: string;
  path: string;
  size: number;
  checksum: string;
  replicas: {
    nodeId: string;
    hostname: string;
    endpoint: string;
  }[];
  createdAt: number;
}

export interface SmartDFSConfig {
  replicationFactor: number;  // How many copies to keep (1 = no replication, 2 = RAID-1 like)
  minFreeSpacePercent: number;  // Don't use nodes with less than this % free space
}

export class SmartDFS {
  private nodes: { endpoint: string; info: NodeInfo }[] = [];
  private fileIndex: Map<string, StoredFile> = new Map();

  constructor(
    private endpoints: string[],
    private config: SmartDFSConfig = { replicationFactor: 2, minFreeSpacePercent: 10 }
  ) {}

  private async request<T>(endpoint: string, path: string, body?: unknown): Promise<T> {
    const url = endpoint.startsWith('http') ? endpoint : `http://${endpoint}`;
    const res = await fetch(`${url}${path}`, {
      method: body ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    return res.json();
  }

  /**
   * Discover all available nodes and their disk info
   */
  async discoverNodes(): Promise<{ endpoint: string; info: NodeInfo }[]> {
    this.nodes = [];
    for (const endpoint of this.endpoints) {
      try {
        const info = await this.request<NodeInfo>(endpoint, '/fs/info');
        if (info && info.nodeId) {
          this.nodes.push({ endpoint, info });
        }
      } catch {
        // Node not available
      }
    }
    return this.nodes;
  }

  /**
   * Get detailed disk info for all nodes
   */
  async getDiskInfo(): Promise<{ endpoint: string; disk: DiskInfo }[]> {
    const results: { endpoint: string; disk: DiskInfo }[] = [];
    for (const { endpoint } of this.nodes) {
      try {
        const disk = await this.request<DiskInfo>(endpoint, '/fs/disk-info');
        results.push({ endpoint, disk });
      } catch {
        // Node not available
      }
    }
    return results;
  }

  /**
   * Get nodes sorted by free space (most free first)
   * Filters out nodes with less than minFreeSpacePercent
   */
  async getNodesByFreeSpace(): Promise<{ endpoint: string; disk: DiskInfo }[]> {
    const diskInfo = await this.getDiskInfo();
    return diskInfo
      .filter(d => (100 - d.disk.usagePercent) >= this.config.minFreeSpacePercent)
      .sort((a, b) => b.disk.freeSpace - a.disk.freeSpace);
  }

  /**
   * Save a file with smart placement and replication
   */
  async save(filename: string, content: string): Promise<StoredFile> {
    const sortedNodes = await this.getNodesByFreeSpace();

    if (sortedNodes.length === 0) {
      throw new Error('No nodes available with sufficient free space');
    }

    const replicaCount = Math.min(this.config.replicationFactor, sortedNodes.length);
    const targetNodes = sortedNodes.slice(0, replicaCount);

    const replicas: StoredFile['replicas'] = [];
    let checksum = '';
    let size = 0;

    // Write to each target node
    for (const { endpoint, disk } of targetNodes) {
      const path = `${disk.storagePath}/${filename}`;
      const result = await this.request<WriteResponse>(endpoint, '/fs/write', {
        path,
        content
      });

      if (result.error) {
        console.error(`Failed to write to ${disk.hostname}: ${result.error}`);
        continue;
      }

      checksum = result.checksum;
      size = result.bytesWritten;
      replicas.push({
        nodeId: result.nodeId,
        hostname: result.hostname,
        endpoint
      });
    }

    if (replicas.length === 0) {
      throw new Error('Failed to write file to any node');
    }

    const storedFile: StoredFile = {
      filename,
      path: filename,
      size,
      checksum,
      replicas,
      createdAt: Date.now()
    };

    this.fileIndex.set(filename, storedFile);
    return storedFile;
  }

  /**
   * Read a file (with automatic failover to replicas)
   */
  async read(filename: string): Promise<{ content: string; fromNode: string }> {
    const storedFile = this.fileIndex.get(filename);

    if (storedFile) {
      // Try each replica until one succeeds
      for (const replica of storedFile.replicas) {
        try {
          const path = `${await this.getStoragePath(replica.endpoint)}/${filename}`;
          const result = await this.request<ReadResponse>(replica.endpoint, '/fs/read', { path });

          if (!result.error) {
            // Verify checksum
            if (result.checksum === storedFile.checksum) {
              return { content: result.content, fromNode: result.hostname };
            } else {
              console.warn(`Checksum mismatch on ${replica.hostname}, trying next replica`);
            }
          }
        } catch {
          // Try next replica
        }
      }
    }

    // File not in index, search all nodes
    const searchResults = await this.search(filename);
    if (searchResults.length > 0) {
      const firstResult = searchResults[0];
      const result = await this.request<ReadResponse>(
        this.getEndpointForNode(firstResult.nodeId)!,
        '/fs/read',
        { path: firstResult.path }
      );
      if (!result.error) {
        return { content: result.content, fromNode: result.hostname };
      }
    }

    throw new Error(`File not found: ${filename}`);
  }

  /**
   * Delete a file from all replicas
   */
  async delete(filename: string): Promise<{ deleted: string[]; failed: string[] }> {
    const deleted: string[] = [];
    const failed: string[] = [];

    const storedFile = this.fileIndex.get(filename);

    if (storedFile) {
      for (const replica of storedFile.replicas) {
        try {
          const path = `${await this.getStoragePath(replica.endpoint)}/${filename}`;
          const result = await this.request<DeleteResponse>(replica.endpoint, '/fs/delete', { path });
          if (result.deleted) {
            deleted.push(replica.hostname);
          } else {
            failed.push(replica.hostname);
          }
        } catch {
          failed.push(replica.hostname);
        }
      }
      this.fileIndex.delete(filename);
    } else {
      // Search and delete from all nodes
      for (const { endpoint, info } of this.nodes) {
        const path = `${info.storagePath}/${filename}`;
        try {
          const result = await this.request<DeleteResponse>(endpoint, '/fs/delete', { path });
          if (result.deleted) {
            deleted.push(info.hostname);
          }
        } catch {
          // Ignore
        }
      }
    }

    return { deleted, failed };
  }

  /**
   * Search for files across all nodes
   */
  async search(pattern: string): Promise<(FileInfo & { nodeId: string; hostname: string; endpoint: string })[]> {
    const allResults: (FileInfo & { nodeId: string; hostname: string; endpoint: string })[] = [];

    for (const { endpoint, info } of this.nodes) {
      try {
        const result = await this.request<SearchResponse>(endpoint, '/fs/search', {
          pattern,
          path: info.storagePath,
          maxResults: 50
        });

        if (!result.error) {
          for (const file of result.results) {
            allResults.push({
              ...file,
              nodeId: result.nodeId,
              hostname: result.hostname,
              endpoint
            });
          }
        }
      } catch {
        // Node not available
      }
    }

    return allResults;
  }

  /**
   * List all files on a specific node
   */
  async listNode(nodeIndex: number): Promise<LsResponse> {
    if (nodeIndex >= this.nodes.length) {
      throw new Error(`Invalid node index: ${nodeIndex}`);
    }
    const { endpoint, info } = this.nodes[nodeIndex];
    return this.request<LsResponse>(endpoint, '/fs/ls', { path: info.storagePath });
  }

  /**
   * List all files across all nodes
   */
  async listAll(): Promise<{ node: string; files: FileInfo[] }[]> {
    const results: { node: string; files: FileInfo[] }[] = [];

    for (const { endpoint, info } of this.nodes) {
      const result = await this.request<LsResponse>(endpoint, '/fs/ls', { path: info.storagePath });
      if (!result.error) {
        results.push({ node: info.hostname, files: result.files });
      }
    }

    return results;
  }

  /**
   * Get file index (tracked files)
   */
  getFileIndex(): Map<string, StoredFile> {
    return this.fileIndex;
  }

  /**
   * Get all nodes
   */
  getNodes(): { endpoint: string; info: NodeInfo }[] {
    return this.nodes;
  }

  private async getStoragePath(endpoint: string): Promise<string> {
    const node = this.nodes.find(n => n.endpoint === endpoint);
    return node?.info.storagePath || '/data';
  }

  private getEndpointForNode(nodeId: string): string | undefined {
    const node = this.nodes.find(n => n.info.nodeId === nodeId);
    return node?.endpoint;
  }
}

// ============================================================================
// Utility functions
// ============================================================================

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

export function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`;
}
