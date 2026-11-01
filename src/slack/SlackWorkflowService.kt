package com.fantamomo.slack.approver.slack

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants
import com.fantamomo.slack.approver.data.Constants.BULLET
import com.fantamomo.slack.approver.data.Constants.MARKETPLACE_URL
import com.fantamomo.slack.approver.decision.DecisionContext
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import com.fantamomo.slack.approver.manager.SettingsManager
import com.fantamomo.slack.approver.manager.SlackManager
import com.fantamomo.slack.approver.manager.VerificationManager
import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.utils.blockkit.TagColor
import com.fantamomo.slack.approver.utils.blockkit.TextStyle
import com.fantamomo.slack.approver.utils.blockkit.tag
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.slack.api.model.kotlin_extension.block.ActionsBlockBuilder
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.model.kotlin_extension.block.element.ListStyle
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

object SlackWorkflowService {
    private val logger = LoggerFactory.getLogger(SlackWorkflowService::class.java)

    private const val MAX_ADMIN_RULES_SHOWN = 55

    private const val MAX_TEST_INACTIVE_SHOWN = 40

    private val appHomeCheckCooldown = ConcurrentHashMap<String, Instant>()

    enum class HomeView { TEAM, REQUESTS, ADMIN, INFO }

    private data class HomeTab(val view: HomeView, val label: String, val actionId: String)

    private data class ScopeTestInput(val scope: String, val tokenType: String, val verified: Boolean)

    private val homeViews = ConcurrentHashMap<String, HomeView>()

    private val scopeTests = ConcurrentHashMap<String, ScopeTestInput>()

    private fun isScopeInfoScreenEnabled(): Boolean =
        "true".equals(SettingsManager["scope_info_screen"], ignoreCase = true)

    private fun statusEmoji(status: RequestStatus): String = when (status) {
        RequestStatus.PENDING_REVIEW -> "hourglass_flowing_sand"
        RequestStatus.APPROVED -> "white_check_mark"
        RequestStatus.DENIED -> "no_entry_sign"
        RequestStatus.WITHDRAWN -> "outbox_tray"
    }

    private fun statusColor(status: RequestStatus, resolution: RequestDecision?): TagColor {
        if (resolution == RequestDecision.UNDONE) return TagColor.PURPLE
        return when (status) {
            RequestStatus.PENDING_REVIEW -> TagColor.YELLOW
            RequestStatus.APPROVED -> TagColor.GREEN
            RequestStatus.DENIED -> TagColor.RED
            RequestStatus.WITHDRAWN -> TagColor.BLUE
        }
    }

    private fun statusLabel(status: RequestStatus, resolution: RequestDecision?): String {
        if (resolution == RequestDecision.RESTRICTED) return "Restricted"
        if (resolution == RequestDecision.UNDONE) return "Undone"
        return when (status) {
            RequestStatus.PENDING_REVIEW -> "Pending Review"
            RequestStatus.APPROVED -> "Approved"
            RequestStatus.DENIED -> "Denied"
            RequestStatus.WITHDRAWN -> "Withdrawn"
        }
    }

    private fun formatReason(reason: RequestDecisionReason?, resolutionMessage: String?, resolvedBy: String?): String {
        if (resolvedBy == "THIRD_PARTY") {
            return "The request was resolved by a third party"
        }
        if (resolvedBy != null) {
            val msg = if (!resolutionMessage.isNullOrBlank()) " ($resolutionMessage)" else ""
            return "Resolved by <@$resolvedBy>$msg"
        }
        return when (reason) {
            RequestDecisionReason.USER_VERIFIED -> "Verified user with allowed scopes"
            RequestDecisionReason.USER_UNVERIFIED -> "User is not verified"
            RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES -> "Unverified user with explicitly allowed scopes"
            RequestDecisionReason.ENTERPRISE_INSTALL -> "Enterprise installation"
            RequestDecisionReason.RESTRICTED_SCOPE -> "Restricted scope requested"
            RequestDecisionReason.PREVIOUSLY_DENIED -> "Identical request previously denied"
            RequestDecisionReason.RESTRICTED_APPLICATION -> "Application is restricted"
            RequestDecisionReason.MANUAL_REVIEW -> "Manual review"
            RequestDecisionReason.MANUAL_OVERRIDE -> "Manual override"
            RequestDecisionReason.USER_WITHDRAWN -> "Withdrawn by user"
            RequestDecisionReason.USER_REQUESTED_REVIEW -> "Manual review requested by user"
            RequestDecisionReason.RESOLUTION_UNDONE -> "Resolution undone"
            RequestDecisionReason.COMMAND_ALREADY_IN_USE -> "Command is already in use by another app"
            RequestDecisionReason.SLACK_AUTOMATION -> "The request was resolved by slack automation."
            RequestDecisionReason.THIRD_PARTY -> "The request was resolved by a third party"
            null -> "None"
        }
    }

