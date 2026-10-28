package com.fireplace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A small, dependency-free Markdown renderer for assistant answers. It handles
 * the constructs a chat model actually emits - headings, bullet and numbered
 * lists, bold/italic, inline code - plus Fireplace's own `[n]` citation markers,
 * which are coloured so a grounded answer reads clearly. Deliberately minimal:
 * block-level parsing line by line, inline parsing with a single left-to-right
 * scan, so it stays fast and predictable while streaming.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier) {
        var first = true
        for (block in blocks(text)) {
            if (!first) Spacer(Modifier.height(if (block is Block.Heading) 10.dp else 6.dp))
            first = false
            when (block) {
                is Block.Heading -> Text(
                    inline(block.text, primary, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                is Block.Bullet -> ListItem("•", block.text, primary, codeBg)
                is Block.Numbered -> ListItem("${block.n}.", block.text, primary, codeBg)
                is Block.Para -> Text(inline(block.text, primary, codeBg), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ListItem(marker: String, text: String, primary: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(start = 4.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
        Text(inline(text, primary, codeBg), style = MaterialTheme.typography.bodyLarge)
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Numbered(val n: Int, val text: String) : Block
    data class Para(val text: String) : Block
}

private val HEADING = Regex("^(#{1,3})\\s+(.*)")
private val BULLET = Regex("^\\s*[-*]\\s+(.*)")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)")

/** Split text into block-level pieces; consecutive plain lines merge into one paragraph. */
private fun blocks(text: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(Block.Para(para.toString().trim())); para.setLength(0) }
    for (raw in text.split("\n")) {
        val line = raw.trimEnd()
        val h = HEADING.matchEntire(line); val b = BULLET.matchEntire(line); val n = NUMBERED.matchEntire(line)
        when {
            line.isBlank() -> flush()
            h != null -> { flush(); out.add(Block.Heading(h.groupValues[1].length, h.groupValues[2])) }
            b != null -> { flush(); out.add(Block.Bullet(b.groupValues[1])) }
            n != null -> { flush(); out.add(Block.Numbered(n.groupValues[1].toInt(), n.groupValues[2])) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
        }
    }
    flush()
    return out
}

/** Parse inline bold, italic, inline code, and [n] citation markers. */
private fun inline(s: String, primary: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '*' && i + 1 < s.length && s[i + 1] == '*' -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 } else { append(c); i++ }
            }
            (c == '*' || c == '_') -> {
                val end = s.indexOf(c, i + 1)
                if (end > i + 1) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(c); i++ }
            }
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(c); i++ }
            }
            c == '[' && i + 1 < s.length && s[i + 1].isDigit() -> {
                val end = s.indexOf(']', i + 1)
                if (end > 0 && s.substring(i + 1, end).all { it.isDigit() }) {
                    withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(s.substring(i, end + 1)) }; i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
