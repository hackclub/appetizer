package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// stores the previous resolution we got from slack
object InstallRequestPreviousResolutionTable : Table(
    "install_request_previous_resolution"
) {

    val requestId = reference("request_id", InstallRequestTable.requestId)
        .comment("The request id")

    val status = varchar("status", 30)
        .comment("The status of the previous solution")

    val resolvedByType = varchar("resolved_by_type", 30)
        .nullable()
        .comment("The type of the actor who resolved this")

    val resolvedById = varchar("resolved_by_id", 20)
        .nullable()
        .comment("The id of the actor who resolved this")

    val resolvedAt = timestamp("resolved_at")
        .nullable()
        .comment("When the request was resolved")

    // scopes in tokenType:name format separated by comma
    val rawScopes = text("raw_scopes")
        .nullable()
        .comment("The scopes in 'token_type:scope' separated by comma")

    override val primaryKey = PrimaryKey(requestId)
}