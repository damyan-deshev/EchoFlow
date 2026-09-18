package com.echoflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextChunkerTest {
    @Test
    fun `silent chunk retry split preserves all normalized text`() {
        val text = "The chapter is titled Aversive Control: Avoidance and Punishment 5, " +
            "and this arrangement leaves no alternative response and no way out."

        val pieces = TtsTextChunker.splitForRetry(text)

        assertEquals(2, pieces.size)
        assertEquals(TtsTextNormalizer.normalize(text), pieces.joinToString(" "))
        assertTrue(pieces.all(String::isNotBlank))
    }

    @Test
    fun `retry split never bisects an unbroken token`() {
        val text = "Supercalifragilisticexpialidocious"

        assertEquals(listOf(text), TtsTextChunker.splitForRetry(text))
    }

    @Test
    fun `normalizer replaces unsupported astral letters with a separator`() {
        assertEquals("alpha beta", TtsTextNormalizer.normalize("alpha\uD801\uDC00beta"))
    }

    @Test
    fun `normalizer keeps spoken text and prosodic punctuation only`() {
        val normalized = TtsTextNormalizer.normalize(
            "IQ3_XXS: 92.57 ≤ 93.12, €40 • 🥹 наистина?!",
        )

        assertEquals("IQ3 XXS: 92.57 93.12, 40 наистина?!", normalized)
    }

    @Test
    fun `normalizer canonicalizes typographic punctuation`() {
        val normalized = TtsTextNormalizer.normalize(
            "„Кавички“ — ‘single’… После.",
        )

        assertEquals("\"Кавички\" - 'single'... После.", normalized)
    }

    @Test
    fun `normalizer preserves decomposed diacritics but drops orphan marks`() {
        val normalized = TtsTextNormalizer.normalize("И\u0306ордан =\u0338 тест")

        assertEquals("И\u0306ордан тест", normalized)
    }

    @Test
    fun `normalizer removes format controls without splitting a word`() {
        assertEquals("zerowidth", TtsTextNormalizer.normalize("zero\u200Bwidth"))
    }

    @Test
    fun `normalizer drops astral combining marks unsupported by the tokenizer`() {
        assertEquals("ab", TtsTextNormalizer.normalize("a\uD834\uDD65b"))
    }

    @Test
    fun `first sentence is emitted immediately and later sentences are packed`() {
        val chunks = TtsTextChunker.chunk(
            "Първото изречение тръгва веднага. Второто може да чака. Третото върви с него.",
            firstMaxChars = 50,
            laterMaxChars = 80,
        )

        assertEquals("Първото изречение тръгва веднага.", chunks.first())
        assertEquals(2, chunks.size)
        assertEquals("Второто може да чака. Третото върви с него.", chunks.last())
    }

    @Test
    fun `oversized first sentence is split before the first request limit`() {
        val text = (1..40).joinToString(" ") { "дума$it" } + ". Следва кратък край."
        val chunks = TtsTextChunker.chunk(text, firstMaxChars = 70, laterMaxChars = 120)

        assertTrue(chunks.first().length <= 70)
        assertTrue(chunks.drop(1).all { it.length <= 120 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `paragraph whitespace becomes stable spoken text without losing content`() {
        val chunks = TtsTextChunker.chunk("  Един ред.\n\n   Втори   ред!  ")

        assertEquals("Един ред. Втори ред!", chunks.joinToString(" "))
    }

    @Test
    fun `blank answer produces no requests`() {
        assertTrue(TtsTextChunker.chunk(" \n ").isEmpty())
    }
}
