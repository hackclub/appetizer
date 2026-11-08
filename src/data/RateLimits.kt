package com.fantamomo.slack.approver.data

import com.fantamomo.slack.approver.App
import com.fantamomo.slack.approver.utils.RateLimitAvoider

// collections of RateLimitAvoider instances to ... well avoid rate limits
object RateLimits {
    val ADMIN_APPS_REQUESTS_LIST = create(20)       // https://docs.slack.dev/reference/methods/admin.apps.requests.list
    val ADMIN_APPS_REQUESTS_CANCEL = create(20)     // https://docs.slack.dev/reference/methods/admin.apps.requests.cancel
    val ADMIN_APPS_RESTRICT = create(20)            // https://docs.slack.dev/reference/methods/admin.apps.restrict
    val ADMIN_APPS_APPROVE = create(20)             // https://docs.slack.dev/reference/methods/admin.apps.approve
    val ADMIN_APPS_CLEAR_RESOLUTION = create(20)    // https://docs.slack.dev/reference/methods/admin.apps.clearResolution
    val CHAT_POST_MESSAGE = create(60)              // https://docs.slack.dev/reference/methods/chat.postMessage
    val CHAT_POST_EPHEMERAL = create(100)           // https://docs.slack.dev/reference/methods/chat.postEphemeral
    val CHAT_UPDATE = create(50)                    // https://docs.slack.dev/reference/methods/chat.update
    val CONVERSATION_OPEN = create(50)              // https://docs.slack.dev/reference/methods/conversations.open
    val VIEWS_OPEN = create(100)                    // https://docs.slack.dev/reference/methods/views.open
    val VIEWS_PUBLISH = create(100)                 // https://docs.slack.dev/reference/methods/views.publish

    val AUTH_CHECK = create(60)                     // https://auth.hackclub.com/docs/api

    private fun create(perMinute: Int) = RateLimitAvoider(perMinute, App.scope)
}