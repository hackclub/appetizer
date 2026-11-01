package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.block.element.RichTextElement
import com.slack.api.model.block.element.RichTextSectionElement
import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl

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

fun RichTextObjectDsl.tag(text: String, color: TagColor? = null, style: RichTextSectionElement.LimitedTextStyle? = null) {
    addElement(RichTextTagElement(text, color?.value, style))
}