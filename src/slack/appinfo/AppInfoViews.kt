package com.fantamomo.slack.approver.slack.appinfo

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants
import com.fantamomo.slack.approver.data.Constants.BULLET
import com.fantamomo.slack.approver.data.Constants.MARKETPLACE_URL
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.SettingsManager
import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.fantamomo.slack.approver.utils.blockkit.TagColor
import com.fantamomo.slack.approver.utils.blockkit.TextStyle
import com.fantamomo.slack.approver.utils.blockkit.addMarkdown
import com.fantamomo.slack.approver.utils.blockkit.tag
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.element.RichTextSectionElement
import com.slack.api.model.kotlin_extension.block.ActionsBlockBuilder
import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.model.kotlin_extension.block.element.ListStyle
import com.slack.api.model.kotlin_extension.block.element.RichTextSectionElementBuilder
import com.slack.api.model.kotlin_extension.block.withBlocks
import java.time.ZoneOffset
import java.time.Instant as JavaInstant

// who is looking at the modal, decides which buttons are shown
data class AppInfoViewer(val userId: String, val isAdmin: Boolean, val isTeam: Boolean)

// a short message shown at the top of the modal, e.g. the result of an admin action
data class AppInfoNotice(val text: String, val success: Boolean)

// everything a section needs to render itself
class AppInfoContext(
    val details: AppDetails,
    val viewer: AppInfoViewer,
    val notice: AppInfoNotice?,
)

// one part of the app info modal.
// To show more information, implement this interface and add it to AppInfoViews.SECTIONS.
// Long lists should not be rendered inline, add an AppInfoDetail instead and show its button (see detailButtons).
fun interface AppInfoSection {
    fun LayoutBlockDsl.render(ctx: AppInfoContext)
}

// the actions an admin can perform in the info modal
enum class AppAdminAction(val title: String, val submit: String) {
    APPROVE("Approve App", "Approve"),
    RESTRICT("Restrict App", "Restrict"),
    UNINSTALL("Uninstall App", "Uninstall"),
    PROTECT("Protect App", "Protect"),
    UNPROTECT("Unprotect App", "Unprotect"),
}

// one entry of a detail list, rendered as one bullet point
typealias AppInfoEntry = RichTextSectionElementBuilder.() -> Unit

