package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// the MAIN table, stores every request we got and progressed
object InstallRequestTable : Table("install_request") {

    val requestId = varchar("request_id", 20)
        .comment("The request id")

    val appId = varchar("app_id", 20)
        .comment("The app id")

    val appName = varchar("app_name", 100)
        .comment("The app name")

    val appDescription = varchar("app_description", 1000)
        .comment("The app description")

    val appUrl = varchar("app_url", 255)
        .nullable()
        .comment("The app URL")

    val developerType = enumerationByDynamicName<AppDevelopmentType>("developer_type")
        .comment("The developer type of the app (slack, internal, third_party)")

    val userId = varchar("user_id", 20)
        .comment("The user who requested this app")

    val teamId = varchar("team_id", 20)
        .nullable()
        .comment("On which team the request was made")

    val enterpriseId = varchar("enterprise_id", 20)
        .nullable()
        .comment("On which enterprise the request was made")

    val message = varchar("message", 1000)
        .nullable()
        .comment("The user message")

    val isUserAppCollaborator = bool("is_user_app_collaborator")
        .comment("If the user who requested the app is also a collaborator")

    val requestedAt = long("requested_at")
        .comment("When the request was made")

    val userVerified = bool("user_verified")
        .comment("At the time when the request was made, if the user was verified")

    val slackResolution = enumerationByDynamicName<SlackResolution>("slack_resolution")
        .nullable()
        .comment("What resolution slack came")

    val automaticDecision = enumerationByDynamicName<RequestStatus>("automatic_decision")
        .comment("The automated decision our system came")

    val automaticDecisionReason = enumerationByDynamicName<RequestDecisionReason>("automatic_decision_reason")
        .nullable()
        .comment("Why this automated decision was made")

    val status = enumerationByDynamicName<RequestStatus>("status")
        .comment("The current state of the request")

    val resolvedBy = varchar("resolved_by", 20)
        .nullable()
        .comment("Who resolved it")

    val resolvedAt = timestamp("resolved_at")
        .nullable()
        .comment("When it was resolved")

    val resolutionMessage = varchar("resolution_message", 2000)
        .nullable()
        .comment("The message of the resolution")

    val resolution = enumerationByDynamicName<RequestDecision>("resolution")
        .nullable()
        .comment("The final resolution")

    val reviewMessageChannel = varchar("review_message_channel", 20)
        .nullable()
        .comment("The channel where the review message was posted")

    val reviewMessageTs = varchar("review_message_ts", 30)
        .nullable()
        .comment("The thread timestamp of the review message")

    val stateId = varchar("state_id", 20)
        .nullable()
        .comment("An internal id, to prevent simultaneous changing on older state")

    val createdAt = timestamp("created_at")
        .comment("When the request was created")

    val updatedAt = timestamp("updated_at")
        .comment("When the request was the last time updated")

    init {
        index(false, appId)
        index(false, userId)
        index(false, status)
    }

    override val primaryKey = PrimaryKey(requestId)
}