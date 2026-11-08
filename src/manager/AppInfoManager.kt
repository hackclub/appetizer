package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.model.AppInfo
import com.fantamomo.slack.approver.model.SlashCommand
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.EOFException

// we the following apis to gather as much information as possible
// 1. https://slack.com/api/apps.profile.get
// 2. https://slack.com/marketplace/
// I tried some more, but those two give all required infos
object AppInfoManager {
    private val logger = LoggerFactory.getLogger(AppInfoManager::class.java)
    private const val CACHE_EXPIRATION_TIME = 1000L * 60L * 5L // 5 minutes

    private val cache = mutableMapOf<String, Pair<AppInfo, Long>>()
    private val cacheMutex = Mutex()
    private val requestMutex = Mutex()

    fun canBeUsed() = SharedData.validClientTokens

    private const val COMPONENT_START = """<div data-automount-component=""""
    private const val PROPS_START = """data-automount-props=""""

    suspend fun getAppInfo(id: String): AppInfo? {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.startsWith("A")) { "id must start with A" }
        require(canBeUsed()) { "Client tokens are not valid" }

        suspend fun getCache(): AppInfo? {
            if (cache.containsKey(id)) {
                cacheMutex.withLock {
                    if (cache.containsKey(id)) {
                        val info = cache[id]!!.first
                        cache[id] = info to System.currentTimeMillis()
                        clearCache()
                        return info
                    }
                }
            }
            return null
        }

        getCache()?.let { return it }