// a list which is too long for the main modal, it is shown in its own (paginated) modal when the button is clicked.
// To add a new list, add an entry here and show its button in a section with detailButtons(...).
enum class AppInfoDetail(val title: String, val buttonLabel: String) {
    INSTALLERS("Installed by", "Installed by") {
        override fun items(d: AppDetails): List<AppInfoEntry> = d.marketplace?.authorizations.orEmpty().map { auth ->
            {
                if (auth.userId != null) user(auth.userId) else text(auth.userDisplayName ?: "unknown user")
                auth.date?.let {
                    text(" on ")
                    date(it)
                }
                auth.description?.let { text("\n${it.take(200)}", style = TextStyle(italic = true)) }
            }
        }
    },
    REQUESTS("Requests", "Requests") {
        override fun items(d: AppDetails): List<AppInfoEntry> = d.requests.map { req ->
            {
                text(req.requestId, style = TextStyle(code = true))
                text(" ")
                statusTag(req)
                text(" by ")
                user(req.userId)
                text(" on ")
                date(req.requestedAt)
                if (req.teamId == null && req.enterpriseId != null) {
                    text(" ")
                    tag("org-wide", TagColor.ORANGE)
                }
                if (req.requestId == d.focusRequestId) {
                    text(" ")
                    tag("looked up", TagColor.PURPLE)
                }
                req.resolvedBy?.takeIf { it.isUserId() }?.let {
                    text("\nResolved by ")
                    user(it)
                }
                req.reviewMessageTs?.let {
                    text(" - ")
                    link(threadUrl(req.reviewChannelId, it), "View Thread")
                }
            }
        }

        override fun footer(d: AppDetails): String? =
            if (d.totalRequestCount > d.requests.size) "Only the newest ${d.requests.size} of ${d.totalRequestCount} requests are shown." else null
    },
    CHANNELS("Channels", "Channels") {
        override fun items(d: AppDetails): List<AppInfoEntry> =
            d.marketplace?.channelIds.orEmpty().map { id -> { channel(id) } }

        override fun footer(d: AppDetails): String? {
            val other = d.marketplace?.info?.otherChannelCount ?: return null
            return if (other > 0) "The bot is also in $other channels which are not visible to us (private channels or DMs)." else null
        }
    },
    SCOPES("Scopes", "Scopes") {
        override fun items(d: AppDetails): List<AppInfoEntry> {
            val current = d.currentScopes.sortedWith(compareBy({ it.tokenType }, { it.name }))
            val currentKeys = current.map { it.tokenType.lowercase() to it.name.lowercase() }.toSet()
            val old = d.allRequestedScopes.filter { (it.tokenType.lowercase() to it.name.lowercase()) !in currentKeys }
            return current.map { scopeEntry(d, it, old = false) } + old.map { scopeEntry(d, it, old = true) }
        }

        override fun footer(d: AppDetails) =
            "Scopes of the newest request, evaluated against the current scope rules. User rules are not included."
    },
    COMMANDS("Slash Commands", "Commands") {
        override fun items(d: AppDetails): List<AppInfoEntry> {
            val known = d.marketplace?.commands.orEmpty().associateBy { it.name }
            val names = (known.keys + d.indexedCommands.keys).distinct().sorted()
            return names.map { name ->
                val command = known[name]
                val entry: AppInfoEntry = {
                    text(name, style = TextStyle(code = true))
                    command?.usageHint?.takeIf { it.isNotBlank() }?.let { text(" ${it.take(100)}") }
                    when (d.indexedCommands[name]) {
                        true -> {
                            text(" ")
                            tag("active", TagColor.GREEN)
                        }

                        false -> {
                            text(" ")
                            tag("not active", TagColor.BROWN)
                        }

                        null -> {}
                    }
                    command?.description?.takeIf { it.isNotBlank() }?.let { text("\n${it.take(200)}") }
                    command?.url?.let {
                        text("\nRequest URL: ", style = TextStyle(bold = true))
                        text(it.take(300), style = TextStyle(code = true))
                    }
                }
                entry
            }
        }

        override fun footer(d: AppDetails) =
            "Not active means another app owns the command or the command was removed."
    },
    SHORTCUTS("Shortcuts", "Shortcuts") {
        override fun items(d: AppDetails): List<AppInfoEntry> {
            val market = d.marketplace ?: return emptyList()
            fun entry(kind: String, color: TagColor, shortcut: AppShortcut): AppInfoEntry = {
                tag(kind, color)
                text(" ")
                text(shortcut.name, style = TextStyle(bold = true))
                shortcut.callbackId?.let {
                    text(" (callback ")
                    text(it, style = TextStyle(code = true))
                    text(")")
                }
                if (shortcut.description.isNotBlank()) text("\n${shortcut.description.take(200)}")
            }
            return market.globalShortcuts.map { entry("global", TagColor.BLUE, it) } +
                    market.messageShortcuts.map { entry("message", TagColor.PURPLE, it) }
        }
    },
    MCP_SERVERS("MCP Servers", "MCP Servers") {
        override fun items(d: AppDetails): List<AppInfoEntry> = d.marketplace?.mcpServers.orEmpty().map { server ->
            {
                text(server.name, style = TextStyle(bold = true))
                text(" (${server.toolCount} tools)")
                server.url?.let {
                    text("\n")
                    text(it.take(300), style = TextStyle(code = true))
                }
            }
        }
    },
    PERMISSIONS("Permissions", "Permissions") {
        override fun items(d: AppDetails): List<AppInfoEntry> =
            d.marketplace?.permissions.orEmpty().map { permission -> { text(permission) } }

        override fun footer(d: AppDetails) = "What the app is allowed to do, as described by Slack."
    },
    URLS("URLs", "URLs") {
        override fun items(d: AppDetails): List<AppInfoEntry> = d.urls().map { (label, url) ->
            {
                text("$label: ", style = TextStyle(bold = true))
                if (url.startsWith("http://") || url.startsWith("https://")) link(url, url.take(200))
                else text(url.take(200), style = TextStyle(code = true))
                if (label.isCallbackUrlLabel() && isDangerous(url)) {
                    text(" ")
                    tag("protected domain", TagColor.RED)
                }
            }
        }
    };

    // every entry is one bullet point, they are paginated automatically
    abstract fun items(d: AppDetails): List<AppInfoEntry>

    open fun footer(d: AppDetails): String? = null

    fun count(d: AppDetails) = items(d).size
}

object AppInfoViews {
    // the user asked to never exceed 50 blocks in the info modal (slack allows 100 in modals, but 50 in messages)
    const val MAX_BLOCKS = 50

    // blocks which are always reserved for the footer
    private const val RESERVED_BLOCKS = 2

    private const val INLINE_PREVIEW = 5
    private const val ITEMS_PER_PAGE = 25

    const val CALLBACK_LOOKUP = "app_info_lookup_submit"
    const val CALLBACK_ACTION = "app_info_action_submit"
    const val ACTION_DETAIL = "app_info_detail"
    const val ACTION_PAGE = "app_info_page"
    const val ACTION_ADMIN = "app_info_admin"
    const val ACTION_LOOKUP = "home_app_lookup"

