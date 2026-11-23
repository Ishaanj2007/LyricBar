package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight, safe Markdown renderer for Compose.
 * Supports:
 * - Headers (# H1, ## H2, ### H3)
 * - Bullet lists (- item, * item)
 * - Numbered lists (1. item)
 * - Fenced code blocks (``` ... ```)
 * - Bold (**bold**) and Italic (*italic* / _italic_)
 * - Inline code (`code`)
 * - Links ([text](url)) with click-to-open
 *
 * Designed to never crash on malformed Markdown.
 */
@Composable
fun SimpleMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val parsedBlocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    if (parsedBlocks.isEmpty()) {
        Text(
            text = "No release notes provided.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (block in parsedBlocks) {
            when (block) {
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        else -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = style,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        MarkdownInteractiveText(
                            rawText = block.text,
                            onUrlClick = { url -> openUrlSafe(context, url) }
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        MarkdownInteractiveText(
                            rawText = block.text,
                            onUrlClick = { url -> openUrlSafe(context, url) }
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    MarkdownInteractiveText(
                        rawText = block.text,
                        onUrlClick = { url -> openUrlSafe(context, url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownInteractiveText(
    rawText: String,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val (annotatedString, urlRanges) = remember(rawText) { buildAnnotatedMarkdownWithUrls(rawText) }

    if (urlRanges.isEmpty()) {
        Text(
            text = annotatedString,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            ),
            modifier = modifier
        )
    } else {
        // If has links, we can use clickable Text or Row
        Text(
            text = annotatedString,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            ),
            modifier = modifier.clickable {
                // When clicked, if there is a primary URL, open it
                val firstUrl = urlRanges.firstOrNull()?.second
                if (firstUrl != null) {
                    onUrlClick(firstUrl)
                }
            }
        )
    }
}

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var index = 0
    val total = lines.size

    while (index < total) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            index++
            continue
        }

        // Fenced code block
        if (trimmed.startsWith("```")) {
            val codeLines = mutableListOf<String>()
            index++
            while (index < total && !lines[index].trim().startsWith("```")) {
                codeLines.add(lines[index])
                index++
            }
            if (index < total) index++ // skip closing ```
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
            continue
        }

        // Headers
        if (trimmed.startsWith("### ")) {
            blocks.add(MarkdownBlock.Header(3, trimmed.removePrefix("### ").trim()))
            index++
            continue
        }
        if (trimmed.startsWith("## ")) {
            blocks.add(MarkdownBlock.Header(2, trimmed.removePrefix("## ").trim()))
            index++
            continue
        }
        if (trimmed.startsWith("# ")) {
            blocks.add(MarkdownBlock.Header(1, trimmed.removePrefix("# ").trim()))
            index++
            continue
        }

        // Bullet lists
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            blocks.add(MarkdownBlock.BulletItem(trimmed.substring(2).trim()))
            index++
            continue
        }

        // Numbered lists (e.g. "1. Item")
        val numMatch = Regex("^([0-9]+)\\.\\s+(.*)").find(trimmed)
        if (numMatch != null) {
            val number = numMatch.groupValues[1]
            val rest = numMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(number, rest.trim()))
            index++
            continue
        }

        // Default: Paragraph
        blocks.add(MarkdownBlock.Paragraph(trimmed))
        index++
    }

    return blocks
}

private fun renderInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedMarkdownWithUrls(text).first
}

private fun buildAnnotatedMarkdownWithUrls(text: String): Pair<AnnotatedString, List<Pair<IntRange, String>>> {
    val urlRanges = mutableListOf<Pair<IntRange, String>>()
    val annotated = buildAnnotatedString {
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            // Check for links [label](url)
            if (text[cursor] == '[') {
                val closeBracket = text.indexOf(']', cursor)
                if (closeBracket > cursor && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 1)
                    if (closeParen > closeBracket + 1) {
                        val label = text.substring(cursor + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        val start = length
                        pushStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF90CAF9), textDecoration = TextDecoration.Underline))
                        append(label)
                        pop()
                        urlRanges.add(IntRange(start, length) to url)
                        cursor = closeParen + 1
                        continue
                    }
                }
            }

            // Check for bold **text**
            if (cursor + 1 < len && text.substring(cursor, cursor + 2) == "**") {
                val closeBold = text.indexOf("**", cursor + 2)
                if (closeBold != -1) {
                    val content = text.substring(cursor + 2, closeBold)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(content)
                    pop()
                    cursor = closeBold + 2
                    continue
                }
            }

            // Check for inline code `text`
            if (text[cursor] == '`') {
                val closeCode = text.indexOf('`', cursor + 1)
                if (closeCode != -1) {
                    val codeContent = text.substring(cursor + 1, closeCode)
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x33888888)))
                    append(" $codeContent ")
                    pop()
                    cursor = closeCode + 1
                    continue
                }
            }

            // Check for italic *text*
            if (text[cursor] == '*' && (cursor + 1 >= len || text[cursor + 1] != '*')) {
                val closeItalic = text.indexOf('*', cursor + 1)
                if (closeItalic != -1 && closeItalic > cursor + 1) {
                    val content = text.substring(cursor + 1, closeItalic)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(content)
                    pop()
                    cursor = closeItalic + 1
                    continue
                }
            }

            append(text[cursor])
            cursor++
        }
    }
    return annotated to urlRanges
}

private fun openUrlSafe(context: android.content.Context, url: String) {
    try {
        val trimmed = url.trim()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