        return requestMutex.withLock {
            getCache()?.let { return it }

            coroutineScope {
                val componentsDeferred = async { marketplaceData(id) }
                val appProfileDeferred = async { appProfile(id) }

                val components = runCatching { componentsDeferred.await() }.getOrElse {
                    logger.error("Failed to get marketplace data for $id", it)
                    null
                }
                val appProfile = runCatching { appProfileDeferred.await() }.getOrElse {
                    logger.error("Failed to get app profile for $id", it)
                    null
                }

                if (components == null && appProfile == null) {
                    logger.error("Failed to get marketplace data and app profile for $id")
                    return@coroutineScope null
                }

                val appDirectoryAdditionalInfo = components?.get("AppDirectoryAdditionalInfo")
                val appDirectoryFeatures = components?.get("AppDirectoryFeatures")
                val appDirectoryAuthorizations = components?.get("AppDirectoryAuthorizations")
                val appDirectoryBotUser = components?.get("AppDirectoryBotUser")

                val botUser = appProfile?.get("bot_user")?.jsonObjectOrNull

                val botUserId = botUser?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
                    ?.takeIf { it.isNotBlank() && (it[0] == 'U' || it[0] == 'W') }

                val appInfo = AppInfo(
                    id = id,
                    publicChannelCount = appDirectoryBotUser?.get("memberships")?.jsonArrayOrNull?.size,
                    otherChannelCount = appDirectoryBotUser?.get("otherGroups")?.jsonPrimitiveOrNull?.intOrNull,
                    allChannelCount = botUser?.get("memberships_count")?.jsonPrimitiveOrNull?.intOrNull,
                    botUserId = botUserId,
                    isExternal = appDirectoryAdditionalInfo?.get("is_external")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isInstalled = appProfile?.get("is_installed")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isCertified = appDirectoryAdditionalInfo?.get("is_certified")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isAiApp = appDirectoryFeatures?.get("is_ai_app")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isAgentApp = appDirectoryFeatures?.get("is_agent_app")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isSearchApp = appDirectoryFeatures?.get("is_search_app")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    isAgentResponseModeEnabled = appDirectoryAuthorizations?.get("is_agent_response_mode_enabled")?.jsonPrimitiveOrNull?.booleanOrNull == true,
                    dateInstalled = appProfile?.get("date_installed")?.jsonPrimitiveOrNull?.longOrNull,
                    authorizationsCount = appDirectoryAuthorizations?.get("authorizations")?.jsonArrayOrNull
                        ?.count { auth -> auth.jsonObjectOrNull?.get("encodedUserLink")?.jsonPrimitiveOrNull?.contentOrNull.let { it != null && it != botUserId } },
                    commands = mutableSetOf<SlashCommand>()
                        .apply {
                            addAll(
                                appProfile?.get("commands")
                                    ?.jsonObjectOrNull?.values
                                    ?.mapNotNull { it.jsonObjectOrNull?.asSlashCommand() }
                                    ?: emptyList()
                            )
                            addAll(
                                appDirectoryFeatures?.get("slashCommands")?.jsonArrayOrNull
                                    ?.mapNotNull { it.jsonObjectOrNull?.asSlashCommand() }
                                    ?: emptyList()
                            )
                        }
                        .toList()
                )
                cacheMutex.withLock {
                    cache[id] = appInfo to System.currentTimeMillis()
                }
                return@coroutineScope appInfo
            }
        }
    }

    private fun JsonObject.asSlashCommand(): SlashCommand? {
        val name = this["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        val description = this["desc"]?.jsonPrimitiveOrNull?.contentOrNull
        val usageDescription = this["usage"]?.jsonPrimitiveOrNull?.contentOrNull
        return SlashCommand(name, description ?: "", usageDescription ?: "")
    }

    private suspend fun appProfile(id: String): JsonObject {
        val url = "https://slack.com/api/apps.profile.get"

        val response = SharedData.httpClient.get(url) {
            parameter("app", id)
            parameter("token", Config.SLACK_CLIENT_TOKEN)

            if (SharedData.rawDCookie) {
                header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
            } else {
                cookie("d", Config.SLACK_SESSION_COOKIE)
            }
        }

        if (!response.status.isSuccess()) {
            logger.error("Failed to get app profile for $id: ${response.status}")
            throw IllegalStateException("Failed to get app profile for $id: ${response.status}")
        }

        val responseText = response.bodyAsText()
        val responseJson = SharedData.json.parseToJsonElement(responseText).jsonObject
        if (responseJson["ok"]?.jsonPrimitiveOrNull?.booleanOrNull != true) {
            logger.error("Failed to get app profile for $id: ${responseJson["error"]?.jsonPrimitiveOrNull?.contentOrNull}")
            throw IllegalStateException("Failed to get app profile for $id: ${responseJson["error"]?.jsonPrimitiveOrNull?.contentOrNull}")
        }
        val profile = responseJson["app_profile"]?.jsonObjectOrNull
        if (profile == null) {
            logger.error("Failed to get app profile for $id: ${responseJson["error"]?.jsonPrimitiveOrNull?.contentOrNull}")
            throw IllegalStateException("Failed to get app profile for $id: ${responseJson["error"]?.jsonPrimitiveOrNull?.contentOrNull}")
        }

        return profile
    }

    private suspend fun marketplaceData(id: String): Map<String, JsonObject> {
        val url = Constants.MARKETPLACE_URL + id

        val response = SharedData.httpClient.get(url) {
            if (SharedData.rawDCookie) {
                header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
            } else {
                cookie("d", Config.SLACK_SESSION_COOKIE)
            }
        }

        if (!response.status.isSuccess()) {
            logger.error("Failed to get app info for $id: ${response.status}")
            throw IllegalStateException("Failed to get app info for $id: ${response.status}")
        }

        val components = mutableMapOf<String, JsonObject>()

        val channel = response.bodyAsChannel()

        while (!channel.isClosedForRead) {
            try {
                channel.findString(COMPONENT_START) || break

                val componentName = channel.readUntil('"'.code.toByte())

                // The component name is followed by whitespace and data-automount-props.
                if (!channel.findString(PROPS_START)) {
                    logger.warn("Missing data-automount-props for component $componentName")
                    continue
                }

                val props = channel.readUntil('"'.code.toByte())

                try {
                    val json = SharedData.json.parseToJsonElement(decodeHtmlEntities(props))

                    components[componentName] = json.jsonObject
                } catch (e: Exception) {
                    logger.warn("Failed to parse props for component $componentName", e)
                }
            } catch (_: EOFException) {
                break
            }
        }

        logger.debug("Found ${components.size} automount components for $id")

        return components
    }

    private val JsonElement.jsonPrimitiveOrNull get() = this as? JsonPrimitive
    private val JsonElement.jsonObjectOrNull get() = this as? JsonObject
    private val JsonElement.jsonArrayOrNull get() = this as? JsonArray

    private suspend fun ByteReadChannel.readUntil(delimiter: Byte): String {
        val result = StringBuilder()

        while (true) {
            val byte = readByte()

            if (byte == delimiter) {
                return result.toString()
            }

            result.append(byte.toInt().toChar())
        }
    }

    private suspend fun ByteReadChannel.findString(target: String): Boolean {
        var matched = 0

        while (true) {
            val byte = try {
                readByte().toInt().toChar()
            } catch (_: EOFException) {
                return false
            }

            if (byte == target[matched]) {
                matched++

                if (matched == target.length) {
                    return true
                }
            } else {
                matched = if (byte == target[0]) 1 else 0
            }
        }
    }

    private fun decodeHtmlEntities(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    suspend fun removeFromCache(appId: String) {
        if (!cache.contains(appId)) return
        cacheMutex.withLock {
            cache.remove(appId)
        }
    }

    private fun clearCache() {
        val current = System.currentTimeMillis()
        for ((id, pair) in cache) {
            val timestamp = pair.second

            if (current - timestamp > CACHE_EXPIRATION_TIME) {
                cache.remove(id)
            }
        }
    }
}