    suspend fun postInitialReviewMessage(
        request: AppRequested,
        isUserVerified: Boolean,
        decision: DecisionResult,
        decisionContext: DecisionContext,
        stateId: String
    ): String? {
        val appUrl = request.app.appHomepageUrl.ifBlank { request.app.appDirectoryUrl }
        val reasonText = formatReason(decision.reason, null, null)
        val buttonValue = "${request.id}:$stateId"

        val messageTs = SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW) {
            header {
                text("App Installation Request", true)
            }
            richText {
                richTextSection {
                    text("App: ", style = TextStyle(bold = true))
                    if (appUrl.isNotBlank()) {
                        link(appUrl, request.app.name)
                    } else {
                        text(request.app.name)
                    }
                    text("\nUser: ", style = TextStyle(bold = true))
                    user(request.user.id)
                    text("\nStatus: ", style = TextStyle(bold = true))
                    emoji(statusEmoji(decision.status))
                    text(" ")
                    tag(statusLabel(decision.status, null), statusColor(decision.status, null))
                    text("\nReason: ", style = TextStyle(bold = true))
                    text(reasonText)
                }
            }
            actions {
                if (decision.status == RequestStatus.APPROVED) {
                    button {
                        text("Undo", true)
                        actionId("review_undo")
                        value(buttonValue)
                        style("primary")
                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                } else if (decision.status == RequestStatus.DENIED) {
                    button {
                        text("Override (Approve)", true)
                        actionId("review_approve")
                        value(buttonValue)
                        style("primary")
                    }
//                    button {
//                        text("Undo", true)
//                        actionId("review_undo")
//                        value(request.id)
//                        style("danger")
//                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                } else {
                    button {
                        text("Approve", true)
                        actionId("review_approve")
                        value(buttonValue)
                        style("primary")
                    }
                    button {
                        text("Deny", true)
                        actionId("review_deny")
                        value(buttonValue)
                        style("danger")
                    }
                    button {
                        text("Restrict", true)
                        actionId("review_restrict")
                        value(buttonValue)
                        style("danger")
                    }
                }
            }
        } ?: return null

        SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW, messageTs) {
            richText {
                val userMessageWithNewLines = request.message?.contains('\n') == true
                richTextSection {
                    elements {
                        text("Request ID: ", style = TextStyle(bold = true))
                        text(request.id, style = TextStyle(code = true))
                        text("\n")
                        text("User: ", style = TextStyle(bold = true))
                        user(request.user.id)
                        text(" (Verified: ")
                        tag("$isUserVerified", if (isUserVerified) TagColor.GREEN else TagColor.RED)
                        text(", Collaborator: ")
                        tag(
                            "${request.isUserAppCollaborator}",
                            if (request.isUserAppCollaborator) TagColor.GREEN else TagColor.RED
                        )
                        text(")\n")
                        if (request.team != null) {
                            text("Team: ", style = TextStyle(bold = true))
                            text(request.team.id, style = TextStyle(code = true))
                            text(" (")
                            text(request.team.name)
                            text(")\n")
                        }
                        if (request.enterprise != null) {
                            text("Enterprise: ", style = TextStyle(bold = true))
                            text(request.enterprise.id, style = TextStyle(code = true))
                            text(" (")
                            text(request.enterprise.name)
                            text(")\n")
                        }
                        if (!request.message.isNullOrEmpty()) {
                            text("User Message: ", style = TextStyle(bold = true))
                            if (!userMessageWithNewLines) {
                                text(request.message + "\n")
                            }
                        }
                        if (!userMessageWithNewLines) {
                            text("\nApp Details ", style = TextStyle(bold = true))
                            link(MARKETPLACE_URL +request.app.id, text = "(marketplace)")
                            text(": ", style = TextStyle(bold = true))
                        }
                    }
                }
                if (userMessageWithNewLines) {
                    richTextQuote {
                        text(request.message)
                    }
                    richTextSection {
                        text("App Details", style = TextStyle(bold = true))
                        text(" (")
                        link(MARKETPLACE_URL +request.app.id, text = "marketplace")
                        text(")")
                        text(": ", style = TextStyle(bold = true))
                    }
                }
                richTextList {
                    style(ListStyle.BULLET)
                    elements {
                        richTextSection {
                            text("ID: ", style = TextStyle(bold = true))
                            text(request.app.id, style = TextStyle(code = true))
                        }
                        richTextSection {
                            text("Name: ", style = TextStyle(bold = true))
                            text(request.app.name)
                        }
                        richTextSection {
                            text("Developer Type: ", style = TextStyle(bold = true))
                            val developerType = request.app.developerType
                            val color = when (developerType.lowercase()) {
                                "slack" -> TagColor.GREEN
                                "internal" -> TagColor.BLUE
                                "third_party" -> TagColor.RED
                                else -> TagColor.BROWN
                            }
                            tag(developerType, color)
                        }
                        if (request.app.description.isNotBlank()) richTextSection {
                            text("Description: ", style = TextStyle(bold = true))
                            text(request.app.description)
                        }
                    }
                }
                richTextSection {
                    text("\nRequested Scopes:", style = TextStyle(bold = true))
                    if (request.scopes.isEmpty()) {
                        text(" None")
                    }
                }
                if (request.scopes.isNotEmpty()) {
                    richTextList {
                        style(ListStyle.BULLET)
                        elements {
                            val hadPreviousFinalResolutions = decisionContext.previousRequests.any { it.status.final }
                            for (scope in request.scopes) {
                                richTextSection {
                                    text(scope.name, style = TextStyle(code = true))
                                    text(" - ${scope.tokenType}")
                                    val attributes = mutableListOf<Pair<String, TagColor>>()

                                    if (scope.isOptional) attributes.add("optional" to TagColor.BROWN)
                                    if (hadPreviousFinalResolutions) {
                                        val previousResolution = decisionContext.previousRequests.findLast {
                                            it.status.final && it.scopes.any { s -> scope.name == s.name && scope.tokenType == s.tokenType }
                                        }?.status
                                        val statusTag = when (previousResolution) {
                                            RequestStatus.APPROVED -> "approved" to TagColor.GREEN
                                            RequestStatus.DENIED -> "denied" to TagColor.RED
                                            else -> "new" to TagColor.PURPLE
                                        }
                                        attributes.add(statusTag)
                                    }
                                    // we are not using scope.isSensitive, because it set from slack and has nothing to do with our rules
//                                    if (scope.isSensitive) attributes.add("sensitive" to TagColor.ORANGE)

                                    for (attribute in attributes) {
                                        text(" ")
                                        tag("(${attribute.first})", attribute.second)
                                    }
                                }
                            }
                        }
                    }
                }
                richTextSection {
                    text("\nSlash Commands:", style = TextStyle(bold = true))
                    if (decisionContext.commands.isEmpty()) {
                        text(" None")
                    }
                }
                if (decisionContext.commands.isNotEmpty()) {
                    richTextList {
                        style(ListStyle.BULLET)
                        elements {
                            for (cmd in decisionContext.commands) {
                                richTextSection {
                                    text(cmd.name, style = TextStyle(code = true))
                                    val owner = decision.relevantCommands[cmd.name]
                                    if (!owner.isNullOrEmpty()) {
                                        text(" (Owned by ")
                                        text(owner, style = TextStyle(code = true))
                                        text(")")
                                    }
                                    if (cmd.description.isNotEmpty()) {
                                        text("\n${cmd.description}")
                                    }
                                }
                            }
                        }
                    }
                }
                richTextSection {
                    text("\nUnfurl Domains:", style = TextStyle(bold = true))
                    if (decisionContext.unfurlDomains.isEmpty()) {
                        text(" None")
                    }
                }
                if (decisionContext.unfurlDomains.isNotEmpty()) {
                    richTextList {
                        style(ListStyle.BULLET)
                        elements {
                            for (domain in decisionContext.unfurlDomains) {
                                richTextSection {
                                    text(domain, style = TextStyle(code = true))
                                    if (Constants.DANGEROUS_DOMAINS.any { domain.endsWith(it) }) {
                                        text(" ")
                                        tag("(dangerous)", color = TagColor.RED)
                                    }
                                }
                            }
                        }
                    }
                }
                richTextSection {
                    text("\nInitial Evaluation:", style = TextStyle(bold = true))
                }
                richTextList {
                    style(ListStyle.BULLET)
                    elements {
                        richTextSection {
                            text("Status: ", style = TextStyle(bold = true))
                            val color = when (decision.status) {
                                RequestStatus.PENDING_REVIEW -> TagColor.YELLOW
                                RequestStatus.APPROVED -> TagColor.GREEN
                                RequestStatus.DENIED -> TagColor.RED
                                RequestStatus.WITHDRAWN -> TagColor.BROWN // should be impossible, but who knows
                            }
                            tag(decision.status.name, color = color)
                        }
                        richTextSection {
                            text("Reason: ", style = TextStyle(bold = true))
                            text(decision.reason.name)
                        }
                        if (decision.explanation.isNotBlank()) {
                            richTextSection {
                                text("Explanation: ", style = TextStyle(bold = true))
                                text(decision.explanation)
                            }
                        }
                        if (decision.relevantRestrictedScopes.isNotEmpty()) {
                            richTextSection {
                                text("Restricted scopes: ", style = TextStyle(bold = true))
                                for ((index, scope) in decision.relevantRestrictedScopes.withIndex()) {
                                    if (index != 0) text(", ")
                                    text(scope, style = TextStyle(code = true))
                                }
                            }
                        }
                    }
                }
            }
        }