    // the sections of the main modal, in display order
    val SECTIONS: List<AppInfoSection> = listOf(
        AppInfoSection { notice(it) },
        AppInfoSection { overview(it) },
        AppInfoSection { focusRequest(it) },
        AppInfoSection { details(it) },
        AppInfoSection { capabilities(it) },
        AppInfoSection { adminActions(it) },
    )

    // ---- metadata (stored in private_metadata of the modals) ----

    data class Metadata(val appId: String, val requestId: String?) {
        fun encode() = "$appId|${requestId ?: ""}"

        companion object {
            fun decode(value: String): Metadata? {
                val parts = value.split('|')
                val appId = parts.getOrNull(0)?.takeIf { it.startsWith('A') } ?: return null
                return Metadata(appId, parts.getOrNull(1)?.ifBlank { null })
            }
        }
    }

    // ---- info ----

    fun isUninstallEnabled() = "true".equals(SettingsManager["modal_uninstall_enabled"], ignoreCase = true)
    fun isRestrictEnabled() = "true".equals(SettingsManager["modal_restrict_enabled"], ignoreCase = true)

    // ---- modals ----

    fun lookupModal(): JsonObject = modalFromJson(
        title = "Look up an App",
        callbackId = CALLBACK_LOOKUP,
        metadata = "",
        submit = "Look up",
        blocks = listOf(
            JsonObject().apply {
                addProperty("type", "input")
                addProperty("block_id", "lookup_block")
                add("label", plainText("App"))
                add(
                    "hint",
                    plainText("A bot mention (@MyApp), an app id (A…), bot id (B…), user id of the bot (U…), request id (Ar…), a marketplace link or the app name.")
                )
                add("element", JsonObject().apply {
                    addProperty("type", "plain_text_input")
                    addProperty("action_id", "lookup_input")
                    add("placeholder", plainText("e.g. A0123456789 or @MyApp"))
                })
            }
        ),
        close = "Cancel"
    )

    // the to-do list shown while the information is collected
    fun progressModal(target: String, states: Map<CollectStep, StepState>): JsonObject = modal(
        title = "App Info",
        blocks = blocks {
            richText {
                richTextSection {
                    text("Collecting everything we know about ")
                    if (target.startsWith('`') && target.endsWith('`')) {
                        val normalized = target.substring(1, target.length - 1)
                        text(normalized, style = TextStyle(bold = true))
                    } else if (target.startsWith('<') && target.endsWith('>') && target.contains('|')) {
                        val before = target.substring(0, target.indexOf('|'))
                        val after = target.substring(target.indexOf('|') + 1)
                        if (before.startsWith("@U")) {
                            user(before.substring(2))
                        } else {
                            text(after, style = TextStyle(bold = true))
                        }
                    } else if (target.startsWith('<') && target.endsWith('>')) {
                        text(target.substring(1, target.length - 1), style = TextStyle(code = true))
                    } else {
                        text(target, style = TextStyle(code = true))
                    }
//                    text(target.take(100), style = TextStyle(bold = true))
                    text(". Slack can take a few seconds, this list updates automatically.")
                }
                richTextList {
                    style(ListStyle.BULLET)
                    elements {
                        for (step in CollectStep.entries) {
                            val state = states[step] ?: StepState.WAITING
                            richTextSection {
                                text(step.label, style = TextStyle(bold = state == StepState.RUNNING))
                                text(" ")
                                when (state) {
                                    StepState.WAITING -> tag("waiting", TagColor.BLUE)
                                    StepState.RUNNING -> tag("in progress", TagColor.YELLOW)
                                    StepState.DONE -> tag("done", TagColor.GREEN)
                                    StepState.FAILED -> tag("failed", TagColor.RED)
                                    StepState.SKIPPED -> tag("skipped", TagColor.PURPLE)
                                }
                            }
                        }
                    }
                }
            }
        },
        close = "Close"
    )

    fun messageModal(title: String, message: String): JsonObject = modal(
        title = title,
        blocks = blocks { section { markdownText(message.take(3000)) } },
        close = "Close"
    )

    fun mainModal(details: AppDetails, viewer: AppInfoViewer, notice: AppInfoNotice? = null): JsonObject {
        val ctx = AppInfoContext(details, viewer, notice)
        val result = mutableListOf<LayoutBlock>()
        var skipped = 0
        for (section in SECTIONS) {
            val sectionBlocks = blocks { with(section) { render(ctx) } }
            if (result.size + sectionBlocks.size > MAX_BLOCKS - RESERVED_BLOCKS) {
                skipped++
                continue
            }
            result += sectionBlocks
        }
        result += blocks { footer(ctx, skipped) }.take(MAX_BLOCKS - result.size)

        return modal(
            title = "App Info",
            callbackId = "app_info_view",
            metadata = Metadata(details.appId, details.focusRequestId).encode(),
            blocks = result,
            close = "Close"
        )
    }

