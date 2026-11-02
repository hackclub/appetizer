package com.fantamomo.slack.approver.model

data class AppInfo(
    val id: String,
    val publicChannelCount: Int?,
    val otherChannelCount: Int?,
    val allChannelCount: Int?, // not really all
    val botUserId: String?,
    val isExternal: Boolean,
    val isInstalled: Boolean,
    val isCertified: Boolean,
    val isAiApp: Boolean,
    val isAgentApp: Boolean,
    val isSearchApp: Boolean,
    val isAgentResponseModeEnabled: Boolean,
    val dateInstalled: Long?,
    val authorizationsCount: Int?,
    val commands: List<SlashCommand>
)