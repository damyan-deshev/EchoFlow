package com.echoflow.data

/** How EchoFlow turns the editor text into the system message for an ordinary chat. */
enum class SystemPromptMode(val storageKey: String) {
    Safe("safe"),
    Yolo("yolo");

    companion object {
        fun fromStorage(value: String?): SystemPromptMode =
            entries.firstOrNull { it.storageKey == value } ?: Safe
    }
}

/**
 * A prompt choice persisted either as the default for new chats or directly on one thread.
 * In Safe mode [content] is the editable identity/behaviour section. In Yolo mode it is the
 * complete raw conversational prompt.
 */
data class SystemPromptPreference(
    val mode: SystemPromptMode = SystemPromptMode.Safe,
    /** Null means "use EchoFlow's default"; an empty string is an explicit empty prompt. */
    val content: String? = null,
) {
    fun normalizedForStorage(): SystemPromptPreference =
        if (mode == SystemPromptMode.Safe) copy(content = content?.trim()) else this

    fun resolve(runtime: SystemPromptRuntime): String = when (mode) {
        SystemPromptMode.Safe -> runtime.assemble(content)
        SystemPromptMode.Yolo -> content.orEmpty()
    }
}

/** Facts that decide which transport-specific sections belong in the assembled prompt. */
data class SystemPromptRuntime(
    val isLocalModel: Boolean,
    val effectiveProvider: String,
    val customProviderActive: Boolean,
    val customToolCallingActive: Boolean,
) {
    fun assemble(identityOverride: String? = null): String = when {
        customToolCallingActive -> SystemPrompts.build(
            isLocalModel = false,
            provider = effectiveProvider,
            identityOverride = identityOverride,
        )
        customProviderActive -> SystemPrompts.buildCustomProvider(
            provider = effectiveProvider,
            identityOverride = identityOverride,
        )
        else -> SystemPrompts.build(
            isLocalModel = isLocalModel,
            provider = effectiveProvider,
            identityOverride = identityOverride,
        )
    }
}
