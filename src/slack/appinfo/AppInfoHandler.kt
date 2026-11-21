package com.fantamomo.slack.approver.slack.appinfo

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants.MARKETPLACE_URL
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import com.fantamomo.slack.approver.manager.SlackManager
import com.fantamomo.slack.approver.slack.SlackInteractionHandler
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import com.google.gson.JsonObject as GsonObject

// handles everything around the app info modal (opened via "/app-approver info <app>" or the button in the review queue)
//
// Views are shown instantly if the app is already in the cache (AppDetailsService).
// Otherwise a loading view with a to-do list is shown first (the trigger id is only valid for 3 seconds),
// which is updated while the data is collected and replaced by the real view at the end.
object AppInfoHandler {
    private val logger = LoggerFactory.getLogger(AppInfoHandler::class.java)

    private const val ERROR_MESSAGE = ":no_entry_sign: Something went wrong while collecting the app information. Please try again."

    private suspend fun viewer(userId: String): AppInfoViewer {
        val isAdmin = SlackInteractionHandler.isAdmin(userId)
        val isTeam = isAdmin || InstallRequestRepository.isTeamMemberAuthorized(userId)
        return AppInfoViewer(userId, isAdmin, isTeam)
    }

    // the review queue button, opens a modal where the user can enter the app
    suspend fun openLookupModal(userId: String, triggerId: String) {
        if (!viewer(userId).isTeam) {
            SlackWorkflowService.openUnauthorizedMessage(triggerId)
            return
        }
        SlackManager.openView(triggerId, AppInfoViews.lookupModal())
    }

    // opens the info modal for the given input (from the slash command or the lookup modal), always with fresh data
    suspend fun open(userId: String, triggerId: String, input: String) {
        val viewer = viewer(userId)
        if (!viewer.isTeam) {
            SlackWorkflowService.openUnauthorizedMessage(triggerId)
            return
        }

        val target = input.trim().take(100)
        val viewId = SlackManager.openViewForId(
            triggerId,
            AppInfoViews.progressModal(target, AppInfoProgress.initialStates(appKnown = false))
        )
        if (viewId.isNullOrEmpty()) {
            logger.warn("Could not open the app info modal for $userId")
            return
        }

        showWithProgress(viewId, target, appKnown = false) { progress ->
            when (val result = AppLookup.resolve(input)) {
                is AppLookup.Result.NotFound -> {
                    progress[CollectStep.LOOKUP] = StepState.FAILED
                    AppInfoViews.messageModal("App not found", result.message)
                }

                is AppLookup.Result.Found -> {
                    progress[CollectStep.LOOKUP] = StepState.DONE
                    val details = AppDetailsService.get(result.appId, result.requestId, forceRefresh = true, progress = progress)
                    AppInfoViews.mainModal(details, viewer)
                }
            }
        }
    }

    // the user submitted the lookup modal
    suspend fun handleLookupSubmit(userId: String, triggerId: String, values: JsonObject?) {
        val input = ((values?.get("lookup_block") as? JsonObject)?.get("lookup_input") as? JsonObject)
            ?.get("value")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return
        open(userId, triggerId, input)
    }

