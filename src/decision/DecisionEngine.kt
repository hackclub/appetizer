package com.fantamomo.slack.approver.decision

import com.fantamomo.slack.approver.model.*

object DecisionEngine {

    fun evaluate(context: DecisionContext): DecisionResult {

        // 1. if the request was resolved by slack automation, we cannot do anything, so return the resolution status
        val resolutionStatus = context.request.resolution?.status?.lowercase()?.let { status -> SlackResolution.entries.find { status == it.value } }
        if (resolutionStatus != null && resolutionStatus != SlackResolution.PENDING) {
            return DecisionResult(
                status = if (resolutionStatus == SlackResolution.APPROVED) RequestStatus.APPROVED else RequestStatus.DENIED,
                reason = RequestDecisionReason.SLACK_AUTOMATION,
                explanation = "The request was ${resolutionStatus.value} by slack automation."
            )
        }

        // 2. if the app is restricted, deny the request
        if (context.isAppRestricted) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.RESTRICTED_APPLICATION,
                explanation = "The application is restricted from being installed."
            )
        }

        // 3. if the request is for an enterprise installation, deny the request
        if (context.request.enterprise != null) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.ENTERPRISE_INSTALL,
                explanation = "Enterprise installations require manual approval."
            )
        }

        val currentScopes = context.request.scopes.map {
            ScopeIdentity(it.name.trim().lowercase(), it.tokenType.trim().lowercase())
        }.toSet()

        val isRepeatedDenied = context.previousRequests.any { prev ->
            if (prev.isEnterprise) return@any false
            val wasDenied = (prev.status == RequestStatus.DENIED ||
                    prev.resolution == RequestDecision.DENIED ||
                    prev.resolution == RequestDecision.RESTRICTED) &&
                    prev.resolution != RequestDecision.UNDONE
            if (!wasDenied) return@any false
            val prevScopes = prev.scopes.map {
                ScopeIdentity(it.name.trim().lowercase(), it.tokenType.trim().lowercase())
            }.toSet()
            prevScopes == currentScopes
        }

        // 4. if the request has been previously denied with the same scopes, deny the request
        if (isRepeatedDenied) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.PREVIOUSLY_DENIED,
                isRepeatedRequest = true,
                explanation = "An identical request with the same scopes was previously denied."
            )
        }

        val commandsInUse = context.commandsAllReadyInUse

        if (commandsInUse.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.COMMAND_ALREADY_IN_USE,
                explanation = "One or more commands are already in use by another app.",
                relevantCommands = commandsInUse
            )
        }

        // 5. check all the scopes using rule evaluation
        val blockedScopes = mutableListOf<String>()
        val reviewScopes = mutableListOf<String>()
        val unallowedForUnverifiedScopes = mutableListOf<String>()

        for (requestedScope in context.request.scopes) {
            val scopeName = requestedScope.name.trim()
            val tokenType = requestedScope.tokenType.trim()

            val matchingRules = context.scopeRules.filter { rule ->
                rule.matches(scopeName, tokenType, context.isUserVerified)
            }

            if (matchingRules.isEmpty()) {
                if (!context.isUserVerified) {
                    unallowedForUnverifiedScopes.add(scopeName)
                }
                continue
            }

            val bestRule = matchingRules.sortedWith(ScopeRulePrecedenceComparator).first()

            when (bestRule.action) {
                RuleAction.BLOCK -> blockedScopes.add(scopeName)
                RuleAction.REVIEW -> reviewScopes.add(scopeName)
                RuleAction.ALLOW -> { /* explicitly allowed */ }
            }
        }

        val allNonAllowedScopes = (blockedScopes + reviewScopes + unallowedForUnverifiedScopes).distinct()

        val previousApprovedRequests = context.previousRequests.filter { prev ->
            !prev.isEnterprise && (prev.status == RequestStatus.APPROVED || prev.resolution == RequestDecision.APPROVED)
        }
        val previouslyApprovedScopeNames = previousApprovedRequests.flatMap { it.scopes.map { s -> s.name.trim().lowercase() } }.toSet()

        if (previousApprovedRequests.isNotEmpty()) {
            val newlyRestrictedScopes = allNonAllowedScopes.filter { scope -> !previouslyApprovedScopeNames.contains(scope.lowercase()) }
            // 6. if the request has been previously approved with some restricted scopes, but no new restricted scopes, approve the request
            if (newlyRestrictedScopes.isEmpty()) {
                if (context.isUserVerified) {
                    return DecisionResult(
                        status = RequestStatus.APPROVED,
                        reason = RequestDecisionReason.USER_VERIFIED,
                        explanation = "Request automatically approved: all requested restricted scopes were previously approved for this user."
                    )
                } else {
                    return DecisionResult(
                        status = RequestStatus.APPROVED,
                        reason = RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES,
                        explanation = "Request automatically approved: all requested scopes were previously approved."
                    )
                }
            }
        }

        // 7. if the request contains at least one blocked scope, deny the request
        if (blockedScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.RESTRICTED_SCOPE,
                relevantRestrictedScopes = blockedScopes.distinct(),
                requiresManualReview = false,
                explanation = "The request contains restricted scopes that cannot be approved."
            )
        }

        // 8. if the request contains reviewable scopes, send to manual review
        if (reviewScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.PENDING_REVIEW,
                reason = RequestDecisionReason.RESTRICTED_SCOPE,
                relevantRestrictedScopes = reviewScopes.distinct(),
                requiresManualReview = true,
                explanation = "The request contains restricted scopes requiring manual review."
            )
        }

        // 9. if the user is unverified and has scopes not explicitly allowed, deny
        if (unallowedForUnverifiedScopes.isNotEmpty()) {
            return DecisionResult(
                status = RequestStatus.DENIED,
                reason = RequestDecisionReason.USER_UNVERIFIED,
                relevantRestrictedScopes = unallowedForUnverifiedScopes.distinct(),
                requiresManualReview = false,
                explanation = "User is not verified and some requested scopes are not explicitly allowed for unverified users."
            )
        }

        // 10. approve the request
        return if (context.isUserVerified) {
            DecisionResult(
                status = RequestStatus.APPROVED,
                reason = RequestDecisionReason.USER_VERIFIED,
                explanation = "Request automatically approved for verified user."
            )
        } else {
            DecisionResult(
                status = RequestStatus.APPROVED,
                reason = RequestDecisionReason.USER_UNVERIFIED_ALLOWED_SCOPES,
                explanation = "Request automatically approved because all requested scopes are allowed for unverified users."
            )
        }
    }
}
