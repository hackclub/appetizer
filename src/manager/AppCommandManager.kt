package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.db.AppCommandsTable
import com.fantamomo.slack.approver.model.SlashCommand
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.associate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.json.io.decodeFromSource
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

object AppCommandManager {
    private val logger = LoggerFactory.getLogger(AppCommandManager::class.java)

    private val featureFinderRegex =
        Regex("""<div\b(?=[^>]*\bdata-automount-component\s*=\s*["']AppDirectoryFeatures["'])(?=[^>]*\bdata-automount-props\s*=\s*["']([^"']*)["'])[^>]*>""".trimIndent())

    private val authorizationsFinderRegex =
        Regex("""<div\b(?=[^>]*\bdata-automount-component\s*=\s*["']AppDirectoryAuthorizations["'])(?=[^>]*\bdata-automount-props\s*=\s*["']([^"']*)["'])[^>]*>""")

    private const val CORE = "CORE"

    @Serializable
    private data class Response(
        val ok: Boolean,
        val commands: List<ResponseCommand>? = null,
        val error: String? = null
    )

    @Serializable
    private data class ResponseCommand(
        val name: String,
        val canonical_name: String? = null,
        val name_normalized: String? = null,
        val type: String? = null,
        val app: String? = null
    )

    fun isValid(): Boolean = SharedData.validClientTokens

