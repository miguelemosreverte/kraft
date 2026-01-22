package kraft.dsl.durable.runtime

import kraft.dsl.durable.storage.*
import kraft.dsl.durable.model.*
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.compiletime.uninitialized

/**
 * Background services for per-node durable execution.
 *
 * These services run on each node:
 * - TimerProcessor: Wakes suspended workflows when timers fire
 * - RecoveryProcessor: Resumes workflows after node restart
 *
 * Example:
 * {{{
 * val storage = NodeStorage.make(RocksDBStore.open("/data/node-1"))
 * val services = NodeServices.make(storage, resumeCallback, config)
 *
 * services.start()
 * // ... run workflows ...
 * services.stop()
 * }}}
 */
object NodeServices:

  /**
   * Configuration for node services.
   */
  case class Config(
    timerPollInterval: FiniteDuration = 100.millis,
    recoveryPollInterval: FiniteDuration = 1.second,
    maxBatchSize: Int = 100
  )

  object Config:
    val default: Config = Config()

  /**
   * Callback to resume a workflow.
   */
  type ResumeCallback = String => Unit

  /**
   * Create node services.
   */
  def make(
    storage: NodeStorage.Storage,
    onResume: ResumeCallback,
    config: Config = Config.default
  ): Services =
    new ServicesImpl(storage, onResume, config)

  /**
   * Node services interface.
   */
  trait Services:
    def start(): Unit
    def stop(): Unit
    def isRunning: Boolean

  private class ServicesImpl(
    storage: NodeStorage.Storage,
    onResume: ResumeCallback,
    config: Config
  ) extends Services:
    private val running = new AtomicBoolean(false)
    private var executor: ScheduledExecutorService = uninitialized

    def start(): Unit =
      if !running.compareAndSet(false, true) then return

      executor = Executors.newScheduledThreadPool(2)

      // Timer processor
      executor.scheduleAtFixedRate(
        () => processTimers(),
        config.timerPollInterval.toMillis,
        config.timerPollInterval.toMillis,
        TimeUnit.MILLISECONDS
      )

      // Recovery processor (less frequent)
      executor.scheduleAtFixedRate(
        () => processSuspended(),
        config.recoveryPollInterval.toMillis,
        config.recoveryPollInterval.toMillis,
        TimeUnit.MILLISECONDS
      )

    def stop(): Unit =
      if !running.compareAndSet(true, false) then return

      if executor != null then
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        executor = null

    def isRunning: Boolean = running.get()

    private def processTimers(): Unit =
      if !running.get() then return

      try
        val now = System.currentTimeMillis()
        val readyTimers = storage.timer.findReady(now, config.maxBatchSize)

        readyTimers.foreach { timer =>
          // Mark timer as fired by deleting it
          storage.timer.delete(timer.timerId, timer.wakeTime)

          // Resume the workflow
          try
            onResume(timer.workflowId)
          catch
            case e: Exception =>
              System.err.println(s"Failed to resume workflow ${timer.workflowId}: ${e.getMessage}")
        }
      catch
        case e: Exception =>
          System.err.println(s"Timer processing error: ${e.getMessage}")

    private def processSuspended(): Unit =
      if !running.get() then return

      try
        val now = System.currentTimeMillis()
        val suspended = storage.workflow.findSuspendedReady(now, config.maxBatchSize)

        suspended.foreach { metadata =>
          // Only resume if it's actually ready
          if metadata.suspendedUntil.forall(_ <= now) then
            try
              onResume(metadata.workflowId)
            catch
              case e: Exception =>
                System.err.println(s"Failed to resume suspended workflow ${metadata.workflowId}: ${e.getMessage}")
        }
      catch
        case e: Exception =>
          System.err.println(s"Recovery processing error: ${e.getMessage}")
