package com.fantamomo.slack.approver.app

import io.ktor.server.application.*

fun Application.rootModule() {
    // no rate limiter because we are running behind a proxy
//    configureRateLimiting()
    configureSerialization()
    configureRouting()
}
