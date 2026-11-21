package com.fantamomo.slack.approver.model

import kotlin.time.Instant

// an app which can not be uninstalled or restricted by anyone, and only admins can approve it
data class ProtectedApp(
    val appId: String,
    val appName: String?,
    val protectedBy: String,
    val protectedAt: Instant,
    val reason: String?,
)

// the restriction of an app, as stored in our database
data class AppRestrictionInfo(
    val appId: String,
    val appName: String,
    val restrictedBy: String,
    val restrictedAt: Instant,
    val reason: String?,
)
