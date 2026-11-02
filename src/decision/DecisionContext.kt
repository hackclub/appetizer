package com.fantamomo.slack.approver.decision

import com.fantamomo.slack.approver.model.*

data class DecisionContext(
    val request: AppRequested,
    val isUserVerified: Boolean,
    val scopeRules: List<ScopeRule> = emptyList(),
    val isAppRestricted: Boolean,
    val previousRequests: List<PreviousRequestRecord>,
    val commands: List<SlashCommand>,
    val commandsAllReadyInUse: Map<String, String>,
    val unfurlDomains: Set<String>,
    val appInfo: AppInfo?
) {
    val restrictedScopes: List<RestrictedScope>
        get() = scopeRules.map { RestrictedScope.fromScopeRule(it) }
}