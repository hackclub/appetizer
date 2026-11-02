package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl

private val regex = Regex(":(\\w+):|<@([UW]\\w+)>")

fun RichTextObjectDsl.addMarkdown(text: String) {
    if (text.isEmpty()) return

    var lastIndex = 0

    regex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            text(text.substring(lastIndex, match.range.first))
        }

        when {
            match.groups[1] != null -> {
                emoji(match.groups[1]!!.value)
            }

            match.groups[2] != null -> {
                user(match.groups[2]!!.value)
            }
        }

        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        text(text.substring(lastIndex))
    }
}