    fun detailModal(details: AppDetails, detail: AppInfoDetail, requestedPage: Int): JsonObject {
        val items = detail.items(details)
        val pageCount = ((items.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val pageItems = items.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)

        val blocks = blocks {
            richText {
                richTextSection {
                    text(detail.title, style = TextStyle(bold = true))
                    text(" of ")
                    text(details.name, style = TextStyle(bold = true))
                    text(" (${items.size})")
                }
                if (pageItems.isEmpty()) {
                    richTextSection { text("\nNothing to show.", style = TextStyle(italic = true)) }
                } else {
                    richTextList {
                        style(ListStyle.BULLET)
                        elements {
                            for (item in pageItems) richTextSection(item)
                        }
                    }
                }
            }
            val footer = listOfNotNull(
                detail.footer(details),
                if (pageCount > 1) "Page ${page + 1} of $pageCount" else null
            )
            if (footer.isNotEmpty()) context { markdownText(footer.joinToString(" $BULLET ")) }
            if (pageCount > 1) {
                actions {
                    if (page > 0) button {
                        text("Previous", true)
                        actionId("${ACTION_PAGE}_prev")
                        value("${detail.name}:${page - 1}")
                    }
                    if (page < pageCount - 1) button {
                        text("Next", true)
                        actionId("${ACTION_PAGE}_next")
                        value("${detail.name}:${page + 1}")
                        style("primary")
                    }
                }
            }
        }

        return modal(
            title = detail.title,
            callbackId = "app_info_detail_view",
            metadata = Metadata(details.appId, details.focusRequestId).encode(),
            blocks = blocks,
            close = "Back"
        )
    }

    fun actionModal(details: AppDetails, action: AppAdminAction, rootViewId: String): JsonObject {
        val request = details.actionableRequest
        val name = esc(details.name)
        val explanation = when (action) {
            AppAdminAction.APPROVE -> if (request != null) {
                "This approves the pending request `${request.requestId}` of <@${request.userId}> for *$name*."
            } else {
                "There is no pending request, so *$name* is approved for the *whole organisation*."
            } + if (details.restriction != null) "\nThe restriction of the app is removed." else ""

            AppAdminAction.RESTRICT -> if (request != null) {
                "This restricts *$name* and declines the pending request `${request.requestId}` of <@${request.userId}>. Future requests are declined automatically."
            } else {
                "This restricts *$name* for the whole organisation. Future requests are declined automatically."
            }

            AppAdminAction.UNINSTALL ->
                ":warning: This uninstalls *$name* from the *whole organisation*. Users have to request it again to use it."

            AppAdminAction.PROTECT ->
                "This protects *$name*: nobody can uninstall or restrict it anymore, and only admins can approve it." +
                        if (details.restriction != null) "\nThe app is currently restricted, protecting it does not remove the restriction." else ""

            AppAdminAction.UNPROTECT ->
                "This removes the protection of *$name*. Team members can then approve and restrict it again."
        }

        val reasonOptional = action != AppAdminAction.PROTECT
        val blocks =
            blocks { section { markdownText(explanation) } }.map { SharedData.gson.toJsonTree(it).asJsonObject } +
                    JsonObject().apply {
                        addProperty("type", "input")
                        addProperty("block_id", "reason_block")
                        addProperty("optional", reasonOptional)
                        add("label", plainText(if (reasonOptional) "Reason / Notes (optional)" else "Reason"))
                        add("element", JsonObject().apply {
                            addProperty("type", "plain_text_input")
                            addProperty("action_id", "reason_input")
                            addProperty("multiline", true)
                            addProperty("max_length", 2000)
                        })
                    }

        return modalFromJson(
            title = action.title,
            callbackId = CALLBACK_ACTION,
            metadata = "${action.name}|${details.appId}|${details.focusRequestId ?: ""}|$rootViewId",
            submit = action.submit,
            blocks = blocks,
            close = "Cancel"
        )
    }

    // ---- sections ----

    private fun LayoutBlockDsl.notice(ctx: AppInfoContext) {
        val notice = ctx.notice ?: return
        richText {
            richTextSection {
                if (notice.success) tag("Done", TagColor.GREEN) else tag("Failed", TagColor.RED)
                text(" ")
                addMarkdown(notice.text.take(500))
            }
        }
        divider()
    }

    // name, id, bot user, status and description (like the header of the review message)
    private fun LayoutBlockDsl.overview(ctx: AppInfoContext) {
        val d = ctx.details
        val market = d.marketplace
        val latest = d.latestRequest

        header { text(d.name.take(150), true) }
        richText {
            richTextSection {
                text("App: ", style = TextStyle(bold = true))
                link(d.appUrl ?: "$MARKETPLACE_URL${d.appId}", d.name)
                text(" (")
                link("$MARKETPLACE_URL${d.appId}", "marketplace")
                text(")")
                text("\nID: ", style = TextStyle(bold = true))
                text(d.appId, style = TextStyle(code = true))
                market?.info?.botUserId?.let {
                    text("\nBot: ", style = TextStyle(bold = true))
                    user(it)
                    market.botUsername?.let { name -> text(" (@$name)") }
                }
                text("\nStatus: ", style = TextStyle(bold = true))
                if (latest != null) statusTag(latest) else tag("Never requested", TagColor.RED)
                d.developerType?.let {
                    text("\nDeveloper Type: ", style = TextStyle(bold = true))
                    tag(it.value, developerTypeColor(it.value))
                }
            }
            d.description?.let { description ->
                richTextQuote { text(description.take(500)) }
            }
        }
    }

    // the request which was looked up
    private fun LayoutBlockDsl.focusRequest(ctx: AppInfoContext) {
        val req = ctx.details.focusRequest ?: return
        richText {
            richTextSection {
                text("\nRequest ", style = TextStyle(bold = true))
                text(req.requestId, style = TextStyle(code = true))
                req.reviewMessageTs?.let {
                    text(" (")
                    link(threadUrl(req.reviewChannelId, it), "View Thread")
                    text(")")
                }
                text(":", style = TextStyle(bold = true))
            }
            richTextList {
                style(ListStyle.BULLET)
                elements {
                    richTextSection {
                        text("Requested by: ", style = TextStyle(bold = true))
                        user(req.userId)
                        text(" on ")
                        date(req.requestedAt)
                        text(" (Verified: ")
                        if (req.altOwner != null) {
                            tag("alt account", TagColor.GREEN)
                            text(" of ")
                            user(req.altOwner)
                        } else {
                            tag("${req.userVerified}", if (req.userVerified) TagColor.GREEN else TagColor.RED)
                        }
                        text(", Collaborator: ")
                        tag(
                            "${req.isUserAppCollaborator}",
                            if (req.isUserAppCollaborator) TagColor.GREEN else TagColor.RED
                        )
                        text(")")
                    }
                    richTextSection {
                        text("Status: ", style = TextStyle(bold = true))
                        statusTag(req)
                        req.automaticDecisionReason?.let {
                            text(
                                " (initially ${
                                    it.name.lowercase().replace('_', ' ')
                                })"
                            )
                        }
                    }
                    req.resolvedBy?.takeIf { it.isUserId() }?.let {
                        richTextSection {
                            text("Resolved by: ", style = TextStyle(bold = true))
                            user(it)
                            req.resolutionMessage?.takeIf { msg -> msg.isNotBlank() }
                                ?.let { msg -> text(" (${msg.take(300)})") }
                        }
                    }
                }
            }
            req.message?.takeIf { it.isNotBlank() }?.let { message ->
                richTextQuote { text(message.take(500)) }
            }
        }
    }

    // status, restriction, protection and who installed / requested / approved the app
    private fun LayoutBlockDsl.details(ctx: AppInfoContext) {
        val d = ctx.details
        val info = d.marketplace?.info
        val latest = d.latestRequest
        val pending = d.requests.count { it.status == RequestStatus.PENDING_REVIEW }
        val installers = d.marketplace?.authorizations.orEmpty().mapNotNull { it.userId }

        richText {
            richTextSection { text("\nDetails:", style = TextStyle(bold = true)) }
            richTextList {
                style(ListStyle.BULLET)
                elements {
                    richTextSection {
                        text("Installed: ", style = TextStyle(bold = true))
                        when (info?.isInstalled) {
                            true -> {
                                tag("true", TagColor.GREEN)
                                info.dateInstalled?.let {
                                    text(" since ")
                                    date(it)
                                }
                            }

                            false -> tag("false", TagColor.RED)
                            null -> tag("unknown", TagColor.BROWN)
                        }
                    }
                    richTextSection {
                        text("Restricted: ", style = TextStyle(bold = true))
                        val restriction = d.restriction
                        if (restriction == null) {
                            tag("false", TagColor.GREEN)
                        } else {
                            tag("true", TagColor.RED)
                            text(" by ")
                            user(restriction.restrictedBy)
                            text(" on ")
                            date(restriction.restrictedAt.epochSeconds)
                            restriction.reason?.takeIf { it.isNotBlank() }?.let { text(" (${it.take(200)})") }
                        }
                    }
                    richTextSection {
                        text("Protected: ", style = TextStyle(bold = true))
                        val protection = d.protection
                        if (protection == null) {
                            tag("false", TagColor.RED)
                        } else {
                            tag("true", TagColor.BLUE)
                            text(" by ")
                            user(protection.protectedBy)
                            text(" on ")
                            date(protection.protectedAt.epochSeconds)
                            protection.reason?.takeIf { it.isNotBlank() }?.let { text(" (${it.take(200)})") }
                        }
                    }
                    richTextSection {
                        text("Requests: ", style = TextStyle(bold = true))
                        text("${d.totalRequestCount}")
                        if (pending > 0) {
                            text(" ")
                            tag("$pending pending", TagColor.YELLOW)
                        }
                        text(", installed in ")
                        val orgWide = d.enterpriseIds.isNotEmpty() && d.requests.any { it.teamId == null }
                        when {
                            orgWide -> tag("whole organisation", TagColor.ORANGE)
                            d.teamIds.isEmpty() -> text("unknown workspaces")
                            else -> preview(d.teamIds) { text(it, style = TextStyle(code = true)) }
                        }
                    }
                    val flags = buildList {
                        if (info?.isCertified == true) add("certified" to TagColor.GREEN)
                        if (info?.isExternal == true) add("external" to TagColor.ORANGE)
                        if (latest?.developerType == AppDevelopmentType.INTERNAL) add("internal" to TagColor.BLUE)
                        if (d.marketplace?.supportsEnterpriseManagedAuthorization == true) add("enterprise managed auth" to TagColor.BLUE)
                        if (info?.isAiApp == true) add("AI app" to TagColor.PURPLE)
                        if (info?.isAgentApp == true) add("agent" to TagColor.PURPLE)
                        if (info?.isAgentResponseModeEnabled == true) add("agent response mode" to TagColor.PURPLE)
                        if (info?.isSearchApp == true) add("search" to TagColor.PURPLE)
                        if (d.marketplace?.isCallsApp == true) add("calls" to TagColor.PURPLE)
                        if (d.marketplace?.isOrgReadyApp == true) add("org ready" to TagColor.PURPLE)
                        if (d.marketplace?.mcpServers?.isNotEmpty() == true) add("MCP" to TagColor.PURPLE)
                    }
                    if (flags.isNotEmpty()) richTextSection {
                        text("Features: ", style = TextStyle(bold = true))
                        for ((index, flag) in flags.withIndex()) {
                            if (index != 0) text(" ")
                            tag(flag.first, flag.second)
                        }
                    }
                    richTextSection {
                        text("Installed by: ", style = TextStyle(bold = true))
                        when {
                            d.marketplace == null -> text("unknown", style = TextStyle(italic = true))
                            installers.isEmpty() -> text("nobody")
                            else -> preview(installers) { user(it) }
                        }
                    }
                    richTextSection {
                        text("Requested by: ", style = TextStyle(bold = true))
                        if (d.requesters.isEmpty()) text("nobody") else preview(d.requesters) { user(it) }
                    }
                    richTextSection {
                        text("Approved by: ", style = TextStyle(bold = true))
                        val automatic = d.requests.any { it.status == RequestStatus.APPROVED && it.resolution == null }
                        when {
                            d.approvers.isNotEmpty() -> {
                                preview(d.approvers) { user(it) }
                                if (automatic) text(" and automatically")
                            }

                            automatic -> text("automatically")
                            else -> text("nobody (in this system)", style = TextStyle(italic = true))
                        }
                    }
                    if (d.knownCollaborators.isNotEmpty()) richTextSection {
                        text("Owners / Collaborators: ", style = TextStyle(bold = true))
                        preview(d.knownCollaborators) { user(it) }
                    }
                }
            }
        }
        detailButtons(d, AppInfoDetail.INSTALLERS, AppInfoDetail.REQUESTS)
    }

    // commands, shortcuts, scopes, mcp servers and channels
    private fun LayoutBlockDsl.capabilities(ctx: AppInfoContext) {
        val d = ctx.details
        val market = d.marketplace
        val commandNames = (market?.commands.orEmpty().map { it.name } + d.indexedCommands.keys).distinct().sorted()
        val scopes = d.currentScopes
        val actions = scopes.groupingBy { d.scopeAction(it) }.eachCount()
        val urls = d.urls()

        richText {
            richTextSection { text("\nCapabilities:", style = TextStyle(bold = true)) }
            richTextList {
                style(ListStyle.BULLET)
                elements {
                    richTextSection {
                        text("Slash Commands: ", style = TextStyle(bold = true))
                        if (commandNames.isEmpty()) text("None") else preview(commandNames) {
                            text(
                                it,
                                style = TextStyle(code = true)
                            )
                        }
                    }
                    if (market != null) richTextSection {
                        text("Shortcuts: ", style = TextStyle(bold = true))
                        text("${market.globalShortcuts.size} global, ${market.messageShortcuts.size} message")
                    }
                    richTextSection {
                        text("Scopes: ", style = TextStyle(bold = true))
                        if (scopes.isEmpty()) {
                            text("None known")
                        } else {
                            val bot = scopes.count { it.tokenType.equals("bot", true) }
                            text("$bot bot, ${scopes.size - bot} user")
                            val blocked = actions[RuleAction.BLOCK] ?: 0
                            val review = actions[RuleAction.REVIEW] ?: 0
                            if (blocked > 0) {
                                text(" ")
                                tag("$blocked blocked", TagColor.RED)
                            }
                            if (review > 0) {
                                text(" ")
                                tag("$review need review", TagColor.YELLOW)
                            }
                        }
                    }
                    if (market != null && market.mcpServers.isNotEmpty()) richTextSection {
                        text("MCP Servers: ", style = TextStyle(bold = true))
                        preview(market.mcpServers.map { it.name }) { text(it) }
                    }
                    if (market != null) richTextSection {
                        val info = market.info
                        text("Channels: ", style = TextStyle(bold = true))
                        val counts = buildList {
                            add("${market.channelIds.size} public")
                            info.otherChannelCount?.takeIf { it > 0 }?.let { add("$it other") }
                            info.allChannelCount?.takeIf { it > 0 }?.let { add("$it in total") }
                        }
                        text(counts.joinToString(", "))
                        if (market.channelIds.isNotEmpty()) {
                            text(" (")
                            preview(market.channelIds) { channel(it) }
                            text(")")
                        }
                    }
                    val callbackUrls = urls.filter { it.first.isCallbackUrlLabel() }
                    if (callbackUrls.isNotEmpty()) richTextSection {
                        text("Callback URLs: ", style = TextStyle(bold = true))
                        text("${callbackUrls.size}")
                        val dangerous = callbackUrls.count { isDangerous(it.second) }
                        if (dangerous > 0) {
                            text(" ")
                            tag("$dangerous to protected domains", TagColor.RED)
                        }
                    }
                }
            }
        }
        detailButtons(
            d,
            AppInfoDetail.COMMANDS,
            AppInfoDetail.SHORTCUTS,
            AppInfoDetail.SCOPES,
            AppInfoDetail.PERMISSIONS,
            AppInfoDetail.MCP_SERVERS,
            AppInfoDetail.CHANNELS,
            AppInfoDetail.URLS,
        )
    }

    private fun LayoutBlockDsl.adminActions(ctx: AppInfoContext) {
        if (!ctx.viewer.isAdmin) return
        val d = ctx.details
        val isProtected = d.protection != null
        divider()
        actions {
            adminButton(AppAdminAction.APPROVE, "Approve", "primary")
            if (!isProtected) {
                if (isRestrictEnabled()) adminButton(AppAdminAction.RESTRICT, "Restrict", "danger")
                if (isUninstallEnabled()) adminButton(AppAdminAction.UNINSTALL, "Uninstall", "danger")
                adminButton(AppAdminAction.PROTECT, "Protect", null)
            } else {
                adminButton(AppAdminAction.UNPROTECT, "Remove Protection", null)
            }
        }
        if (isProtected) {
            context {
                markdownText("This app is protected: it can not be restricted or uninstalled, and only admins can approve it.")
            }
        }
    }

    private fun LayoutBlockDsl.footer(ctx: AppInfoContext, skippedSections: Int) {
        val d = ctx.details
        val lines = buildList {
            addAll(d.warnings)
            if (skippedSections > 0) add("$skippedSections sections are hidden to stay below the block limit.")
            add("Collected ${formatDate(System.currentTimeMillis() / 1000, withTime = true)}")
        }
        context { markdownText(lines.joinToString("\n").take(3000)) }
    }

    // ---- helpers ----

    private fun LayoutBlockDsl.detailButtons(d: AppDetails, vararg details: AppInfoDetail) {
        val visible = details.map { it to it.count(d) }.filter { it.second > 0 }
        if (visible.isEmpty()) return
        actions {
            for ((detail, count) in visible) {
                button {
                    text("${detail.buttonLabel} ($count)", true)
                    actionId("${ACTION_DETAIL}_${detail.name.lowercase()}")
                    value(detail.name)
                }
            }
        }
    }

    private fun ActionsBlockBuilder.adminButton(action: AppAdminAction, label: String, style: String?) {
        button {
            text(label, true)
            actionId("${ACTION_ADMIN}_${action.name.lowercase()}")
            value(action.name)
            if (style != null) style(style)
        }
    }

    // renders the first few items and "and N more"
    private fun <T> RichTextObjectDsl.preview(items: List<T>, render: RichTextObjectDsl.(T) -> Unit) {
        for ((index, item) in items.take(INLINE_PREVIEW).withIndex()) {
            if (index != 0) text(", ")
            render(item)
        }
        if (items.size > INLINE_PREVIEW) text(" and ${items.size - INLINE_PREVIEW} more")
    }

    private fun blocks(builder: LayoutBlockDsl.() -> Unit): List<LayoutBlock> = withBlocks(builder)

    private fun modal(
        title: String,
        blocks: List<LayoutBlock>,
        callbackId: String? = null,
        metadata: String? = null,
        submit: String? = null,
        close: String = "Close",
    ): JsonObject = modalFromJson(
        title = title,
        callbackId = callbackId,
        metadata = metadata,
        submit = submit,
        blocks = blocks.map { SharedData.gson.toJsonTree(it).asJsonObject },
        close = close
    )

    private fun modalFromJson(
        title: String,
        blocks: List<JsonObject>,
        callbackId: String? = null,
        metadata: String? = null,
        submit: String? = null,
        close: String = "Close",
    ): JsonObject = JsonObject().apply {
        addProperty("type", "modal")
        if (callbackId != null) addProperty("callback_id", callbackId)
        if (metadata != null) addProperty("private_metadata", metadata.take(3000))
        add("title", plainText(title.take(24)))
        if (submit != null) add("submit", plainText(submit.take(24)))
        add("close", plainText(close.take(24)))
        val array = JsonArray()
        blocks.take(MAX_BLOCKS).forEach { array.add(it) }
        add("blocks", array)
    }

    private fun plainText(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "plain_text")
        addProperty("text", text)
        addProperty("emoji", true)
    }
}

