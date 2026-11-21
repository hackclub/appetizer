package com.fantamomo.slack.approver.slack.appinfo

import com.fantamomo.slack.approver.App
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.manager.SlackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// the steps of collecting the app information, shown as a to-do list while loading
enum class CollectStep(val label: String) {
    LOOKUP("Finding the app"),
    REQUESTS("Loading the requests from the database"),
    POLICY("Checking restrictions, protection and scope rules"),
    COMMANDS("Loading the command index"),
    PROFILE("Fetching the app profile from Slack"),
    MARKETPLACE("Reading the marketplace page"),
}

enum class StepState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

// shows a to-do list in a loading view and updates it while the information is collected.
//
// Progress updates are unimportant, so they:
// - are throttled (at most one every MIN_INTERVAL, the first one not before MIN_INTERVAL, so fast loads never send one)
// - use the low priority queue of the rate limiter, so every other view update goes first
// - are skipped if the rate limiter has no spare capacity
// The final view is always sent (with normal priority), and never overwritten by a late progress update.
class AppInfoProgress(
    private val viewId: String,
    private val target: String,
    appKnown: Boolean,
) {
    private val logger = LoggerFactory.getLogger(AppInfoProgress::class.java)

    private val states = ConcurrentHashMap(initialStates(appKnown))

    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val started = TimeSource.Monotonic.markNow()

    // runs in the application scope, so it is independent of the caller (finish() always stops it)
    private val job = App.scope.launch { updateLoop() }

    fun snapshot(): Map<CollectStep, StepState> = CollectStep.entries.associateWith { states[it] ?: StepState.WAITING }

    operator fun set(step: CollectStep, state: StepState) {
        states[step] = state
        changed.trySend(Unit)
    }

    private suspend fun updateLoop() {
        var lastUpdate = started
        for (signal in changed) {
            val wait = MIN_INTERVAL - lastUpdate.elapsedNow()
            if (wait.isPositive()) delay(wait)

            if (!RateLimits.VIEWS_UPDATE.hasSpareCapacity(RESERVED_UPDATES)) {
                // other views are more important, try again later (the newest state is sent then)
                delay(MIN_INTERVAL)
                changed.trySend(Unit)
                continue
            }

            SlackManager.updateView(viewId, AppInfoViews.progressModal(target, snapshot()), low = true)
            lastUpdate = TimeSource.Monotonic.markNow()
        }
    }

    // stops the progress updates and shows the final view, retries once and falls back to an error view
    suspend fun finish(view: com.google.gson.JsonObject) = withContext(NonCancellable) {
        job.cancelAndJoin()
        changed.close()

        if (SlackManager.updateView(viewId, view)) return@withContext
        logger.warn("Could not show the final app info view in $viewId, retrying")
        delay(1.seconds)
        if (SlackManager.updateView(viewId, view)) return@withContext
        SlackManager.updateView(
            viewId,
            AppInfoViews.messageModal(
                "Error",
                ":warning: The information was collected, but Slack did not accept the view. Please try again."
            )
        )
    }

    companion object {
        // the states shown in the first loading view (before anything was loaded)
        fun initialStates(appKnown: Boolean): Map<CollectStep, StepState> = CollectStep.entries.associateWith {
            when {
                it == CollectStep.LOOKUP && appKnown -> StepState.DONE
                it == CollectStep.LOOKUP -> StepState.RUNNING
                else -> StepState.WAITING
            }
        }

        private val MIN_INTERVAL = 1500.milliseconds

        // progress updates are only sent if at least this many view updates per minute are left for others
        private const val RESERVED_UPDATES = 20
    }
}
