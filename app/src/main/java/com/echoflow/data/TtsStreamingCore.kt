package com.echoflow.data

import java.text.BreakIterator
import java.util.Locale

data class TtsOptions(
    val baseUrl: String = "http://192.168.1.117:7788",
    val voice: String = "M1",
    /** null means Auto: omit lang and let the active Supertonic model use its fallback. */
    val language: String? = null,
    val speed: Float = 1.0f,
    val steps: Int = 8,
    val silenceDuration: Float = 0.3f,
)

/**
 * Positive allowlist at the product boundary. The TTS backends receive only spoken text,
 * numbers, whitespace, and punctuation that carries phrasing or intonation.
 */
object TtsTextNormalizer {
    private val spokenPunctuation = setOf('.', ',', '!', '?', ';', ':', '\'', '"', '(', ')', '-')

    fun normalize(text: String): String {
        val result = StringBuilder(text.length)
        var offset = 0
        var acceptsCombiningMark = false

        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)

            val canonical = canonicalPunctuation(codePoint)
            if (canonical != null) {
                result.append(canonical)
                acceptsCombiningMark = false
                continue
            }

            val type = Character.getType(codePoint)
            when {
                Character.isWhitespace(codePoint) -> {
                    result.append(' ')
                    acceptsCombiningMark = false
                }
                codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in spokenPunctuation -> {
                    result.appendCodePoint(codePoint)
                    acceptsCombiningMark = false
                }
                Character.isLetter(codePoint) || Character.isDigit(codePoint) -> {
                    result.appendCodePoint(codePoint)
                    acceptsCombiningMark = true
                }
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt() -> {
                    if (acceptsCombiningMark) result.appendCodePoint(codePoint)
                }
                type == Character.FORMAT.toInt() -> Unit
                else -> {
                    // A separator prevents rejected symbols from joining neighboring words.
                    result.append(' ')
                    acceptsCombiningMark = false
                }
            }
        }

        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun canonicalPunctuation(codePoint: Int): String? = when (codePoint) {
        0x2013, 0x2011, 0x2014 -> "-" // dash variants
        0x201C, 0x201D, 0x201E, 0x201F, 0x00AB, 0x00BB -> "\"" // double quotes
        0x2018, 0x2019, 0x201A, 0x201B, 0x2039, 0x203A, 0x00B4, 0x0060 -> "'"
        0x2026 -> "..."
        else -> null
    }
}

/** Small first request for fast speech, denser later requests to stay ahead of playback. */
object TtsTextChunker {
    fun chunk(
        text: String,
        firstMaxChars: Int = 140,
        laterMaxChars: Int = 280,
    ): List<String> {
        require(firstMaxChars > 0 && laterMaxChars > 0)
        val clean = TtsTextNormalizer.normalize(text)
        if (clean.isEmpty()) return emptyList()

        val sentences = sentenceFragments(clean, laterMaxChars).toMutableList()
        if (sentences.isEmpty()) return emptyList()
        if (sentences.first().length > firstMaxChars) {
            val firstPieces = mutableListOf<String>()
            splitOversized(sentences.removeAt(0), firstMaxChars, firstPieces)
            sentences.addAll(0, firstPieces)
        }

        val result = mutableListOf<String>()
        var current = StringBuilder()
        var limit = firstMaxChars

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString().trim()
                current = StringBuilder()
                limit = laterMaxChars
            }
        }

        sentences.forEach { sentence ->
            // Do not wait for a second sentence before sending the first one.
            if (result.isEmpty() && current.isNotEmpty()) flush()
            if (current.isNotEmpty() && current.length + 1 + sentence.length > limit) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
            if (current.length >= limit) flush()
        }
        flush()
        return result
    }

    private fun sentenceFragments(text: String, maxChars: Int): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag("bg"))
        iterator.setText(text)
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            splitOversized(text.substring(start, end).trim(), maxChars, sentences)
            start = end
            end = iterator.next()
        }
        if (sentences.isEmpty()) splitOversized(text, maxChars, sentences)
        return sentences
    }

    private fun splitOversized(value: String, maxChars: Int, out: MutableList<String>) {
        var rest = value.trim()
        while (rest.length > maxChars) {
            val comma = rest.lastIndexOfAny(charArrayOf(',', ';', ':', '—', '-'), maxChars)
            val space = rest.lastIndexOf(' ', maxChars)
            val cut = when {
                comma >= maxChars / 2 -> comma + 1
                space > 0 -> space
                else -> maxChars
            }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
    }
}

/** Remote now, ONNX on-device later. The player only knows it receives ordered WAV songs. */
fun interface TtsAudioChunkSource {
    suspend fun synthesize(text: String, options: TtsOptions): ByteArray
}
