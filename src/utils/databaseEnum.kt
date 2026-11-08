package com.fantamomo.slack.approver.utils

import org.jetbrains.exposed.v1.core.Table
import kotlin.enums.enumEntries

/**
 * Creates an enumeration column whose SQL name is provided and whose length is
 * derived from the longest enum constant name of the reified enum type.
 *
 * @param T the enum type stored by the column.
 * @param name the database column name.
 * @return the registered enumeration column for the specified enum type.
 */
inline fun <reified T : Enum<T>> Table.enumerationByDynamicName(name: String) =
    enumerationByName(name, enumEntries<T>().maxOf { it.name.length }, T::class)