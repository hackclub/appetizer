package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// a table which stores if an app is restriced and who did it
object AppRestrictionTable : Table("app_restriction") {
    val appId = varchar("app_id", 20)
        .comment("The app id")
    val appName = varchar("app_name", 100)
        .comment("The name of the app")
    val restrictedBy = varchar("restricted_by", 20)
        .comment("Who restricted the app")
    val restrictedAt = timestamp("restricted_at")
        .comment("When it was restricted")
    val reason = varchar("reason", 2000)
        .nullable()
        .comment("The reason why the app is restricted")
    val deleted = bool("deleted")
        .default(false)
        .comment("If the restricting is deleted")

    override val primaryKey = PrimaryKey(appId)
}
