package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object InstallRequestTable : Table("install_request") {

    val requestId = varchar("request_id", 20)
    val appId = varchar("app_id", 20)

    val appName = varchar("app_name", 100)
    val appDescription = varchar("app_description", 1000)
    val appUrl = varchar("app_url", 255)
        .nullable()

    val developerType = enumerationByDynamicName<AppDevelopmentType>("developer_type")

    val userId = varchar("user_id", 20)

    val teamId = varchar("team_id", 20)
        .nullable()
    val enterpriseId = varchar("enterprise_id", 20)
        .nullable()

    val message = varchar("message", 1000)
        .nullable()
    val isUserAppCollaborator = bool("is_user_app_collaborator")

    val requestedAt = long("requested_at")
    val userVerified = bool("user_verified")

    val slackResolution = enumerationByDynamicName<SlackResolution>("slack_resolution")
        .nullable()

    val automaticDecision = enumerationByDynamicName<RequestStatus>("automatic_decision")

    val automaticDecisionReason = enumerationByDynamicName<RequestDecisionReason>("automatic_decision_reason")
        .nullable()

    val status = enumerationByDynamicName<RequestStatus>("status")

    val resolvedBy = varchar("resolved_by", 20)
        .nullable()
    val resolvedAt = timestamp("resolved_at")
        .nullable()

    val resolutionMessage = varchar("resolution_message", 2000)
        .nullable()

    val resolution = enumerationByDynamicName<RequestDecision>("resolution")
        .nullable()

    val reviewMessageChannel = varchar("review_message_channel", 20)
        .nullable()
    val reviewMessageTs = varchar("review_message_ts", 30)
        .nullable()

    val stateId = varchar("state_id", 20)
        .nullable()

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index(false, appId)
        index(false, userId)
        index(false, status)
    }

    override val primaryKey = PrimaryKey(requestId)
}