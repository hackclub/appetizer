package com.fantamomo.slack.approver.slack.appinfo

import com.fantamomo.slack.approver.manager.AppInfoManager
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.utils.ExpiringCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// everything we know about an app, collected from our database and from Slack
data class AppDetails(
    val appId: String,
    // the request which was looked up (if the user looked up a request id)
    val focusRequestId: String?,
    // newest first, at most MAX_REQUESTS
    val requests: List<InstallRequestRecord>,
    val totalRequestCount: Long,
    val restriction: AppRestrictionInfo?,
    val protection: ProtectedApp?,
    // command -> active, from our command index
    val indexedCommands: Map<String, Boolean>,
    val scopeRules: List<ScopeRule>,
    // null if the client token is not configured or slack did not answer
    val marketplace: AppMarketplaceDetails?,
    // things that went wrong while collecting, shown in the modal so nobody thinks the data is complete
    val warnings: List<String>,
) {
    val latestRequest: InstallRequestRecord? get() = requests.firstOrNull()
    val focusRequest: InstallRequestRecord? get() = focusRequestId?.let { id -> requests.firstOrNull { it.requestId == id } }

    val name: String
        get() = latestRequest?.appName ?: marketplace?.name ?: appId

    val description: String? get() = latestRequest?.appDescription?.takeIf { it.isNotBlank() }
    val developerType: AppDevelopmentType? get() = latestRequest?.developerType
    val appUrl: String? get() = latestRequest?.appUrl?.takeIf { it.isNotBlank() }

    // the request which can still be approved/denied by the team (pending or automatically denied)
    val actionableRequest: InstallRequestRecord?
        get() = requests.firstOrNull {
            it.status == RequestStatus.PENDING_REVIEW || (it.status == RequestStatus.DENIED && it.resolution == null)
        }

    val requesters: List<String> get() = requests.map { it.userId }.distinct()

    // we can't ask slack who the collaborators of an app are, but every request tells us if the requester is one
    val knownCollaborators: List<String>
        get() = requests.filter { it.isUserAppCollaborator }.map { it.userId }.distinct()

    // who approved the app (manual approvals only, automatic approvals are shown as "automatically")
    val approvers: List<String>
        get() = requests
            .filter { it.resolution == RequestDecision.APPROVED }
            .mapNotNull { it.resolvedBy }
            .filter { it.startsWith('U') || it.startsWith('W') }
            .distinct()

    val teamIds: List<String> get() = requests.mapNotNull { it.teamId }.distinct()
    val enterpriseIds: List<String> get() = requests.mapNotNull { it.enterpriseId }.distinct()

    val isApproved: Boolean
        get() = requests.any { it.status == RequestStatus.APPROVED } || marketplace?.info?.isInstalled == true

    // the scopes of the newest request, these are the scopes the app currently wants
    val currentScopes: List<ScopeIdentity> get() = latestRequest?.scopes.orEmpty()

    // every scope that was ever requested (by any request)
    val allRequestedScopes: List<ScopeIdentity>
        get() = requests.flatMap { it.scopes }.distinctBy { it.tokenType.lowercase() to it.name.lowercase() }

    // evaluates a scope against the scope rules (as the latest requester would get it)
    fun scopeAction(scope: ScopeIdentity): RuleAction? {
        val verified = latestRequest?.userVerified ?: true
        return scopeRules
            .filter { it.matches(scope.name, scope.tokenType, verified) }
            .minWithOrNull(ScopeRulePrecedenceComparator)
            ?.action
    }
}

// collects AppDetails and caches them, so the detail views (users, channels, ...) open instantly
object AppDetailsService {
    private val logger = LoggerFactory.getLogger(AppDetailsService::class.java)

    private const val MAX_REQUESTS = 100

    // slack sometimes takes very long (or never answers), the modal must not wait forever
    private val SLACK_TIMEOUT = 20.seconds

    // bounded and expiring, the details of an app can be large (e.g. thousands of channel ids).
    // The ttl is refreshed every time the details are used, so an open modal keeps its data.
    private val CACHE_TTL = 15.minutes
    private val cache = ExpiringCache<String, AppDetails>(maxSize = 25, defaultTtl = CACHE_TTL)

