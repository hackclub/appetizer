package com.fantamomo.slack.approver.model

import kotlin.time.Instant

// a rule which only applies to the requests of a single user,
// it takes precedence over the global scope rules for every scope it matches
data class CustomUserRule(
    val id: Long = 0,
    val userId: String,
    val pattern: String = "*",
    val action: RuleAction,
    val scopeType: ScopeType = ScopeType.BOTH,
    val enabled: Boolean = true,
    val description: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
) {
    val matcher: ScopeMatcher = ScopeMatcher.fromString(pattern)

    fun matches(userId: String, scopeName: String, tokenType: String): Boolean {
        if (!enabled) return false
        if (this.userId != userId) return false

        val typeMatches = when (scopeType) {
            ScopeType.BOTH -> true
            ScopeType.USER -> tokenType.equals("user", ignoreCase = true)
            ScopeType.BOT -> tokenType.equals("bot", ignoreCase = true)
        }
        return typeMatches && matcher.match(scopeName.trim())
    }
}

// same precedence as for scope rules: most specific pattern first, then scope type, then the strictest action
object CustomUserRulePrecedenceComparator : Comparator<CustomUserRule> {
    override fun compare(r1: CustomUserRule, r2: CustomUserRule): Int {
        // 1. + 2. Matcher priority and specificity within the same matcher type
        val matcherComp = compareMatcherSpecificity(r1.pattern, r1.matcher, r2.pattern, r2.matcher)
        if (matcherComp != 0) return matcherComp

        // 3. Scope Type Specificity (USER or BOT is more specific than BOTH)
        val s1 = if (r1.scopeType == ScopeType.BOTH) 2 else 1
        val s2 = if (r2.scopeType == ScopeType.BOTH) 2 else 1
        if (s1 != s2) {
            return s1.compareTo(s2)
        }

        // 4. Action Tie-Breaker (BLOCK > REVIEW > ALLOW for safety)
        val actionComp = r1.action.severity.compareTo(r2.action.severity)
        if (actionComp != 0) return actionComp

        // 5. Tie-breaker by ID
        return r1.id.compareTo(r2.id)
    }
}
