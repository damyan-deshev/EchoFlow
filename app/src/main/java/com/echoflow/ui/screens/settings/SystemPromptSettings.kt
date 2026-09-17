@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.echoflow.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.echoflow.data.SystemPromptMode
import com.echoflow.data.SystemPromptPreference
import com.echoflow.ui.ChatViewModel
import com.echoflow.ui.SettingsViewModel
import com.echoflow.ui.theme.Spacing

private const val ScopeDefault = "default"
private const val ScopeChat = "chat"

/** Global default plus the optional override for the conversation still open behind Settings. */
@Composable
internal fun SystemPromptSettingsPage(
    viewModel: SettingsViewModel,
    chatViewModel: ChatViewModel?,
    onBack: () -> Unit,
) {
    val global by viewModel.systemPromptPreference.collectAsState()
    val chatPreference = chatViewModel?.currentSystemPromptPreference?.collectAsState()?.value
    val chatInherited = chatViewModel?.currentSystemPromptInherited?.collectAsState()?.value ?: true
    val currentChatId = chatViewModel?.currentChatThreadId?.collectAsState()?.value
    var scope by remember { mutableStateOf(ScopeDefault) }
    val source = if (scope == ScopeChat && chatPreference != null) chatPreference else global
    val editorKey = "$scope:${source.mode.storageKey}:${source.content.hashCode()}:$chatInherited"

    SystemPromptEditor(
        key = editorKey,
        source = source,
        defaultSafeText = viewModel.defaultSafeSystemInstructions(),
        assembled = viewModel::assembledSystemPrompt,
        provenance = viewModel.systemPromptProvenance(),
        scope = scope,
        chatLabel = if (currentChatId == null) "Next chat" else "This chat",
        chatInherited = scope == ScopeChat && chatInherited,
        canEditChat = chatViewModel != null,
        onScope = { scope = it },
        onSave = { preference ->
            if (scope == ScopeChat) chatViewModel?.saveCurrentSystemPrompt(preference)
            else viewModel.saveSystemPromptPreference(preference)
        },
        onReset = {
            if (scope == ScopeChat) chatViewModel?.resetCurrentSystemPrompt()
            else viewModel.saveSystemPromptPreference(SystemPromptPreference())
        },
        onMakeDefault = { viewModel.saveSystemPromptPreference(it) },
        onBack = onBack,
    )
}

@Composable
private fun SystemPromptEditor(
    key: String,
    source: SystemPromptPreference,
    defaultSafeText: String,
    assembled: (SystemPromptPreference) -> String,
    provenance: String,
    scope: String,
    chatLabel: String,
    chatInherited: Boolean,
    canEditChat: Boolean,
    onScope: (String) -> Unit,
    onSave: (SystemPromptPreference) -> Unit,
    onReset: () -> Unit,
    onMakeDefault: (SystemPromptPreference) -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember(key) { mutableStateOf(source.mode) }
    var safeText by remember(key) {
        mutableStateOf(if (source.mode == SystemPromptMode.Safe) source.content ?: defaultSafeText else defaultSafeText)
    }
    var yoloText by remember(key) {
        mutableStateOf(
            if (source.mode == SystemPromptMode.Yolo) source.content.orEmpty()
            else assembled(SystemPromptPreference(SystemPromptMode.Safe, safeText))
        )
    }
    val preference = SystemPromptPreference(mode, if (mode == SystemPromptMode.Safe) safeText else yoloText)
    val preview = assembled(preference)

    SettingsPageScaffold(title = "System prompt", subtitle = "Safe when you want it · YOLO when you don't", onBack = onBack) {
        if (canEditChat) {
            PageSection("Scope", "Defaults seed new conversations; a chat override only affects that thread")
            ConnectedToggleRow(
                options = listOf(ScopeDefault to "New chats", ScopeChat to chatLabel),
                selected = scope,
                onSelect = onScope,
            )
            if (chatInherited) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "This conversation currently inherits the default. Saving creates its own copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }

        PageSection("Editing mode")
        ConnectedToggleRow(
            options = listOf(SystemPromptMode.Safe.storageKey to "Safe", SystemPromptMode.Yolo.storageKey to "YOLO"),
            selected = mode.storageKey,
            onSelect = { selected ->
                val next = SystemPromptMode.fromStorage(selected)
                if (next == SystemPromptMode.Yolo && mode != SystemPromptMode.Yolo) {
                    yoloText = assembled(SystemPromptPreference(SystemPromptMode.Safe, safeText))
                }
                mode = next
            },
        )
        Spacer(Modifier.height(Spacing.m))

        val warning = if (mode == SystemPromptMode.Yolo) {
            "Raw override. EchoFlow will send this base prompt as written and will not repair search, tool, formatting, or date instructions."
        } else {
            "Edit the identity and behaviour section. Provider, search, formatting, and freshness instructions stay current automatically."
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (mode == SystemPromptMode.Yolo) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                warning,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mode == SystemPromptMode.Yolo) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(Spacing.base),
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        PageSection(
            if (mode == SystemPromptMode.Safe) "Identity & behaviour" else "Raw system prompt",
            if (mode == SystemPromptMode.Safe) "The editable part of the assembled prompt" else "A frozen copy of the assembled default",
        )
        OutlinedTextField(
            value = if (mode == SystemPromptMode.Safe) safeText else yoloText,
            onValueChange = { if (mode == SystemPromptMode.Safe) safeText = it else yoloText = it },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 8,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        )
        if (mode == SystemPromptMode.Yolo) {
            Spacer(Modifier.height(Spacing.s))
            FilledTonalButton(
                onClick = { yoloText = assembled(SystemPromptPreference(SystemPromptMode.Safe, safeText)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(Spacing.s))
                Text("Rebuild from current defaults")
            }
        }

        Spacer(Modifier.height(Spacing.xl))
        PageSection("Effective prompt", provenance)
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Spacing.base),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onSave(preference) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (scope == ScopeChat) "Save for this chat" else "Save default")
            }
            if (scope == ScopeChat) {
                FilledTonalButton(onClick = { onMakeDefault(preference) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Use as default for new chats")
                }
            }
            FilledTonalButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Row {
                    Icon(Icons.Default.RestartAlt, null)
                    Spacer(Modifier.width(Spacing.s))
                    Text(if (scope == ScopeChat) "Use inherited default" else "Reset app default")
                }
            }
        }
    }
}
