package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RestrictionLevel
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table

// stores the requested scopes of a request
object InstallRequestScopeTable : Table("install_request_scope") {

    val id = long("id")
        .autoIncrement()
        .comment("Unique id for every scope entry")

    val requestId = reference("request_id", InstallRequestTable.requestId)
        .comment("The request id")

    val scope = varchar("scope", 50)
        .comment("The scope")

    val description = varchar("description", 500)
        .nullable()
        .comment("The description of the scope (from slack)")

    // this is slack whos says if the scope is sensitive, not we
    val sensitive = bool("sensitive")
        .comment("If the scope is sensitive (from slack)")

    val tokenType = varchar("token_type", 20)
        .comment("The type of the token (bot or user)")

    val optional = bool("optional")
        .comment("If the scope is optional")

    val previousApproved = bool("previous_approved")
        .comment("If the scope was previous approved")

    // this is deprecated: TODO remove this when safe
    val restrictionLevel = enumerationByDynamicName<RestrictionLevel>("restriction_level")
        .nullable()
        .comment("On which level the scope is restricted")

    init {
        index(false, requestId)
        index(false, scope)
    }

    override val primaryKey = PrimaryKey(id)
}