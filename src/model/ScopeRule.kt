package com.fantamomo.slack.approver.model

import kotlin.time.Instant

data class ScopeRule(
    val id: Long = 0,
    val pattern: String,
    val action: RuleAction,
    val userTarget: RuleUserTarget = RuleUserTarget.ALL,
    val scopeType: ScopeType = ScopeType.BOTH,
    val enabled: Boolean = true,
    val description: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    val matcher: ScopeMatcher = ScopeMatcher.fromString(pattern)

    fun matches(scopeName: String, tokenType: String, isVerified: Boolean): Boolean {
        if (!enabled) return false

        val typeMatches = when (scopeType) {
            ScopeType.BOTH -> true
            ScopeType.USER -> tokenType.equals("user", ignoreCase = true)
            ScopeType.BOT -> tokenType.equals("bot", ignoreCase = true)
        }
        if (!typeMatches) return false

        val userMatches = when (userTarget) {
            RuleUserTarget.ALL -> true
            RuleUserTarget.VERIFIED -> isVerified
            RuleUserTarget.UNVERIFIED -> !isVerified
        }
        if (!userMatches) return false

        return matcher.match(scopeName.trim())
    }
}

object ScopeRulePrecedenceComparator : Comparator<ScopeRule> {
    override fun compare(r1: ScopeRule, r2: ScopeRule): Int {
        // 1. Matcher priority (1: Direct, 2: Prefix, 3: Wildcard, 4: Regex, 100: All)
        val m1 = r1.matcher
        val m2 = r2.matcher
        if (m1.priority != m2.priority) {
            return m1.priority.compareTo(m2.priority)
        }

        // 2. Specificity within the same matcher type
        if (m1 is ScopeMatcher.PrefixScopeMatcher && m2 is ScopeMatcher.PrefixScopeMatcher) {
            // Longer prefix is more specific (higher precedence -> smaller index)
            val prefixComp = m2.prefix.length.compareTo(m1.prefix.length)
            if (prefixComp != 0) return prefixComp
        } else if (m1 is ScopeMatcher.WildcardScopeMatcher && m2 is ScopeMatcher.WildcardScopeMatcher) {
            val nonWildcards1 = r1.pattern.count { it != '*' }
            val nonWildcards2 = r2.pattern.count { it != '*' }
            if (nonWildcards1 != nonWildcards2) {
                return nonWildcards2.compareTo(nonWildcards1)
            }
        } else if (m1 is ScopeMatcher.DirectScopeMatcher && m2 is ScopeMatcher.DirectScopeMatcher) {
            val lenComp = m2.scope.length.compareTo(m1.scope.length)
            if (lenComp != 0) return lenComp
        }

        // 3. User Target Specificity (VERIFIED or UNVERIFIED is more specific than ALL)
        val t1 = if (r1.userTarget == RuleUserTarget.ALL) 2 else 1
        val t2 = if (r2.userTarget == RuleUserTarget.ALL) 2 else 1
        if (t1 != t2) {
            return t1.compareTo(t2)
        }

        // 4. Scope Type Specificity (USER or BOT is more specific than BOTH)
        val s1 = if (r1.scopeType == ScopeType.BOTH) 2 else 1
        val s2 = if (r2.scopeType == ScopeType.BOTH) 2 else 1
        if (s1 != s2) {
            return s1.compareTo(s2)
        }

        // 5. Action Tie-Breaker (BLOCK > REVIEW > ALLOW for safety)
        val a1 = when (r1.action) {
            RuleAction.BLOCK -> 1
            RuleAction.REVIEW -> 2
            RuleAction.ALLOW -> 3
        }
        val a2 = when (r2.action) {
            RuleAction.BLOCK -> 1
            RuleAction.REVIEW -> 2
            RuleAction.ALLOW -> 3
        }
        if (a1 != a2) {
            return a1.compareTo(a2)
        }

        // 6. Tie-breaker by ID
        return r1.id.compareTo(r2.id)
    }
}
