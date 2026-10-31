package com.fantamomo.slack.approver

import com.fantamomo.slack.approver.app.rootModule
import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.*
import com.fantamomo.slack.approver.utils.maskSlackToken
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Clock

object App {
    private val logger = LoggerFactory.getLogger(App::class.java)

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val onlyServer = System.getenv("ONLY_SERVER")?.equals("true", ignoreCase = true) == true

    suspend fun start() {
        // switching to application scope
        val job = scope.launch {
            run()
        }
        job.join()
    }

    private suspend fun run() = coroutineScope {
        if (onlyServer) {
            val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 80
            val host = System.getenv("SERVER_HOST") ?: "0.0.0.0"
            val server = embeddedServer(
                factory = Netty,
                port = port,
                host = host,
                module = Application::rootModule
            )
            server.startSuspend(wait = true)
            logger.info("Server stopped")
            return@coroutineScope
        }

        // before we do anything else, check that all the tokens we got
        if (!checkTokens()) {
            logger.error("Failed to check tokens, check logs above to see what went wrong, exiting...")
            exitProcess(1)
        }

        SharedData.workspaces = SlackManager.getWorkspaces() ?: return@coroutineScope

        logger.info(
            "Using ${SharedData.authorizedUserId} (${SharedData.authorizedUserName}) for " +
                    "approving/cancel/restricting apps in following workspaces: ${SharedData.workspaces.joinToString(", ")}"
        )

        try {
            DatabaseManager.init()
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            return@coroutineScope
        }

        try {
            SettingsManager.start()
        } catch (e: Exception) {
            logger.error("Failed to start settings manager", e)
            return@coroutineScope
        }

        launch { SettingsManager.run() }

        if (Config.SOCKET_MODE) {
            launch {
                SlackManager.connectViaSocket()
            }
        }

        if (SharedData.validClientTokens) {
            launch {
                AppCommandManager.start()
            }
        }

        launch {
            VerificationManager.start()
        }
        launch {
            AppManager.start()
        }

        if (!Config.SOCKET_MODE && Config.SIGNING_SECRET.isBlank()) {
            logger.warn("Socket Mode is disabled and no signing secret is provided")
            logger.warn("We are unable to verify requests from Slack, please provide a signing secret")
        }


        val server = embeddedServer(
            factory = Netty,
            port = Config.PORT,
            host = Config.HOST,
            module = Application::rootModule
        )
        server.startSuspend(wait = true)
        logger.info("Server stopped")
    }

    private suspend fun checkTokens(): Boolean = coroutineScope {
        require(Config.SLACK_BOT_TOKEN.startsWith("xoxb-")) { "A bot token must start with xoxb-" }
        require(Config.SLACK_USER_TOKEN.startsWith("xoxp-")) { "A user token must start with xoxp-" }
        require(!Config.SOCKET_MODE || Config.SLACK_APP_TOKEN.startsWith("xapp-")) { "When using Socket Mode, a Slack App Token must start with xapp-" }
        require(Config.SLACK_CLIENT_TOKEN.run { isEmpty() || startsWith("xoxc-") }) { "A client token must start with xoxc-" }
        require(Config.SLACK_SESSION_COOKIE.run { isEmpty() || startsWith("xoxd-") }) { "A session cookie must start with xoxd-" }
        require(Config.SLACK_CLIENT_TOKEN.isEmpty() == Config.SLACK_SESSION_COOKIE.isEmpty()) { "A client token and session cookie must be provided together" }

        val start = Clock.System.now()

        logger.info("Checking tokens")
        val slackBotToken = async {
            checkToken(
                Config.SLACK_BOT_TOKEN,
                mapOf(
                    "chat:write" to "For sending messages in the log/review channel and to the users",
                    "commands" to "Required for registering and receiving slash commands",
                    "im:write" to "For sending direct messages",
                    "im:read" to "For opening direct messages"
                )
            )
        }
        val slackUserToken = async {
            checkToken(
                Config.SLACK_USER_TOKEN,
                mapOf(
                    "admin.apps:read" to "For receiving app requests events",
                    "admin.apps:write" to "For approving/restricting/canceling app requests"
                )
            )
        }
        val slackAppToken = Config.SLACK_APP_TOKEN.ifBlank { null }?.let {
            async {
                checkToken(
                    it,
                    mapOf(
                        "connections:write" to "Required when using Socket Mode"
                    )
                )
            }
        }
        val slackClientToken = Config.SLACK_CLIENT_TOKEN.ifBlank { null }?.let {
            async {
                checkToken(
                    it,
                    null,
                    dCookie = Config.SLACK_SESSION_COOKIE
                )
            }
        }

        val botResponse = slackBotToken.await() ?: return@coroutineScope false
        val userResponse = slackUserToken.await() ?: return@coroutineScope false
        val appResponse = slackAppToken?.await()
        val clientResponse = slackClientToken?.await()

        val duration = Clock.System.now() - start
        logger.info("Checked tokens in $duration")

        val botEnterpriseId = (botResponse["enterprise_id"] as? JsonPrimitive)?.contentOrNull
        val userEnterpriseId = (userResponse["enterprise_id"] as? JsonPrimitive)?.contentOrNull
        val clientEnterpriseId = (clientResponse?.get("enterprise_id") as? JsonPrimitive)?.contentOrNull

        if (botEnterpriseId != userEnterpriseId) {
            logger.error("User token ($userEnterpriseId) is from a different enterprise than the bot token ($botEnterpriseId)")
            return@coroutineScope false
        }
        if (botEnterpriseId != clientEnterpriseId && clientResponse != null) {
            logger.error("Client token ($clientEnterpriseId) is from a different enterprise than the bot token ($botEnterpriseId)")
            return@coroutineScope false
        }
        SharedData.validClientTokens = clientResponse != null
        if (botEnterpriseId == null) {
            // we should never get here because Slack does not allow non-enterprise apps to access our required scopes
            logger.error("App is not installed in an enterprise organisation")
            return@coroutineScope false
        }

        val botEnterpriseInstalled = (botResponse["is_enterprise_install"] as? JsonPrimitive)?.booleanOrNull
        val userEnterpriseInstalled = (userResponse["is_enterprise_install"] as? JsonPrimitive)?.booleanOrNull

        if (botEnterpriseInstalled != true || userEnterpriseInstalled != true) {
            logger.error("App is not installed at the org level")
            return@coroutineScope false
        }

        SharedData.enterpriseId = botEnterpriseId
        val orgUrl = (botResponse["url"] as? JsonPrimitive)?.contentOrNull?.let { Url(it) }
        if (orgUrl == null) {
            logger.error("Could not find org url in response $botResponse")
            return@coroutineScope false
        }
        SharedData.slackOrgUrl = orgUrl
        val team = (botResponse["team"] as? JsonPrimitive)?.contentOrNull
        if (team == null) {
            logger.error("Could not find team in response $botResponse")
            return@coroutineScope false
        }
        SharedData.slackOrgName = team
        val botId = (botResponse["bot_id"] as? JsonPrimitive)?.contentOrNull
        if (botId == null) {
            logger.error("Could not find bot id in response $botResponse")
            return@coroutineScope false
        }
        SharedData.botId = botId
        val botUserId = (botResponse["user_id"] as? JsonPrimitive)?.contentOrNull
        if (botUserId == null) {
            logger.error("Could not find bot user id in response $botResponse")
            return@coroutineScope false
        }
        SharedData.botUserId = botUserId
        val userId = (userResponse["user_id"] as? JsonPrimitive)?.contentOrNull
        if (userId == null) {
            logger.error("Could not find user id in response $botResponse")
            return@coroutineScope false
        }
        SharedData.authorizedUserId = userId
        val userName = (userResponse["user"] as? JsonPrimitive)?.contentOrNull
        if (userName == null) {
            logger.error("Could not find user name in response $botResponse")
            return@coroutineScope false
        }
        SharedData.authorizedUserName = userName
        val appId = (appResponse?.get("app_id") as? JsonPrimitive)?.contentOrNull
        if (appResponse != null && appId == null) {
            logger.error("Could not find app id in response $appResponse")
            return@coroutineScope false
        }
        SharedData.appId = appId

        runCatching { SharedData.rawDCookie = false }

        return@coroutineScope true
    }

