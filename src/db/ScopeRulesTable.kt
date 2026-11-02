package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RuleAction
import com.fantamomo.slack.approver.model.RuleUserTarget
import com.fantamomo.slack.approver.model.ScopeType
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

object ScopeRulesTable : Table("scope_rules") {
    val id = long("id").autoIncrement()

    val pattern = varchar("pattern", 255)
        .comment("The scope pattern (exact, prefix, wildcard, regex, or *)")

    val action = enumerationByDynamicName<RuleAction>("action")
        .comment("Action: ALLOW, REVIEW, or BLOCK")

    val userTarget = enumerationByDynamicName<RuleUserTarget>("user_target")
        .default(RuleUserTarget.ALL)
        .comment("Target user verification: ALL, VERIFIED, or UNVERIFIED")

    val scopeType = enumerationByDynamicName<ScopeType>("scope_type")
        .default(ScopeType.BOTH)
        .comment("Scope type: BOTH, USER, or BOT")

    val enabled = bool("enabled")
        .default(true)
        .comment("Whether the rule is enabled")

    val description = varchar("description", 500)
        .nullable()
        .comment("Optional description or note")

    val createdAt = timestamp("created_at")
        .defaultExpression(CurrentTimestamp)
        .comment("Timestamp of when the rule was created")

    val updatedAt = timestamp("updated_at")
        .defaultExpression(CurrentTimestamp)
        .comment("Timestamp of when the rule was last updated")

    override val primaryKey = PrimaryKey(id)
}
