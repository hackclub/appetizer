package com.fantamomo.slack.approver.data

import com.fantamomo.slack.approver.utils.oneTimeSetProperty
import com.google.gson.Gson
import com.slack.api.util.json.GsonFactory
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

// some data which is used all around the code base (SharedData has a currently 100 times referenced)
object SharedData {
    // the real json
    val json = Json { ignoreUnknownKeys = true }

    // that i even need to use this: TODO: switch completely to kotlinx.serialization.json
    val gson: Gson = GsonFactory.createSnakeCase()

    val httpClient = HttpClient(OkHttp) {
        install(WebSockets)
        install(ContentNegotiation) {
            // do i even need this, i dont know: TODO: figure this out
            json(json)
        }
    }

    // those are all com.fantamomo.slack.approver.utils.OneTimeSetProperty, which can only be set onetime (who thought)

    var slackOrgUrl: Url by oneTimeSetProperty()

    var slackOrgName: String by oneTimeSetProperty()

    var enterpriseId: String by oneTimeSetProperty()
    var appId: String? by oneTimeSetProperty()
    var botId: String by oneTimeSetProperty()
    var botUserId: String by oneTimeSetProperty()
    var authorizedUserId: String by oneTimeSetProperty()
    var authorizedUserName: String by oneTimeSetProperty()
    var workspaces: List<String> by oneTimeSetProperty()
    var validClientTokens: Boolean by oneTimeSetProperty()

    // if the session cookie we got is raw or URL encoded
    var rawDCookie: Boolean by oneTimeSetProperty()
}