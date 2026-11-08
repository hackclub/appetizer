package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.block.element.RichTextElement
import com.slack.api.model.block.element.RichTextSectionElement
import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl

/**
 * Represents a Slack rich text tag element.
 *
 * A tag element renders text as a labeled segment within Slack rich text content and can
 * optionally include a Slack-supported tag color and limited text styling.
 *
 * @param text the text displayed inside the tag
 * @param color the Slack tag color value, or null to use the default tag color
 * @param style optional limited rich text styling applied to the tag text
 */
@Suppress("unused") // all properties are used by gson
class RichTextTagElement(
    val text: String,
    val color: String? = null,
    val style: RichTextSectionElement.LimitedTextStyle? = null
) : RichTextElement {
    private val type: String = TYPE

    override fun getType() = TYPE

    companion object {
        @JvmStatic
        val TYPE: String = "tag"
    }
}

/**
 * Defines the supported color names for Slack rich text tag elements.
 *
 * Each enum constant corresponds to a Slack-compatible tag color identifier used when
 * rendering a tag within a rich text block. Use a nullable color when the default Slack
 * tag styling should be applied.
 */
enum class TagColor(val value: String) {
    GRAY("gray"),
    BROWN("brown"),
    PURPLE("purple"),
    INDIGO("indigo"),
    BLUE("blue"),
    GREEN("green"),
    YELLOW("yellow"),
    ORANGE("orange"),
    RED("red")
}

/**
 * Adds a Slack rich text tag element to this rich text object.
 *
 * The tag displays the supplied text as a labeled segment and may use an optional
 * Slack-supported tag color and limited text styling.
 *
 * @param text the text displayed inside the tag
 * @param color the tag color to apply, or null to use the default Slack tag color
 * @param style optional limited rich text styling applied to the tag text
 * @throws IllegalArgumentException if this DSL instance is not a supported rich text element builder
 */
fun RichTextObjectDsl.tag(text: String, color: TagColor? = null, style: RichTextSectionElement.LimitedTextStyle? = null) {
    addElement(RichTextTagElement(text, color?.value, style))
}