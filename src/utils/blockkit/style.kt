package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.block.element.RichTextSectionElement

/**
 * Creates a rich text style configuration for Slack rich text section elements.
 *
 * @param bold whether the text should be bold
 * @param italic whether the text should be italic
 * @param strike whether the text should be displayed with strikethrough
 * @param highlight whether the text should be highlighted
 * @param clientHighlight whether the text should use client-side highlighting
 * @param underline whether the text should be underlined
 * @param unlink whether links should be displayed as plain text without link behavior
 * @param code whether the text should be displayed as inline code
 * @return a rich text section text style with the specified formatting options
 */
fun TextStyle(
    bold: Boolean = false,
    italic: Boolean = false,
    strike: Boolean = false,
    highlight: Boolean = false,
    clientHighlight: Boolean = false,
    underline: Boolean = false,
    unlink: Boolean = false,
    code: Boolean = false
): RichTextSectionElement.TextStyle =
    RichTextSectionElement.TextStyle(bold, italic, strike, highlight, clientHighlight, underline, unlink, code)

/**
 * Creates a limited rich text style configuration for Slack rich text section elements.
 *
 * @param bold whether the text should be rendered in bold
 * @param italic whether the text should be rendered in italic
 * @param strike whether the text should be rendered with strikethrough
 * @param highlight whether the text should be rendered with highlight styling
 * @param clientHighlight whether the text should use client-side highlight styling
 * @param underline whether the text should be rendered with underline
 * @param unlink whether links should be rendered as plain text without link behavior
 * @return a limited text style instance with the specified style flags
 */
fun LimitedTextStyle(
    bold: Boolean = false,
    italic: Boolean = false,
    strike: Boolean = false,
    highlight: Boolean = false,
    clientHighlight: Boolean = false,
    underline: Boolean = false,
    unlink: Boolean = false
): RichTextSectionElement.LimitedTextStyle =
    RichTextSectionElement.LimitedTextStyle(bold, italic, strike, highlight, clientHighlight, underline, unlink)