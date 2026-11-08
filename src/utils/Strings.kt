package com.fantamomo.slack.approver.utils

import kotlin.random.Random

fun String.substringBeforeCount(delimiter: String, count: Int): String =
    split(delimiter).take(count + 1).joinToString(delimiter)

private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/**
 * Generates a random string with the specified length using characters from the configured character set.
 *
 * @param length the number of characters to generate.
 * @return a random string containing the requested number of characters, or an empty string if length is not positive.
 */
fun randomString(length: Int): String = (1..length)
    .map { CHARS[Random.nextInt(CHARS.length)] }
    .joinToString("")


/**
 * Masks sensitive portions of a Slack token while preserving enough structure to identify its type
 * and selected leading or trailing characters.
 *
 * Blank tokens are represented as a not-set placeholder. For non-blank tokens, the token is split
 * by hyphens and each segment is masked according to its position and token prefix.
 *
 * @param token the Slack token to mask.
 * @return the masked token, or "<not set>" if the supplied token is blank.
 */
fun maskSlackToken(token: String): String {
    if (token.isBlank()) return "<not set>"

    val segments = token.split("-")

    return segments.mapIndexed { index, segment ->
        when {
            index == 0 -> segment

            index == 1 && segments[0] == "xapp" -> segment

            index == 2 || (index == 1 && segments[0] != "xapp") ->
                segment.take(4) + "*".repeat((segment.length - 4).coerceAtLeast(0))

            index == segments.lastIndex ->
                "*".repeat((segment.length - 4).coerceAtLeast(0)) + segment.takeLast(4)

            else -> "*".repeat(segment.length)
        }
    }.joinToString("-")
}
