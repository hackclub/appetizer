package com.fantamomo.slack.approver.model

// everything we can find out about an app from the (undocumented) marketplace page and apps.profile.get,
// this is only fetched on demand (e.g. for the info modal) and never cached for a long time, because it can be large
data class AppMarketplaceDetails(
    val info: AppInfo,
    val name: String?,
    val botUsername: String?,
    // ids of the public channels the bot user is in
    val channelIds: List<String>,
    // users who authorized (installed) the app
    val authorizations: List<AppAuthorization>,
    val globalShortcuts: List<AppShortcut>,
    val messageShortcuts: List<AppShortcut>,
    val commands: List<AppCommandDetail>,
    val mcpServers: List<AppMcpServer>,
    // human readable list of what the app is allowed to do (from the marketplace auth summary)
    val permissions: List<String>,
    val isCallsApp: Boolean,
    val isOrgReadyApp: Boolean,
    val supportsEnterpriseManagedAuthorization: Boolean,
)

data class AppAuthorization(
    val userId: String?,
    val userDisplayName: String?,
    // unix timestamp in seconds
    val date: Long?,
    val description: String?,
)

data class AppShortcut(
    val name: String,
    val description: String,
    val callbackId: String?,
)

data class AppCommandDetail(
    val name: String,
    val description: String,
    val usageHint: String,
    // the request url the command is sent to
    val url: String?,
)

data class AppMcpServer(
    val name: String,
    val url: String?,
    val toolCount: Int,
)