// ---- shared formatting helpers (also used by AppInfoDetail) ----

private fun String.isUserId() = startsWith('U') || startsWith('W')

// escapes user provided text for mrkdwn
internal fun esc(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

// some slack apis return milliseconds instead of seconds
private fun toEpochSeconds(value: Long) = if (value > 100_000_000_000L) value / 1000 else value

// renders the date in the time zone of the viewer (mrkdwn)
internal fun formatDate(epochSeconds: Long, withTime: Boolean = false): String {
    val seconds = toEpochSeconds(epochSeconds)
    val fallback = JavaInstant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).let {
        if (withTime) "${it.toLocalDate()} ${it.toLocalTime().withNano(0)} UTC" else it.toLocalDate().toString()
    }
    val format = if (withTime) "{date_short_pretty} {time}" else "{date_short_pretty}"
    return "<!date^$seconds^$format|$fallback>"
}

// renders the date in the time zone of the viewer (rich text)
private fun RichTextObjectDsl.date(epochSeconds: Long) {
    val seconds = toEpochSeconds(epochSeconds)
    val fallback = JavaInstant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    date(seconds.toInt(), "{date_short_pretty}", null, null, fallback)
}

internal fun threadUrl(channel: String?, ts: String): String =
    "https://slack.com/archives/${channel ?: Config.SLACK_CHANNEL_REVIEW}/p${ts.replace(".", "")}"

