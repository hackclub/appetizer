package com.fantamomo.slack.approver.model

// the actions a custom user rule can have, they are more fine-grained than the actions of the global scope rules
enum class UserRuleAction(
    // the lower the value, the stricter the action (used as tie-breaker, the stricter action wins)
    val severity: Int
) {
    // the scope is declined, the request is denied
    BLOCK(1),

    // the scope always requires a manual review
    REVIEW(2),

    // the user is treated as verified for this scope, but the scope rules (review and block) still apply
    TREAT_AS_VERIFIED(3),

    // the user skips the manual review (and the verification) for this scope, but blocked scopes stay blocked
    SKIP_REVIEW(4),

    // the scope is always allowed, even if a scope rule blocks it
    ALLOW(5);

    // whether the action can approve a scope which the scope rules would not approve
    val canOverride: Boolean get() = this == ALLOW || this == SKIP_REVIEW || this == TREAT_AS_VERIFIED
}