    private suspend fun checkToken(
        token: String,
        requiredScopes: Map<String, String>?,
        dCookie: String? = null
    ): JsonObject? {
        val (jsonObject, response) = checkToken(token, dCookie = dCookie) ?: return null
        if (requiredScopes == null) return jsonObject
        val scopeHeaders = response.headers["x-oauth-scopes"]
        if (scopeHeaders == null) {
            logger.error("No scope headers found for token $token")
            return null
        }
        val scopes = scopeHeaders.split(",")
        val missingScopes = requiredScopes.keys.filter { it !in scopes }
        if (missingScopes.isNotEmpty()) {
            val scopeSummary = missingScopes.joinToString(", ") { scope ->
                scope + (requiredScopes[scope]?.let { " ($it)" } ?: "")
            }
            logger.error("Token ${maskSlackToken(token)} is missing required scopes: $scopeSummary")
            return null
        }
        val unnecessaryScopes = scopes.filter { it !in requiredScopes }.filter { it != "identify" }
        if (unnecessaryScopes.isNotEmpty()) {
            logger.warn("Token ${maskSlackToken(token)} " +
                    "has unnecessary scopes: ${unnecessaryScopes.joinToString(", ")}")
            logger.warn("Please remove unnecessary scopes from the token, to follow the least privilege principle.")
        }
        return jsonObject
    }

    private suspend fun checkToken(
        token: String,
        dCookie: String?,
        rawTry: Boolean = false
    ): Pair<JsonObject, HttpResponse>? {
        try {
            val response = SharedData.httpClient.get("https://slack.com/api/auth.test") {
                bearerAuth(token)
                if (dCookie != null) {
                    if (rawTry) {
                        header("Cookie", "d=$dCookie")
                    } else {
                        cookie("d", dCookie)
                    }
                }
            }
            val text = response.bodyAsText()
            val jsonObject = SharedData.json.parseToJsonElement(text).jsonObject
            if ((jsonObject["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                val error = (jsonObject["error"] as? JsonPrimitive)?.contentOrNull
                if (dCookie != null && !rawTry && error == "invalid_auth") {
                    logger.warn("Invalid auth error, retrying with raw Cookie header")
                    // if you straight copy the dCookie from the it network tab, it might not work because it is URL encoded,
                    // but the cookie function which we use to pass the cooke to the server, also encodes it,
                    // so now it is encoded two times and the server will reject it,
                    // for that reason we try again with the raw Cookie header
                    return checkToken(token, dCookie = null, rawTry = true)
                }
                logger.error("Failed to check token ${maskSlackToken(token)}: ${error ?: jsonObject}")
                return null
            }
            runCatching { SharedData.rawDCookie = rawTry }
            return jsonObject to response
        } catch (e: Exception) {
            logger.error("Failed to check token ${maskSlackToken(token)}", e)
            return null
        }
    }
}