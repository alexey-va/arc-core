package ru.arc.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Task DSL - A Kotlin DSL for scheduling Bukkit tasks.
 *
 * Features:
 * - Fluent API with Duration support
 * - Cancellable tasks
 * - Task context with utilities
 * - Testable via TaskScheduler abstraction
 *
 * Usage:
 * ```kotlin
 * // Simple delayed task
 * delayed(20.ticks) {
 *     player.sendMessage("Hello!")
 * }
 *
 * // Repeating task with cancellation
 * val task = repeating(60.ticks, delay = 20.ticks) {
 *     if (shouldStop()) cancel()
 *     updateDisplay()
 * }
 *
 * // Async operations
 * async {
 *     val data = loadFromDatabase()
 *     sync { player.sendMessage("Loaded: $data") }
 * }
 *
 * // With custom scheduler (for testing)
 * val scheduler = TestTaskScheduler()
 * scheduler.delayed(20.ticks) { ... }
 * scheduler.tick(20) // execute
 * ```
 */

// ==================== Duration Extensions ====================

/**
 * Convert Int to ticks duration.
 */
val Int.ticks: Duration get() = (this * 50L).milliseconds

/**
 * Convert Long to ticks duration.
 */
val Long.ticks: Duration get() = (this * 50L).milliseconds

/**
 * Convert Duration to Bukkit ticks.
 */
val Duration.inWholeTicks: Long get() = this.inWholeMilliseconds / 50L

// ==================== Task Context ====================

/**
 * Context available inside task lambdas.
 * Provides utilities for task management.
 */
class TaskContext internal constructor(
    private val task: ScheduledTask,
    private val scheduler: TaskScheduler,
) {
    /**
     * Cancel the current task.
     */
    fun cancel() {
        task.cancel()
    }

    /**
     * Check if task is cancelled.
     */
    val isCancelled: Boolean get() = task.isCancelled

    /**
     * Task ID.
     */
    val taskId: Int get() = task.id

    /**
     * Schedule a sync task from within async context.
     */
    fun sync(block: () -> Unit): ScheduledTask = scheduler.runSync(Runnable { block() })

    /**
     * Schedule delayed task from context.
     */
    fun delayed(
        delay: Duration,
        block: TaskContext.() -> Unit,
    ): ScheduledTask = scheduler.delayed(delay, block)

    /**
     * Run async from context.
     */
    fun async(block: TaskContext.() -> Unit): ScheduledTask = scheduler.async(block)
}

// ==================== Cancellable Task ====================

/**
 * A task handle that can be cancelled and checked.
 */
interface CancellableTask {
    val id: Int
    val isCancelled: Boolean

    fun cancel()
}

private class CancellableTaskImpl(
    private val task: ScheduledTask,
) : CancellableTask {
    override val id: Int get() = task.id
    override val isCancelled: Boolean get() = task.isCancelled

    override fun cancel() = task.cancel()
}

// ==================== Task DSL Functions ====================

/**
 * Run a task synchronously on the main thread.
 */
fun TaskScheduler.sync(block: TaskContext.() -> Unit): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        runSync(
            Runnable {
                TaskContext(scheduledTask, this).block()
            },
        )
    return scheduledTask
}

/**
 * Run a task asynchronously.
 */
fun TaskScheduler.async(block: TaskContext.() -> Unit): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        runAsync(
            Runnable {
                TaskContext(scheduledTask, this).block()
            },
        )
    return scheduledTask
}

/**
 * Run a task after a delay (sync).
 */
fun TaskScheduler.delayed(
    delay: Duration,
    block: TaskContext.() -> Unit,
): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        scheduleDelayed(
            this,
            delay,
            async = false,
            task =
                Runnable {
                    TaskContext(scheduledTask, this).block()
                },
        )
    return scheduledTask
}

/**
 * Run a task after a delay (async).
 */
fun TaskScheduler.delayedAsync(
    delay: Duration,
    block: TaskContext.() -> Unit,
): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        scheduleDelayed(
            this,
            delay,
            async = true,
            task =
                Runnable {
                    TaskContext(scheduledTask, this).block()
                },
        )
    return scheduledTask
}

/**
 * Run a repeating task (sync).
 * @param period Time between executions
 * @param delay Initial delay before first execution (default: same as period)
 */
fun TaskScheduler.repeating(
    period: Duration,
    delay: Duration = period,
    block: TaskContext.() -> Unit,
): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        runTimer(
            delay.inWholeTicks,
            period.inWholeTicks,
            Runnable {
                TaskContext(scheduledTask, this).block()
            },
        )
    return scheduledTask
}

