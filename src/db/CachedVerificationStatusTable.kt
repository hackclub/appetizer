package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.VerificationStatus
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// used to cache the verification status of users to avoid spamming the auth servers with it
object CachedVerificationStatusTable : Table("cached_verification_status") {
    val userId = varchar("user_id", 20)

    val verificationStatus = enumerationByDynamicName<VerificationStatus>("verification_status")

    // for faster retrieval
    val verified = bool("verified")
        .default(false)

    val mainAccount = reference("main_account", userId)
        .nullable()

    val lastChecked = timestamp("last_checked")

    override val primaryKey = PrimaryKey(userId)
}