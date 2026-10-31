package com.fantamomo.slack.approver.model

class RestrictedScope(
    val pattern: String,
    val level: RestrictionLevel,
    val scopeType: ScopeType,
    val review: Boolean,
) {
    val scope = ScopeMatcher.fromString(pattern)

    fun toScopeRule(): ScopeRule {
        val action = when (level) {
            RestrictionLevel.ALLOWED -> RuleAction.ALLOW
            RestrictionLevel.ALLOWED_FOR_UNVERIFIED -> RuleAction.ALLOW
            RestrictionLevel.RESTRICTED -> if (review) RuleAction.REVIEW else RuleAction.BLOCK
        }
        val target = when (level) {
            RestrictionLevel.ALLOWED -> RuleUserTarget.VERIFIED
            RestrictionLevel.ALLOWED_FOR_UNVERIFIED -> RuleUserTarget.ALL
            RestrictionLevel.RESTRICTED -> RuleUserTarget.ALL
        }
        return ScopeRule(
            pattern = pattern,
            action = action,
            userTarget = target,
            scopeType = scopeType,
            enabled = true
        )
    }

    companion object {
        fun fromScopeRule(rule: ScopeRule): RestrictedScope {
            val level = when {
                rule.action == RuleAction.BLOCK || (rule.action == RuleAction.REVIEW) -> RestrictionLevel.RESTRICTED
                rule.userTarget == RuleUserTarget.ALL || rule.userTarget == RuleUserTarget.UNVERIFIED -> RestrictionLevel.ALLOWED_FOR_UNVERIFIED
                else -> RestrictionLevel.ALLOWED
            }
            val review = rule.action == RuleAction.REVIEW
            return RestrictedScope(
                pattern = rule.pattern,
                level = level,
                scopeType = rule.scopeType,
                review = review
            )
        }
    }
}