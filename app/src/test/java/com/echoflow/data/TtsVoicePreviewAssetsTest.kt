package com.echoflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsVoicePreviewAssetsTest {
    @Test
    fun `every catalog line has a bundled wav asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clips = TtsVoicePreviewCatalog.voices.flatMap { voice ->
            listOf("bg", "en").flatMap { language ->
                voice.lines(language).mapIndexed { index, text ->
                    TtsPreviewClip(voice, language, index + 1, text)
                }
            }
        }

        assertEquals(100, clips.size)
        clips.forEach { clip ->
            val header = context.assets.open(clip.assetPath).use { input ->
                ByteArray(12).also { assertEquals(12, input.read(it)) }
            }
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            assertTrue("Missing text for ${clip.assetPath}", clip.text.isNotBlank())
        }
    }
}
