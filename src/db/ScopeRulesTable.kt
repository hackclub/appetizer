package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RuleAction
import com.fantamomo.slack.approver.model.RuleUserTarget
import com.fantamomo.slack.approver.model.ScopeType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object ScopeRulesTable : Table("scope_rules") {
    val id = long("id").autoIncrement()
    val pattern = varchar("pattern", 255).comment("The scope pattern (exact, prefix, wildcard, regex, or *)")
    val action = enumerationByName<RuleAction>(
        "action",
        RuleAction.entries.maxOf { it.name.length }
    ).comment("Action: ALLOW, REVIEW, or BLOCK")
    val userTarget = enumerationByName<RuleUserTarget>(
        "user_target",
        RuleUserTarget.entries.maxOf { it.name.length }
    ).default(RuleUserTarget.ALL).comment("Target user verification: ALL, VERIFIED, or UNVERIFIED")
    val scopeType = enumerationByName<ScopeType>(
        "scope_type",
        ScopeType.entries.maxOf { it.name.length }
    ).default(ScopeType.BOTH).comment("Scope type: BOTH, USER, or BOT")
    val enabled = bool("enabled").default(true).comment("Whether the rule is enabled")
    val description = varchar("description", 500).nullable().comment("Optional description or note")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
