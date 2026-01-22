package kraft.dsl.durable.model

import scala.concurrent.duration.FiniteDuration

/**
 * Core data types for durable execution.
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
  case Awakeable   // Waiting for external completion

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

object JournalEntry:
  def call(seq: Long, name: String, inputJson: String): JournalEntry =
    JournalEntry(seq, JournalEntryType.Call, name, Some(inputJson), None)

  def sideEffect(seq: Long, name: String): JournalEntry =
    JournalEntry(seq, JournalEntryType.SideEffect, name, None, None)

  def sleep(seq: Long, wakeTime: Long): JournalEntry =
    JournalEntry(seq, JournalEntryType.Sleep, s"sleep_until_$wakeTime", Some(wakeTime.toString), None)

  def state(seq: Long, key: String, valueJson: String): JournalEntry =
    JournalEntry(seq, JournalEntryType.State, key, Some(valueJson), None)

// =============================================================================
// Workflow Status
// =============================================================================

/**
 * Workflow execution status.
 */
enum WorkflowStatus:
  case Pending     // Queued, not yet started
  case Running     // Currently executing
  case Suspended   // Waiting for external input (sleep, signal)
  case Completed   // Successfully finished
  case Failed      // Finished with error
  case Cancelled   // Explicitly cancelled

/**
 * Workflow metadata stored alongside status.
 */
case class WorkflowMetadata(
  workflowId: String,
  workflowType: String,
  status: WorkflowStatus,
  ownerId: Option[String],        // Pod/worker currently executing
  inputJson: Option[String],
  outputJson: Option[String],
  errorMessage: Option[String],
  createdAt: Long,
  updatedAt: Long,
  lockedUntil: Option[Long],      // Lock expiry time
  suspendedUntil: Option[Long],   // For sleep operations
  retryCount: Int = 0,
  maxRetries: Int = 3
)

object WorkflowMetadata:
  def create(workflowId: String, workflowType: String, inputJson: String): WorkflowMetadata =
    val now = System.currentTimeMillis()
    WorkflowMetadata(
      workflowId = workflowId,
      workflowType = workflowType,
      status = WorkflowStatus.Pending,
      ownerId = None,
      inputJson = Some(inputJson),
      outputJson = None,
      errorMessage = None,
      createdAt = now,
      updatedAt = now,
      lockedUntil = None,
      suspendedUntil = None
    )

// =============================================================================
// Lock Types
// =============================================================================

/**
 * Information about a distributed lock.
 */
case class LockInfo(
  resourceId: String,
  ownerId: String,
  acquiredAt: Long,
  expiresAt: Long
):
  def isExpired: Boolean = System.currentTimeMillis() > expiresAt
  def remainingMs: Long = math.max(0, expiresAt - System.currentTimeMillis())

/**
 * Result of attempting to acquire a lock.
 */
enum LockResult:
  case Acquired(lock: LockInfo)
  case AlreadyHeld(by: String, expiresAt: Long)
  case Failed(reason: String)

// =============================================================================
// Retry Policy
// =============================================================================

/**
 * Configures retry behavior for durable calls.
 */
case class RetryPolicy(
  maxAttempts: Int = 3,
  initialDelay: FiniteDuration,
  maxDelay: FiniteDuration,
  backoffMultiplier: Double = 2.0,
  retryableExceptions: Set[Class[? <: Throwable]] = Set(classOf[Exception])
):
  def shouldRetry(attempt: Int, error: Throwable): Boolean =
    attempt < maxAttempts && retryableExceptions.exists(_.isAssignableFrom(error.getClass))

  def delayFor(attempt: Int): FiniteDuration =
    import scala.concurrent.duration.*
    val delayMs = initialDelay.toMillis * math.pow(backoffMultiplier, attempt - 1)
    val cappedMs = math.min(delayMs.toLong, maxDelay.toMillis)
    cappedMs.millis

object RetryPolicy:
  import scala.concurrent.duration.*

  val default: RetryPolicy = RetryPolicy(
    maxAttempts = 3,
    initialDelay = 100.millis,
    maxDelay = 30.seconds
  )

  val noRetry: RetryPolicy = RetryPolicy(
    maxAttempts = 1,
    initialDelay = 0.millis,
    maxDelay = 0.millis
  )

  val aggressive: RetryPolicy = RetryPolicy(
    maxAttempts = 10,
    initialDelay = 100.millis,
    maxDelay = 1.minute
  )

// =============================================================================
// Timer Types
// =============================================================================

/**
 * A durable timer that survives process restarts.
 */
case class DurableTimer(
  timerId: String,
  workflowId: String,
  sequenceNumber: Long,
  wakeTime: Long,
  createdAt: Long = System.currentTimeMillis()
):
  def isReady: Boolean = System.currentTimeMillis() >= wakeTime
  def remainingMs: Long = math.max(0, wakeTime - System.currentTimeMillis())

// =============================================================================
// Workflow Result
// =============================================================================

/**
 * Result of a workflow execution.
 */
enum WorkflowResult[+A]:
  case Completed(value: A)
  case Failed(error: Throwable)
  case Running
  case Suspended(until: Option[Long])
  case Cancelled

  def isCompleted: Boolean = this match
    case Completed(_) => true
    case _ => false

  def isFailed: Boolean = this match
    case Failed(_) => true
    case _ => false

  def isTerminal: Boolean = this match
    case Completed(_) | Failed(_) | Cancelled => true
    case _ => false

  def toOption: Option[A] = this match
    case Completed(v) => Some(v)
    case _ => None

  def toEither: Either[Throwable, A] = this match
    case Completed(v) => Right(v)
    case Failed(e) => Left(e)
    case Cancelled => Left(new RuntimeException("Workflow cancelled"))
    case _ => Left(new IllegalStateException("Workflow not completed"))

// =============================================================================
// Exceptions
// =============================================================================

/**
 * Exception indicating workflow should suspend.
 */
case class SuspendException(until: Option[Long]) extends Exception("Workflow suspended")

/**
 * Exception for durable execution errors.
 */
case class DurableException(message: String, cause: Option[Throwable] = None)
  extends Exception(message, cause.orNull)
