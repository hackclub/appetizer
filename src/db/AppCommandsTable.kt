package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table

// table to store all commands and which are active
object AppCommandsTable : Table("app_commands") {
    val command = varchar("command", 32) // slacks max length is 32 (with the slash)
        .comment("The command")
    val app = varchar("app", 20)
        .comment("The app id")

    // only ONE "command" is active at a time
    val active = bool("active")
        .comment("If the command is currently active, there can only be one active command with the same name")


    override val primaryKey = PrimaryKey(command, app)
}