package com.fantamomo.slack.approver.app

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun Application.configureSerialization() {
    // do we need this, i don't know
    install(ContentNegotiation) {
        json()
    }
}