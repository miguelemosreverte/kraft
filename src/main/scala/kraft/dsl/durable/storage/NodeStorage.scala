package kraft.dsl.durable.storage

import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * High-level storage interface for a single node.
 *
 * This wraps an EmbeddedStore (RocksDB, LevelDB, etc.) and provides
 * typed operations for journals, state, workflows, and timers.
 *
 * Each node has exactly one NodeStorage instance.
 *
 * Example:
 * {{{
 * val embeddedStore = RocksDBStore.open("/data/node-1")
 * val storage = NodeStorage.make(embeddedStore)
 *
 * // Journal operations
 * storage.journal.append(workflowId, entry)
 * storage.journal.getAll(workflowId)
 *
 * // State operations
 * storage.state.set(workflowId, "key", "value")
 * storage.state.get[String](workflowId, "key")
 *
 * // Workflow operations
 * storage.workflow.create(metadata)
 * storage.workflow.get(workflowId)
 * }}}
 */
object NodeStorage:

  /**
   * Create a NodeStorage from an EmbeddedStore.
   */
  def make(store: EmbeddedStore): Storage =
    new Storage:
      val journal: JournalOps = new JournalOpsImpl(store)
      val state: StateOps = new StateOpsImpl(store)
      val workflow: WorkflowOps = new WorkflowOpsImpl(store)
      val timer: TimerOps = new TimerOpsImpl(store)

      def close(): Unit = store.close()

  /**
   * Storage interface.
   */
  trait Storage:
    def journal: JournalOps
    def state: StateOps
    def workflow: WorkflowOps
    def timer: TimerOps
    def close(): Unit

  // ===========================================================================
  // Journal Operations
  // ===========================================================================

  trait JournalOps:
    def append(workflowId: String, entry: JournalEntry): Unit
    def complete(workflowId: String, sequenceNumber: Long, outputJson: String): Unit
    def getAll(workflowId: String): Seq[JournalEntry]
    def delete(workflowId: String): Unit

  private given journalCodec: JsonValueCodec[JournalEntry] = JsonCodecMaker.make

  private class JournalOpsImpl(store: EmbeddedStore) extends JournalOps:
    def append(workflowId: String, entry: JournalEntry): Unit =
      val key = KeyEncoder.journalKey(workflowId, entry.sequenceNumber)
      val value = writeToArray(entry)
      store.put(key, value)

    def complete(workflowId: String, sequenceNumber: Long, outputJson: String): Unit =
      val key = KeyEncoder.journalKey(workflowId, sequenceNumber)
      store.get(key).foreach { existing =>
        val entry = readFromArray[JournalEntry](existing)
        val updated = entry.copy(completed = true, outputJson = Some(outputJson))
        store.put(key, writeToArray(updated))
      }

    def getAll(workflowId: String): Seq[JournalEntry] =
      val prefix = KeyEncoder.journalPrefix(workflowId)
      store.scan(prefix).map { case (_, value) =>
        readFromArray[JournalEntry](value)
      }.toSeq

    def delete(workflowId: String): Unit =
      val prefix = KeyEncoder.journalPrefix(workflowId)
      val keys = store.scan(prefix).map(_._1).toSeq
      store.batch(keys.map(BatchOp.Delete(_)))

  // ===========================================================================
  // State Operations
  // ===========================================================================

  trait StateOps:
    def get[A: JsonValueCodec](workflowId: String, key: String): Option[A]
    def set[A: JsonValueCodec](workflowId: String, key: String, value: A): Unit
    def delete(workflowId: String, key: String): Unit
    def deleteAll(workflowId: String): Unit

  private class StateOpsImpl(store: EmbeddedStore) extends StateOps:
    def get[A: JsonValueCodec](workflowId: String, key: String): Option[A] =
      val k = KeyEncoder.stateKey(workflowId, key)
      store.get(k).map(readFromArray[A](_))

    def set[A: JsonValueCodec](workflowId: String, key: String, value: A): Unit =
      val k = KeyEncoder.stateKey(workflowId, key)
      store.put(k, writeToArray(value))

    def delete(workflowId: String, key: String): Unit =
      val k = KeyEncoder.stateKey(workflowId, key)
      store.delete(k)

    def deleteAll(workflowId: String): Unit =
      val prefix = KeyEncoder.statePrefix(workflowId)
      val keys = store.scan(prefix).map(_._1).toSeq
      store.batch(keys.map(BatchOp.Delete(_)))

  // ===========================================================================
  // Workflow Operations
  // ===========================================================================

  trait WorkflowOps:
    def create(metadata: WorkflowMetadata): Boolean
    def get(workflowId: String): Option[WorkflowMetadata]
    def update(metadata: WorkflowMetadata): Unit
    def delete(workflowId: String): Unit
    def findByStatus(status: WorkflowStatus, limit: Int): Seq[WorkflowMetadata]
    def findReady(limit: Int): Seq[WorkflowMetadata]
    def findSuspendedReady(now: Long, limit: Int): Seq[WorkflowMetadata]
    def all(limit: Int): Seq[WorkflowMetadata]

  private given metadataCodec: JsonValueCodec[WorkflowMetadata] = JsonCodecMaker.make

  private class WorkflowOpsImpl(store: EmbeddedStore) extends WorkflowOps:
    def create(metadata: WorkflowMetadata): Boolean =
      val key = KeyEncoder.workflowKey(metadata.workflowId)
      if store.exists(key) then false
      else
        val ops = Seq(
          BatchOp.Put(key, writeToArray(metadata)),
          BatchOp.Put(KeyEncoder.statusIndexKey(metadata.status.toString, metadata.workflowId), Array.empty)
        )
        store.batch(ops)
        true

    def get(workflowId: String): Option[WorkflowMetadata] =
      val key = KeyEncoder.workflowKey(workflowId)
      store.get(key).map(readFromArray[WorkflowMetadata](_))

    def update(metadata: WorkflowMetadata): Unit =
      val key = KeyEncoder.workflowKey(metadata.workflowId)
      // Get old status for index update
      val oldStatus = store.get(key).map(readFromArray[WorkflowMetadata](_).status.toString)
      val ops = scala.collection.mutable.ArrayBuffer[BatchOp]()

      // Remove old status index if changed
      oldStatus.filter(_ != metadata.status.toString).foreach { old =>
        ops += BatchOp.Delete(KeyEncoder.statusIndexKey(old, metadata.workflowId))
      }

      // Update workflow and status index
      ops += BatchOp.Put(key, writeToArray(metadata))
      ops += BatchOp.Put(KeyEncoder.statusIndexKey(metadata.status.toString, metadata.workflowId), Array.empty)

      store.batch(ops.toSeq)

    def delete(workflowId: String): Unit =
      get(workflowId).foreach { metadata =>
        store.batch(Seq(
          BatchOp.Delete(KeyEncoder.workflowKey(workflowId)),
          BatchOp.Delete(KeyEncoder.statusIndexKey(metadata.status.toString, workflowId))
        ))
      }

    def findByStatus(status: WorkflowStatus, limit: Int): Seq[WorkflowMetadata] =
      val prefix = KeyEncoder.statusIndexPrefix(status.toString)
      store.scan(prefix)
        .take(limit)
        .flatMap { case (key, _) =>
          // Extract workflowId from index key
          val keyStr = new String(key, "UTF-8")
          val workflowId = keyStr.split(':').lastOption
          workflowId.flatMap(get)
        }
        .toSeq

    def findReady(limit: Int): Seq[WorkflowMetadata] =
      findByStatus(WorkflowStatus.Pending, limit)

    def findSuspendedReady(now: Long, limit: Int): Seq[WorkflowMetadata] =
      findByStatus(WorkflowStatus.Suspended, limit * 2) // Over-fetch to filter
        .filter { m =>
          m.suspendedUntil.forall(_ <= now)
        }
        .take(limit)

    def all(limit: Int): Seq[WorkflowMetadata] =
      val prefix = KeyEncoder.workflowPrefix()
      store.scan(prefix)
        .take(limit)
        .map { case (_, value) => readFromArray[WorkflowMetadata](value) }
        .toSeq

  // ===========================================================================
  // Timer Operations
  // ===========================================================================

  trait TimerOps:
    def schedule(timer: DurableTimer): Unit
    def findReady(now: Long, limit: Int): Seq[DurableTimer]
    def delete(timerId: String, wakeTime: Long): Unit
    def deleteForWorkflow(workflowId: String): Unit

  private given timerCodec: JsonValueCodec[DurableTimer] = JsonCodecMaker.make

  private class TimerOpsImpl(store: EmbeddedStore) extends TimerOps:
    def schedule(timer: DurableTimer): Unit =
      val key = KeyEncoder.timerKey(timer.wakeTime, timer.timerId)
      store.put(key, writeToArray(timer))

    def findReady(now: Long, limit: Int): Seq[DurableTimer] =
      // Scan from beginning of timers to now
      val prefix = KeyEncoder.timerPrefix()
      val end = KeyEncoder.timerKey(now + 1, "") // Exclusive end
      store.scanRange(prefix, end)
        .take(limit)
        .map { case (_, value) => readFromArray[DurableTimer](value) }
        .toSeq

    def delete(timerId: String, wakeTime: Long): Unit =
      val key = KeyEncoder.timerKey(wakeTime, timerId)
      store.delete(key)

    def deleteForWorkflow(workflowId: String): Unit =
      // Scan all timers and delete those matching workflowId
      // This is less efficient but timers have fireTime-first keys
      val prefix = KeyEncoder.timerPrefix()
      val toDelete = store.scan(prefix)
        .filter { case (_, value) =>
          readFromArray[DurableTimer](value).workflowId == workflowId
        }
        .map(_._1)
        .toSeq
      store.batch(toDelete.map(BatchOp.Delete(_)))
