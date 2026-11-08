package com.fantamomo.slack.approver.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// stores all the migrations we're done to the database so that we don't do them again
// this is the first table that is created and accessed, any structural changes to this table must be done very carefully
object MigrationTable : Table("migration") {
    val migration = varchar("migration", 255)
    val appliedAt = timestamp("applied_at")
}