    // returns the cached details without loading anything (or null)
    fun getCached(appId: String, focusRequestId: String? = null): AppDetails? {
        val cached = cache[appId] ?: return null
        cache.put(appId, cached, CACHE_TTL)
        return cached.copy(focusRequestId = focusRequestId)
    }

    suspend fun get(
        appId: String,
        focusRequestId: String? = null,
        forceRefresh: Boolean = false,
        progress: AppInfoProgress? = null
    ): AppDetails {
        if (!forceRefresh) {
            getCached(appId, focusRequestId)?.let { return it }
        }
        val details = collect(appId, focusRequestId, progress)
        cache[appId] = details
        return details
    }

    fun invalidate(appId: String) {
        cache.remove(appId)
        AppInfoManager.removeFromCache(appId)
    }

    private suspend fun collect(appId: String, focusRequestId: String?, progress: AppInfoProgress?): AppDetails = coroutineScope {
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        // runs a step and reports its state
        suspend fun <T> step(step: CollectStep, block: suspend () -> T): T {
            progress?.set(step, StepState.RUNNING)
            return try {
                block().also { progress?.set(step, StepState.DONE) }
            } catch (e: Exception) {
                progress?.set(step, StepState.FAILED)
                throw e
            }
        }

        val marketplaceDeferred = async {
            if (!AppInfoManager.canBeUsed()) {
                warnings += "No client token is configured, so only data from our own database is shown."
                progress?.set(CollectStep.PROFILE, StepState.SKIPPED)
                progress?.set(CollectStep.MARKETPLACE, StepState.SKIPPED)
                return@async null
            }
            progress?.set(CollectStep.PROFILE, StepState.RUNNING)
            progress?.set(CollectStep.MARKETPLACE, StepState.RUNNING)
            try {
                val result = withTimeoutOrNull(SLACK_TIMEOUT) {
                    AppInfoManager.getAppDetails(appId) { part, success ->
                        val step = when (part) {
                            AppInfoManager.Part.PROFILE -> CollectStep.PROFILE
                            AppInfoManager.Part.MARKETPLACE -> CollectStep.MARKETPLACE
                        }
                        progress?.set(step, if (success) StepState.DONE else StepState.FAILED)
                    }
                }
                when {
                    result != null -> result
                    else -> {
                        // either a timeout or slack returned nothing
                        progress?.let {
                            val snapshot = it.snapshot()
                            for (s in listOf(CollectStep.PROFILE, CollectStep.MARKETPLACE)) {
                                if (snapshot[s] == StepState.RUNNING) it[s] = StepState.FAILED
                            }
                        }
                        warnings += "Slack did not return marketplace data for this app in time (it may be deleted or private)."
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to load marketplace details of $appId", e)
                warnings += "Loading the marketplace data failed."
                progress?.set(CollectStep.PROFILE, StepState.FAILED)
                progress?.set(CollectStep.MARKETPLACE, StepState.FAILED)
                null
            }
        }

        val requests = async {
            step(CollectStep.REQUESTS) {
                val count = InstallRequestRepository.countRequestsForApp(appId)
                var loaded = InstallRequestRepository.getRequestsForApp(appId, MAX_REQUESTS)
                // the focused request may be older than the newest MAX_REQUESTS requests
                if (focusRequestId != null && loaded.none { it.requestId == focusRequestId }) {
                    InstallRequestRepository.getRequest(focusRequestId)?.let { loaded = loaded + it }
                }
                count to loaded
            }
        }
        val policy = async {
            step(CollectStep.POLICY) {
                Triple(
                    InstallRequestRepository.getAppRestriction(appId),
                    InstallRequestRepository.getProtectedApp(appId),
                    InstallRequestRepository.getScopeRules().filter { it.enabled }
                )
            }
        }
        val commands = async { step(CollectStep.COMMANDS) { InstallRequestRepository.getIndexedCommands(appId) } }

        val (count, loadedRequests) = requests.await()
        val (restriction, protection, rules) = policy.await()

        AppDetails(
            appId = appId,
            focusRequestId = focusRequestId,
            requests = loadedRequests,
            totalRequestCount = count,
            restriction = restriction,
            protection = protection,
            indexedCommands = commands.await(),
            scopeRules = rules,
            marketplace = marketplaceDeferred.await(),
            warnings = warnings.toList(),
        )
    }
}
