package com.fantamomo.slack.approver.slack.appinfo

import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.RateLimits
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.InstallRequestRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

// resolves what a user typed (a bot mention, an app/bot/user id, a request id, a marketplace url or an app name) to an app
object AppLookup {
    private val logger = LoggerFactory.getLogger(AppLookup::class.java)

    sealed interface Result {
        // requestId is set if the user looked up a request, so the modal can highlight it
        data class Found(val appId: String, val requestId: String? = null) : Result
        data class NotFound(val message: String) : Result
    }

    // all those regexes are matched against the trimmed input
    private val USER_MENTION = Regex("^<@([UW][A-Z0-9]+)(?:\\|[^>]*)?>$")
    private val USER_ID = Regex("^[UW][A-Z0-9]{6,}$")
    private val BOT_ID = Regex("^B[A-Z0-9]{6,}$")
    private val APP_ID = Regex("^A[A-Z0-9]{6,}$")
    private val REQUEST_ID = Regex("^Ar[A-Z0-9]{6,}$")
    // e.g. https://slack.com/marketplace/A012L8JFBBR-some-name or https://x.slack.com/apps/A012L8JFBBR
    private val APP_URL = Regex("""^<?https?://[^\s/]*slack\.com/(?:marketplace|apps)/(A[A-Z0-9]{6,})(?:[-/?#|>].*)?$""")
    private val NAME = Regex("^@?[\\p{L}\\p{N} ._'-]{2,80}$")

    const val USAGE = "Use a bot mention (`@MyApp`), an app id (`A…`), a bot id (`B…`), the user id of the bot (`U…`), " +
            "a request id (`Ar…`), a marketplace link or the name of an app."

    suspend fun resolve(rawInput: String): Result {
        val input = rawInput.trim().removeSurrounding("`")
        if (input.isEmpty()) return Result.NotFound("Please tell me which app you want to look up. $USAGE")

        REQUEST_ID.matchEntire(input)?.let {
            val request = InstallRequestRepository.getRequest(input)
                ?: return Result.NotFound("There is no request with the id `$input`.")
            return Result.Found(request.appId, request.requestId)
        }
        APP_ID.matchEntire(input)?.let { return Result.Found(input) }
        APP_URL.matchEntire(input)?.let { return Result.Found(it.groupValues[1]) }
        USER_MENTION.matchEntire(input)?.let { return resolveUser(it.groupValues[1]) }

        // an all caps app name (e.g. "WEATHERBOT") looks like an id, so if it is not an id we search for the name
        val idResult = when {
            USER_ID.matches(input) -> resolveUser(input)
            BOT_ID.matches(input) -> resolveBot(input)
            else -> null
        }
        if (idResult is Result.Found) return idResult
        val nameResult = if (NAME.matches(input)) resolveName(input) else null
        if (nameResult is Result.Found || idResult == null) {
            return nameResult ?: Result.NotFound("I don't understand `${input.take(100)}`. $USAGE")
        }
        return idResult
    }

    private suspend fun resolveName(input: String): Result {
        val name = input.removePrefix("@").trim()
        val candidates = InstallRequestRepository.findAppIdsByName(name)
        val exact = candidates.filter { it.second.equals(name, ignoreCase = true) }
        return when {
            exact.size == 1 -> Result.Found(exact.single().first)
            candidates.size == 1 -> Result.Found(candidates.single().first)
            candidates.isEmpty() -> Result.NotFound(
                "I could not find an app called `$name`. Only apps which were requested at least once can be found by name. $USAGE"
            )

            else -> Result.NotFound(
                "There are multiple apps matching `$name`, please use one of their ids:\n" +
                        candidates.joinToString("\n") { "• ${it.second}: `${it.first}`" }
            )
        }
    }

    private suspend fun resolveUser(userId: String): Result {
        val user = callApi("users.info", "user", userId, RateLimits.USERS_INFO)?.get("user") as? JsonObject
            ?: return Result.NotFound("I could not load the user <@$userId>. Try the app id (`A…`) instead.")

        val profile = user["profile"] as? JsonObject
        val appId = (profile?.get("api_app_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (appId != null) return Result.Found(appId)

        val botId = (profile?.get("bot_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (botId != null) return resolveBot(botId)

        val isBot = (user["is_bot"] as? JsonPrimitive)?.booleanOrNull == true
        return if (isBot) {
            Result.NotFound("<@$userId> is a bot, but Slack did not tell me which app it belongs to. Try the app id (`A…`) instead.")
        } else {
            Result.NotFound("<@$userId> is a person, not an app.\n\n$USAGE")
        }
    }

    private suspend fun resolveBot(botId: String): Result {
        val bot = callApi("bots.info", "bot", botId, RateLimits.BOTS_INFO)?.get("bot") as? JsonObject
            ?: return Result.NotFound("I could not load the bot `$botId`. Try the app id (`A…`) instead.")
        val appId = (bot["app_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return Result.NotFound("The bot `$botId` does not belong to an app (it may be a legacy integration).")
        return Result.Found(appId)
    }

    // the bot token normally lacks users:read, so we prefer the client token (if configured) and fall back to the bot token
    private suspend fun callApi(
        method: String,
        parameterName: String,
        value: String,
        rateLimit: com.fantamomo.slack.approver.utils.RateLimitAvoider
    ): JsonObject? {
        val useClientToken = SharedData.validClientTokens
        return try {
            val response = rateLimit.withLimit {
                SharedData.httpClient.get("https://slack.com/api/$method") {
                    parameter(parameterName, value)
                    if (useClientToken) {
                        parameter("token", Config.SLACK_CLIENT_TOKEN)
                        if (SharedData.rawDCookie) {
                            header("Cookie", "d=${Config.SLACK_SESSION_COOKIE}")
                        } else {
                            cookie("d", Config.SLACK_SESSION_COOKIE)
                        }
                    } else {
                        bearerAuth(Config.SLACK_BOT_TOKEN)
                    }
                }
            }
            val json = SharedData.json.parseToJsonElement(response.bodyAsText()).jsonObject
            if ((json["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                logger.warn("$method failed for $value: ${json["error"]}")
                null
            } else json
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Error calling $method for $value", e)
            null
        }
    }
}