/**
 * Run a repeating task (async).
 */
fun TaskScheduler.repeatingAsync(
    period: Duration,
    delay: Duration = period,
    block: TaskContext.() -> Unit,
): ScheduledTask {
    lateinit var scheduledTask: ScheduledTask
    scheduledTask =
        runTimerAsync(
            delay.inWholeTicks,
            period.inWholeTicks,
            Runnable {
                TaskContext(scheduledTask, this).block()
            },
        )
    return scheduledTask
}

/**
 * Run a task a limited number of times.
 * @param times Number of times to run
 * @param period Time between executions
 * @param delay Initial delay
 */
fun TaskScheduler.repeat(
    times: Int,
    period: Duration,
    delay: Duration = period,
    block: TaskContext.(iteration: Int) -> Unit,
): ScheduledTask {
    var iteration = 0
    return repeating(period, delay) {
        block(iteration++)
        if (iteration >= times) cancel()
    }
}

/**
 * Run a task while a condition is true.
 * @param period Time between checks/executions
 * @param delay Initial delay
 * @param condition Condition to check
 */
fun TaskScheduler.repeatWhile(
    period: Duration,
    delay: Duration = period,
    condition: () -> Boolean,
    block: TaskContext.() -> Unit,
): ScheduledTask {
    return repeating(period, delay) {
        if (!condition()) {
            cancel()
            return@repeating
        }
        block()
    }
}

/**
 * Run a task until a condition becomes true.
 */
fun TaskScheduler.repeatUntil(
    period: Duration,
    delay: Duration = period,
    condition: () -> Boolean,
    block: TaskContext.() -> Unit,
): ScheduledTask = repeatWhile(period, delay, { !condition() }, block)

// ==================== Global Functions (use default scheduler) ====================

// Global convenience functions
fun sync(block: TaskContext.() -> Unit) = Tasks.scheduler.sync(block)

fun async(block: TaskContext.() -> Unit) = Tasks.scheduler.async(block)

