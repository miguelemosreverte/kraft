package kraft.dsl.durable.storage

import java.util.concurrent.ConcurrentSkipListMap
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/**
 * In-memory implementation of EmbeddedStore.
 *
 * Uses ConcurrentSkipListMap for ordered key iteration (needed for prefix scans).
 * This is for testing only - data is lost on process restart.
 *
 * Example:
 * {{{
 * val store = InMemoryStore.open()
 * store.put("key".getBytes, "value".getBytes)
 * store.get("key".getBytes) // Some(value)
 * }}}
 */
object InMemoryStore:

  /**
   * Create a new in-memory store.
   */
  def open(): EmbeddedStore = new Impl()

  private class Impl extends EmbeddedStore:
    private val data = new ConcurrentSkipListMap[ByteArrayKey, Array[Byte]]()

    def get(key: Array[Byte]): Option[Array[Byte]] =
      Option(data.get(ByteArrayKey(key))).map(_.clone())

    def put(key: Array[Byte], value: Array[Byte]): Unit =
      data.put(ByteArrayKey(key), value.clone())

    def delete(key: Array[Byte]): Unit =
      data.remove(ByteArrayKey(key))

    def scan(prefix: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      val start = ByteArrayKey(prefix)
      val end = ByteArrayKey(KeyEncoder.prefixEnd(prefix))
      data.subMap(start, true, end, false)
        .entrySet()
        .iterator()
        .asScala
        .map(e => (e.getKey.bytes.clone(), e.getValue.clone()))

    def scanRange(start: Array[Byte], end: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
      data.subMap(ByteArrayKey(start), true, ByteArrayKey(end), false)
        .entrySet()
        .iterator()
        .asScala
        .map(e => (e.getKey.bytes.clone(), e.getValue.clone()))

    def batch(operations: Seq[BatchOp]): Unit =
      // Not truly atomic for in-memory, but sufficient for testing
      operations.foreach:
        case BatchOp.Put(k, v) => put(k, v)
        case BatchOp.Delete(k) => delete(k)

    def close(): Unit =
      data.clear()

  /**
   * Wrapper for byte arrays to use as map keys with proper comparison.
   */
  private case class ByteArrayKey(bytes: Array[Byte]) extends Comparable[ByteArrayKey]:
    override def compareTo(other: ByteArrayKey): Int =
      compareBytes(bytes, other.bytes)

    override def equals(obj: Any): Boolean = obj match
      case other: ByteArrayKey => java.util.Arrays.equals(bytes, other.bytes)
      case _ => false

    override def hashCode(): Int = java.util.Arrays.hashCode(bytes)

  private def compareBytes(a: Array[Byte], b: Array[Byte]): Int =
    val minLen = math.min(a.length, b.length)
    var i = 0
    while i < minLen do
      val cmp = (a(i) & 0xFF) - (b(i) & 0xFF)
      if cmp != 0 then return cmp
      i += 1
    a.length - b.length
