package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.block.element.RichTextElement
import com.slack.api.model.kotlin_extension.block.composition.container.MultiRichTextObjectContainer
import com.slack.api.model.kotlin_extension.block.composition.dsl.RichTextObjectDsl
import com.slack.api.model.kotlin_extension.block.element.RichTextPreformattedElementBuilder
import com.slack.api.model.kotlin_extension.block.element.RichTextQuoteElementBuilder
import com.slack.api.model.kotlin_extension.block.element.RichTextSectionElementBuilder

private val preformattedElementContainerField = RichTextPreformattedElementBuilder::class.java.getDeclaredField("elementsContainer").apply { isAccessible = true }
private val quoteElementContainerField = RichTextQuoteElementBuilder::class.java.getDeclaredField("elementsContainer").apply { isAccessible = true }
private val sectionElementContainerField = RichTextSectionElementBuilder::class.java.getDeclaredField("elementsContainer").apply { isAccessible = true }

/**
 * Adds a rich text element to this rich text object DSL.
 *
 * Supports rich text object containers and rich text element builders for preformatted,
 * quote, and section elements. If this DSL instance is not backed by a supported
 * rich text element container, an IllegalArgumentException is thrown.
 *
 * @param element the rich text element to append
 * @throws IllegalArgumentException if this DSL instance is not a supported rich text element builder
 */
fun RichTextObjectDsl.addElement(element: RichTextElement) {
    val underlying = when (this) {
        is MultiRichTextObjectContainer -> underlying
        is RichTextPreformattedElementBuilder -> (preformattedElementContainerField.get(this) as MultiRichTextObjectContainer).underlying
        is RichTextQuoteElementBuilder -> (quoteElementContainerField.get(this) as MultiRichTextObjectContainer).underlying
        is RichTextSectionElementBuilder -> (sectionElementContainerField.get(this) as MultiRichTextObjectContainer).underlying
        else -> throw IllegalArgumentException("Unsupported rich text element builder: $this")
    }
    underlying += element
}