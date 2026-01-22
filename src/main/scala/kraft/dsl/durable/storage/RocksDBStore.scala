package kraft.dsl.durable.storage

import org.rocksdb.{RocksDB, Options, WriteOptions, WriteBatch, ReadOptions}
import scala.util.Using

/**
 * RocksDB implementation of EmbeddedStore.
 *
 * This is the recommended production storage for per-node durable execution.
 * RocksDB is a high-performance embedded key-value store developed by Facebook.
 *
 * Features:
 * - Persistent storage (survives process restarts)
 * - High write throughput with LSM tree
 * - Efficient prefix scans
 * - Atomic batch writes
 *
 * Example:
 * {{{
 * // Each node opens its own database
 * val store = RocksDBStore.open("/data/node-1/durable")
 *
 * // Use for all durable operations
 * val runtime = NodeRuntime.make(store, config)
 *
 * // Clean shutdown
 * store.close()
 * }}}
 */
object RocksDBStore:

  // Load native library once
  RocksDB.loadLibrary()

  /**
   * Configuration for RocksDB.
   */
  case class Config(
    createIfMissing: Boolean = true,
    maxOpenFiles: Int = -1,
    writeBufferSize: Long = 64 * 1024 * 1024, // 64MB
    maxWriteBufferNumber: Int = 3,
    targetFileSizeBase: Long = 64 * 1024 * 1024 // 64MB
  )

  object Config:
    val default: Config = Config()

  /**
   * Open a RocksDB store at the given path.
   */
  def open(path: String, config: Config = Config.default): EmbeddedStore =
    val options = new Options()
    options.setCreateIfMissing(config.createIfMissing)
    options.setMaxOpenFiles(config.maxOpenFiles)
    options.setWriteBufferSize(config.writeBufferSize)
    options.setMaxWriteBufferNumber(config.maxWriteBufferNumber)
    options.setTargetFileSizeBase(config.targetFileSizeBase)

    val db = RocksDB.open(options, path)
    new Impl(db, options)

  private class Impl(db: RocksDB, options: Options) extends EmbeddedStore:
    private val writeOptions = new WriteOptions()

    def get(key: Array[Byte]): Option[Array[Byte]] =
      Option(db.get(key))

    def put(key: Array[Byte], value: Array[Byte]): Unit =
      db.put(writeOptions, key, value)

    def delete(key: Array[Byte]): Unit =
      db.delete(writeOptions, key)

    def scan(prefix: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      new PrefixIterator(db, prefix)

    def scanRange(start: Array[Byte], end: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      new RangeIterator(db, start, end)

    def batch(operations: Seq[BatchOp]): Unit =
      val batch = new WriteBatch()
      try
        operations.foreach:
          case BatchOp.Put(k, v) => batch.put(k, v)
          case BatchOp.Delete(k) => batch.delete(k)
        db.write(writeOptions, batch)
      finally
        batch.close()

    def close(): Unit =
      writeOptions.close()
      db.close()
      options.close()

  /**
   * Iterator for prefix scans.
   */
  private class PrefixIterator(db: RocksDB, prefix: Array[Byte]) extends Iterator[(Array[Byte], Array[Byte])]:
    private val iter = db.newIterator()
    iter.seek(prefix)

    private var nextItem: Option[(Array[Byte], Array[Byte])] = advance()

    private def advance(): Option[(Array[Byte], Array[Byte])] =
      if iter.isValid then
        val key = iter.key()
        if startsWith(key, prefix) then
          val value = iter.value()
          iter.next()
          Some((key, value))
        else
          iter.close()
          None
      else
        iter.close()
        None

    def hasNext: Boolean = nextItem.isDefined

    def next(): (Array[Byte], Array[Byte]) =
      val result = nextItem.getOrElse(throw new NoSuchElementException)
      nextItem = advance()
      result

    private def startsWith(key: Array[Byte], prefix: Array[Byte]): Boolean =
      if key.length < prefix.length then false
      else
        var i = 0
        while i < prefix.length do
          if key(i) != prefix(i) then return false
          i += 1
        true

  /**
   * Iterator for range scans.
   */
  private class RangeIterator(db: RocksDB, start: Array[Byte], end: Array[Byte]) extends Iterator[(Array[Byte], Array[Byte])]:
    private val iter = db.newIterator()
    iter.seek(start)

    private var nextItem: Option[(Array[Byte], Array[Byte])] = advance()

    private def advance(): Option[(Array[Byte], Array[Byte])] =
      if iter.isValid then
        val key = iter.key()
        if compareBytes(key, end) < 0 then
          val value = iter.value()
          iter.next()
          Some((key, value))
        else
          iter.close()
          None
      else
        iter.close()
        None

    def hasNext: Boolean = nextItem.isDefined

    def next(): (Array[Byte], Array[Byte]) =
      val result = nextItem.getOrElse(throw new NoSuchElementException)
      nextItem = advance()
      result

    private def compareBytes(a: Array[Byte], b: Array[Byte]): Int =
      val minLen = math.min(a.length, b.length)
      var i = 0
      while i < minLen do
        val cmp = (a(i) & 0xFF) - (b(i) & 0xFF)
        if cmp != 0 then return cmp
        i += 1
      a.length - b.length