    private fun requireValid() {
        require(isValid()) { "AppCommandManager requires valid Slack client token" }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun start() {
        requireValid()
        while (true) {
            try {
                val response = SharedData.httpClient.get("https://slack.com/api/client.appCommands") {
                    parameter("token", Config.SLACK_CLIENT_TOKEN)
                    if (SharedData.rawDCookie) {
                        header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
                    } else {
                        cookie("d", Config.SLACK_SESSION_COOKIE)
                    }
                }

                // doing it this way because the response from slack it currently around 4 MB and there it no way to limit it
                val decodedResponse = SharedData.json.decodeFromSource<Response>(
                    response.bodyAsChannel().asSource().buffered()
                )
                if (!decodedResponse.ok) {
                    logger.error("Failed to fetch commands from Slack: ${decodedResponse.error}")
                } else {
                    val commands = decodedResponse.commands

                    if (commands == null) {
                        logger.error("Slack returned no commands field; skipping synchronization")
                    } else {
                        syncCommands(commands)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch commands", e)
            }
            delay(1.minutes)
        }
    }

    private suspend fun syncCommands(commands: List<ResponseCommand>) {
        var ignoredCommands = 0

        val normalizedCommands = commands.mapNotNull { command ->
            when (command.type) {
                "app" if command.app == null -> {
                    logger.error("App command without app: $command")
                    null
                }

                "core" if command.app != null -> {
                    logger.error("Core command with app: $command")
                    null
                }

                null -> {
                    // this is new, type can be none existing in the response
                    if (command.app == null && command.name_normalized == null && command.canonical_name == null) {
                        // i think this happens when an app is deleted, but i dont know, because it is new
                        ignoredCommands++
                        return@mapNotNull null
                    } else {
                        logger.warn("Command with unknown type but with other properties: $command")
                        ignoredCommands++
                        return@mapNotNull null
                    }
                }

                "custom", "service" -> {
                    // I have absolutely no idea what those commands do and why they exist
                    ignoredCommands++
                    return@mapNotNull null
                }

                !in setOf("app", "core") -> {
                    logger.error("Unknown command type: ${command.type}")
                    null
                }

                else -> command
            }
        }

        if (normalizedCommands.size != commands.size && normalizedCommands.size != (commands.size - ignoredCommands)) {
            logger.error("Invalid command snapshot; skipping synchronization")
            return
        }
        val activeCommandNames: Set<String> = normalizedCommands.mapTo(mutableSetOf()) { it.name }

        val overriddenCommands: MutableList<Triple<String, String, String>> = mutableListOf()

        try {
            DatabaseManager.transaction {
                val currentlyActiveCommands =
                    AppCommandsTable.select(AppCommandsTable.command)
                        .where { AppCommandsTable.active eq true }
                        .associate {
                            it[AppCommandsTable.command] to Unit
                        }.keys

                val commandsToDeactivate = currentlyActiveCommands - activeCommandNames

                // check for commands that changed ownership
                val existingOwners = if (activeCommandNames.isNotEmpty()) {
                    AppCommandsTable.select(AppCommandsTable.command, AppCommandsTable.app)
                        .where {
                            (AppCommandsTable.command inList activeCommandNames) and
                                    (AppCommandsTable.active eq true)
                        }
                        .associate {
                            it[AppCommandsTable.command] to it[AppCommandsTable.app]
                        }
                } else {
                    emptyMap()
                }

                normalizedCommands.forEach { command ->
                    val previousApp = existingOwners[command.name]
                    val newApp = command.app ?: CORE

                    if (previousApp != null && previousApp != newApp) {
                        overriddenCommands.add(Triple(command.name, newApp, previousApp))
                    }
                }

                if (commandsToDeactivate.isNotEmpty()) {
                    AppCommandsTable.update({
                        (AppCommandsTable.command inList commandsToDeactivate) and
                                (AppCommandsTable.active eq true)
                    }) {
                        it[active] = false
                    }
                    logger.info("Deactivated ${commandsToDeactivate.size} commands")
                }

                AppCommandsTable.batchUpsert(
                    normalizedCommands,
                    shouldReturnGeneratedValues = false
                ) {
                    this[AppCommandsTable.command] = it.name
                    this[AppCommandsTable.app] = it.app ?: CORE
                    this[AppCommandsTable.active] = true
                }

                logger.info("Synchronized ${normalizedCommands.size} active commands")
            }
        } catch (e: Exception) {
            logger.error("Failed to synchronize commands", e)
        }

        try {
            onCommandOverrides(overriddenCommands)
        } catch (e: Exception) {
            logger.error("Failed to notify about command overrides", e)
        }
    }

    private suspend fun onCommandOverrides(overrides: List<Triple<String, String, String>>) {
        data class AppInfo(
            val owner: String?,
            val botUser: String?
        )

        val appInfoCache = mutableMapOf<String, AppInfo>()

        val ownerRegex = Regex("""href="/team/([UW][A-Z0-9]+)"""")

        suspend fun getAppInfo(appId: String): AppInfo {
            return appInfoCache.getOrPut(appId) {
                try {
                    val response = SharedData.httpClient.get("https://slack.com/marketplace/$appId") {
                        if (SharedData.rawDCookie) {
                            header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
                        } else {
                            cookie("d", Config.SLACK_SESSION_COOKIE)
                        }
                    }

                    val site = response.bodyAsText()

                    val owner = ownerRegex.find(site)?.groupValues?.get(1)

                    if (owner == null) {
                        logger.warn("Failed to fetch app owner (appId = $appId)")
                    }

                    val encodedProps = authorizationsFinderRegex.find(site)?.groupValues?.get(1)

                    val botUser = if (encodedProps != null) {
                        try {
                            val props = decodeHtmlEncodedJson(encodedProps).jsonObject
                            val authorizations = props["authorizations"]?.jsonArray

                            authorizations
                                ?.firstOrNull { element ->
                                    element.jsonObject["fullDescription"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.contains("Send messages as @") == true
                                }
                                ?.jsonObject
                                ?.get("encodedUserLink")
                                ?.jsonPrimitive
                                ?.contentOrNull
                        } catch (e: Exception) {
                            logger.error("Failed to parse app authorizations (appId = $appId)", e)
                            null
                        }
                    } else {
                        logger.warn("Failed to find AppDirectoryAuthorizations for app $appId")
                        null
                    }

                    if (botUser == null) {
                        logger.warn("Failed to fetch bot user (appId = $appId)")
                    }

                    AppInfo(owner = owner, botUser = botUser)
                } catch (e: Exception) {
                    logger.error("Failed to fetch app information (appId = $appId)", e)
                    AppInfo(owner = null, botUser = null)
                }
            }
        }

        for ((command, newApp, previousApp) in overrides) {
            try {
                val newAppInfo = getAppInfo(newApp)
                val previousAppInfo = getAppInfo(previousApp)

                val newAppOwner = newAppInfo.owner ?: continue
                val previousAppOwner = previousAppInfo.owner ?: continue
                val newBotUser = newAppInfo.botUser ?: continue
                val previousBotUser = previousAppInfo.botUser ?: continue

                onCommandOverride(
                    command = command,
                    newAppId = newApp,
                    previousAppId = previousApp,
                    newAppOwner = newAppOwner,
                    previousAppOwner = previousAppOwner,
                    newBotUser = newBotUser,
                    previousBotUser = previousBotUser
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to process command override (command = $command, newApp = $newApp, previousApp = $previousApp)",
                    e
                )
            }
        }
    }

    private suspend fun onCommandOverride(
        command: String,
        newAppId: String,
        previousAppId: String,
        newAppOwner: String,
        previousAppOwner: String,
        newBotUser: String,
        previousBotUser: String
    ) {
        logger.info(
            "Command override detected: command = $command, " +
                    "newApp = $newAppId, previousApp = $previousAppId, " +
                    "newAppOwner = $newAppOwner, previousAppOwner = $previousAppOwner, " +
                    "newBotUser = $newBotUser, previousBotUser = $previousBotUser"
        )

        SlackManager.sendDm(previousAppOwner) {
            section {
                markdownText(
                    """
                *Your app has lost ownership of a slash command*

                Your app <@$previousBotUser> (<https://slack.com/marketplace/$previousAppId|$previousAppId>) previously registered the slash command `$command`.

                However, another app <@$newBotUser> (<https://slack.com/marketplace/$newAppId|$newAppId>) has now registered the same command.

                *What does this mean?*

                - Your app will no longer receive executions of `/$command`.
                - All future executions of this command will be handled by the new app.
                - Existing users of your app may not notice this change immediately. They might continue using `/$command` without realizing that they are now interacting with a completely different app.

                *What should you do?*

                We recommend contacting the owner of the new app (<@$newAppOwner>) to clarify whether this change was intentional.

                If this was an intentional change, you can safely ignore this message.

                However, if you believe that another app has deliberately taken over your command without authorization, please contact <@U07K4TS9HQE>, so the situation can be investigated.
                """.trimIndent()
                )
            }
            context {
                markdownText(
                    "_This is an automated notification from App Approver._"
                )
            }
        }
    }

    suspend fun getCommands(appId: String): List<SlashCommand> {
        requireValid()
        require(appId.isNotBlank()) { "appId cannot be blank" }
        require(appId.startsWith('A')) { "appId must start with A" }
        require(appId.all { it.isLetterOrDigit() }) { "appId must contain only letters and digits" }

        // we have two shoots at this

        // DEVELOPER NODE:
        // the problem is there is no (official) API for this,
        // so we use two unofficial APIs, which don't have documentations,
        // I experimented with those apis, and sometimes it returns the "slash_commands" list, but not always,
        // often I got an empty list on apps that clearly had slash commands
        // so I dont know how to handle this
        // TODO: figure this out

        // 1. the simple one
        try {
            val response = SharedData.httpClient.get("https://slack.com/api/apps.about.info") {
                if (SharedData.rawDCookie) {
                    header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
                } else {
                    cookie("d", Config.SLACK_SESSION_COOKIE)
                }
                parameter("token", Config.SLACK_CLIENT_TOKEN)
                parameter("app_id", appId)
            }
            val text = response.bodyAsText()
            val json = SharedData.json.parseToJsonElement(text).jsonObject
            if (json["ok"]?.jsonPrimitive?.booleanOrNull != null) {
                if ((json["error"] as? JsonPrimitive)?.contentOrNull != "app_not_found") {
                    logger.warn("Failed to fetch commands for app $appId: $text")
                }
            } else {
                val slashCommands = json["slash_commands"]?.jsonArray
                if (slashCommands == null) {
                    logger.error("Response did not contain slash_commands: $text")
                } else {
                    // the problem is if slash_commands is empty, that does not mean it have no commands
                    // yeah, i know kinda bad but it is so
                    if (!slashCommands.isEmpty()) {
                        return slashCommands.mapNotNull {
                            try {
                                val jsonObject = it.jsonObject
                                SlashCommand(
                                    name = jsonObject["name"]?.jsonPrimitive?.contentOrNull
                                        ?: throw IllegalArgumentException("name is missing in $jsonObject"),
                                    description = jsonObject["description"]?.jsonPrimitive?.contentOrNull ?: "",
                                    usageHint = jsonObject["usage_hint"]?.jsonPrimitive?.contentOrNull ?: ""
                                )
                            } catch (e: Exception) {
                                logger.error("Failed to parse slash command: $it", e)
                                null
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch commands for app $appId", e)
        }

        // ok, first shoot missed,
        // now the second try

        val url = "https://slack.com/marketplace/$appId"
        try {
            val response = SharedData.httpClient.get(url) {
                if (SharedData.rawDCookie) {
                    header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
                } else {
                    cookie("d", Config.SLACK_SESSION_COOKIE)
                }
            }

            if (response.status != HttpStatusCode.OK) {
                logger.error("$url did not return OK status: ${response.status}")
            } else {
                val text = response.bodyAsText()
                val jsonText = featureFinderRegex.find(text)?.groupValues?.get(1)
                if (jsonText == null) {
                    logger.warn("Failed to find JSON text in response body for $url")
                } else {
                    val json = decodeHtmlEncodedJson(jsonText).jsonObject
                    val slashCommands = json["slashCommands"]?.jsonArray
                    if (slashCommands == null) {
                        logger.error("Failed to find slash_commands in JSON for $url: $json")
                    } else {
                        val commands = slashCommands.mapNotNull { element ->
                            try {
                                val jsonObject = element.jsonObject
                                SlashCommand(
                                    name = jsonObject["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException(
                                        "Missing name field in slash command JSON"
                                    ),
                                    description = jsonObject["desc"]?.jsonPrimitive?.content ?: "",
                                    usageHint = jsonObject["usage"]?.jsonPrimitive?.content ?: ""
                                )
                            } catch (e: Exception) {
                                logger.error("Failed to parse slash command from JSON: $element", e)
                                null
                            }
                        }
                        if (commands.isNotEmpty()) {
                            return commands
                        }
                        // again if the commands are empty, pass on to the third try (forgot it does not exist)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch commands for app $appId via $url", e)
            return emptyList()
        }

        // if both of our attempts failed, return an empty list
        return emptyList()
    }

    suspend fun indexCommands(appId: String): List<SlashCommand> {
        val commands = getCommands(appId)

        try {
            DatabaseManager.transaction {
                AppCommandsTable.batchUpsert(
                    commands,
                    onUpdateExclude = listOf(AppCommandsTable.active),
                    shouldReturnGeneratedValues = false
                ) {
                    this[AppCommandsTable.command] = it.name
                    this[AppCommandsTable.app] = appId
                    this[AppCommandsTable.active] = false
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to index commands for app $appId", e)
        }
        return commands
    }

    suspend fun isCommandFree(command: String): Boolean {
        return DatabaseManager.transaction {
            AppCommandsTable.selectAll()
                .where { (AppCommandsTable.command eq command) and (AppCommandsTable.active eq true) }
                .empty()
        }
    }

    suspend fun commandOwnedBy(command: String): String? {
        return DatabaseManager.transaction {
            AppCommandsTable.select(AppCommandsTable.app)
                .where { (AppCommandsTable.command eq command) and (AppCommandsTable.active eq true) }
                .map { it[AppCommandsTable.app] }
                .firstOrNull()
        }
    }

    suspend fun areCommandFree(commands: List<String>): Boolean {
        return DatabaseManager.transaction {
            AppCommandsTable.selectAll()
                .where { AppCommandsTable.command inList commands and (AppCommandsTable.active eq true) }
                .empty()
        }
    }

    suspend fun returnCommandOwners(commands: List<String>): Map<String, String> {
        return DatabaseManager.transaction {
            AppCommandsTable.select(AppCommandsTable.command, AppCommandsTable.app)
                .where { AppCommandsTable.command inList commands and (AppCommandsTable.active eq true) }
                .associate { it[AppCommandsTable.command] to it[AppCommandsTable.app] }
        }
    }

    private fun decodeHtmlEncodedJson(encoded: String): JsonElement {
        val decoded = encoded
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

        return SharedData.json.parseToJsonElement(decoded)
    }
}