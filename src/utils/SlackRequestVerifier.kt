package com.fantamomo.slack.approver.utils

import io.ktor.server.request.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// helper class to verify requests from slack
object SlackRequestVerifier {
    private val logger = LoggerFactory.getLogger(SlackRequestVerifier::class.java)

    private const val VERSION = "v0"
    // the maximum time difference we allow when receiving requests from slack
    private const val MAX_REQUEST_AGE_SECONDS = 60L * 5

    @Suppress("UastIncorrectHttpHeaderInspection")
    fun verify(
        call: ApplicationRequest,
        rawBody: String,
        signingSecret: String
    ): Boolean {
        // no signing secret -> allow
        if (signingSecret == "") return true
        val timestamp = call.headers["X-Slack-Request-Timestamp"] ?: return false // when slack send there request
        val slackSignature = call.headers["X-Slack-Signature"] ?: return false // the signature of the request
        val timestampLong = timestamp.toLongOrNull() ?: return false

        val currentTimestamp = System.currentTimeMillis() / 1000
        if (abs(currentTimestamp - timestampLong) > MAX_REQUEST_AGE_SECONDS) {
            logger.warn("Received request but the time difference is to high (${currentTimestamp - timestampLong})")
            return false
        }

        val baseString = "$VERSION:$timestamp:$rawBody"
        val expectedSignature = hmacSha256(signingSecret, baseString)

        // we use MessageDigest.isEqual, because it takes exact the same time, regardless of the slackSignature from the header
        val isSignatureValid = MessageDigest.isEqual(
            expectedSignature.toByteArray(Charsets.UTF_8),
            slackSignature.toByteArray(Charsets.UTF_8)
        )
        if (!isSignatureValid) {
            logger.warn("Received request, but the expected signature is different from the what the client send")
        } else {
            logger.debug("Successfully verified the request from slack")
        }
        return isSignatureValid
    }

    private fun hmacSha256(
        secret: String,
        value: String // the format is "<version>:<timestamp>:<rawBody>
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

        mac.init(secretKey)

        val hash = mac.doFinal(value.toByteArray(Charsets.UTF_8))

        return "$VERSION=${hash.toHexString()}"
    }
}