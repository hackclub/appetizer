package com.fantamomo.slack.approver.utils.blockkit

import com.slack.api.model.block.element.RichTextSectionElement

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