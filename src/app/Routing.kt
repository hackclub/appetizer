
package com.fantamomo.slack.approver.app

import com.fantamomo.slack.approver.App
import com.fantamomo.slack.approver.data.Config
import com.fantamomo.slack.approver.data.SharedData
import com.fantamomo.slack.approver.manager.AppManager
import com.fantamomo.slack.approver.model.AppRequested
import com.fantamomo.slack.approver.slack.SlackInteractionHandler
import com.fantamomo.slack.approver.slack.SlackWorkflowService
import com.fantamomo.slack.approver.utils.SlackRequestVerifier
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private object Routing

private val logger = LoggerFactory.getLogger(Routing::class.java)

fun Application.configureRouting() {
    val slackSigningSecret =
        runCatching { Config.SIGNING_SECRET }.getOrNull()
            ?: System.getenv("SIGNING_SECRET")
            ?: ""

    routing {
        get("/") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        post("/slack/events") {
            handleSlackEndpoint(
                call = call,
                scope = App.scope,
                signingSecret = slackSigningSecret
            )
        }

        post("/slack/actions") {
            handleSlackEndpoint(
                call = call,
                scope = App.scope,
                signingSecret = slackSigningSecret
            )
        }

        post("/slack/interactive") {
            handleSlackEndpoint(
                call = call,
                scope = App.scope,
                signingSecret = slackSigningSecret
            )
        }

        post("/slack/commands") {
            handleSlackEndpoint(
                call = call,
                scope = App.scope,
                signingSecret = slackSigningSecret
            )
        }
    }
}

private suspend fun handleSlackEndpoint(
    call: ApplicationCall,
    scope: CoroutineScope,
    signingSecret: String
) {
    val rawBody = call.receiveText()
    val contentType = call.request.contentType()

    if (
        !SlackRequestVerifier.verify(
            call.request,
            rawBody,
            signingSecret
        )
    ) {
        logger.warn("Invalid Slack request signature")
        call.respond(
            HttpStatusCode.Unauthorized,
            "Invalid Slack signature"
        )
        return
    }

    if (contentType.match(ContentType.Application.FormUrlEncoded)) {
        val params = try {
            rawBody.parseUrlEncodedParameters()
        } catch (e: Exception) {
            logger.warn("Could not parse Slack form body", e)
            call.respond(
                HttpStatusCode.BadRequest,
                "Invalid form body"
            )
            return
        }

        val payload = params["payload"]

        if (!payload.isNullOrBlank()) {
            call.respond(HttpStatusCode.OK)

            scope.launch {
                try {
                    SlackInteractionHandler.handlePayload(payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to handle Slack interaction", e)
                }
            }
            return
        }

        val command = params["command"]

        if (!command.isNullOrBlank()) {
            logger.info(
                "Received Slack slash command: {} from user {}",
                command,
                params["user_id"]
            )

            val commandPayload = buildJsonObject {
                put("type", "slash_commands")
                put("command", command)
                put("text", params["text"].orEmpty())
                put("user_id", params["user_id"].orEmpty())
                put("user_name", params["user_name"].orEmpty())
                put("channel_id", params["channel_id"].orEmpty())
                put("channel_name", params["channel_name"].orEmpty())
                put("team_id", params["team_id"].orEmpty())
                put("team_domain", params["team_domain"].orEmpty())
                put("response_url", params["response_url"].orEmpty())
                put("trigger_id", params["trigger_id"].orEmpty())
            }

            call.respond(HttpStatusCode.OK)

            scope.launch {
                try {
                    SlackInteractionHandler.handlePayload(
                        commandPayload.toString()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to handle Slack slash command: {}",
                        command,
                        e
                    )
                }
            }
            return
        }

        logger.warn("Slack form request contained neither payload nor command")
        call.respond(
            HttpStatusCode.BadRequest,
            "Missing Slack payload or command"
        )
        return
    }

    val body = try {
        SharedData.json.parseToJsonElement(rawBody) as? JsonObject
    } catch (e: Exception) {
        logger.warn("Could not parse JSON from request body", e)
        null
    }

    if (body == null) {
        logger.warn("Invalid request body received, expected a JSON object")
        call.respondText(
            "Invalid request body",
            status = HttpStatusCode.BadRequest
        )
        return
    }

    when (body["type"]?.jsonPrimitive?.contentOrNull) {
        "url_verification" -> {
            val challenge =
                body["challenge"]?.jsonPrimitive?.contentOrNull

            if (challenge.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Missing Slack challenge"
                )
            } else {
                call.respond(
                    buildJsonObject {
                        put("challenge", challenge)
                    }
                )
            }
        }

        "event_callback" -> {
            val eventPayload = body["event"] as? JsonObject

            if (eventPayload == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid event payload"
                )
                return
            }

            call.respond(HttpStatusCode.OK)

            scope.launch {
                try {
                    when (
                        eventPayload["type"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                    ) {
                        "app_requested" -> {
                            val requestElement =
                                eventPayload["app_request"] ?: eventPayload

                            val appRequested =
                                SharedData.json.decodeFromJsonElement(
                                    AppRequested.serializer(),
                                    requestElement
                                )

                            AppManager.incomingAppRequest(appRequested)
                        }

                        "app_home_opened" -> {
                            val userId =
                                eventPayload["user"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull

                            if (!userId.isNullOrBlank()) {
                                SlackWorkflowService.handleAppHomeOpened(
                                    userId
                                )
                            }
                        }

                        else -> {
                            logger.debug(
                                "Ignoring Slack event type: {}",
                                eventPayload["type"]
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to process Slack event", e)
                }
            }
        }

        "block_actions", "view_submission" -> {
            call.respond(HttpStatusCode.OK)

            scope.launch {
                try {
                    SlackInteractionHandler.handlePayload(rawBody)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to handle Slack interaction", e)
                }
            }
        }

        else -> {
            logger.debug(
                "Ignoring Slack request type: {}",
                body["type"]
            )
            call.respond(HttpStatusCode.OK)
        }
    }
}
