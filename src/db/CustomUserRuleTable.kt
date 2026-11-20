package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RuleAction
import com.fantamomo.slack.approver.model.ScopeType
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

// the table which stores rules that only apply to the requests of a specific user,
// these rules take precedence over the global scope rules (see ScopeRulesTable)
object CustomUserRuleTable : Table("custom_user_rule") {
    val id = long("id").autoIncrement()

    val userId = varchar("user_id", 20)
        .index()
        .comment("The Slack user ID of the user the rule applies to")

    val pattern = varchar("pattern", 255)
        .default("*")
        .comment("The scope pattern (exact, prefix, wildcard, regex, or *)")

    val action = enumerationByDynamicName<RuleAction>("action")
        .comment("Action: ALLOW, REVIEW, or BLOCK")

    val scopeType = enumerationByDynamicName<ScopeType>("scope_type")
        .default(ScopeType.BOTH)
        .comment("Scope type: BOTH, USER, or BOT")

    val enabled = bool("enabled")
        .default(true)
        .comment("Whether the rule is enabled")

    val description = varchar("description", 500)
        .nullable()
        .comment("Optional internal description or note")

    val createdBy = varchar("created_by", 20)
        .comment("The Slack user ID of the user who created the rule")

    val createdAt = timestamp("created_at")
        .defaultExpression(CurrentTimestamp)
        .comment("Timestamp of when the rule was created")

    val updatedBy = varchar("updated_by", 20)
        .comment("The Slack user ID of the user who last updated the rule")

    val updatedAt = timestamp("updated_at")
        .defaultExpression(CurrentTimestamp)
        .comment("Timestamp of when the rule was last updated")

    override val primaryKey = PrimaryKey(id)
}
