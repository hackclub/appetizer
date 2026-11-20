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
    val publicDescription: String? = null,
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
        return userMatches && matcher.match(scopeName.trim())
    }
}

object ScopeRulePrecedenceComparator : Comparator<ScopeRule> {
    override fun compare(r1: ScopeRule, r2: ScopeRule): Int {
        // 1. + 2. Matcher priority and specificity within the same matcher type
        val matcherComp = compareMatcherSpecificity(r1.pattern, r1.matcher, r2.pattern, r2.matcher)
        if (matcherComp != 0) return matcherComp

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
        val actionComp = r1.action.severity.compareTo(r2.action.severity)
        if (actionComp != 0) return actionComp

        // 6. Tie-breaker by ID
        return r1.id.compareTo(r2.id)
    }
}

// compares two scope matchers, a negative result means the first one is more specific (has higher precedence)
//  1. Matcher priority (1: Direct, 2: Prefix, 3: Wildcard, 4: Regex, 100: All)
//  2. Specificity within the same matcher type
internal fun compareMatcherSpecificity(pattern1: String, m1: ScopeMatcher, pattern2: String, m2: ScopeMatcher): Int {
    if (m1.priority != m2.priority) {
        return m1.priority.compareTo(m2.priority)
    }

    if (m1 is ScopeMatcher.PrefixScopeMatcher && m2 is ScopeMatcher.PrefixScopeMatcher) {
        // Longer prefix is more specific (higher precedence -> smaller index)
        return m2.prefix.length.compareTo(m1.prefix.length)
    } else if (m1 is ScopeMatcher.WildcardScopeMatcher && m2 is ScopeMatcher.WildcardScopeMatcher) {
        val nonWildcards1 = pattern1.count { it != '*' }
        val nonWildcards2 = pattern2.count { it != '*' }
        return nonWildcards2.compareTo(nonWildcards1)
    } else if (m1 is ScopeMatcher.DirectScopeMatcher && m2 is ScopeMatcher.DirectScopeMatcher) {
        return m2.scope.length.compareTo(m1.scope.length)
    }
    return 0
}

// the lower the value, the stricter the action (used as tie-breaker, the stricter action wins)
internal val RuleAction.severity: Int
    get() = when (this) {
        RuleAction.BLOCK -> 1
        RuleAction.REVIEW -> 2
        RuleAction.ALLOW -> 3
    }
