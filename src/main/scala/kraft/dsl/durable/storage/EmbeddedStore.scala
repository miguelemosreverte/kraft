package kraft.dsl.durable.storage

/**
 * Core abstraction for embedded key-value storage.
 *
 * Each worker node has its OWN instance of this store - no shared database.
 * This is the foundation for Restate-style per-node durable execution.
 *
 * Implementations:
 * - RocksDB (recommended for production)
 * - LevelDB (simpler alternative)
 * - InMemory (testing only)
 *
 * Example:
 * {{{
 * // Each node creates its own store
 * val store = RocksDBStore.open("/data/node-1")
 *
 * // All durable operations use this local store
 * val runtime = NodeRuntime.make(store, config)
 * }}}
 */
trait EmbeddedStore:
  /**
   * Get a value by key.
   */
  def get(key: Array[Byte]): Option[Array[Byte]]

  /**
   * Put a key-value pair.
   */
  def put(key: Array[Byte], value: Array[Byte]): Unit

  /**
   * Delete a key.
   */
  def delete(key: Array[Byte]): Unit

  /**
   * Check if key exists.
   */
  def exists(key: Array[Byte]): Boolean = get(key).isDefined

  /**
   * Scan keys with a given prefix.
   */
  def scan(prefix: Array[Byte]): Iterator[(Array[Byte], Array[Byte])]

  /**
   * Scan keys in a range [start, end).
   */
  def scanRange(start: Array[Byte], end: Array[Byte]): Iterator[(Array[Byte], Array[Byte])]

  /**
   * Atomic batch write.
   */
  def batch(operations: Seq[BatchOp]): Unit

  /**
   * Close the store and release resources.
   */
  def close(): Unit

/**
 * Batch operations for atomic writes.
 */
enum BatchOp:
  case Put(key: Array[Byte], value: Array[Byte])
  case Delete(key: Array[Byte])

/**
 * Key encoding utilities for the embedded store.
 *
 * Keys are structured as: prefix:partition:subkey
 * This allows efficient prefix scans for a workflow's data.
 */
object KeyEncoder:
  private val Separator: Byte = ':'.toByte

  /**
   * Encode a journal entry key.
   * Format: j:{workflowId}:{sequenceNumber:016d}
   */
  def journalKey(workflowId: String, sequenceNumber: Long): Array[Byte] =
    s"j:$workflowId:${"%016d".format(sequenceNumber)}".getBytes("UTF-8")

  /**
   * Encode prefix for all journal entries of a workflow.
   */
  def journalPrefix(workflowId: String): Array[Byte] =
    s"j:$workflowId:".getBytes("UTF-8")

  /**
   * Encode a state key.
   * Format: s:{workflowId}:{key}
   */
  def stateKey(workflowId: String, key: String): Array[Byte] =
    s"s:$workflowId:$key".getBytes("UTF-8")

  /**
   * Encode prefix for all state of a workflow.
   */
  def statePrefix(workflowId: String): Array[Byte] =
    s"s:$workflowId:".getBytes("UTF-8")

  /**
   * Encode a workflow metadata key.
   * Format: w:{workflowId}
   */
  def workflowKey(workflowId: String): Array[Byte] =
    s"w:$workflowId".getBytes("UTF-8")

  /**
   * Prefix for all workflows.
   */
  def workflowPrefix(): Array[Byte] =
    "w:".getBytes("UTF-8")

  /**
   * Encode a timer key.
   * Format: t:{fireTime:016d}:{timerId}
   * Using fireTime first allows efficient "find ready timers" scan.
   */
  def timerKey(fireTime: Long, timerId: String): Array[Byte] =
    s"t:${"%016d".format(fireTime)}:$timerId".getBytes("UTF-8")

  /**
   * Prefix for timers up to a certain time.
   */
  def timerPrefix(): Array[Byte] =
    "t:".getBytes("UTF-8")

  /**
   * Encode an index key for workflow status.
   * Format: i:status:{status}:{workflowId}
   */
  def statusIndexKey(status: String, workflowId: String): Array[Byte] =
    s"i:status:$status:$workflowId".getBytes("UTF-8")

  /**
   * Prefix for workflows by status.
   */
  def statusIndexPrefix(status: String): Array[Byte] =
    s"i:status:$status:".getBytes("UTF-8")

  /**
   * Compute the "next" prefix for range scans.
   * Increments the last byte to get exclusive end bound.
   */
  def prefixEnd(prefix: Array[Byte]): Array[Byte] =
    val result = prefix.clone()
    var i = result.length - 1
    while i >= 0 && result(i) == 0xFF.toByte do
      result(i) = 0
      i -= 1
    if i >= 0 then result(i) = (result(i) + 1).toByte
    result
