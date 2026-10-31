package com.fantamomo.slack.approver.model

import kotlinx.serialization.Serializable

@Serializable
data class SlackResolvedBy(
    val actor_id: String? = null,
    val actor_type: String? = null
)

@Serializable
data class SlackAppEntry(
    val id: String,
    val name: String? = null
)

@Serializable
data class SlackApprovedApp(
    val app: SlackAppEntry,
    val date_updated: Long? = null,
    val last_resolved_by: SlackResolvedBy? = null
)

@Serializable
data class SlackRestrictedApp(
    val app: SlackAppEntry,
    val date_updated: Long? = null,
    val last_resolved_by: SlackResolvedBy? = null
)
