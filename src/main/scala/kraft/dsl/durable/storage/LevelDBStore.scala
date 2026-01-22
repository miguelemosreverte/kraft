package kraft.dsl.durable.storage

import org.iq80.leveldb.{DB, DBFactory, Options, WriteBatch, DBIterator}
import org.iq80.leveldb.impl.Iq80DBFactory
import java.io.File
import scala.jdk.CollectionConverters.*

/**
 * LevelDB implementation of EmbeddedStore.
 *
 * LevelDB is a fast key-value storage library by Google.
 * This is a pure Java implementation (iq80) - no native dependencies.
 *
 * Compared to RocksDB:
 * - Simpler, fewer configuration options
 * - Pure Java (easier deployment)
 * - Lower throughput than RocksDB
 * - Good for moderate workloads
 *
 * Example:
 * {{{
 * // Each node opens its own database
 * val store = LevelDBStore.open("/data/node-1/durable")
 *
 * // Use for all durable operations
 * val runtime = NodeRuntime.make(store, config)
 *
 * // Clean shutdown
 * store.close()
 * }}}
 */
object LevelDBStore:

  private val factory: DBFactory = Iq80DBFactory.factory

  /**
   * Configuration for LevelDB.
   */
  case class Config(
    createIfMissing: Boolean = true,
    cacheSize: Long = 64 * 1024 * 1024, // 64MB
    writeBufferSize: Int = 4 * 1024 * 1024, // 4MB
    blockSize: Int = 4096,
    compressionType: Boolean = true // Snappy compression
  )

  object Config:
    val default: Config = Config()

  /**
   * Open a LevelDB store at the given path.
   */
  def open(path: String, config: Config = Config.default): EmbeddedStore =
    val options = new Options()
    options.createIfMissing(config.createIfMissing)
    options.cacheSize(config.cacheSize)
    options.writeBufferSize(config.writeBufferSize)
    options.blockSize(config.blockSize)
    if !config.compressionType then
      options.compressionType(org.iq80.leveldb.CompressionType.NONE)

    val db = factory.open(new File(path), options)
    new Impl(db)

  private class Impl(db: DB) extends EmbeddedStore:

    def get(key: Array[Byte]): Option[Array[Byte]] =
      Option(db.get(key))

    def put(key: Array[Byte], value: Array[Byte]): Unit =
      db.put(key, value)

    def delete(key: Array[Byte]): Unit =
      db.delete(key)

    def scan(prefix: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      new PrefixIterator(db, prefix)

    def scanRange(start: Array[Byte], end: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      new RangeIterator(db, start, end)

    def batch(operations: Seq[BatchOp]): Unit =
      val batch = db.createWriteBatch()
      try
        operations.foreach:
          case BatchOp.Put(k, v) => batch.put(k, v)
          case BatchOp.Delete(k) => batch.delete(k)
        db.write(batch)
      finally
        batch.close()

    def close(): Unit =
      db.close()

  /**
   * Iterator for prefix scans.
   */
  private class PrefixIterator(db: DB, prefix: Array[Byte]) extends Iterator[(Array[Byte], Array[Byte])]:
    private val iter: DBIterator = db.iterator()
    iter.seek(prefix)

    private var nextItem: Option[(Array[Byte], Array[Byte])] = advance()

    private def advance(): Option[(Array[Byte], Array[Byte])] =
      if iter.hasNext then
        val entry = iter.peekNext()
        val key = entry.getKey
        if startsWith(key, prefix) then
          iter.next()
          Some((key, entry.getValue))
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
  private class RangeIterator(db: DB, start: Array[Byte], end: Array[Byte]) extends Iterator[(Array[Byte], Array[Byte])]:
    private val iter: DBIterator = db.iterator()
    iter.seek(start)

    private var nextItem: Option[(Array[Byte], Array[Byte])] = advance()

    private def advance(): Option[(Array[Byte], Array[Byte])] =
      if iter.hasNext then
        val entry = iter.peekNext()
        val key = entry.getKey
        if compareBytes(key, end) < 0 then
          iter.next()
          Some((key, entry.getValue))
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
