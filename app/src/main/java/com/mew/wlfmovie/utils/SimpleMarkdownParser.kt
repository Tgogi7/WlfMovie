package com.mew.wlfmovie.utils

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * WLFMOVIE: Parser simple de markdown para el changelog.
 *
 * Soporta:
 * - ## Header → texto grande bold
 * - ### Header → texto mediano bold
 * - **bold** → bold
 * - - item → bullet point
 * - Líneas vacías → separación
 * - Texto normal
 *
 * No usa librerías externas — solo SpannableStringBuilder.
 */
object SimpleMarkdownParser {

    fun parse(markdown: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = markdown.split("\n")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            when {
                // Header ##
                trimmed.startsWith("## ") -> {
                    val text = trimmed.removePrefix("## ").trim()
                    val start = builder.length
                    builder.append(text)
                    builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.3f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append("\n")
                }
                // Header ###
                trimmed.startsWith("### ") -> {
                    val text = trimmed.removePrefix("### ").trim()
                    val start = builder.length
                    builder.append(text)
                    builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.15f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append("\n")
                }
                // Bullet list
                trimmed.startsWith("- ") -> {
                    val text = parseBold(trimmed.removePrefix("- ").trim())
                    val start = builder.length
                    builder.append("•  ")
                    builder.append(text)
                    // BulletSpan con color requiere API 28+, usar versión simple
                    builder.setSpan(BulletSpan(20), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append("\n")
                }
                // Empty line
                trimmed.isEmpty() -> {
                    builder.append("\n")
                }
                // Normal text (con bold)
                else -> {
                    val text = parseBold(trimmed)
                    builder.append(text)
                    builder.append("\n")
                }
            }
        }

        return builder
    }

    /**
     * Parsea **bold** dentro de una línea y aplica el span.
     */
    private fun parseBold(text: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val regex = Regex("\\*\\*(.+?)\\*\\*")
        var lastIndex = 0

        for (match in regex.findAll(text)) {
            // Texto antes del bold
            if (match.range.first > lastIndex) {
                builder.append(text.substring(lastIndex, match.range.first))
            }
            // Texto en bold
            val boldText = match.groupValues[1]
            val start = builder.length
            builder.append(boldText)
            builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            lastIndex = match.range.last + 1
        }

        // Texto restante
        if (lastIndex < text.length) {
            builder.append(text.substring(lastIndex))
        }

        return builder
    }
}
