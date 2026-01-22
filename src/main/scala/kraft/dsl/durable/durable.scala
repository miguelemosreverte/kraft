package kraft.dsl.durable

import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.concurrent.{ConcurrentHashMap, ScheduledExecutorService, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.UUID

/**
 * Durable Execution DSL - Restate-style durable workflows and function calls.
 *
 * This DSL provides reliable execution of distributed workflows with:
 * - Automatic journaling of all operations
 * - Exactly-once execution semantics
 * - Automatic retry with backoff
 * - Durable timers and sleep
 * - Side effect handling
 * - Saga pattern support for compensations
 *
 * Example workflow:
 * {{{
 * import kraft.dsl.durable.*
 *
 * val orderWorkflow = Workflow("ProcessOrder") { ctx =>
 *   // These calls are durable - they survive failures
 *   val order = ctx.call("ValidateOrder", orderId)
 *   val payment = ctx.call("ProcessPayment", order.amount)
 *
 *   // Durable sleep
 *   ctx.sleep(1.hour)
 *
 *   // Side effect (idempotent)
 *   ctx.sideEffect("SendEmail") {
 *     emailService.sendConfirmation(order.email)
 *   }
 *
 *   OrderResult(order.id, payment.transactionId)
 * }
 * }}}
 */

// =============================================================================
// Journal Entry Types
// =============================================================================

/**
 * Types of entries in the execution journal.
 */
enum JournalEntryType:
  case Call        // Remote service call
  case SideEffect  // Local side effect
  case Sleep       // Durable timer
  case Signal      // External signal received
  case State       // State update

/**
 * A journal entry recording a durable operation.
 */
case class JournalEntry(
  sequenceNumber: Long,
  entryType: JournalEntryType,
  name: String,
  inputJson: Option[String],
  outputJson: Option[String],
  timestamp: Long = System.currentTimeMillis(),
  completed: Boolean = false
)

/**
 * The execution journal for a workflow instance.
 */
class Journal:
  private val entries = new ConcurrentHashMap[Long, JournalEntry]()
  private val sequence = new AtomicLong(0)

  def nextSequence(): Long = sequence.getAndIncrement()
  def currentSequence(): Long = sequence.get()

  def append(entry: JournalEntry): Unit =
    entries.put(entry.sequenceNumber, entry)

  def get(sequenceNumber: Long): Option[JournalEntry] =
    Option(entries.get(sequenceNumber))

  def complete(sequenceNumber: Long, outputJson: String): Unit =
    Option(entries.get(sequenceNumber)).foreach { entry =>
      entries.put(sequenceNumber, entry.copy(outputJson = Some(outputJson), completed = true))
    }

  def allEntries: Seq[JournalEntry] =
    import scala.jdk.CollectionConverters.*
    entries.values().asScala.toSeq.sortBy(_.sequenceNumber)

// =============================================================================
// Retry Policy
// =============================================================================

/**
 * Configures retry behavior for durable calls.
 */
case class RetryPolicy(
  maxAttempts: Int = 3,
  initialDelay: FiniteDuration = 100.millis,
  maxDelay: FiniteDuration = 30.seconds,
  backoffMultiplier: Double = 2.0,
  retryableExceptions: Set[Class[? <: Throwable]] = Set(classOf[Exception])
):
  def shouldRetry(attempt: Int, error: Throwable): Boolean =
    attempt < maxAttempts && retryableExceptions.exists(_.isAssignableFrom(error.getClass))

  def delayFor(attempt: Int): FiniteDuration =
    val delay = initialDelay * math.pow(backoffMultiplier, attempt - 1)
    if delay > maxDelay then maxDelay else FiniteDuration(delay.toMillis, MILLISECONDS)

object RetryPolicy:
  val default: RetryPolicy = RetryPolicy()
  val noRetry: RetryPolicy = RetryPolicy(maxAttempts = 1)
  val aggressive: RetryPolicy = RetryPolicy(maxAttempts = 10, maxDelay = 1.minute)

// =============================================================================
// Workflow Context
// =============================================================================

/**
 * Execution context for durable workflows.
 * All operations through this context are journaled and recoverable.
 */
class WorkflowContext private[durable] (
  val workflowId: String,
  val instanceId: String,
  private val journal: Journal,
  private val stateStore: StateStore,
  private val callHandler: DurableCallHandler
):
  private val replayMode = new AtomicReference[Boolean](false)
  private val replayIndex = new AtomicLong(0)

  /**
   * Make a durable call to another service/function.
   * The result is journaled and replayed on recovery.
   */
  def call[Req, Res](
    name: String,
    request: Req,
    retryPolicy: RetryPolicy = RetryPolicy.default
  )(using reqCodec: JsonValueCodec[Req], resCodec: JsonValueCodec[Res]): Res =
    val seqNum = journal.nextSequence()

    // Check if we're replaying from journal
    journal.get(seqNum) match
      case Some(entry) if entry.completed =>
        // Replay: return cached result
        readFromString[Res](entry.outputJson.get)(using resCodec)
      case _ =>
        // Execute: make the call and journal it
        val inputJson = writeToString(request)(using reqCodec)
        journal.append(JournalEntry(
          sequenceNumber = seqNum,
          entryType = JournalEntryType.Call,
          name = name,
          inputJson = Some(inputJson),
          outputJson = None
        ))

        val result = executeWithRetry(name, request, retryPolicy)(using reqCodec, resCodec)
        val outputJson = writeToString(result)(using resCodec)
        journal.complete(seqNum, outputJson)
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
        return callHandler.handle[Req, Res](name, request)(using reqCodec, resCodec)
      catch
        case e: Throwable if policy.shouldRetry(attempt, e) =>
          lastError = e
          val delay = policy.delayFor(attempt)
          Thread.sleep(delay.toMillis)
          attempt += 1
        case e: Throwable =>
          throw DurableException(s"Call to $name failed", Some(e))

    throw DurableException(s"Call to $name failed after ${policy.maxAttempts} attempts", Option(lastError))

  /**
   * Execute a side effect with exactly-once semantics.
   * The side effect is only executed once, even on replay.
   */
  def sideEffect[A: JsonValueCodec](name: String)(effect: => A): A =
    val seqNum = journal.nextSequence()

    journal.get(seqNum) match
      case Some(entry) if entry.completed =>
        // Replay: return cached result
        readFromString[A](entry.outputJson.get)
      case _ =>
        // Execute: run side effect and journal
        journal.append(JournalEntry(
          sequenceNumber = seqNum,
          entryType = JournalEntryType.SideEffect,
          name = name,
          inputJson = None,
          outputJson = None
        ))

        val result = effect
        val outputJson = writeToString(result)
        journal.complete(seqNum, outputJson)
        result

  /**
   * Durable sleep that survives process restarts.
   */
  def sleep(duration: FiniteDuration): Unit =
    val seqNum = journal.nextSequence()
    val wakeTime = System.currentTimeMillis() + duration.toMillis

    journal.get(seqNum) match
      case Some(entry) if entry.completed =>
        // Already completed, no need to sleep
        ()
      case Some(entry) =>
        // Resuming: check if we still need to sleep
        val remaining = wakeTime - System.currentTimeMillis()
        if remaining > 0 then Thread.sleep(remaining)
        journal.complete(seqNum, "null")
      case None =>
        // New sleep
        journal.append(JournalEntry(
          sequenceNumber = seqNum,
          entryType = JournalEntryType.Sleep,
          name = s"sleep_${duration.toMillis}ms",
          inputJson = Some(wakeTime.toString),
          outputJson = None
        ))
        Thread.sleep(duration.toMillis)
        journal.complete(seqNum, "null")

  /**
   * Get durable state associated with a key.
   */
  def getState[A: JsonValueCodec](key: String): Option[A] =
    stateStore.get[A](instanceId, key)

  /**
   * Set durable state for a key.
   */
  def setState[A: JsonValueCodec](key: String, value: A): Unit =
    val seqNum = journal.nextSequence()
    journal.append(JournalEntry(
      sequenceNumber = seqNum,
      entryType = JournalEntryType.State,
      name = key,
      inputJson = Some(writeToString(value)),
      outputJson = None
    ))
    stateStore.set(instanceId, key, value)
    journal.complete(seqNum, "null")

  /**
   * Clear durable state for a key.
   */
  def clearState(key: String): Unit =
    stateStore.clear(instanceId, key)

  /**
   * Generate a deterministic random value (same on replay).
   */
  def random(): Double =
    given JsonValueCodec[Double] = JsonCodecMaker.make
    sideEffect("random")(scala.util.Random.nextDouble())

  /**
   * Generate a deterministic UUID (same on replay).
   */
  def uuid(): String =
    given JsonValueCodec[String] = JsonCodecMaker.make
    sideEffect("uuid")(UUID.randomUUID().toString)

// =============================================================================
// State Store
// =============================================================================

/**
 * Storage for durable workflow state.
 */
trait StateStore:
  def get[A: JsonValueCodec](instanceId: String, key: String): Option[A]
  def set[A: JsonValueCodec](instanceId: String, key: String, value: A): Unit
  def clear(instanceId: String, key: String): Unit
  def clearAll(instanceId: String): Unit

/**
 * In-memory state store for testing.
 */
class InMemoryStateStore extends StateStore:
  private val store = new ConcurrentHashMap[String, String]()

  private def storeKey(instanceId: String, key: String): String = s"$instanceId:$key"

  def get[A: JsonValueCodec](instanceId: String, key: String): Option[A] =
    Option(store.get(storeKey(instanceId, key))).map(readFromString[A](_))

  def set[A: JsonValueCodec](instanceId: String, key: String, value: A): Unit =
    store.put(storeKey(instanceId, key), writeToString(value))

  def clear(instanceId: String, key: String): Unit =
    store.remove(storeKey(instanceId, key))

  def clearAll(instanceId: String): Unit =
    import scala.jdk.CollectionConverters.*
    store.keys().asScala.filter(_.startsWith(s"$instanceId:")).foreach(store.remove)

// =============================================================================
// Durable Call Handler
// =============================================================================

/**
 * Handles calls to external services/functions.
 */
trait DurableCallHandler:
  def handle[Req: JsonValueCodec, Res: JsonValueCodec](name: String, request: Req): Res

/**
 * A call handler that delegates to registered functions.
 */
class FunctionRegistry extends DurableCallHandler:
  private val functions = new ConcurrentHashMap[String, (String, DurableCallHandler) => String]()

  def register[Req: JsonValueCodec, Res: JsonValueCodec](
    name: String
  )(handler: Req => Res): FunctionRegistry =
    functions.put(name, (inputJson, _) => {
      val request = readFromString[Req](inputJson)
      val response = handler(request)
      writeToString(response)
    })
    this

  def handle[Req: JsonValueCodec, Res: JsonValueCodec](name: String, request: Req): Res =
    val handler = functions.get(name)
    if handler == null then
      throw DurableException(s"Unknown function: $name")
    val inputJson = writeToString(request)
    val outputJson = handler(inputJson, this)
    readFromString[Res](outputJson)

// =============================================================================
// Workflow Definition
// =============================================================================

/**
 * A durable workflow definition.
 */
class WorkflowDef[In: JsonValueCodec, Out: JsonValueCodec](
  val name: String,
  val handler: (WorkflowContext, In) => Out
)

/**
 * Builder for workflow definitions.
 */
object Workflow:
  /**
   * Define a new workflow.
   */
  def apply[In: JsonValueCodec, Out: JsonValueCodec](name: String)(
    handler: (WorkflowContext, In) => Out
  ): WorkflowDef[In, Out] =
    new WorkflowDef(name, handler)

  /**
   * Define a workflow with no input.
   */
  def unit[Out: JsonValueCodec](name: String)(
    handler: WorkflowContext => Out
  ): WorkflowDef[Unit, Out] =
    given JsonValueCodec[Unit] = new JsonValueCodec[Unit]:
      def decodeValue(in: JsonReader, default: Unit): Unit =
        in.readRawValAsBytes()
        ()
      def encodeValue(x: Unit, out: JsonWriter): Unit = out.writeNull()
      def nullValue: Unit = ()
    new WorkflowDef(name, (ctx, _) => handler(ctx))

// =============================================================================
// Workflow Runtime
// =============================================================================

/**
 * Runtime for executing durable workflows.
 */
class WorkflowRuntime(
  private val stateStore: StateStore = new InMemoryStateStore(),
  private val callHandler: DurableCallHandler = new FunctionRegistry()
):
  private val journals = new ConcurrentHashMap[String, Journal]()
  private val instances = new ConcurrentHashMap[String, Any]()

  /**
   * Start a new workflow instance.
   */
  def start[In: JsonValueCodec, Out: JsonValueCodec](
    workflow: WorkflowDef[In, Out],
    input: In,
    instanceId: String = UUID.randomUUID().toString
  ): WorkflowHandle[Out] =
    val journal = new Journal()
    journals.put(instanceId, journal)

    val ctx = new WorkflowContext(
      workflowId = workflow.name,
      instanceId = instanceId,
      journal = journal,
      stateStore = stateStore,
      callHandler = callHandler
    )

    try
      val result = workflow.handler(ctx, input)
      instances.put(instanceId, WorkflowResult.Completed(result))
      new WorkflowHandle(instanceId, this)
    catch
      case e: Throwable =>
        instances.put(instanceId, WorkflowResult.Failed(e))
        new WorkflowHandle(instanceId, this)

  /**
   * Get the result of a workflow instance.
   */
  def getResult[Out](instanceId: String): Option[WorkflowResult[Out]] =
    Option(instances.get(instanceId)).map(_.asInstanceOf[WorkflowResult[Out]])

  /**
   * Get the journal for a workflow instance.
   */
  def getJournal(instanceId: String): Option[Journal] =
    Option(journals.get(instanceId))

  /**
   * Resume a workflow from its journal.
   */
  def resume[In: JsonValueCodec, Out: JsonValueCodec](
    workflow: WorkflowDef[In, Out],
    input: In,
    instanceId: String
  ): WorkflowHandle[Out] =
    val journal = journals.getOrDefault(instanceId, new Journal())
    journals.put(instanceId, journal)

    val ctx = new WorkflowContext(
      workflowId = workflow.name,
      instanceId = instanceId,
      journal = journal,
      stateStore = stateStore,
      callHandler = callHandler
    )

    try
      val result = workflow.handler(ctx, input)
      instances.put(instanceId, WorkflowResult.Completed(result))
      new WorkflowHandle(instanceId, this)
    catch
      case e: Throwable =>
        instances.put(instanceId, WorkflowResult.Failed(e))
        new WorkflowHandle(instanceId, this)

  /**
   * With a custom call handler.
   */
  def withCallHandler(handler: DurableCallHandler): WorkflowRuntime =
    new WorkflowRuntime(stateStore, handler)

  /**
   * With a custom state store.
   */
  def withStateStore(store: StateStore): WorkflowRuntime =
    new WorkflowRuntime(store, callHandler)

/**
 * Result of a workflow execution.
 */
enum WorkflowResult[+A]:
  case Completed(value: A)
  case Failed(error: Throwable)
  case Running
  case Suspended

  def isCompleted: Boolean = this match
    case Completed(_) => true
    case _ => false

  def isFailed: Boolean = this match
    case Failed(_) => true
    case _ => false

  def toOption: Option[A] = this match
    case Completed(v) => Some(v)
    case _ => None

  def toEither: Either[Throwable, A] = this match
    case Completed(v) => Right(v)
    case Failed(e) => Left(e)
    case _ => Left(new IllegalStateException("Workflow not completed"))

/**
 * Handle to a running or completed workflow instance.
 */
class WorkflowHandle[Out] private[durable] (
  val instanceId: String,
  private val runtime: WorkflowRuntime
):
  /**
   * Get the current result (if completed).
   */
  def result: Option[WorkflowResult[Out]] =
    runtime.getResult[Out](instanceId)

  /**
   * Get the execution journal.
   */
  def journal: Option[Journal] =
    runtime.getJournal(instanceId)

  /**
   * Check if workflow is completed.
   */
  def isCompleted: Boolean =
    result.exists(_.isCompleted)

  /**
   * Check if workflow failed.
   */
  def isFailed: Boolean =
    result.exists(_.isFailed)

// =============================================================================
// Saga Support
// =============================================================================

/**
 * A compensating action for saga rollback.
 */
case class Compensation(name: String, action: () => Unit)

/**
 * Context for saga-based workflows with compensation.
 */
class SagaContext private[durable] (
  private val underlying: WorkflowContext
):
  private val compensations = new java.util.Stack[Compensation]()

  /**
   * Execute an action with a compensating rollback action.
   */
  def step[A: JsonValueCodec](
    name: String,
    action: => A,
    compensate: A => Unit
  ): A =
    val result = underlying.sideEffect(name)(action)
    compensations.push(Compensation(s"compensate_$name", () => compensate(result)))
    result

  /**
   * Execute all compensations in reverse order.
   */
  def rollback(): Unit =
    while !compensations.isEmpty do
      val comp = compensations.pop()
      try comp.action()
      catch case _: Throwable => () // Log but continue

  /**
   * Access the underlying workflow context.
   */
  def ctx: WorkflowContext = underlying

/**
 * DSL for saga-based workflows.
 */
object Saga:
  def apply[Out: JsonValueCodec](name: String)(
    handler: SagaContext => Out
  ): WorkflowDef[Unit, Out] =
    given JsonValueCodec[Unit] = new JsonValueCodec[Unit]:
      def decodeValue(in: JsonReader, default: Unit): Unit =
        in.readRawValAsBytes()
        ()
      def encodeValue(x: Unit, out: JsonWriter): Unit = out.writeNull()
      def nullValue: Unit = ()

    new WorkflowDef(name, (ctx, _) => {
      val sagaCtx = new SagaContext(ctx)
      try
        handler(sagaCtx)
      catch
        case e: Throwable =>
          sagaCtx.rollback()
          throw e
    })

// =============================================================================
// Durable Exception
// =============================================================================

/**
 * Exception for durable execution failures.
 */
case class DurableException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull)

// =============================================================================
// Convenience Extensions
// =============================================================================

extension (ctx: WorkflowContext)
  /**
   * Run a block with retry policy.
   */
  def withRetry[A: JsonValueCodec](name: String, policy: RetryPolicy)(block: => A): A =
    var attempt = 1
    var lastError: Throwable = null

    while attempt <= policy.maxAttempts do
      try
        return ctx.sideEffect(s"${name}_attempt_$attempt")(block)
      catch
        case e: Throwable if policy.shouldRetry(attempt, e) =>
          lastError = e
          ctx.sleep(policy.delayFor(attempt))
          attempt += 1
        case e: Throwable =>
          throw DurableException(s"$name failed", Some(e))

    throw DurableException(s"$name failed after ${policy.maxAttempts} attempts", Option(lastError))
