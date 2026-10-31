package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.db.SettingsTable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

object SettingsManager {
    const val MAX_SETTINGS_NAME_LENGTH = 24
    const val MAX_SETTINGS_VALUE_LENGTH = 64

    private val logger = LoggerFactory.getLogger(SettingsManager::class.java)

    private val settings: MutableMap<String, String?> = mutableMapOf()

    private sealed interface Action {
        val name: String

        data class Insert(override val name: String, val value: String?) : Action
        data class Set(override val name: String, val value: String?) : Action
        data class Delete(override val name: String) : Action
    }

    private val actionsQuery = Channel<Action>(Channel.UNLIMITED)

    suspend fun start() {
        DatabaseManager.transaction {
            SettingsTable.selectAll()
                .collect { settings[it[SettingsTable.name]] = it[SettingsTable.value] }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun run() = coroutineScope {
        while (isActive && !actionsQuery.isClosedForReceive) {
            val actions = mutableListOf<Action>()
            actions.add(actionsQuery.receive())
            while (true) {
                try {
                    withTimeout(30.seconds) {
                        val result = actionsQuery.receiveCatching()
                        if (result.isSuccess) {
                            actions.add(result.getOrNull()!!)
                        } else throw IllegalStateException("Failed to receive action: ${result.exceptionOrNull()}")
                    }
                } catch (_: TimeoutCancellationException) {
                    break
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("Failed to receive action") == true) {
                        break
                    }
                    logger.warn("Failed to receive action", e)
                } catch (e: Exception) {
                    logger.warn("Unknown error while receiving action", e)
                }
            }

            putInDatabase(actions)
        }
    }

    operator fun get(name: String): String? {
        if (name.length > MAX_SETTINGS_NAME_LENGTH) logger.warn("Settings name is too long: $name")
        return settings[name]
    }

    operator fun set(name: String, value: String?) {
        checkName(name)
        checkValue(value)
        if (settings.containsKey(name)) {
            actionsQuery.trySend(Action.Set(name, value))
        } else {
            actionsQuery.trySend(Action.Insert(name, value))
        }
        settings[name] = value
    }

    fun remove(name: String) {
        checkName(name)
        actionsQuery.trySend(Action.Delete(name))
        settings.remove(name)
    }

    operator fun contains(name: String) = settings.containsKey(name)

    val entries: List<Pair<String, String?>> get() = settings.toList()

    private fun checkName(name: String) {
        require(name.isNotBlank()) { "Name cannot be blank" }
        require(name.length <= MAX_SETTINGS_NAME_LENGTH) { "Name cannot be longer than $MAX_SETTINGS_NAME_LENGTH characters" }
    }

    private fun checkValue(value: String?) {
        if (value != null && value.length > MAX_SETTINGS_VALUE_LENGTH) logger.warn("Settings value is too long: $value")
    }

    private suspend fun putInDatabase(actions: List<Action>) {
        val actions = actions.distinctFromBack { it.name }
        DatabaseManager.transaction {
            for (action in actions) {
                when (action) {
                    is Action.Delete -> SettingsTable.deleteWhere { SettingsTable.name eq action.name }
                    is Action.Set -> SettingsTable.update(where = { SettingsTable.name eq action.name }) {
                        it[SettingsTable.value] = action.value
                    }
                    is Action.Insert -> SettingsTable.insert {
                        it[SettingsTable.name] = action.name
                        it[SettingsTable.value] = action.value
                    }
                }
            }
        }
    }

    private fun <E, D> List<E>.distinctFromBack(func: (E) -> D): List<E> {
        val seen = mutableSetOf<D>()
        val result = mutableListOf<E>()
        for (i in indices.reversed()) {
            val e = this[i]
            val d = func(e)
            if (!seen.add(d)) continue
            result.add(e)
        }
        return result
    }
}