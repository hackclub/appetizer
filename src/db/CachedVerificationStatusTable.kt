package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.VerificationStatus
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// used to cache the verification status of users to avoid spamming the auth servers with it
object CachedVerificationStatusTable : Table("cached_verification_status") {
    val userId = varchar("user_id", 20)
        .comment("The id of the user")

    val verificationStatus = enumerationByDynamicName<VerificationStatus>("verification_status")
        .comment("The current verification status")

    // for faster retrieval
    val verified = bool("verified")
        .default(false)
        .comment("Only for faster retrieval, the real status is done by 'verification_status'")

    val mainAccount = reference("main_account", userId)
        .nullable()
        .comment("If the user is an alt, this field contains the user id of the main account")

    val lastChecked = timestamp("last_checked")
        .comment("When the status was last checked")

    override val primaryKey = PrimaryKey(userId)
}