fun delayed(
    delay: Duration,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.delayed(delay, block)

fun delayedAsync(
    delay: Duration,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.delayedAsync(delay, block)

fun repeating(
    period: Duration,
    delay: Duration = period,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.repeating(period, delay, block)

fun repeatingAsync(
    period: Duration,
    delay: Duration = period,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.repeatingAsync(period, delay, block)

fun repeat(
    times: Int,
    period: Duration,
    delay: Duration = period,
    block: TaskContext.(Int) -> Unit,
) = Tasks.scheduler.repeat(times, period, delay, block)

fun repeatWhile(
    period: Duration,
    delay: Duration = period,
    condition: () -> Boolean,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.repeatWhile(period, delay, condition, block)

fun repeatUntil(
    period: Duration,
    delay: Duration = period,
    condition: () -> Boolean,
    block: TaskContext.() -> Unit,
) = Tasks.scheduler.repeatUntil(period, delay, condition, block)

// ==================== Task Builder DSL ====================

/**
 * Builder for complex task configurations.
 */
class TaskBuilder internal constructor(
    private val scheduler: TaskScheduler,
) {
    private var delay: Duration = 0.ticks
    private var period: Duration? = null
    private var isAsync: Boolean = false
    private var times: Int? = null
    private var condition: (() -> Boolean)? = null
    private var onCancel: (() -> Unit)? = null
    private var onComplete: (() -> Unit)? = null

    /**
     * Set initial delay.
     */
    fun delay(delay: Duration) = apply { this.delay = delay }

    /**
     * Make task repeating with period.
     */
    fun every(period: Duration) = apply { this.period = period }

    /**
     * Run asynchronously.
     */
    fun async() = apply { this.isAsync = true }

    /**
     * Limit number of executions.
     */
    fun times(count: Int) = apply { this.times = count }

    /**
     * Run while condition is true.
     */
    fun whileTrue(condition: () -> Boolean) = apply { this.condition = condition }

    /**
     * Run until condition becomes true.
     */
    fun untilTrue(condition: () -> Boolean) = apply { this.condition = { !condition() } }

    /**
     * Callback when task is cancelled.
     */
    fun onCancel(block: () -> Unit) = apply { this.onCancel = block }

    /**
     * Callback when task completes (for limited tasks).
     */
    fun onComplete(block: () -> Unit) = apply { this.onComplete = block }

    /**
     * Execute the task.
     */
    fun run(block: TaskContext.() -> Unit): ScheduledTask {
        val periodValue = period
        val timesValue = times
        val conditionValue = condition

        // Simple delayed task
        if (periodValue == null) {
            return if (isAsync) {
                scheduler.delayedAsync(delay, block)
            } else {
                scheduler.delayed(delay, block)
            }
        }

        // Repeating task with limits
        var iteration = 0
        val wrappedBlock: TaskContext.() -> Unit = wrapper@{
            // Check condition
            if (conditionValue != null && !conditionValue()) {
                cancel()
                onComplete?.invoke()
                return@wrapper
            }

            // Check times limit
            if (timesValue != null && iteration >= timesValue) {
                cancel()
                onComplete?.invoke()
                return@wrapper
            }

            // Execute
            block()
            iteration++

            // Check if completed after execution
            if (timesValue != null && iteration >= timesValue) {
                cancel()
                onComplete?.invoke()
            }
        }

        val task =
            if (isAsync) {
                scheduler.repeatingAsync(periodValue, delay, wrappedBlock)
            } else {
                scheduler.repeating(periodValue, delay, wrappedBlock)
            }

        // Track cancellation
        if (onCancel != null) {
            // Wrap to call onCancel when cancelled externally
            return object : ScheduledTask by task {
                override fun cancel() {
                    task.cancel()
                    onCancel?.invoke()
                }
            }
        }

        return task
    }
}

/**
 * Create a task with builder DSL.
 */
fun task(
    scheduler: TaskScheduler = Tasks.scheduler,
    configure: TaskBuilder.() -> Unit,
): TaskBuilder = TaskBuilder(scheduler).apply(configure)

/**
 * Create and immediately run a task.
 */
fun runTask(
    scheduler: TaskScheduler = Tasks.scheduler,
    configure: TaskBuilder.() -> Unit,
    block: TaskContext.() -> Unit,
): ScheduledTask = TaskBuilder(scheduler).apply(configure).run(block)

// ==================== Advanced Task Features ====================

/**
 * Task group - manage multiple related tasks together.
 */
class TaskGroup(
    private val scheduler: TaskScheduler = Tasks.scheduler,
) {
    private val tasks = mutableListOf<ScheduledTask>()

    /**
     * Add a task to the group.
     */
    fun add(task: ScheduledTask): ScheduledTask {
        tasks.add(task)
        return task
    }

    /**
     * Schedule a sync task in this group.
     */
    fun sync(block: TaskContext.() -> Unit) = add(scheduler.sync(block))

    /**
     * Schedule an async task in this group.
     */
    fun async(block: TaskContext.() -> Unit) = add(scheduler.async(block))

    /**
     * Schedule a delayed task in this group.
     */
    fun delayed(
        delay: Duration,
        block: TaskContext.() -> Unit,
    ) = add(scheduler.delayed(delay, block))

    /**
     * Schedule a repeating task in this group.
     */
    fun repeating(
        period: Duration,
        delay: Duration = period,
        block: TaskContext.() -> Unit,
    ) = add(scheduler.repeating(period, delay, block))

    /**
     * Cancel all tasks in the group.
     */
    fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    /**
     * Number of active (non-cancelled) tasks.
     */
    fun activeCount(): Int = tasks.count { !it.isCancelled }

    /**
     * Check if all tasks are completed or cancelled.
     */
    fun isComplete(): Boolean = tasks.all { it.isCancelled }
}

/**
 * Create a task group for managing related tasks.
 */
fun taskGroup(scheduler: TaskScheduler = Tasks.scheduler): TaskGroup = TaskGroup(scheduler)

// ==================== Countdown ====================

/**
 * Run a countdown task.
 * @param from Starting number
 * @param to Ending number (inclusive, default 0)
 * @param period Time between counts
 * @param onTick Called for each tick with remaining count
 * @param onComplete Called when countdown reaches end
 */
fun TaskScheduler.countdown(
    from: Int,
    to: Int = 0,
    period: Duration = 1.seconds,
    onTick: (remaining: Int) -> Unit,
    onComplete: () -> Unit = {},
): ScheduledTask {
    var current = from
    val step = if (from > to) -1 else 1

    return repeating(period, delay = 0.ticks) {
        onTick(current)
        current += step

        if ((step > 0 && current > to) || (step < 0 && current < to)) {
            cancel()
            onComplete()
        }
    }
}

fun countdown(
    from: Int,
    to: Int = 0,
    period: Duration = 1.seconds,
    onTick: (remaining: Int) -> Unit,
    onComplete: () -> Unit = {},
) = Tasks.scheduler.countdown(from, to, period, onTick, onComplete)

// ==================== Animation ====================

/**
 * Run an animation with frame callback.
 * @param frames Total number of frames
 * @param period Time between frames
 * @param onFrame Called for each frame with (frameIndex, progress 0.0-1.0)
 * @param onComplete Called when animation ends
 */
fun TaskScheduler.animate(
    frames: Int,
    period: Duration = 1.ticks,
    onFrame: (frame: Int, progress: Float) -> Unit,
    onComplete: () -> Unit = {},
): ScheduledTask {
    var frame = 0

    return repeating(period, delay = 0.ticks) {
        val progress = frame.toFloat() / (frames - 1).coerceAtLeast(1)
        onFrame(frame, progress)
        frame++

        if (frame >= frames) {
            cancel()
            onComplete()
        }
    }
}

fun animate(
    frames: Int,
    period: Duration = 1.ticks,
    onFrame: (frame: Int, progress: Float) -> Unit,
    onComplete: () -> Unit = {},
) = Tasks.scheduler.animate(frames, period, onFrame, onComplete)

// ==================== Debounce & Throttle ====================

/**
 * Debounced action - only executes after no calls for [delay] duration.
 * Useful for search inputs, resize handlers, etc.
 */
class Debouncer(
    private val scheduler: TaskScheduler,
    private val delay: Duration,
) {
    private var pendingTask: ScheduledTask? = null

    /**
     * Schedule the action. Previous pending action is cancelled.
     */
    fun call(action: () -> Unit) {
        pendingTask?.cancel()
        pendingTask = scheduler.delayed(delay) { action() }
    }

    /**
     * Cancel any pending action.
     */
    fun cancel() {
        pendingTask?.cancel()
        pendingTask = null
    }
}

/**
 * Create a debouncer.
 */
fun debouncer(
    delay: Duration,
    scheduler: TaskScheduler = Tasks.scheduler,
) = Debouncer(scheduler, delay)

/**
 * Throttled action - executes at most once per [period].
 */
class Throttler(
    private val scheduler: TaskScheduler,
    private val period: Duration,
) {
    private var lastExecution: Long = 0
    private var pendingTask: ScheduledTask? = null

    /**
     * Try to execute the action. If within cooldown, queues for later.
     * @param queue If true, queues action for after cooldown. If false, drops it.
     */
    fun call(
        queue: Boolean = true,
        action: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastExecution

        if (elapsed >= period.inWholeMilliseconds) {
            // Can execute immediately
            lastExecution = now
            action()
        } else if (queue && pendingTask?.isCancelled != false) {
            // Queue for later
            val remaining = period.inWholeMilliseconds - elapsed
            pendingTask =
                scheduler.delayed((remaining / 50).ticks) {
                    lastExecution = System.currentTimeMillis()
                    action()
                }
        }
        // else: within cooldown and not queuing - drop the call
    }

    /**
     * Cancel any pending action.
     */
    fun cancel() {
        pendingTask?.cancel()
        pendingTask = null
    }

    /**
     * Reset cooldown, allowing immediate execution.
     */
    fun reset() {
        lastExecution = 0
        cancel()
    }
}

/**
 * Create a throttler.
 */
fun throttler(
    period: Duration,
    scheduler: TaskScheduler = Tasks.scheduler,
) = Throttler(scheduler, period)

// ==================== Task Chain ====================

/**
 * A chain of sequential tasks.
 */
class TaskChain(
    private val scheduler: TaskScheduler,
) {
    private val steps = mutableListOf<ChainStep>()
    private var onError: ((Throwable) -> Unit)? = null
    private var onComplete: (() -> Unit)? = null

    private sealed class ChainStep {
        data class Sync(
            val block: () -> Unit,
        ) : ChainStep()

        data class Async(
            val block: () -> Unit,
        ) : ChainStep()

        data class Delay(
            val duration: Duration,
        ) : ChainStep()

        data class Conditional(
            val condition: () -> Boolean,
            val ifTrue: TaskChain?,
            val ifFalse: TaskChain?,
        ) : ChainStep()
    }

    /**
     * Add a sync step.
     */
    fun sync(block: () -> Unit) = apply { steps.add(ChainStep.Sync(block)) }

    /**
     * Add an async step.
     */
    fun async(block: () -> Unit) = apply { steps.add(ChainStep.Async(block)) }

    /**
     * Add a delay step.
     */
    fun delay(duration: Duration) = apply { steps.add(ChainStep.Delay(duration)) }

    /**
     * Add a conditional branch.
     */
    fun branch(
        condition: () -> Boolean,
        ifTrue: (TaskChain.() -> Unit)? = null,
        ifFalse: (TaskChain.() -> Unit)? = null,
    ) = apply {
        val trueChain = ifTrue?.let { TaskChain(scheduler).apply(it) }
        val falseChain = ifFalse?.let { TaskChain(scheduler).apply(it) }
        steps.add(ChainStep.Conditional(condition, trueChain, falseChain))
    }

    /**
     * Set error handler.
     */
    fun onError(handler: (Throwable) -> Unit) = apply { onError = handler }

    /**
     * Set completion handler.
     */
    fun onComplete(handler: () -> Unit) = apply { onComplete = handler }

    /**
     * Execute the chain.
     */
    fun execute(): ScheduledTask = executeSteps(steps.toList(), 0)

    private fun executeSteps(
        stepList: List<ChainStep>,
        index: Int,
    ): ScheduledTask {
        if (index >= stepList.size) {
            onComplete?.invoke()
            return object : ScheduledTask {
                override val id = -1
                override val isCancelled = true

                override fun cancel() {}
            }
        }

        return when (val step = stepList[index]) {
            is ChainStep.Sync -> {
                scheduler.sync {
                    try {
                        step.block()
                        executeSteps(stepList, index + 1)
                    } catch (e: Throwable) {
                        onError?.invoke(e)
                    }
                }
            }

            is ChainStep.Async -> {
                scheduler.async {
                    try {
                        step.block()
                        sync { executeSteps(stepList, index + 1) }
                    } catch (e: Throwable) {
                        sync { onError?.invoke(e) }
                    }
                }
            }

            is ChainStep.Delay -> {
                scheduler.delayed(step.duration) {
                    executeSteps(stepList, index + 1)
                }
            }

            is ChainStep.Conditional -> {
                val branch = if (step.condition()) step.ifTrue else step.ifFalse
                if (branch != null) {
                    branch.onComplete { executeSteps(stepList, index + 1) }
                    branch.execute()
                } else {
                    executeSteps(stepList, index + 1)
                }
            }
        }
    }
}

/**
 * Create a task chain.
 *
 * Example:
 * ```kotlin
 * chain {
 *     sync { player.sendMessage("Step 1") }
 *     delay(1.seconds)
 *     async { loadData() }
 *     sync { player.sendMessage("Step 2") }
 *     branch(
 *         condition = { hasData },
 *         ifTrue = {
 *             sync { processData() }
 *         },
 *         ifFalse = {
 *             sync { showError() }
 *         }
 *     )
 *     onComplete { player.sendMessage("Done!") }
 * }.execute()
 * ```
 */
fun chain(
    scheduler: TaskScheduler = Tasks.scheduler,
    configure: TaskChain.() -> Unit,
): TaskChain = TaskChain(scheduler).apply(configure)

// ==================== Retry with Backoff ====================

/**
 * Retry strategy.
 */
sealed class RetryStrategy {
    /**
     * Fixed delay between retries.
     */
    data class Fixed(
        val delay: Duration,
    ) : RetryStrategy()

    /**
     * Exponential backoff.
     */
    data class Exponential(
        val initialDelay: Duration,
        val multiplier: Double = 2.0,
        val maxDelay: Duration = 60.seconds,
    ) : RetryStrategy()

    /**
     * Linear backoff.
     */
    data class Linear(
        val initialDelay: Duration,
        val increment: Duration,
    ) : RetryStrategy()

    fun getDelay(attempt: Int): Duration =
        when (this) {
            is Fixed -> {
                delay
            }

            is Exponential -> {
                val computed = initialDelay.inWholeMilliseconds * Math.pow(multiplier, attempt.toDouble())
                minOf(computed.toLong(), maxDelay.inWholeMilliseconds).milliseconds
            }

            is Linear -> {
                initialDelay + (increment.inWholeMilliseconds * attempt).milliseconds
            }
        }
}

/**
 * Result of a retry operation.
 */
sealed class RetryResult<T> {
    data class Success<T>(
        val value: T,
        val attempts: Int,
    ) : RetryResult<T>()

    data class Failure<T>(
        val lastError: Throwable,
        val attempts: Int,
    ) : RetryResult<T>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun getOrThrow(): T =
        when (this) {
            is Success -> value
            is Failure -> throw lastError
        }
}

/**
 * Retry an operation with backoff.
 *
 * Example:
 * ```kotlin
 * retry(
 *     times = 3,
 *     strategy = RetryStrategy.Exponential(1.seconds)
 * ) {
 *     fetchFromApi()
 * }.onSuccess { data ->
 *     println("Got data after ${it.attempts} attempts")
 * }.onFailure { error ->
 *     println("Failed after ${it.attempts} attempts: ${error.lastError}")
 * }
 * ```
 */
fun <T> retry(
    times: Int,
    strategy: RetryStrategy = RetryStrategy.Fixed(1.seconds),
    scheduler: TaskScheduler = Tasks.scheduler,
    shouldRetry: (Throwable) -> Boolean = { true },
    operation: () -> T,
    callback: (RetryResult<T>) -> Unit,
): ScheduledTask {
    var attempt = 0
    var lastError: Throwable? = null

    fun tryOnce(): ScheduledTask =
        scheduler.async {
            try {
                val result = operation()
                sync { callback(RetryResult.Success(result, attempt + 1)) }
            } catch (e: Throwable) {
                lastError = e
                attempt++

                if (attempt >= times || !shouldRetry(e)) {
                    sync { callback(RetryResult.Failure(e, attempt)) }
                } else {
                    val delay = strategy.getDelay(attempt - 1)
                    delayed(delay) { tryOnce() }
                }
            }
        }

    return tryOnce()
}

// ==================== Progress Task ====================

/**
 * A task with progress tracking.
 */
class ProgressTask(
    private val scheduler: TaskScheduler,
    private val totalSteps: Int,
) {
    private var currentStep = 0
    private var onProgress: ((current: Int, total: Int, percent: Float) -> Unit)? = null
    private var onComplete: (() -> Unit)? = null

    /**
     * Set progress callback.
     */
    fun onProgress(callback: (current: Int, total: Int, percent: Float) -> Unit) =
        apply {
            onProgress = callback
        }

    /**
     * Set completion callback.
     */
    fun onComplete(callback: () -> Unit) =
        apply {
            onComplete = callback
        }

    /**
     * Advance progress by one step.
     */
    fun advance() {
        if (currentStep < totalSteps) {
            currentStep++
            val percent = currentStep.toFloat() / totalSteps
            onProgress?.invoke(currentStep, totalSteps, percent)

            if (currentStep >= totalSteps) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Set progress to specific step.
     */
    fun setProgress(step: Int) {
        currentStep = step.coerceIn(0, totalSteps)
        val percent = currentStep.toFloat() / totalSteps
        onProgress?.invoke(currentStep, totalSteps, percent)

        if (currentStep >= totalSteps) {
            onComplete?.invoke()
        }
    }

    /**
     * Get current progress as percentage (0.0 - 1.0).
     */
    fun getProgress(): Float = currentStep.toFloat() / totalSteps

    /**
     * Check if complete.
     */
    fun isComplete(): Boolean = currentStep >= totalSteps
}

/**
 * Create a progress tracker.
 */
fun progressTask(
    totalSteps: Int,
    scheduler: TaskScheduler = Tasks.scheduler,
) = ProgressTask(scheduler, totalSteps)

// Duration extensions are available from kotlin.time.Duration.Companion:
// - Int.seconds, Long.seconds
// - Int.minutes, Long.minutes
// - Int.milliseconds, Long.milliseconds
// Import them with: import kotlin.time.Duration.Companion.seconds etc.

// ==================== Tick-based Runnable overloads (legacy / Velocity-style) ====================

fun sync(task: Runnable): ScheduledTask = Tasks.scheduler.runSync(task)

fun async(task: Runnable): ScheduledTask = Tasks.scheduler.runAsync(task)

fun delayed(delayTicks: Long, task: Runnable): ScheduledTask = Tasks.scheduler.runLater(delayTicks, task)

inline fun delayed(delayTicks: Long, crossinline block: () -> Unit): ScheduledTask =
    delayed(delayTicks, Runnable { block() })

fun repeating(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
    Tasks.scheduler.runTimer(delayTicks, periodTicks, task)

inline fun repeating(delayTicks: Long, periodTicks: Long, crossinline block: () -> Unit): ScheduledTask =
    repeating(delayTicks, periodTicks, Runnable { block() })

