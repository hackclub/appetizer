package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RestrictionLevel
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table

// stores the requested scopes of a request
object InstallRequestScopeTable : Table("install_request_scope") {

    val id = long("id")
        .autoIncrement()

    val requestId = reference("request_id", InstallRequestTable.requestId)

    val scope = varchar("scope", 50)

    val description = varchar("description", 500)
        .nullable()

    // this is slack whos says if the scope is sensitive, not we
    val sensitive = bool("sensitive")

    val tokenType = varchar("token_type", 20)

    val optional = bool("optional")
    val previousApproved = bool("previous_approved")

    val restrictionLevel = enumerationByDynamicName<RestrictionLevel>("restriction_level")
        .nullable()

    init {
        index(false, requestId)
        index(false, scope)
    }

    override val primaryKey = PrimaryKey(id)
}