        return messageTs
    }

    suspend fun updateReviewMessage(
        record: InstallRequestRecord,
        status: RequestStatus,
        resolution: RequestDecision?,
        reason: RequestDecisionReason?,
        resolvedBy: String?,
        resolutionMessage: String?
    ) {
        val reviewTs = record.reviewMessageTs ?: return
        val appUrl = record.appUrl
        val reasonText = formatReason(reason, resolutionMessage, resolvedBy)

        SlackManager.updateMessage(Config.SLACK_CHANNEL_REVIEW, reviewTs) {
            header {
                text("App Installation Request", true)
            }
            richText {
                richTextSection {
                    text("App: ", style = TextStyle(bold = true))
                    if (!appUrl.isNullOrBlank()) {
                        link(appUrl, record.appName)
                    } else {
                        text(record.appName)
                    }
                    text("\nUser: ", style = TextStyle(bold = true))
                    user(record.userId)
                    text("\nStatus: ", style = TextStyle(bold = true))
                    if (resolution == RequestDecision.WITHDRAWN && reason == RequestDecisionReason.THIRD_PARTY) {
                        emoji("question")
                        text(" ")
                        tag("Resolved by someone", TagColor.BROWN)
                    } else {
                        emoji(statusEmoji(status))
                        text(" ")
                        tag(statusLabel(status, resolution), statusColor(status, resolution))
                    }
                    text("\nReason: ", style = TextStyle(bold = true))
                    text(reasonText)
                }
//                markdownText("*App:* $appLink (`${record.appId}`)\n*User:* <@${record.userId}>\n*Status:* $statusText\n*Reason:* $reasonText")
            }
            actions {
                reviewButtons(
                    record.stateId?.let { "${record.requestId}:$it" } ?: record.requestId,
                    status,
                    resolution,
                    reason
                )
            }
        }
    }

    suspend fun postReviewThreadUpdate(
        reviewMessageTs: String,
        actorId: String?,
        actionText: String,
        reason: String? = null,
        actorName: String? = null
    ) {
        val actor = if (actorId != null) "<@$actorId>" else (actorName ?: "System")
        val reasonStr = if (!reason.isNullOrBlank()) "\n*Reason / Note:* $reason" else ""
        SlackManager.sendMessage(Config.SLACK_CHANNEL_REVIEW, reviewMessageTs) {
            section {
                markdownText("$actor $actionText$reasonStr")
            }
        }
    }

    suspend fun sendDecisionDm(
        userId: String,
        appName: String,
        requestId: String,
        appId: String,
        status: RequestStatus,
        reason: RequestDecisionReason?,
        relevantScopes: List<String> = emptyList(),
        relevantCommands: Map<String, String> = emptyMap(),
        customMessage: String? = null,
        resolvedBy: String? = null
    ) {
        val appName = if (appId.startsWith('A')) "<$MARKETPLACE_URL$appId|$appName>" else appName
        when (status) {
            RequestStatus.APPROVED -> {
                if (reason != RequestDecisionReason.SLACK_AUTOMATION) {
                    val by = if (resolvedBy != null) "approved by <@$resolvedBy>" else "automatically approved"
                    SlackManager.sendDm(userId) {
                        section {
                            markdownText(":white_check_mark: Your installation request for *$appName* has been $by!\n\nThe app is now approved and ready to be installed.")
                        }
                        if (!customMessage.isNullOrBlank()) {
                            section {
                                markdownText("*Note:* $customMessage")
                            }
                        }
                    }
                }
            }

            RequestStatus.PENDING_REVIEW -> {
                val scopesInfo = if (relevantScopes.isNotEmpty()) {
                    "\n*Restricted scopes requiring review:* `${relevantScopes.joinToString(", ")}`"
                } else ""
                SlackManager.sendDm(userId) {
                    section {
                        markdownText(":hourglass_flowing_sand: Your installation request for *$appName* has been submitted for manual review by the approval team.$scopesInfo\n\nYou will be notified when a decision is made.")
                    }
                    actions {
                        button {
                            text("Withdraw Request", true)
                            actionId("user_withdraw_request")
                            value(requestId)
                            style("danger")
                        }
                    }
                }
            }

            RequestStatus.DENIED -> {
                if (resolvedBy != null) {
                    val msg = if (!customMessage.isNullOrBlank()) "\n*Reason:* $customMessage" else ""
                    SlackManager.sendDm(userId) {
                        section {
                            markdownText(":no_entry_sign: Your installation request for *$appName* was denied by <@$resolvedBy>.$msg")
                        }
                    }
                    return
                }

                when (reason) {
                    RequestDecisionReason.USER_UNVERIFIED -> {
                        val scopesInfo = if (relevantScopes.isNotEmpty()) {
                            "\n*Requested scopes requiring verification:* `${relevantScopes.joinToString(", ")}`"
                        } else ""
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined because your account is not verified.$scopesInfo\n\nTo proceed, please verify your account or request a manual review from the approval team.")
                            }
                            actions {
                                button {
                                    text("Check Verification", true)
                                    actionId("user_check_verification")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.RESTRICTED_SCOPE -> {
                        val scopesInfo = if (relevantScopes.isNotEmpty()) {
                            "\n*Restricted scopes:* `${relevantScopes.joinToString(", ")}`"
                        } else ""
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(
                                    ":no_entry_sign: Your installation request for *$appName* was declined because it requests restricted scopes that cannot be automatically approved.$scopesInfo\n\nWithdraw your request to remove them or if you really need these scopes for your workflow, you can request a manual review."
                                )
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.ENTERPRISE_INSTALL -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined because you tried to install it org wide. We typically don't allow this. If you really need to install it org wide, you can request a manual review. If you don't, withdraw your request and disabled org-wide installation")
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.PREVIOUSLY_DENIED -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was automatically declined because an identical request with the same scopes was previously declined.")
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    RequestDecisionReason.RESTRICTED_APPLICATION -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":lock: Your installation request for *$appName* was declined because this application has been restricted by administrators from installation.")
                            }
                        }
                    }

                    RequestDecisionReason.SLACK_AUTOMATION -> {
                        // dont send a message, slack automation already notified the user
                    }

                    RequestDecisionReason.COMMAND_ALREADY_IN_USE -> {
                        val commandInfo = if (relevantCommands.isNotEmpty()) {
                            "\n*Command in use:*\n${relevantCommands.entries.joinToString("\n") { "$BULLET `${it.key}` (${it.value})" }}"
                        } else ""
                        val count = when (relevantCommands.size) {
                            0 -> "at least one"
                            1 -> "one"
                            2 -> "two"
                            else -> relevantCommands.size.toString()
                        }
                        val s = if (relevantCommands.size > 1) "s" else ""
                        val are = if (relevantCommands.size > 1) "are" else "is"
                        val apps = if (relevantCommands.values.toSet().size > 1) "apps" else "app"
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined because $count command$s $are already in use by another $apps.\n\n$commandInfo\nWithdraw your request to change the command$s (maybe add a prefix) or request a manual review.")
                            }
                            actions {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(requestId)
                                    style("primary")
                                }
                                button {
                                    text("Withdraw Request", true)
                                    actionId("user_withdraw_request")
                                    value(requestId)
                                    style("danger")
                                }
                            }
                        }
                    }

                    else -> {
                        SlackManager.sendDm(userId) {
                            section {
                                markdownText(":no_entry_sign: Your installation request for *$appName* was declined.")
                            }
                        }
                    }
                }
            }

            RequestStatus.WITHDRAWN -> {
                SlackManager.sendDm(userId) {
                    section {
                        markdownText(":outbox_tray: Your installation request for *$appName* has been withdrawn.")
                    }
                }
            }
        }
    }


    suspend fun sendNotAbleToWithdrawDm(
        userId: String,
        appName: String,
        status: RequestStatus,
    ) {
        require(status != RequestStatus.PENDING_REVIEW) { "Cannot send not able to withdraw DM for pending review" }
        SlackManager.sendDm(userId) {
            section {
                when (status) {
                    RequestStatus.APPROVED -> markdownText(":warning: Could not withdraw your installation request for *$appName* because it has already been approved.")
                    RequestStatus.DENIED -> markdownText(":warning: Could not withdraw your installation request for *$appName* because it has already been denied.")
                    RequestStatus.WITHDRAWN -> markdownText(":warning: Your installation request for *$appName* has already been withdrawn.")
                }
            }
        }
    }

    suspend fun sendNotAbleToRequestReviewDm(
        userId: String,
        appName: String,
        status: RequestStatus
    ) {
        SlackManager.sendDm(userId) {
            section {
                when (status) {
                    RequestStatus.WITHDRAWN -> markdownText(":warning: Your installation request for *$appName* has already been withdrawn.")
                    RequestStatus.PENDING_REVIEW -> markdownText(":warning: Your installation request for *$appName* is already pending review.")
                    RequestStatus.APPROVED -> markdownText(":warning: Your installation request for *$appName* has already been approved.")
                    RequestStatus.DENIED -> markdownText(":warning: Your installation request for *$appName* has already been denied.")
                }
            }
        }
    }

    suspend fun sendUndoDm(
        userId: String,
        appName: String,
        adminId: String
    ) {
        SlackManager.sendDm(userId) {
            section {
                markdownText(":leftwards_arrow_with_hook: The previous resolution for your installation request for *$appName* was undone by <@$adminId>.\n\nIf you still need this application, please submit a new installation request in Slack.")
            }
        }
    }

    suspend fun logInitialTransparencyEvent(
        request: AppRequested,
        decision: DecisionResult
    ) {
        val user = "<@${request.user.id}>"
        val app = if (request.app.id.startsWith('A'))
            "<$MARKETPLACE_URL${request.app.id}|${request.app.name}>"
        else "`${request.app.name}`"

        val eventText = when (decision.status) {
            RequestStatus.APPROVED -> {
                if (decision.reason == RequestDecisionReason.SLACK_AUTOMATION) {
                    ":slackbot: $user requested $app and slack automation approved it."
                } else {
                    ":white_check_mark: $user requested $app, and the request was automatically approved."
                }
            }

            RequestStatus.PENDING_REVIEW -> {
                val scopes = if (decision.relevantRestrictedScopes.isNotEmpty()) {
                    ": `${decision.relevantRestrictedScopes.joinToString(", ")}`"
                } else ""
                ":hourglass_flowing_sand: $user requested $app (submitted for manual review because it requests restricted scopes$scopes)."
            }

            RequestStatus.DENIED -> {
                when (decision.reason) {
                    RequestDecisionReason.USER_UNVERIFIED -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because the user is not verified."
                    }

                    RequestDecisionReason.RESTRICTED_SCOPE -> {
                        val scopes = if (decision.relevantRestrictedScopes.isNotEmpty()) {
                            ": `${decision.relevantRestrictedScopes.joinToString(", ")}`"
                        } else ""
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because it requests restricted scopes$scopes."
                    }

                    RequestDecisionReason.ENTERPRISE_INSTALL -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because org-wide installations are not allowed."
                    }

                    RequestDecisionReason.PREVIOUSLY_DENIED -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because an identical request was previously denied."
                    }

                    RequestDecisionReason.RESTRICTED_APPLICATION -> {
                        ":lock: $user requested $app, but the request was automatically denied because the application is restricted."
                    }

                    RequestDecisionReason.COMMAND_ALREADY_IN_USE -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied because one command is already in use by another app."
                    }

                    RequestDecisionReason.SLACK_AUTOMATION -> {
                        val status = request.resolution?.status?.lowercase()
                            ?.let { status -> SlackResolution.entries.find { it.value == status } }
                        ":no_entry_sign: $user requested $app" + if (status != SlackResolution.CANCELLED) {
                            ", but slack automation denied it."
                        } else {
                            ", but slack automation cancelled it."
                        }
                    }

                    else -> {
                        ":no_entry_sign: $user requested $app, but the request was automatically denied."
                    }
                }
            }

            RequestStatus.WITHDRAWN -> {
                ":outbox_tray: $app request was withdrawn by $user."
            }
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, eventText)
    }

    suspend fun openUnauthorizedMessage(triggerId: String) {
        val view = buildInfoModal(
            "Unauthorized",
            ":no_entry_sign: Sorry, you are not authorized to perform this action."
        )
        SlackManager.openView(triggerId, view)
    }

    suspend fun openStateMismatchMessage(triggerId: String) {
        val view = buildInfoModal(
            "State Mismatch",
            ":no_entry_sign: Sorry, there's a conflict\n\nThe request may have been updated by another user. Please refresh and try again.\n(If the error persists, please contact the administrator)"
        )
        SlackManager.openView(triggerId, view)
    }

    suspend fun sendStateMismatch(channel: String, threadId: String?, userId: String) {
        SlackManager.sendEphemeral(
            channel = channel,
            threadTs = threadId,
            userId = userId,
        ) {
            section {
                markdownText(":no_entry_sign: Sorry, state mismatch. Please try again. If this error persists, please contact the administrator.")
            }
        }
    }

    suspend fun sendUnauthorizedMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(
            responseUrl = responseUrl,
        ) {
            section {
                markdownText(":no_entry_sign: Sorry, you are not authorized to perform this action.")
            }
        }
    }

    suspend fun sendHelpMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(buildString {
                    val B = BULLET

                    appendLine("Usage info for `${Config.SLACK_SLASH_COMMAND}`:")
                    appendLine()
                    appendLine("$B `team`: Shows all team members")
                    appendLine("    $B `add <user>`: Adds a user to the team")
                    appendLine("    $B `remove <user>`: Removes a user from the team")
                    appendLine("$B `scopes`: Lists the current restricted/allowed scopes")
                    appendLine("    $B `allow <scope> [type]`: Allows a scope")
                    appendLine("    $B `restrict <scope> [type] [review]`: Restricts a scope")
                    appendLine("    $B `reset <scope> [type]`: Resets the config for a scope")
                    appendLine("    $B `allow-unverified <scope> [type]`: Allows a scope for unverified users")
                    appendLine("$B `settings`: Shows all settings")
                    appendLine("    $B `set <name> <value>`: Sets the value of a setting")
                    appendLine("    $B `remove <name>`: Removes a setting")
                    appendLine("    $B `get <name>`: Gets the value of a setting")
                    appendLine("$B `help`: Shows this help message")
                    appendLine()
                    appendLine("Args:")
                    appendLine("$B `<scope>`: The scope to match")
                    appendLine("    $B Can be directly the scope, to only match it")
                    appendLine("    $B Or use a `*` for wildcard matching: `admin.*` (every scope starting with `admin.`), `*:read` (every scope ending with `:read`)")
                    appendLine("$B `[type]`: Can be one of the following")
                    appendLine("    $B `USER`: The user scope")
                    appendLine("    $B `BOT`: The bot scope")
                    appendLine("    $B `BOTH`: Both user and bot scope (default)")
                    appendLine("$B `[review]`: bool")
                    appendLine("    $B `true`: App is send to the review team")
                    appendLine("    $B `false`: App is directly declined (default)")
                })
            }
        }
    }

    suspend fun sendUnknownCommandMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(":question: This command is not recognized, please notify an admin. (Maybe wrong command in config)")
            }
        }
    }

    suspend fun sendUnknownArgMessage(responseUrl: Url) {
        SlackManager.sendEphemeral(responseUrl = responseUrl) {
            section {
                markdownText(":question: Unknown argument")
            }
        }
    }

    suspend fun logManualTransparencyEvent(
        appName: String,
        userId: String,
        actorId: String,
        appId: String,
        action: RequestDecision
    ) {
        val app = if (appId.startsWith('A')) "<$MARKETPLACE_URL$appId|$appName>" else "`$appName`"
        val user = "<@$userId>"
        val actor = "<@$actorId>"

        val text = when (action) {
            RequestDecision.APPROVED -> ":white_check_mark: $app requested by $user was approved by $actor."
            RequestDecision.DENIED -> ":no_entry_sign: $app requested by $user was denied by $actor."
            RequestDecision.RESTRICTED -> ":lock: $app was restricted by $actor."
            RequestDecision.WITHDRAWN -> ":outbox_tray: $app request was withdrawn by $user."
            RequestDecision.UNDONE -> ":leftwards_arrow_with_hook: Resolution for $app requested by $user was undone by $actor."
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, text)
    }

    suspend fun logUserActionTransparencyEvent(
        appName: String,
        userId: String,
        action: String,
        appId: String
    ) {
        val app = if (appId.startsWith('A')) "<$MARKETPLACE_URL$appId|$appName>" else "`$appName`"
        val user = "<@$userId>"
        val text = when (action) {
            "REQUEST_REVIEW" -> ":mag: $user requested a manual review for $app."
            "WITHDRAW" -> ":outbox_tray: $app request was withdrawn by $user."
            else -> "$user performed $action for $app."
        }
        SlackManager.sendTextMessage(Config.SLACK_CHANNEL_LOG, text)
    }

    suspend fun handleAppHomeOpened(userId: String) {
        val now = Clock.System.now()
        if (!VerificationManager.isVerifiedCached(userId)) {
            val lastCheck = appHomeCheckCooldown[userId]
            if (lastCheck == null || (now - lastCheck) >= 30.seconds) {
                appHomeCheckCooldown[userId] = now
                val isVerified = VerificationManager.isVerified(userId, ignoreCache = true)
                if (isVerified) {
                    VerificationManager.handleUserBecameVerified(userId)
                }
            }
        }
        publishAppHome(userId)
    }

    suspend fun setHomeView(userId: String, view: HomeView, updateHome: Boolean = true) {
        if (view == HomeView.ADMIN && !SlackInteractionHandler.isAdmin(userId)) return
        homeViews[userId] = view
        if (updateHome) {
            publishAppHome(userId)
        }
    }

    private fun resolveHomeView(userId: String, isAdmin: Boolean, isTeam: Boolean): HomeView {
        val stored = homeViews[userId]
        val allowed = when (stored) {
            HomeView.TEAM -> isTeam
            HomeView.ADMIN -> isAdmin
            HomeView.INFO -> isScopeInfoScreenEnabled()
            HomeView.REQUESTS -> true
            null -> false
        }
        if (stored != null && allowed) return stored
        return if (isTeam) HomeView.TEAM else HomeView.REQUESTS
    }

    suspend fun publishAppHome(userId: String) {
        val isAdmin = SlackInteractionHandler.isAdmin(userId)
        val isTeam = InstallRequestRepository.isTeamMemberAuthorized(userId)

        when (resolveHomeView(userId, isAdmin, isTeam)) {
            HomeView.TEAM -> publishTeamHome(userId, isAdmin, isTeam)
            HomeView.REQUESTS -> publishRequestsHome(userId, isAdmin, isTeam)
            HomeView.ADMIN -> publishAdminHome(userId, isAdmin, isTeam)
            HomeView.INFO -> publishInfoHome(userId, isAdmin, isTeam)
        }
    }

    private fun LayoutBlockDsl.homeNav(current: HomeView, isAdmin: Boolean, isTeam: Boolean) {
        val tabs = listOfNotNull(
            if (isTeam) HomeTab(HomeView.TEAM, "Review Queue", "home_team") else null,
            HomeTab(HomeView.REQUESTS, "My Requests", "home_requests"),
            if (isAdmin) HomeTab(HomeView.ADMIN, "Admin", "home_admin") else null,
            if (isScopeInfoScreenEnabled()) HomeTab(HomeView.INFO, "Scope Policy", "home_info") else null,
        )
        // Nothing to switch between
        if (tabs.size < 2) return

        actions {
            for (tab in tabs) {
                button {
                    text(tab.label, true)
                    actionId(tab.actionId)
                    value(tab.view.name.lowercase())
                    if (tab.view == current) style("primary")
                }
            }
        }
    }

    private suspend fun publishTeamHome(userId: String, isAdmin: Boolean, isTeam: Boolean) {
        val pendingRequests = InstallRequestRepository.getPendingRequests(limit = 10)
        val autoDenied = InstallRequestRepository.getAutoDeniedRequests(limit = 10)
        val recentResolved = InstallRequestRepository.getRecentResolvedRequests(limit = 8)

        SlackManager.publishHomeView(userId) {
            homeNav(HomeView.TEAM, isAdmin, isTeam)
            header {
                text("App Approval Dashboard", true)
            }
            section {
                markdownText(":wave: Welcome, <@$userId>. You can review and manage app installation requests here.")
            }
            divider()

            header {
                text("Pending Requests (${pendingRequests.size})", true)
            }
            if (pendingRequests.isEmpty()) {
                section {
                    markdownText(":tada: No pending installation requests to review!")
                }
            } else {
                for (req in pendingRequests) reviewEntry(req)
            }

            if (autoDenied.isNotEmpty()) {
                header {
                    text("Automatically Denied (${autoDenied.size})", true)
                }
                section {
                    markdownText("These requests were denied automatically and need no review. You can still override them.")
                }
                for (req in autoDenied) reviewEntry(req)
            }

            if (recentResolved.isNotEmpty()) {
                header {
                    text("Recent Decisions", true)
                }
                for (req in recentResolved) reviewEntry(req, false)
            }
        }
    }

    private suspend fun publishRequestsHome(userId: String, isAdmin: Boolean, isTeam: Boolean) {
        val userRequests = InstallRequestRepository.getUserRequests(userId, limit = 20)
        val isVerified = VerificationManager.isVerified(userId)

        SlackManager.publishHomeView(userId) {
            homeNav(HomeView.REQUESTS, isAdmin, isTeam)
            header {
                text("My App Installation Requests", true)
            }
            if (!isVerified) {
                section {
                    markdownText(":warning: *Your account is not verified.* <https://auth.hackclub.com|Verify here>")
                }
            }
            divider()

            if (userRequests.isEmpty()) {
                section {
                    markdownText("You have not requested any apps yet.")
                }
            } else {
                for (req in userRequests) {
                    val appUrl = req.appUrl
                    val appLink = if (!appUrl.isNullOrBlank()) "<$appUrl|${req.appName}>" else req.appName
                    val statusText = "${statusEmoji(req.status)} *${statusLabel(req.status, req.resolution)}*"
                    val reason = formatReason(req.automaticDecisionReason, req.resolutionMessage, req.resolvedBy)

                    section {
                        markdownText("*App:* $appLink (`${req.appId}`)\n*Status:* $statusText\n*Details:* $reason")
                    }

                    if (req.status == RequestStatus.PENDING_REVIEW || (req.status == RequestStatus.DENIED && req.resolution == null)) {
                        actions {
                            if (!isVerified) {
                                button {
                                    text("Check Verification", true)
                                    actionId("user_check_verification")
                                    value(req.requestId)
                                    style("primary")
                                }
                            }
                            if (req.status != RequestStatus.PENDING_REVIEW) {
                                button {
                                    text("Request Manual Review", true)
                                    actionId("user_request_review")
                                    value(req.requestId)
                                }
                            }
                            button {
                                text("Withdraw Request", true)
                                actionId("user_withdraw_request")
                                value(req.requestId)
                                style("danger")
                            }
                        }
                    }
                    divider()
                }
            }
        }
    }

    private suspend fun publishInfoHome(userId: String, isAdmin: Boolean, isTeam: Boolean) {
        val isVerified = VerificationManager.isVerified(userId)
        val rules = InstallRequestRepository.getScopeRules()
            .filter { it.enabled }
            .sortedWith(ScopeRulePrecedenceComparator)

        val groups = listOf(
            Triple(
                RuleAction.ALLOW,
                ":white_check_mark: Allowed",
                "Apps that only request these scopes are approved automatically."
            ),
            Triple(
                RuleAction.REVIEW,
                ":hourglass_flowing_sand: Needs manual review",
                "Apps requesting these scopes are sent to the approval team."
            ),
            Triple(
                RuleAction.BLOCK,
                ":no_entry_sign: Declined",
                "Apps requesting these scopes are declined automatically. You can still ask for a manual review."
            ),
        )

        SlackManager.publishHomeView(userId) {
            homeNav(HomeView.INFO, isAdmin, isTeam)
            header {
                text("Scope Policy", true)
            }
            section {
                markdownText(
                    "Every installation request is checked against the rules below before a person looks at it.\n\n" +
                            if (isVerified) "*Your account:* verified"
                            else "*Your account:* not verified (<https://auth.hackclub.com|verify here>). " +
                                    "Rules marked _unverified users_ or _everyone_ are the ones that apply to you."
                )
            }
            context {
                markdownText(
                    "If several rules match a scope, the most specific one wins. " +
                            "Scopes without a matching rule are approved for verified users; unverified users need a manual review."
                )
            }
            val catchAll =
                rules.filter { it.pattern == "*" && it.scopeType == ScopeType.BOTH && it.userTarget == RuleUserTarget.ALL }
                    .sortedByDescending { it.action }
            if (catchAll.isNotEmpty()) {
                val action = catchAll.first().action
                section {
                    markdownText(
                        "There is a catch-all rule. That means if none of the other rules match, " + when (action) {
                            RuleAction.ALLOW -> "it will be approved."
                            RuleAction.REVIEW -> "it will need a manual review."
                            RuleAction.BLOCK -> "it will be blocked."
                        }
                    )
                }
            }
            divider()

            for ((action, title, explanation) in groups) {
                val groupRules = rules.filter { it.action == action }
                header {
                    text(
                        title.removePrefix(":white_check_mark: ")
                            .removePrefix(":hourglass_flowing_sand: ")
                            .removePrefix(":no_entry_sign: "), true
                    )
                }
                context {
                    markdownText(explanation)
                }
                if (groupRules.isEmpty()) {
                    section {
                        markdownText("_No rules._")
                    }
                } else {
                    val lines = groupRules.map { rule ->
                        "$BULLET `${rule.pattern}`  ${targetLabel(rule.userTarget)}, ${typeLabel(rule.scopeType)}" +
                                if (rule.pattern == "*" && rule.userTarget == RuleUserTarget.ALL && rule.scopeType == ScopeType.BOTH) " (this is the catch all rule)" else ""
                    }
                    for (chunk in chunkLines(lines)) {
                        section {
                            markdownText(chunk)
                        }
                    }
                }
                divider()
            }
        }
    }

    private suspend fun publishAdminHome(userId: String, isAdmin: Boolean, isTeam: Boolean) {
        val members = InstallRequestRepository.getTeamMembers()
        val rules = InstallRequestRepository.getScopeRules()
        val sortedRules = rules.sortedWith(ScopeRulePrecedenceComparator)
        val test = scopeTests[userId]

        val membersText =
            if (members.isEmpty()) "_No team members yet._" else members.joinToString(", ") { "<@$it>" }
        val enabledCount = rules.count { it.enabled }

        SlackManager.publishHomeView(userId) {
            homeNav(HomeView.ADMIN, isAdmin, isTeam)
            header {
                text("Admin Dashboard", true)
            }
            section {
                markdownText("*Team members (${members.size})*\n${membersText.take(2900)}")
            }
            divider()

            header {
                text("Scope Rules", true)
            }
            context {
                markdownText(
                    "$enabledCount of ${rules.size} rules enabled. Rules are listed from most to least specific; " +
                            "when several rules match a scope, the first one wins."
                )
            }
            actions {
                button {
                    text("Add Rule", true)
                    actionId("admin_add_scope_rule")
                    value("add")
                    style("primary")
                }
                button {
                    text(if (test == null) "Test a Scope" else "Test Another Scope", true)
                    actionId("admin_test_scope")
                    value("test")
                }
            }

            if (test != null) {
                scopeTestPanel(test, rules)
            }

            if (rules.isEmpty()) {
                section {
                    markdownText("*No scope rules configured yet.* Use *Add Rule* to create the first one.")
                }
            } else {
                for ((index, rule) in sortedRules.take(MAX_ADMIN_RULES_SHOWN).withIndex()) {
                    if (index != 0 || test == null) divider()
                    adminRuleEntry(rule)
                }
                if (sortedRules.size > MAX_ADMIN_RULES_SHOWN) {
                    context {
                        markdownText("Showing the first $MAX_ADMIN_RULES_SHOWN of ${sortedRules.size} rules.")
                    }
                }
            }
        }
    }

    private fun LayoutBlockDsl.adminRuleEntry(rule: ScopeRule) {
        val title =
            "${actionEmoji(rule.action)} *${actionLabel(rule.action)}*  `${rule.pattern}`" + if (rule.enabled) "" else " (_disabled_)"
        val details = "${targetLabel(rule.userTarget)} $BULLET ${typeLabel(rule.scopeType)} $BULLET #${rule.id}"
        val description = rule.description?.takeIf { it.isNotBlank() }?.let { "\n_${it.take(150)}_" } ?: ""

        section {
            markdownText("$title\n$details$description")
            accessory {
                overflowMenu {
                    actionId("admin_rule_menu")
                    options {
                        option {
                            plainText("Edit", true)
                            value("edit:${rule.id}")
                        }
                        option {
                            plainText(if (rule.enabled) "Disable" else "Enable", true)
                            value("toggle:${rule.id}")
                        }
                        option {
                            plainText("Delete", true)
                            value("delete:${rule.id}")
                        }
                    }
                }
            }
        }
    }

    private enum class RuleState { APPLIED, OVERRIDDEN, INACTIVE }

    private data class RuleEvaluation(val rule: ScopeRule, val state: RuleState, val note: String)

    private fun evaluateScope(
        rules: List<ScopeRule>,
        scope: String,
        tokenType: String,
        verified: Boolean
    ): Pair<ScopeRule?, List<RuleEvaluation>> {
        val sorted = rules.sortedWith(ScopeRulePrecedenceComparator)
        val matching = sorted.filter { it.matches(scope, tokenType, verified) }
        val winner = matching.firstOrNull()

        val evaluations = sorted.map { rule ->
            when {
                rule === winner -> RuleEvaluation(rule, RuleState.APPLIED, "highest precedence of all matching rules")
                matching.any { it === rule } ->
                    RuleEvaluation(rule, RuleState.OVERRIDDEN, "matches, but rule #${winner?.id} takes precedence")

                else -> RuleEvaluation(rule, RuleState.INACTIVE, inactiveReason(rule, scope, tokenType, verified))
            }
        }
        return winner to evaluations
    }

    private fun inactiveReason(rule: ScopeRule, scope: String, tokenType: String, verified: Boolean): String = when {
        !rule.enabled -> "rule is disabled"
        rule.scopeType == ScopeType.USER && !tokenType.equals(
            "user",
            ignoreCase = true
        ) -> "only applies to user scopes"

        rule.scopeType == ScopeType.BOT && !tokenType.equals("bot", ignoreCase = true) -> "only applies to bot scopes"
        rule.userTarget == RuleUserTarget.VERIFIED && !verified -> "only applies to verified users"
        rule.userTarget == RuleUserTarget.UNVERIFIED && verified -> "only applies to unverified users"
        !rule.matcher.match(scope.trim()) -> "pattern does not match"
        else -> "does not apply"
    }

    private fun ruleSummary(rule: ScopeRule): String =
        "#${rule.id} `${rule.pattern}` $BULLET ${actionLabel(rule.action)} $BULLET ${targetLabel(rule.userTarget)} $BULLET ${
            typeLabel(
                rule.scopeType
            )
        }"

    private fun LayoutBlockDsl.scopeTestPanel(test: ScopeTestInput, rules: List<ScopeRule>) {
        val (winner, evaluations) = evaluateScope(rules, test.scope, test.tokenType, test.verified)
        val personText = if (test.verified) "a *verified* user" else "an *unverified* user"
        val tokenText = if (test.tokenType.equals("user", true)) "user" else "bot"

        val outcome = when {
            winner != null -> when (winner.action) {
                RuleAction.ALLOW -> ":white_check_mark: *Allowed.* The scope is approved automatically."
                RuleAction.REVIEW -> ":hourglass_flowing_sand: *Manual review.* The request is sent to the approval team."
                RuleAction.BLOCK -> ":no_entry_sign: *Blocked.* The request is declined automatically."
            }

            test.verified ->
                ":white_check_mark: *Allowed (no rule).* Verified users may use scopes that no rule matches."

            else ->
                ":no_entry_sign: *Declined (no rule).* No rule explicitly allows this scope for unverified users."
        }

        divider()
        section {
            markdownText("*Scope test*\n`${test.scope}` as $tokenText scope, requested by $personText.")
        }
        section {
            markdownText(outcome)
        }

        val applied = evaluations.firstOrNull { it.state == RuleState.APPLIED }
        if (applied != null) {
            section {
                markdownText("*Applied rule*\n${ruleSummary(applied.rule)}")
            }
        }

        val overridden = evaluations.filter { it.state == RuleState.OVERRIDDEN }
        if (overridden.isNotEmpty()) {
            context {
                markdownText("*Also matching, but overridden*")
            }
            for (chunk in chunkLines(overridden.map { "${ruleSummary(it.rule)} (${it.note})" })) {
                context {
                    markdownText(chunk)
                }
            }
        }

        val inactive = evaluations.filter { it.state == RuleState.INACTIVE }
        if (inactive.isNotEmpty()) {
            context {
                markdownText("*Not applicable (${inactive.size})*")
            }
            val lines = inactive.take(MAX_TEST_INACTIVE_SHOWN).map { "${ruleSummary(it.rule)} (${it.note})" }
            for (chunk in chunkLines(lines)) {
                context {
                    markdownText(chunk)
                }
            }
            if (inactive.size > MAX_TEST_INACTIVE_SHOWN) {
                context {
                    markdownText("… and ${inactive.size - MAX_TEST_INACTIVE_SHOWN} more.")
                }
            }
        }

        context {
            markdownText(
                "This test only evaluates scope rules. Restricted apps, org-wide installs, command conflicts " +
                        "and previously approved or denied requests are checked separately."
            )
        }
        actions {
            button {
                text("Clear Test", true)
                actionId("admin_test_clear")
                value("clear")
            }
        }
        divider()
    }

    suspend fun openScopeTestModal(userId: String, triggerId: String) {
        if (!SlackInteractionHandler.isAdmin(userId)) {
            openUnauthorizedMessage(triggerId)
            return
        }
        val previous = scopeTests[userId]
        val modal = buildScopeTestModal(
            initialScope = previous?.scope,
            initialTokenType = previous?.tokenType ?: "bot",
            initialVerified = previous?.verified ?: true
        )
        SlackManager.openView(triggerId, modal)
    }

    suspend fun submitScopeTest(userId: String, scope: String, tokenType: String, userKind: String) {
        if (!SlackInteractionHandler.isAdmin(userId)) return
        val trimmed = scope.trim()
        if (trimmed.isEmpty()) return
        scopeTests[userId] = ScopeTestInput(
            scope = trimmed,
            tokenType = if (tokenType.equals("USER", ignoreCase = true)) "user" else "bot",
            verified = !userKind.equals("UNVERIFIED", ignoreCase = true)
        )
        homeViews[userId] = HomeView.ADMIN
        publishAppHome(userId)
    }

    fun clearScopeTest(userId: String) {
        scopeTests.remove(userId)
    }

    private fun actionLabel(action: RuleAction): String = when (action) {
        RuleAction.ALLOW -> "Allow"
        RuleAction.REVIEW -> "Review"
        RuleAction.BLOCK -> "Block"
    }

    private fun actionEmoji(action: RuleAction): String = when (action) {
        RuleAction.ALLOW -> ":white_check_mark:"
        RuleAction.REVIEW -> ":hourglass_flowing_sand:"
        RuleAction.BLOCK -> ":no_entry_sign:"
    }

    private fun targetLabel(target: RuleUserTarget): String = when (target) {
        RuleUserTarget.ALL -> "everyone"
        RuleUserTarget.VERIFIED -> "verified users"
        RuleUserTarget.UNVERIFIED -> "unverified users"
    }

    private fun typeLabel(type: ScopeType): String = when (type) {
        ScopeType.BOTH -> "bot & user scopes"
        ScopeType.BOT -> "bot scopes"
        ScopeType.USER -> "user scopes"
    }

    private fun chunkLines(lines: List<String>, maxChars: Int = 2800): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            val safeLine = line.take(maxChars)
            if (current.isNotEmpty() && current.length + safeLine.length + 1 > maxChars) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(safeLine)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    fun buildInfoModal(title: String, message: String): JsonObject {
        val view = JsonObject()
        view.addProperty("type", "modal")

        val titleObj = JsonObject()
        titleObj.addProperty("type", "plain_text")
        titleObj.addProperty("text", title.take(24))
        titleObj.addProperty("emoji", true)
        view.add("title", titleObj)

        val closeObj = JsonObject()
        closeObj.addProperty("type", "plain_text")
        closeObj.addProperty("text", "OK")
        closeObj.addProperty("emoji", true)
        view.add("close", closeObj)

        val blocks = JsonArray()

        val section = JsonObject()
        section.addProperty("type", "section")

        val text = JsonObject()
        text.addProperty("type", "mrkdwn")
        text.addProperty("text", message)
        section.add("text", text)
        blocks.add(section)

        view.add("blocks", blocks)

        return view
    }

    fun buildAreYouSureModal(
        title: String,
        callbackId: String,
        message: String,
        metadata: String,
        submitButton: String = "I am sure",
        cancelButton: String = "Cancel"
    ): JsonObject {
        val view = JsonObject()
        view.addProperty("type", "modal")
        view.addProperty("callback_id", callbackId)
        view.addProperty("private_metadata", metadata)

        val titleObj = JsonObject()
        titleObj.addProperty("type", "plain_text")
        titleObj.addProperty("text", title.take(24))
        titleObj.addProperty("emoji", true)
        view.add("title", titleObj)

        val submitObj = JsonObject()
        submitObj.addProperty("type", "plain_text")
        submitObj.addProperty("text", submitButton.take(24))
        submitObj.addProperty("emoji", true)
        view.add("submit", submitObj)

        val closeObj = JsonObject()
        closeObj.addProperty("type", "plain_text")
        closeObj.addProperty("text", cancelButton.take(24))
        closeObj.addProperty("emoji", true)
        view.add("close", closeObj)

        val blocks = JsonArray()

        val section = JsonObject()
        section.addProperty("type", "section")

        val text = JsonObject()
        text.addProperty("type", "mrkdwn")
        text.addProperty("text", message)
        section.add("text", text)
        blocks.add(section)

        view.add("blocks", blocks)

        return view
    }

    fun buildReasonModal(
        title: String,
        callbackId: String,
        requestId: String,
        submitLabel: String = "Submit"
    ): JsonObject {
        val view = JsonObject()
        view.addProperty("type", "modal")
        view.addProperty("callback_id", callbackId)
        view.addProperty("private_metadata", requestId)

        val titleObj = JsonObject()
        titleObj.addProperty("type", "plain_text")
        titleObj.addProperty("text", title.take(24))
        titleObj.addProperty("emoji", true)
        view.add("title", titleObj)

        val submitObj = JsonObject()
        submitObj.addProperty("type", "plain_text")
        submitObj.addProperty("text", submitLabel.take(24))
        submitObj.addProperty("emoji", true)
        view.add("submit", submitObj)

        val closeObj = JsonObject()
        closeObj.addProperty("type", "plain_text")
        closeObj.addProperty("text", "Cancel")
        closeObj.addProperty("emoji", true)
        view.add("close", closeObj)

        val blocks = JsonArray()
        val inputBlock = JsonObject()
        inputBlock.addProperty("type", "input")
        inputBlock.addProperty("block_id", "reason_block")
        inputBlock.addProperty("optional", true)

        val labelObj = JsonObject()
        labelObj.addProperty("type", "plain_text")
        labelObj.addProperty("text", "Reason / Notes (Optional)")
        labelObj.addProperty("emoji", true)
        inputBlock.add("label", labelObj)

        val elementObj = JsonObject()
        elementObj.addProperty("type", "plain_text_input")
        elementObj.addProperty("action_id", "reason_input")
        elementObj.addProperty("multiline", true)

        val placeholderObj = JsonObject()
        placeholderObj.addProperty("type", "plain_text")
        placeholderObj.addProperty(
            "text",
            "Provide an explanation for this decision..."
        )
        elementObj.add("placeholder", placeholderObj)

        inputBlock.add("element", elementObj)
        blocks.add(inputBlock)
        view.add("blocks", blocks)

        return view
    }

    private fun plainText(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "plain_text")
        addProperty("text", text)
        addProperty("emoji", true)
    }

    private fun optionObject(label: String, value: String): JsonObject = JsonObject().apply {
        add("text", plainText(label))
        addProperty("value", value)
    }

    private fun textInputElement(actionId: String, placeholder: String, initial: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", "plain_text_input")
            addProperty("action_id", actionId)
            add("placeholder", plainText(placeholder))
            if (!initial.isNullOrBlank()) addProperty("initial_value", initial)
        }

    private fun selectElement(actionId: String, options: List<Pair<String, String>>, selected: String): JsonObject =
        JsonObject().apply {
            addProperty("type", "static_select")
            addProperty("action_id", actionId)
            val array = JsonArray()
            for ((label, value) in options) array.add(optionObject(label, value))
            add("options", array)
            val initial = options.firstOrNull { it.second == selected } ?: options.first()
            add("initial_option", optionObject(initial.first, initial.second))
        }

    private fun inputBlock(
        blockId: String,
        label: String,
        element: JsonObject,
        optional: Boolean = false,
        hint: String? = null
    ): JsonObject = JsonObject().apply {
        addProperty("type", "input")
        addProperty("block_id", blockId)
        if (optional) addProperty("optional", true)
        add("label", plainText(label))
        if (hint != null) add("hint", plainText(hint))
        add("element", element)
    }

    private fun contextBlock(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "context")
        val elements = JsonArray()
        elements.add(JsonObject().apply {
            addProperty("type", "mrkdwn")
            addProperty("text", text)
        })
        add("elements", elements)
    }

    private fun modalBase(
        title: String,
        callbackId: String,
        metadata: String,
        submit: String,
        blocks: List<JsonObject>
    ): JsonObject = JsonObject().apply {
        addProperty("type", "modal")
        addProperty("callback_id", callbackId)
        addProperty("private_metadata", metadata)
        add("title", plainText(title.take(24)))
        add("submit", plainText(submit.take(24)))
        add("close", plainText("Cancel"))
        val array = JsonArray()
        blocks.forEach { array.add(it) }
        add("blocks", array)
    }

    private val actionOptions = listOf(
        "Allow: approve automatically" to RuleAction.ALLOW.name,
        "Review: require manual review" to RuleAction.REVIEW.name,
        "Block: decline automatically" to RuleAction.BLOCK.name,
    )

    private val targetOptions = listOf(
        "Everyone" to RuleUserTarget.ALL.name,
        "Verified users only" to RuleUserTarget.VERIFIED.name,
        "Unverified users only" to RuleUserTarget.UNVERIFIED.name,
    )

    private val scopeTypeOptions = listOf(
        "Bot and user scopes" to ScopeType.BOTH.name,
        "Bot scopes only" to ScopeType.BOT.name,
        "User scopes only" to ScopeType.USER.name,
    )

    fun buildScopeRuleModal(
        title: String,
        callbackId: String,
        rule: ScopeRule? = null
    ): JsonObject {
        val blocks = listOf(
            inputBlock(
                blockId = "pattern_block",
                label = "Scope pattern",
                element = textInputElement(
                    actionId = "pattern_input",
                    placeholder = "e.g. chat:write, admin:*, *, /^users:.*/",
                    initial = rule?.pattern
                ),
                hint = "Exact scope name, a * wildcard, or a regular expression wrapped in slashes."
            ),
            inputBlock(
                blockId = "action_block",
                label = "What should happen?",
                element = selectElement("action_input", actionOptions, (rule?.action ?: RuleAction.ALLOW).name)
            ),
            inputBlock(
                blockId = "target_block",
                label = "Applies to",
                element = selectElement("target_input", targetOptions, (rule?.userTarget ?: RuleUserTarget.ALL).name)
            ),
            inputBlock(
                blockId = "scope_type_block",
                label = "Scope type",
                element = selectElement("scope_type_input", scopeTypeOptions, (rule?.scopeType ?: ScopeType.BOTH).name)
            ),
            inputBlock(
                blockId = "description_block",
                label = "Note (optional)",
                element = textInputElement(
                    actionId = "description_input",
                    placeholder = "e.g. Security policy for admin scopes",
                    initial = rule?.description
                ),
                optional = true
            ),
            contextBlock("If several rules match a scope, the most specific one wins. Use *Test a Scope* on the admin page to check the outcome.")
        )

        return modalBase(
            title = title,
            callbackId = callbackId,
            metadata = rule?.id?.toString() ?: "",
            submit = if (rule == null) "Create Rule" else "Save Changes",
            blocks = blocks
        )
    }

    fun buildScopeTestModal(
        initialScope: String? = null,
        initialTokenType: String = "bot",
        initialVerified: Boolean = true
    ): JsonObject {
        val blocks = listOf(
            inputBlock(
                blockId = "test_scope_block",
                label = "Scope",
                element = textInputElement("test_scope_input", "e.g. chat:write", initialScope)
            ),
            inputBlock(
                blockId = "test_type_block",
                label = "Token type",
                element = selectElement(
                    "test_type_input",
                    listOf("Bot scope" to "BOT", "User scope" to "USER"),
                    if (initialTokenType.equals("user", true)) "USER" else "BOT"
                )
            ),
            inputBlock(
                blockId = "test_user_block",
                label = "Requested by",
                element = selectElement(
                    "test_user_input",
                    listOf("Verified user" to "VERIFIED", "Unverified user" to "UNVERIFIED"),
                    if (initialVerified) "VERIFIED" else "UNVERIFIED"
                )
            ),
            contextBlock("Shows which rule decides the outcome and why every other rule does not apply.")
        )

        return modalBase(
            title = "Test a Scope",
            callbackId = "modal_scope_test_submit",
            metadata = "",
            submit = "Run Test",
            blocks = blocks
        )
    }

    private fun ActionsBlockBuilder.reviewButtons(
        buttonValue: String,
        status: RequestStatus,
        resolution: RequestDecision?,
        reason: RequestDecisionReason?
    ) {
        when {
            status == RequestStatus.DENIED && resolution == null -> {
                button {
                    text("Override (Approve)", true)
                    actionId("review_approve")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.UNDONE || reason == RequestDecisionReason.RESOLUTION_UNDONE -> {
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.RESTRICTED -> {
                button {
                    text("Undo", true)
                    actionId("review_undo")
                    value(buttonValue)
                    style("primary")
                }
            }

            resolution == RequestDecision.WITHDRAWN -> {
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            resolution == RequestDecision.APPROVED ||
                    status == RequestStatus.APPROVED ||
                    resolution == RequestDecision.DENIED -> {
                button {
                    text("Undo", true)
                    actionId("review_undo")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }

            else -> {
                button {
                    text("Approve", true)
                    actionId("review_approve")
                    value(buttonValue)
                    style("primary")
                }
                button {
                    text("Deny", true)
                    actionId("review_deny")
                    value(buttonValue)
                    style("danger")
                }
                button {
                    text("Restrict", true)
                    actionId("review_restrict")
                    value(buttonValue)
                    style("danger")
                }
            }
        }
    }

    private fun LayoutBlockDsl.reviewEntry(req: InstallRequestRecord, showButtons: Boolean = true) {
        val appUrl = req.appUrl
        val appLink = if (!appUrl.isNullOrBlank()) "<$appUrl|${req.appName}>" else req.appName
        val statusText = "${statusEmoji(req.status)} *${statusLabel(req.status, req.resolution)}*"
        val reviewLink = if (!req.reviewMessageTs.isNullOrBlank()) {
            " - <https://slack.com/archives/${Config.SLACK_CHANNEL_REVIEW}/p${
                req.reviewMessageTs.replace(
                    ".",
                    ""
                )
            }|View Thread>"
        } else ""

        section {
            markdownText(
                "*App:* $appLink (`${req.appId}`)\n*User:* <@${req.userId}>\n" +
                        "*Status:* $statusText$reviewLink\n" +
                        "*Reason:* ${formatReason(req.automaticDecisionReason, req.resolutionMessage, req.resolvedBy)}"
            )
        }
        if (showButtons) {
            actions {
                reviewButtons(req.stateId?.let { "${req.requestId}:$it" } ?: req.requestId,
                    req.status,
                    req.resolution,
                    null)
            }
        }
        divider()
    }
}