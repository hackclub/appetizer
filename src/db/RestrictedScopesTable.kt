package com.fantamomo.slack.approver.db

import com.fantamomo.slack.approver.model.RestrictionLevel
import com.fantamomo.slack.approver.model.ScopeType
import com.fantamomo.slack.approver.utils.enumerationByDynamicName
import org.jetbrains.exposed.v1.core.Table

// the old table which stored all the restricted scopes, it is deprecated see ScopeRulesTable for the new one
// TODO: remove this (when nothing breaks)
object RestrictedScopesTable : Table("restricted_scopes") {
    val scope = varchar("scope", 40)
        .comment("The scope which is restricted")
    val restricted = enumerationByDynamicName<RestrictionLevel>("restricted")
        .comment("How restricted the scope is")

    val scopeType = enumerationByName<ScopeType>("scope_type", ScopeType.entries.maxOf { it.name.length })
        .comment("The type of scope")

    val review = bool("review").comment("Whether the scope requires review or is directly declined")
}