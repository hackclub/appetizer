package com.fantamomo.slack.approver.manager

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.Constants
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.model.*
import com.fantamomo.slack.approver.utils.ExpiringCache
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.EOFException
import kotlin.time.Duration.Companion.minutes

// we the following apis to gather as much information as possible
// 1. https://slack.com/api/apps.profile.get
// 2. https://slack.com/marketplace/
// I tried some more, but those two give all required infos
object AppInfoManager {
    private val logger = LoggerFactory.getLogger(AppInfoManager::class.java)

    // only the small AppInfo is cached, the cache is bounded so it can not grow forever
    private val cache = ExpiringCache<String, AppInfo>(maxSize = 200, defaultTtl = 5.minutes)
    private val requestMutex = Mutex()

    // the marketplace page has a lot of automount components, but we only need those,
    // all other components are skipped while streaming the page instead of being parsed (saves a lot of memory)
    private val NEEDED_COMPONENTS = setOf(
        "AppDirectoryAdditionalInfo",
        "AppDirectoryFeatures",
        "AppDirectoryAuthorizations",
        "AppDirectoryBotUser",
    )

    // props of a single component larger than this are ignored, so a broken page can not exhaust the memory
    private const val MAX_PROPS_LENGTH = 2 * 1024 * 1024

    fun canBeUsed() = SharedData.validClientTokens

    private const val COMPONENT_START = """<div data-automount-component=""""
    private const val PROPS_START = """data-automount-props=""""

    suspend fun getAppInfo(id: String): AppInfo? {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.startsWith("A")) { "id must start with A" }
        require(canBeUsed()) { "Client tokens are not valid" }

        cache[id]?.let { return it }

