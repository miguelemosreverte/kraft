package kraft.dsl.durable.runtime

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.model.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

/**
 * Per-node workflow runtime with embedded storage.
 *
 * This is the correct Restate-style architecture:
 * - Each node has its OWN embedded database (RocksDB, LevelDB, etc.)
 * - No shared central database
 * - Journal and state are local to the node
 *
 * Example:
 * {{{
 * // Create embedded storage for this node
 * val store = RocksDBStore.open("/data/node-1/durable")
 * val storage = NodeStorage.make(store)
 *
 * // Create runtime
 * val runtime = NodeRuntime.make(
 *   storage = storage,
 *   config = NodeConfig.default
 * )
 *
 * // Register functions
 * runtime.register("processPayment") { (req: PaymentRequest) =>
 *   // Process payment
 *   PaymentResult(success = true)
 * }
 *
 * // Execute workflows
 * val handle = runtime.submit(orderWorkflow, OrderInput("order-123"))
 *
 * // Shutdown
 * runtime.shutdown()
 * }}}
 */
object NodeRuntime:

  /**
   * Node configuration.
   */
  case class NodeConfig(
    nodeId: String = s"node-${UUID.randomUUID().toString.take(8)}",
    maxConcurrentWorkflows: Int = 100,
    timerPollInterval: FiniteDuration = 100.millis,
    recoveryPollInterval: FiniteDuration = 1.second
  )

  object NodeConfig:
    val default: NodeConfig = NodeConfig()

  /**
   * Create a node runtime with embedded storage.
   */
  def make(storage: NodeStorage.Storage, config: NodeConfig = NodeConfig.default): Runtime =
    new RuntimeImpl(storage, config)

  /**
   * Node runtime interface.
   */
  trait Runtime:
    def nodeId: String

    def register[Req: JsonValueCodec, Res: JsonValueCodec](name: String)(handler: Req => Res): Runtime

    def submit[In: JsonValueCodec, Out: JsonValueCodec](
      workflow: Workflow[In, Out],
      input: In,
      workflowId: String = UUID.randomUUID().toString
    ): WorkflowHandle[Out]

    def resume[In: JsonValueCodec, Out: JsonValueCodec](
      workflow: Workflow[In, Out],
      workflowId: String
    ): Option[WorkflowHandle[Out]]

    def getStatus(workflowId: String): Option[WorkflowMetadata]

    def cancel(workflowId: String): Boolean

    def shutdown(): Unit

  /**
   * Workflow definition.
   */
  class Workflow[In, Out](
    val name: String,
    val handler: (Context, In) => Out
  )

  object Workflow:
    def apply[In, Out](name: String)(handler: (Context, In) => Out): Workflow[In, Out] =
      new Workflow(name, handler)

  /**
   * Workflow execution context.
   *
   * Provides durable operations that survive node restarts.
   */
  trait Context:
    def workflowId: String

    /**
     * Make a durable function call.
     * Result is journaled - same result on replay.
     */
    def call[Req, Res](
      name: String,
      request: Req,
      retryPolicy: RetryPolicy = RetryPolicy.default
    )(using JsonValueCodec[Req], JsonValueCodec[Res]): Res

    /**
     * Execute a side effect exactly once.
     * Result is journaled - not re-executed on replay.
     */
    def sideEffect[A: JsonValueCodec](name: String)(effect: => A): A

    /**
     * Durable sleep that survives restarts.
     */
    def sleep(duration: FiniteDuration): Unit

    /**
     * Get durable state.
     */
    def getState[A: JsonValueCodec](key: String): Option[A]

    /**
     * Set durable state.
     */
    def setState[A: JsonValueCodec](key: String, value: A): Unit

    /**
     * Clear durable state.
     */
    def clearState(key: String): Unit

    /**
     * Generate deterministic random (same on replay).
     */
    def random(): Double

    /**
     * Generate deterministic UUID (same on replay).
     */
    def uuid(): String

  /**
   * Handle to a workflow execution.
   */
  class WorkflowHandle[Out](
    val workflowId: String,
    private val storage: NodeStorage.Storage,
    private var cachedResult: Option[WorkflowResult[Out]]
  ):
    def result: Option[WorkflowResult[Out]] = cachedResult

    def isCompleted: Boolean = cachedResult.exists(_.isCompleted)

    def isFailed: Boolean = cachedResult.exists(_.isFailed)

    def refresh(): Option[WorkflowMetadata] = storage.workflow.get(workflowId)

  // ===========================================================================
  // Implementation
  // ===========================================================================

  private class RuntimeImpl(
    storage: NodeStorage.Storage,
    config: NodeConfig
  ) extends Runtime:
    private val functions = new FunctionRegistry

    def nodeId: String = config.nodeId

    def register[Req: JsonValueCodec, Res: JsonValueCodec](name: String)(handler: Req => Res): Runtime =
      functions.register(name)(handler)
      this

    def submit[In: JsonValueCodec, Out: JsonValueCodec](
      workflow: Workflow[In, Out],
      input: In,
      workflowId: String
    ): WorkflowHandle[Out] =
      val inputJson = writeToString(input)
      val metadata = WorkflowMetadata.create(workflowId, workflow.name, inputJson)
        .copy(ownerId = Some(config.nodeId))

      // Create workflow record
      if !storage.workflow.create(metadata) then
        throw DurableException(s"Workflow $workflowId already exists")

      // Execute
      executeWorkflow(workflow, workflowId, input)

    def resume[In: JsonValueCodec, Out: JsonValueCodec](
      workflow: Workflow[In, Out],
      workflowId: String
    ): Option[WorkflowHandle[Out]] =
      storage.workflow.get(workflowId).flatMap { metadata =>
        metadata.inputJson.map { json =>
          val input = readFromString[In](json)
          executeWorkflow(workflow, workflowId, input)
        }
      }

    def getStatus(workflowId: String): Option[WorkflowMetadata] =
      storage.workflow.get(workflowId)

    def cancel(workflowId: String): Boolean =
      storage.workflow.get(workflowId) match
        case Some(m) =>
          storage.workflow.update(m.copy(
            status = WorkflowStatus.Cancelled,
            updatedAt = System.currentTimeMillis(),
            errorMessage = Some("Cancelled by user")
          ))
          true
        case None => false

    def shutdown(): Unit =
      storage.close()

    private def executeWorkflow[In: JsonValueCodec, Out: JsonValueCodec](
      workflow: Workflow[In, Out],
      workflowId: String,
      input: In
    ): WorkflowHandle[Out] =
      // Load journal for replay
      val journalEntries = storage.journal.getAll(workflowId)

      // Create context
      val ctx = new ContextImpl(
        workflowId = workflowId,
        storage = storage,
        functions = functions,
        replayEntries = journalEntries
      )

      try
        // Execute
        val result = workflow.handler(ctx, input)
        val outputJson = writeToString(result)

        // Mark completed
        storage.workflow.get(workflowId).foreach { m =>
          storage.workflow.update(m.copy(
            status = WorkflowStatus.Completed,
            outputJson = Some(outputJson),
            updatedAt = System.currentTimeMillis()
          ))
        }

        new WorkflowHandle(workflowId, storage, Some(WorkflowResult.Completed(result)))

      catch
        case e: SuspendException =>
          storage.workflow.get(workflowId).foreach { m =>
            storage.workflow.update(m.copy(
              status = WorkflowStatus.Suspended,
              suspendedUntil = e.until,
              updatedAt = System.currentTimeMillis()
            ))
          }
          new WorkflowHandle(workflowId, storage, Some(WorkflowResult.Suspended(e.until)))

        case e: Exception =>
          storage.workflow.get(workflowId).foreach { m =>
            storage.workflow.update(m.copy(
              status = WorkflowStatus.Failed,
              errorMessage = Some(e.getMessage),
              updatedAt = System.currentTimeMillis()
            ))
          }
          new WorkflowHandle(workflowId, storage, Some(WorkflowResult.Failed(e)))

  private class ContextImpl(
    val workflowId: String,
    storage: NodeStorage.Storage,
    functions: FunctionRegistry,
    replayEntries: Seq[JournalEntry]
  ) extends Context:
    private val replayMap: Map[Long, JournalEntry] = replayEntries.map(e => e.sequenceNumber -> e).toMap
    // Always start from 0 - we replay from the beginning
    private val sequence = new AtomicLong(0)

    private def nextSeq(): Long =
      sequence.getAndIncrement()

    def call[Req, Res](
      name: String,
      request: Req,
      retryPolicy: RetryPolicy
    )(using reqCodec: JsonValueCodec[Req], resCodec: JsonValueCodec[Res]): Res =
      val seqNum = nextSeq()

      replayMap.get(seqNum) match
        case Some(entry) if entry.completed =>
          readFromString[Res](entry.outputJson.get)(using resCodec)

        case _ =>
          val inputJson = writeToString(request)(using reqCodec)
          val entry = JournalEntry.call(seqNum, name, inputJson)
          storage.journal.append(workflowId, entry)

          val result = executeWithRetry(name, request, retryPolicy)(using reqCodec, resCodec)
          val outputJson = writeToString(result)(using resCodec)
          storage.journal.complete(workflowId, seqNum, outputJson)
          result

    private def executeWithRetry[Req, Res](
      name: String,
      request: Req,
      policy: RetryPolicy
    )(using reqCodec: JsonValueCodec[Req], resCodec: JsonValueCodec[Res]): Res =
      var attempt = 1
      var lastError: Throwable = null

      while attempt <= policy.maxAttempts do
        try
          return functions.call[Req, Res](name, request)(using reqCodec, resCodec)
        catch
          case e: Throwable if policy.shouldRetry(attempt, e) =>
            lastError = e
            Thread.sleep(policy.delayFor(attempt).toMillis)
            attempt += 1
          case e: Throwable =>
            throw DurableException(s"Call to $name failed", Some(e))

      throw DurableException(s"Call to $name failed after ${policy.maxAttempts} attempts", Option(lastError))

    def sideEffect[A: JsonValueCodec](name: String)(effect: => A): A =
      val seqNum = nextSeq()

      replayMap.get(seqNum) match
        case Some(entry) if entry.completed =>
          readFromString[A](entry.outputJson.get)

        case _ =>
          val entry = JournalEntry.sideEffect(seqNum, name)
          storage.journal.append(workflowId, entry)

          val result = effect
          val outputJson = writeToString(result)
          storage.journal.complete(workflowId, seqNum, outputJson)
          result

    def sleep(duration: FiniteDuration): Unit =
      val seqNum = nextSeq()
      val wakeTime = System.currentTimeMillis() + duration.toMillis

      replayMap.get(seqNum) match
        case Some(entry) if entry.completed =>
          () // Already completed

        case Some(entry) =>
          val storedWakeTime = entry.inputJson.map(_.toLong).getOrElse(wakeTime)
          val remaining = storedWakeTime - System.currentTimeMillis()
          if remaining > 0 then
            throw SuspendException(Some(storedWakeTime))
          else
            storage.journal.complete(workflowId, seqNum, "null")

        case None =>
          val timerId = s"$workflowId-$seqNum"
          val timer = DurableTimer(timerId, workflowId, seqNum, wakeTime)
          storage.timer.schedule(timer)

          val entry = JournalEntry.sleep(seqNum, wakeTime)
          storage.journal.append(workflowId, entry)

          throw SuspendException(Some(wakeTime))

    def getState[A: JsonValueCodec](key: String): Option[A] =
      storage.state.get[A](workflowId, key)

    def setState[A: JsonValueCodec](key: String, value: A): Unit =
      val seqNum = nextSeq()

      replayMap.get(seqNum) match
        case Some(entry) if entry.completed =>
          () // Already done
        case _ =>
          val entry = JournalEntry.state(seqNum, key, writeToString(value))
          storage.journal.append(workflowId, entry)
          storage.state.set(workflowId, key, value)
          storage.journal.complete(workflowId, seqNum, "null")

    def clearState(key: String): Unit =
      storage.state.delete(workflowId, key)

    def random(): Double =
      given JsonValueCodec[Double] = JsonCodecMaker.make
      sideEffect("random")(scala.util.Random.nextDouble())

    def uuid(): String =
      given JsonValueCodec[String] = JsonCodecMaker.make
      sideEffect("uuid")(UUID.randomUUID().toString)

  /**
   * Registry for callable functions.
   */
  private class FunctionRegistry:
    private val funcs = new java.util.concurrent.ConcurrentHashMap[String, (String) => String]()

    def register[Req: JsonValueCodec, Res: JsonValueCodec](name: String)(handler: Req => Res): Unit =
      funcs.put(name, (inputJson: String) => {
        val request = readFromString[Req](inputJson)
        val response = handler(request)
        writeToString(response)
      })

    def call[Req: JsonValueCodec, Res: JsonValueCodec](name: String, request: Req): Res =
      val handler = funcs.get(name)
      if handler == null then
        throw DurableException(s"Unknown function: $name")
      val inputJson = writeToString(request)
      val outputJson = handler(inputJson)
      readFromString[Res](outputJson)
