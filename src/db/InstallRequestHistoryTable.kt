package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// thr history table of all changes that are done to a request
object InstallRequestHistoryTable : Table("install_request_history") {
    val id = long("id")
        .autoIncrement()
        .comment("Unique id for this history entry")

    val requestId = reference("request_id", InstallRequestTable.requestId)
        .comment("The request id")

    val status = varchar("status", 30)
        .comment("The status")

    val actorId = varchar("actor_id", 20)
        .nullable()
        .comment("The actor which triggered this change")

    val actorType = varchar("actor_type", 20)
        .comment("The type of the actor")

    val action = varchar("action", 30)
        .comment("The action which was done")

    val reason = varchar("reason", 50)
        .nullable()
        .comment("The reason of the action")

    val message = varchar("message", 2000)
        .nullable()
        .comment("A message")

    val createdAt = timestamp("created_at")

    val stateId = varchar("state_id", 20)
        .nullable()
        .comment("Unique state id, here for debugging simultaneous access problems")

    init {
        index(false, requestId)
    }

    override val primaryKey = PrimaryKey(id)
}
