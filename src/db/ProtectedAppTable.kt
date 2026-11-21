package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// apps which are protected by an admin: nobody can uninstall or restrict them and only admins can approve them
object ProtectedAppTable : Table("protected_app") {
    val appId = varchar("app_id", 20)
        .comment("The app id")
    val appName = varchar("app_name", 100)
        .nullable()
        .comment("The name of the app (at the time it was protected)")
    val protectedBy = varchar("protected_by", 20)
        .comment("The admin who protected the app")
    val protectedAt = timestamp("protected_at")
        .comment("When the app was protected")
    val reason = varchar("reason", 2000)
        .nullable()
        .comment("Why the app is protected")

    override val primaryKey = PrimaryKey(appId)
}
