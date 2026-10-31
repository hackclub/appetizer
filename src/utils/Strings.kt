package com.fantamomo.slack.approver.utils

import kotlin.random.Random

fun String.substringBeforeCount(delimiter: String, count: Int): String =
    split(delimiter).take(count + 1).joinToString(delimiter)

private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

fun randomString(length: Int): String = (1..length)
    .map { CHARS[Random.nextInt(CHARS.length)] }
    .joinToString("")


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
