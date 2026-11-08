package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.manager.SettingsManager
import org.jetbrains.exposed.v1.core.Table

// table to store settings which needs to survive a restart
object SettingsTable : Table("settings") {
    val name = varchar("name", SettingsManager.MAX_SETTINGS_NAME_LENGTH)
        .comment("The settings name")
    val value = varchar("value", SettingsManager.MAX_SETTINGS_VALUE_LENGTH)
        .nullable()
        .comment("The settings value")

    override val primaryKey = PrimaryKey(name)
}