// the same status tag as in the review message and the app home
private fun RichTextObjectDsl.statusTag(req: InstallRequestRecord) {
    if (req.status == RequestStatus.DENIED && req.resolution == null) {
        tag("Denied automatically", TagColor.RED)
    } else {
        tag(
            SlackWorkflowService.statusLabel(req.status, req.resolution),
            SlackWorkflowService.statusColor(req.status, req.resolution)
        )
    }
}

private fun developerTypeColor(type: String) = when (type.lowercase()) {
    "slack" -> TagColor.GREEN
    "internal" -> TagColor.BLUE
    "third_party" -> TagColor.RED
    else -> TagColor.BROWN
}

private fun scopeEntry(d: AppDetails, scope: ScopeIdentity, old: Boolean): AppInfoEntry = {
    text(scope.name, style = TextStyle(code = true))
    text(" - ${scope.tokenType.lowercase()} ")
    when (d.scopeAction(scope)) {
        RuleAction.ALLOW -> tag("allowed", TagColor.GREEN)
        RuleAction.REVIEW -> tag("review", TagColor.YELLOW)
        RuleAction.BLOCK -> tag("blocked", TagColor.RED)
        null -> tag("no rule", TagColor.BLUE)
    }
    if (old) {
        text(" ")
        tag("only in older requests", TagColor.BROWN)
    }
}

private fun String.isCallbackUrlLabel() = this != "Homepage" && this != "Marketplace"

// all urls we know of, as (label, url)
internal fun AppDetails.urls(): List<Pair<String, String>> = buildList {
    appUrl?.let { add("Homepage" to it) }
    add("Marketplace" to "$MARKETPLACE_URL$appId")
    marketplace?.commands.orEmpty().forEach { command ->
        command.url?.let { add("Request URL of ${command.name}" to it) }
    }
    marketplace?.mcpServers.orEmpty().forEach { server ->
        server.url?.let { add("MCP ${server.name}" to it) }
    }
}.distinctBy { it.second }

// whether the url points to one of our own (or slack's) domains, an app should never send requests there
internal fun isDangerous(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
    if (host.isEmpty()) return false
    return Constants.DANGEROUS_DOMAINS.any { host == it || host.endsWith(".$it") }
}
