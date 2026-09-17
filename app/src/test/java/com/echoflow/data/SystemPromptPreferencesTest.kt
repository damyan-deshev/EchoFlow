package com.echoflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptPreferencesTest {
    private val runtime = SystemPromptRuntime(
        isLocalModel = false,
        effectiveProvider = "off",
        customProviderActive = true,
        customToolCallingActive = false,
    )

    @Test
    fun `safe replaces identity but preserves transport instructions`() {
        val prompt = SystemPromptPreference(
            mode = SystemPromptMode.Safe,
            content = "You are a terse Bulgarian engineering assistant.",
        ).resolve(runtime)

        assertTrue(prompt.startsWith("You are a terse Bulgarian engineering assistant."))
        assertTrue(prompt.contains("You do not have access to the internet"))
        assertTrue(prompt.contains("## Formatting"))
        assertTrue(prompt.contains("## Before you answer"))
        assertFalse(prompt.contains("You are EchoFlow"))
    }

    @Test
    fun `explicitly blank safe prompt omits identity but preserves app instructions`() {
        val prompt = SystemPromptPreference(SystemPromptMode.Safe, "").resolve(runtime)

        assertFalse(prompt.contains("You are EchoFlow"))
        assertTrue(prompt.startsWith("Current date:"))
        assertTrue(prompt.contains("## Formatting"))
    }

    @Test
    fun `unset safe prompt uses the EchoFlow default identity`() {
        val prompt = SystemPromptPreference(SystemPromptMode.Safe, null).resolve(runtime)

        assertTrue(prompt.startsWith("You are EchoFlow"))
    }

    @Test
    fun `yolo sends the raw prompt without app sections`() {
        val prompt = SystemPromptPreference(SystemPromptMode.Yolo, "Do exactly this.").resolve(runtime)

        assertEquals("Do exactly this.", prompt)
    }

    @Test
    fun `blank yolo remains a deliberately empty raw prompt`() {
        val prompt = SystemPromptPreference(SystemPromptMode.Yolo, "").resolve(runtime)

        assertEquals("", prompt)
    }
}
