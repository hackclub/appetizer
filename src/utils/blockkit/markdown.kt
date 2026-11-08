package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl

private val regex = Regex(":(\\w+):|<@([UW]\\w+)>")

/**
 * Adds the supplied markdown-like text to this rich text object.
 *
 * Recognized tokens are converted into rich text elements, such as emoji and user mentions,
 * while all unmatched content is added as plain text in its original order.
 * Empty input is ignored.
 *
 * @param text the markdown-like text to append to this rich text object
 */
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