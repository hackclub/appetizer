package com.fantamomo.slack.approver.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coordinates execution of suspending operations while keeping their start rate within a fixed
 * per-minute limit.
 *
 * Requests are queued until a permit is available. Normal-priority requests are processed before
 * low-priority requests, while preserving FIFO order within each priority queue. Only one permitted
 * block is allowed to run at a time; the next queued request is considered only after the current
 * block completes.
 *
 * The rate limit is enforced over a rolling 60-second window based on the time each block is
 * permitted to start. When the limit has been reached, queued requests are delayed until the oldest
 * recorded request leaves the window.
 *
 * The internal scheduler is launched in the supplied coroutine scope. Call `close` when this
 * instance is no longer needed to cancel the scheduler and close its wake-up channel.
 *
 * @param maxRequestsPerMinute the maximum number of requests that may be started in any rolling
 * 60-second window.
 * @param scope the coroutine scope used to run the internal scheduler.
 * @throws IllegalArgumentException if `maxRequestsPerMinute` is not greater than zero.
 */
class RateLimitAvoider(
    private val maxRequestsPerMinute: Int,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    init {
        require(maxRequestsPerMinute > 0) {
            "maxRequestsPerMinute must be greater than 0"
        }
    }

    private data class Request(
        val low: Boolean,
        val permit: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private val mutex = Mutex()

    private val normalQueue = ArrayDeque<Request>()
    private val lowQueue = ArrayDeque<Request>()

    private val requestTimes = ArrayDeque<Long>()

    private var requestRunning = false

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val schedulerJob: Job = scope.launch {
        schedulerLoop()
    }

    @OptIn(ExperimentalContracts::class)
    suspend fun <T> withLimit(
        low: Boolean = false,
        block: suspend () -> T
    ): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        val request = Request(low = low)

        mutex.withLock {
            if (low) {
                lowQueue.addLast(request)
            } else {
                normalQueue.addLast(request)
            }
        }

        wakeUp.trySend(Unit)

        try {
            request.permit.await()
        } catch (e: CancellationException) {
            // the caller was cancelled while waiting. If the request is still queued, just remove it.
            // If the permit was already granted, we have to release it, otherwise requestRunning would stay true
            // forever and every following request of this limiter would wait forever.
            withContext(NonCancellable) {
                mutex.withLock {
                    val wasQueued = if (low) lowQueue.remove(request) else normalQueue.remove(request)
                    if (!wasQueued && request.permit.isCompleted) {
                        requestRunning = false
                    }
                }
                wakeUp.trySend(Unit)
            }
            throw e
        }

        return try {
            block()
        } finally {
            // NonCancellable: the release must also happen if the block was cancelled
            withContext(NonCancellable) {
                mutex.withLock {
                    requestRunning = false
                }
            }

            wakeUp.trySend(Unit)
        }
    }

    /**
     * Returns true if a request would be started immediately and at least [reserve] requests of the
     * current window would still be left afterward. Used for unimportant requests (e.g. progress updates),
     * which should rather be skipped than delay other requests.
     */
    suspend fun hasSpareCapacity(reserve: Int): Boolean = mutex.withLock {
        removeExpiredRequestTimes()
        !requestRunning && normalQueue.isEmpty() && lowQueue.isEmpty() &&
                requestTimes.size + 1 + reserve <= maxRequestsPerMinute
    }

    private suspend fun schedulerLoop() {
        while (true) {
            var delayMillis: Long? = null

            mutex.withLock {
                removeExpiredRequestTimes()

                if (requestRunning) {
                    return@withLock
                }

                if (normalQueue.isEmpty() && lowQueue.isEmpty()) {
                    return@withLock
                }

                if (requestTimes.size >= maxRequestsPerMinute) {
                    val oldest = requestTimes.first()
                    val waitUntil = oldest + 60_000L
                    delayMillis = (waitUntil - System.currentTimeMillis())
                        .coerceAtLeast(1L)

                    return@withLock
                }

                val request = if (normalQueue.isNotEmpty()) {
                    normalQueue.removeFirst()
                } else {
                    lowQueue.removeFirst()
                }

                requestRunning = true

                requestTimes.addLast(System.currentTimeMillis())

                request.permit.complete(Unit)
            }

            if (delayMillis != null) {
                delay(delayMillis.milliseconds)
            } else {
                wakeUp.receive()
            }
        }
    }

    private fun removeExpiredRequestTimes() {
        val now = System.currentTimeMillis()
        val limit = now - 60_000L

        while (requestTimes.isNotEmpty() && requestTimes.first() <= limit) {
            requestTimes.removeFirst()
        }
    }

    fun close() {
        schedulerJob.cancel()
        wakeUp.close()
    }
}