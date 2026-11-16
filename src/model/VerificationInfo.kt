package com.fantamomo.slack.approver.model

data class VerificationInfo(
    val isVerified: Boolean,
    val altOwner: String? = null
)