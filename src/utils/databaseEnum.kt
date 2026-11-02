package com.fantamomo.slack.approver.utils

import org.jetbrains.exposed.v1.core.Table
import kotlin.enums.enumEntries

inline fun <reified T : Enum<T>> Table.enumerationByDynamicName(name: String) =
    enumerationByName(name, enumEntries<T>().maxOf { it.name.length }, T::class)