        return requestMutex.withLock {
            cache[id]?.let { return it }
            fetchDetails(id)?.info
        }
    }

    // the two parts which are loaded from slack, used to report the progress
    enum class Part { PROFILE, MARKETPLACE }

    // fetches everything we know about the app, this is never cached here (but the slim AppInfo is updated in the cache)
    // onPartDone is called when a part is loaded (success = false if it failed)
    suspend fun getAppDetails(
        id: String,
        onPartDone: ((part: Part, success: Boolean) -> Unit)? = null
    ): AppMarketplaceDetails? {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.startsWith("A")) { "id must start with A" }
        require(canBeUsed()) { "Client tokens are not valid" }

        return fetchDetails(id, onPartDone)
    }

    private suspend fun fetchDetails(
        id: String,
        onPartDone: ((part: Part, success: Boolean) -> Unit)? = null
    ): AppMarketplaceDetails? = coroutineScope {
        val componentsDeferred = async {
            try {
                marketplaceData(id).also { onPartDone?.invoke(Part.MARKETPLACE, true) }
            } catch (e: Exception) {
                onPartDone?.invoke(Part.MARKETPLACE, false)
                throw e
            }
        }
        val appProfileDeferred = async {
            runCatching { appProfile(id) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }.getOrNull().also { onPartDone?.invoke(Part.PROFILE, it != null) }
        }

        val components = runCatching { componentsDeferred.await() }.getOrElse {
            logger.warn("Failed to get marketplace data for $id", it)
            null
        }
        val appProfile = runCatching { appProfileDeferred.await() }.getOrElse {
            logger.warn("Failed to get app profile for $id", it)
            null
        }

        if (components == null && appProfile == null) {
            logger.warn("Failed to get marketplace data and app profile for $id")
            return@coroutineScope null
        }

        val details = parseDetails(id, components.orEmpty(), appProfile)
        cache[id] = details.info
        details
    }

    private fun parseDetails(id: String, components: Map<String, JsonObject>, appProfile: JsonObject?): AppMarketplaceDetails {
        val additionalInfo = components["AppDirectoryAdditionalInfo"]
        val features = components["AppDirectoryFeatures"]
        val authorizations = components["AppDirectoryAuthorizations"]
        val botUserComponent = components["AppDirectoryBotUser"]

        val botUser = appProfile?.get("bot_user")?.jsonObjectOrNull

        val botUserId = botUser?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() && (it[0] == 'U' || it[0] == 'W') }

        val authorizationList = authorizations?.get("authorizations")?.jsonArrayOrNull
            ?.mapNotNull { it.jsonObjectOrNull }
            ?.map { auth ->
                AppAuthorization(
                    userId = auth.string("encodedUserLink")?.takeIf { it.isNotBlank() },
                    userDisplayName = auth.string("userDisplayName")?.takeIf { it.isNotBlank() },
                    date = auth["date"]?.jsonPrimitiveOrNull?.longOrNull,
                    description = auth.string("fullDescription")?.trim()?.takeIf { it.isNotBlank() },
                )
            }
            ?.filter { it.userId != botUserId }
            .orEmpty()

        val commandDetails = buildList {
            appProfile?.get("commands")?.jsonObjectOrNull?.values
                ?.mapNotNull { it.jsonObjectOrNull?.asCommandDetail() }
                ?.let { addAll(it) }
            features?.get("slashCommands")?.jsonArrayOrNull
                ?.mapNotNull { it.jsonObjectOrNull?.asCommandDetail() }
                ?.let { addAll(it) }
        }
            .groupBy { it.name }
            .map { (name, duplicates) ->
                AppCommandDetail(
                    name = name,
                    description = duplicates.firstNotNullOfOrNull { it.description.ifBlank { null } } ?: "",
                    usageHint = duplicates.firstNotNullOfOrNull { it.usageHint.ifBlank { null } } ?: "",
                    url = duplicates.firstNotNullOfOrNull { it.url?.ifBlank { null } },
                )
            }

        val channelIds = botUserComponent?.get("memberships")?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull ?: it.jsonObjectOrNull?.string("id") }
            .orEmpty()

        val info = AppInfo(
            id = id,
            publicChannelCount = botUserComponent?.get("memberships")?.jsonArrayOrNull?.size,
            otherChannelCount = botUserComponent?.get("otherGroups")?.jsonPrimitiveOrNull?.intOrNull,
            allChannelCount = botUser?.get("memberships_count")?.jsonPrimitiveOrNull?.intOrNull,
            botUserId = botUserId,
            isExternal = additionalInfo.flag("is_external"),
            isInstalled = appProfile.flag("is_installed"),
            isCertified = additionalInfo.flag("is_certified"),
            isAiApp = features.flag("is_ai_app") || features.flag("isAiApp"),
            isAgentApp = features.flag("is_agent_app") || features.flag("isAgentApp"),
            isSearchApp = features.flag("is_search_app") || features.flag("isSearchApp"),
            isAgentResponseModeEnabled = authorizations.flag("is_agent_response_mode_enabled"),
            dateInstalled = appProfile?.get("date_installed")?.jsonPrimitiveOrNull?.longOrNull,
            authorizationsCount = authorizationList.count { it.userId != null },
            commands = commandDetails.map { SlashCommand(it.name, it.description, it.usageHint) }
        )

        return AppMarketplaceDetails(
            info = info,
            name = features?.string("appName") ?: authorizations?.string("appName") ?: appProfile?.string("name"),
            botUsername = botUserComponent?.string("botUsername")?.takeIf { it.isNotBlank() },
            channelIds = channelIds,
            authorizations = authorizationList,
            globalShortcuts = features?.get("globalShortcuts").asShortcuts(),
            messageShortcuts = features?.get("messageShortcuts").asShortcuts(),
            commands = commandDetails,
            mcpServers = features?.get("mcpServers")?.jsonArrayOrNull
                ?.mapNotNull { it.jsonObjectOrNull }
                ?.map {
                    AppMcpServer(
                        name = it.string("name") ?: "Unnamed",
                        url = it.string("url"),
                        toolCount = it["tools"]?.jsonArrayOrNull?.size ?: 0
                    )
                }
                .orEmpty(),
            permissions = extractListItems(authorizations?.string("authSummary")),
            isCallsApp = features.flag("isCallsApp"),
            isOrgReadyApp = features.flag("isOrgReadyApp"),
            supportsEnterpriseManagedAuthorization = features.flag("appSupportsEnterpriseManagedAuthorization"),
        )
    }

    private fun JsonElement?.asShortcuts(): List<AppShortcut> = this?.jsonArrayOrNull
        ?.mapNotNull { it.jsonObjectOrNull }
        ?.map {
            AppShortcut(
                name = it.string("name") ?: "Unnamed",
                description = it.string("desc") ?: "",
                callbackId = it.string("callback_id")?.takeIf { id -> id.isNotBlank() }
            )
        }
        .orEmpty()

    private fun JsonObject.asCommandDetail(): AppCommandDetail? {
        val name = string("name") ?: return null
        return AppCommandDetail(
            name = name,
            description = string("desc") ?: string("description") ?: "",
            usageHint = string("usage") ?: string("usage_hint") ?: "",
            url = string("url")?.takeIf { it.isNotBlank() }
        )
    }

    private val listItemRegex = Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("<[^>]+>")
    private val whitespaceRegex = Regex("\\s+")

    private fun extractListItems(html: String?): List<String> {
        if (html.isNullOrBlank()) return emptyList()
        return listItemRegex.findAll(html)
            .map { decodeHtmlEntities(tagRegex.replace(it.groupValues[1], "")).replace(whitespaceRegex, " ").trim() }
            .filter { it.isNotBlank() }
            .toList()
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

        while (!channel.isClosedForRead && components.size < NEEDED_COMPONENTS.size) {
            try {
                channel.findString(COMPONENT_START) || break

                val componentName = channel.readUntil('"'.code.toByte(), maxLength = 200) ?: continue

                // The component name is followed by whitespace and data-automount-props.
                if (!channel.findString(PROPS_START)) {
                    logger.warn("Missing data-automount-props for component $componentName")
                    continue
                }

                if (componentName !in NEEDED_COMPONENTS) {
                    // we don't need it, so we don't even store it
                    channel.skipUntil('"'.code.toByte())
                    continue
                }

                val props = channel.readUntil('"'.code.toByte(), maxLength = MAX_PROPS_LENGTH)
                if (props == null) {
                    logger.warn("Props of component $componentName of $id are too large, ignoring them")
                    channel.skipUntil('"'.code.toByte())
                    continue
                }

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

        // we stop reading as soon as we have everything, so cancel the rest of the download
        channel.cancel(null)

        logger.debug("Found ${components.size} automount components for $id")

        return components
    }

    private val JsonElement.jsonPrimitiveOrNull get() = this as? JsonPrimitive
    private val JsonElement.jsonObjectOrNull get() = this as? JsonObject
    private val JsonElement.jsonArrayOrNull get() = this as? JsonArray

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull
    private fun JsonObject?.flag(key: String): Boolean = this?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull == true

    // reads until the delimiter, returns null (and stops reading) if the value is longer than maxLength
    private suspend fun ByteReadChannel.readUntil(delimiter: Byte, maxLength: Int): String? {
        val result = java.io.ByteArrayOutputStream()

        while (true) {
            val byte = readByte()

            if (byte == delimiter) {
                // the page is utf-8 (the old implementation converted every byte to a char, which broke non ascii names)
                return result.toString(Charsets.UTF_8)
            }
            if (result.size() >= maxLength) return null

            result.write(byte.toInt())
        }
    }

    private suspend fun ByteReadChannel.skipUntil(delimiter: Byte) {
        while (readByte() != delimiter) {
            // skip
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
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            // &amp; must be last, otherwise "&amp;quot;" would become a quote instead of "&quot;"
            .replace("&amp;", "&")
    }

    fun removeFromCache(appId: String) {
        cache.remove(appId)
    }
}
