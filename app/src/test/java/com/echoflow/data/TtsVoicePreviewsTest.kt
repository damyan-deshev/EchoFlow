package com.echoflow.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoicePreviewsTest {
    @Test
    fun `every bag consumes each line exactly once`() {
        val voice = TtsVoicePreviewCatalog.voice("M1")
        val bags = TtsPreviewShuffleBags(Random(7))

        val firstBag = List(5) { bags.next(voice, "bg") }

        assertEquals(voice.bulgarian.toSet(), firstBag.map { it.text }.toSet())
        assertEquals(5, firstBag.map { it.number }.distinct().size)
    }

    @Test
    fun `refill never repeats the previous bag boundary`() {
        TtsVoicePreviewCatalog.voices.forEachIndexed { index, voice ->
            listOf("bg", "en").forEach { language ->
                val bags = TtsPreviewShuffleBags(Random(index * 17 + language.length))
                val firstBag = List(5) { bags.next(voice, language) }
                val firstOfNextBag = bags.next(voice, language)

                assertNotEquals(firstBag.last().number, firstOfNextBag.number)
            }
        }
    }

    @Test
    fun `voice and language bags advance independently`() {
        val bags = TtsPreviewShuffleBags(Random(11))
        val miles = TtsVoicePreviewCatalog.voice("M1")
        val graham = TtsVoicePreviewCatalog.voice("M2")

        val milesBg = bags.next(miles, "bg")
        val milesEn = bags.next(miles, "en")
        val grahamBg = bags.next(graham, "bg")

        assertTrue(milesBg.text in miles.bulgarian)
        assertTrue(milesEn.text in miles.english)
        assertTrue(grahamBg.text in graham.bulgarian)
        assertNotEquals(milesBg.assetPath, grahamBg.assetPath)
        assertEquals("tts_voice_previews/m1_bg_${milesBg.number}.wav", milesBg.assetPath)
    }

    @Test
    fun `catalog contains five standalone lines for every voice and language`() {
        assertEquals(10, TtsVoicePreviewCatalog.voices.size)
        TtsVoicePreviewCatalog.voices.forEach { voice ->
            assertEquals(5, voice.english.size)
            assertEquals(5, voice.bulgarian.size)
            assertEquals(5, voice.english.distinct().size)
            assertEquals(5, voice.bulgarian.distinct().size)
        }
    }
}
