package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.manager.SettingsManager
import org.jetbrains.exposed.v1.core.Table

object SettingsTable : Table("settings") {
    val name = varchar("name", SettingsManager.MAX_SETTINGS_NAME_LENGTH)
    val value = varchar("value", SettingsManager.MAX_SETTINGS_VALUE_LENGTH).nullable()

    override val primaryKey = PrimaryKey(name)
}