    // returns true if the action belongs to the app info modal
    suspend fun handleBlockAction(
        userId: String,
        triggerId: String,
        actionId: String,
        value: String,
        view: JsonObject?
    ): Boolean {
        if (actionId == AppInfoViews.ACTION_LOOKUP) {
            openLookupModal(userId, triggerId)
            return true
        }
        val isDetail = actionId.startsWith(AppInfoViews.ACTION_DETAIL)
        val isPage = actionId.startsWith(AppInfoViews.ACTION_PAGE)
        val isAdmin = actionId.startsWith(AppInfoViews.ACTION_ADMIN)
        if (!isDetail && !isPage && !isAdmin) return false

        val metadata = (view?.get("private_metadata") as? JsonPrimitive)?.contentOrNull
            ?.let { AppInfoViews.Metadata.decode(it) }
            ?: return true
        val viewId = (view["id"] as? JsonPrimitive)?.contentOrNull ?: return true

        // the cached details are used before anything else (even the permission check) because the trigger id
        // expires after 3 seconds, the permission check is cheap anyway
        val cached = AppDetailsService.getCached(metadata.appId, metadata.requestId)

        val viewer = viewer(userId)
        if (!viewer.isTeam) {
            SlackWorkflowService.openUnauthorizedMessage(triggerId)
            return true
        }

        when {
            isDetail -> {
                val detail = AppInfoDetail.entries.find { it.name == value } ?: return true
                if (cached != null) {
                    SlackManager.pushView(triggerId, AppInfoViews.detailModal(cached, detail, 0))
                } else {
                    pushWithProgress(triggerId, metadata) { progress ->
                        AppInfoViews.detailModal(load(metadata, progress), detail, 0)
                    }
                }
            }

            isPage -> {
                val (detailName, page) = value.split(':', limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                val detail = AppInfoDetail.entries.find { it.name == detailName } ?: return true
                if (cached != null) {
                    SlackManager.updateView(viewId, AppInfoViews.detailModal(cached, detail, page))
                } else {
                    // no trigger id needed, the current view is replaced with the to-do list
                    SlackManager.updateView(viewId, AppInfoViews.progressModal(metadata.appId, AppInfoProgress.initialStates(appKnown = true)))
                    showWithProgress(viewId, metadata.appId, appKnown = true) { progress ->
                        AppInfoViews.detailModal(load(metadata, progress), detail, page)
                    }
                }
            }

            else -> {
                if (!viewer.isAdmin) {
                    SlackManager.pushView(triggerId, AppInfoViews.messageModal("Not allowed", ":no_entry_sign: Only admins can do this."))
                    return true
                }
                val action = AppAdminAction.entries.find { it.name == value } ?: return true
                fun build(details: AppDetails) = protectionError(details, action)
                    ?.let { AppInfoViews.messageModal("Not allowed", it) }
                    ?: AppInfoViews.actionModal(details, action, viewId)

                if (cached != null) {
                    SlackManager.pushView(triggerId, build(cached))
                } else {
                    pushWithProgress(triggerId, metadata) { progress -> build(load(metadata, progress)) }
                }
            }
        }
        return true
    }

    private suspend fun load(metadata: AppInfoViews.Metadata, progress: AppInfoProgress) =
        AppDetailsService.get(metadata.appId, metadata.requestId, progress = progress)

    // pushes the to-do list and replaces it with the real view once it is built
    private suspend fun pushWithProgress(
        triggerId: String,
        metadata: AppInfoViews.Metadata,
        build: suspend (AppInfoProgress) -> GsonObject
    ) {
        val pushedId = SlackManager.pushView(
            triggerId,
            AppInfoViews.progressModal(metadata.appId, AppInfoProgress.initialStates(appKnown = true))
        )
        if (pushedId.isNullOrEmpty()) {
            logger.warn("Could not push the app info loading view for ${metadata.appId}")
            return
        }
        showWithProgress(pushedId, metadata.appId, appKnown = true, build)
    }

    // builds the view while reporting the progress in the given (already opened) view.
    // The final view (or an error view) is always shown, even if building fails or the coroutine is cancelled.
    private suspend fun showWithProgress(
        viewId: String,
        target: String,
        appKnown: Boolean,
        build: suspend (AppInfoProgress) -> GsonObject
    ) {
        val progress = AppInfoProgress(viewId, target, appKnown)
        val view = try {
            build(progress)
        } catch (e: CancellationException) {
            progress.finish(AppInfoViews.messageModal("Error", ERROR_MESSAGE))
            throw e
        } catch (e: Throwable) {
            logger.error("Failed to build the app info view for $target", e)
            AppInfoViews.messageModal("Error", ERROR_MESSAGE)
        }
        progress.finish(view)
    }

    // the message why the action is not allowed because the app is protected, or null if it is allowed
    private fun protectionError(details: AppDetails, action: AppAdminAction): String? {
        if (details.protection == null) return null
        return when (action) {
            AppAdminAction.RESTRICT -> "*${esc(details.name)}* is protected and can not be restricted by anyone. Remove the protection first."
            AppAdminAction.UNINSTALL -> "*${esc(details.name)}* is protected and can not be uninstalled by anyone. Remove the protection first."
            AppAdminAction.PROTECT -> "*${esc(details.name)}* is already protected."
            else -> null
        }
    }

    // the admin confirmed an action in the confirmation modal
    suspend fun handleActionSubmit(userId: String, privateMetadata: String, reason: String?) {
        val parts = privateMetadata.split('|')
        val action = AppAdminAction.entries.find { it.name == parts.getOrNull(0) } ?: return
        val appId = parts.getOrNull(1)?.takeIf { it.startsWith('A') } ?: return
        val requestId = parts.getOrNull(2)?.ifBlank { null }
        val rootViewId = parts.getOrNull(3)?.ifBlank { null }

        val viewer = viewer(userId)
        if (!viewer.isAdmin) {
            logger.warn("User $userId tried to perform $action on $appId without being an admin")
            return
        }

        val notice = try {
            execute(userId, action, AppDetailsService.get(appId, requestId, forceRefresh = true), reason?.trim()?.ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to perform $action on $appId by $userId", e)
            AppInfoNotice("Something went wrong, check the logs.", success = false)
        }

        AppDetailsService.invalidate(appId)
        if (rootViewId != null) {
            val view = try {
                AppInfoViews.mainModal(AppDetailsService.get(appId, requestId, forceRefresh = true), viewer, notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to refresh the app info modal of $appId", e)
                AppInfoViews.messageModal("App Info", "${notice.text}\n\nThe information could not be refreshed, please open the app info again.")
            }
            SlackManager.updateView(rootViewId, view)
        }
    }

    // executes the action and returns the notice shown at the top of the modal
    private suspend fun execute(userId: String, action: AppAdminAction, details: AppDetails, reason: String?): AppInfoNotice {
        protectionError(details, action)?.let { return AppInfoNotice(it.replace("*", ""), success = false) }

        val appId = details.appId
        val appText = "<$MARKETPLACE_URL$appId|${esc(details.name)}>"
        val reasonText = reason?.let { " (${esc(it.take(500))})" } ?: ""
        val request = details.actionableRequest

        fun ok(text: String) = AppInfoNotice(text, success = true)
        fun failed(text: String) = AppInfoNotice(text, success = false)

        return when (action) {
            AppAdminAction.APPROVE -> {
                if (details.restriction != null) InstallRequestRepository.unrestrictApp(appId)
                if (request != null) {
                    SlackInteractionHandler.performApprove(userId, request, reason)
                    ok("The request ${request.requestId} of <@${request.userId}> was approved.")
                } else {
                    if (!SlackManager.approveApp(appId, null, SharedData.enterpriseId, null)) {
                        return failed("Slack did not accept the approval, check the logs.")
                    }
                    log(":white_check_mark: $appText was approved for the organisation by <@$userId>$reasonText.")
                    ok("The app was approved for the organisation.")
                }
            }

            AppAdminAction.RESTRICT -> {
                if (request != null) {
                    SlackInteractionHandler.performRestrict(userId, request, reason)
                } else {
                    InstallRequestRepository.restrictApp(appId, details.name.take(100), userId, reason)
                    if (!SlackManager.restrictApp(appId, null, SharedData.enterpriseId, null)) {
                        return failed("The app is restricted in this system, but Slack did not accept the restriction, check the logs.")
                    }
                    log(":lock: $appText was restricted by <@$userId>$reasonText.")
                }
                ok("The app was restricted.")
            }

            AppAdminAction.UNINSTALL -> {
                if (!SlackManager.uninstallApp(appId)) {
                    return failed("Slack did not uninstall the app, check the logs.")
                }
                log(":wastebasket: $appText was uninstalled from the organisation by <@$userId>$reasonText.")
                ok("The app was uninstalled from the organisation.")
            }

            AppAdminAction.PROTECT -> {
                if (!InstallRequestRepository.protectApp(appId, details.name, userId, reason)) {
                    return failed("The app is already protected.")
                }
                log(":shield: $appText is now protected by <@$userId>$reasonText. It can no longer be restricted or uninstalled.")
                ok("The app is now protected.")
            }

            AppAdminAction.UNPROTECT -> {
                if (!InstallRequestRepository.unprotectApp(appId)) return failed("The app was not protected.")
                log(":unlock: The protection of $appText was removed by <@$userId>$reasonText.")
                ok("The protection was removed.")
            }
        }
    }

    private suspend fun log(text: String) {
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, text)
    }
}
