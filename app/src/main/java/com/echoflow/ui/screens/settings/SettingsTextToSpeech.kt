@file:OptIn(ExperimentalMaterial3Api::class)

package com.echoflow.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.echoflow.data.TtsOptions
import com.echoflow.data.TtsPreviewShuffleBags
import com.echoflow.data.TtsPreviewVoice
import com.echoflow.data.TtsVoicePreviewCatalog
import com.echoflow.ui.ChatViewModel
import com.echoflow.ui.SettingsViewModel
import com.echoflow.ui.screens.chat.ReadAloudPhase
import com.echoflow.ui.screens.chat.ReadAloudState
import com.echoflow.ui.theme.Spacing
import kotlin.math.roundToInt

private val ttsLanguages = listOf(
    null to "Auto",
    "bg" to "Bulgarian",
    "en" to "English",
    "de" to "German",
    "es" to "Spanish",
    "fr" to "French",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ro" to "Romanian",
    "ru" to "Russian",
    "uk" to "Ukrainian",
    "tr" to "Turkish",
    "el" to "Greek",
    "pl" to "Polish",
    "cs" to "Czech",
    "hr" to "Croatian",
    "hu" to "Hungarian",
    "sl" to "Slovenian",
    "sk" to "Slovak",
    "ja" to "Japanese",
    "ko" to "Korean",
    "ar" to "Arabic",
    "hi" to "Hindi",
    "vi" to "Vietnamese",
)

@Composable
internal fun TextToSpeechPage(
    viewModel: SettingsViewModel,
    chatViewModel: ChatViewModel?,
    onBack: () -> Unit,
) {
    val options by viewModel.ttsOptions.collectAsState()
    val controller = chatViewModel?.ttsController
    val previewState = if (controller != null) controller.state.collectAsState().value else ReadAloudState()
    val context = LocalContext.current
    fun save(update: TtsOptions.() -> TtsOptions) = viewModel.saveTtsOptions(options.update())

    LaunchedEffect(previewState.error) {
        previewState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            controller?.clearError()
        }
    }
    DisposableEffect(controller) {
        onDispose {
            if (controller?.state?.value?.messageKey?.startsWith(VOICE_PREVIEW_KEY_PREFIX) == true) {
                controller.stop()
            }
        }
    }

    SettingsPageScaffold(
        title = "Read aloud",
        subtitle = "Supertonic speech playback",
        onBack = onBack,
    ) {
        PageSection("Voice", "These choices remain portable when the backend moves on-device")
        VoiceSettingPicker(
            selectedVoice = options.voice,
            options = options,
            playbackState = previewState,
            enabled = controller != null,
            onSelect = { voice -> save { copy(voice = voice) } },
            onPreview = { key, assetPath, speed -> controller?.toggleAsset(key, assetPath, speed) },
            onStopPreview = { controller?.stop() },
        )
        Spacer(Modifier.height(Spacing.m))
        SettingPicker(
            label = "Language",
            value = options.language,
            choices = ttsLanguages,
            onSelect = { save { copy(language = it) } },
        )
        Text(
            "Auto sends no language override; Supertonic uses its multilingual fallback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.s),
        )

        Spacer(Modifier.height(Spacing.xl))
        PageSection("Delivery", "Speed and model quality")
        FormCard {
            SettingSlider(
                title = "Speed",
                valueLabel = String.format("%.2f×", options.speed),
                value = options.speed,
                range = 0.7f..2f,
                steps = 25,
                onChange = { value -> save { copy(speed = value) } },
            )
            Spacer(Modifier.height(Spacing.m))
            Text("Quality", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.s))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(5 to "Fast", 8 to "Balanced", 12 to "Best").forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = options.steps == item.first,
                        onClick = { save { copy(steps = item.first) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                        label = { Text(item.second) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))
        PageSection("Advanced", "Exact model and connection settings")
        FormCard {
            OutlinedTextField(
                value = options.baseUrl,
                onValueChange = { value -> save { copy(baseUrl = value) } },
                label = { Text("Endpoint URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.m))
            SettingSlider(
                title = "Steps",
                valueLabel = options.steps.toString(),
                value = options.steps.toFloat(),
                range = 1f..20f,
                steps = 18,
                onChange = { value -> save { copy(steps = value.roundToInt()) } },
            )
            Spacer(Modifier.height(Spacing.m))
            SettingSlider(
                title = "Silence between phrases",
                valueLabel = String.format("%.1fs", options.silenceDuration),
                value = options.silenceDuration,
                range = 0f..2f,
                steps = 19,
                onChange = { value -> save { copy(silenceDuration = value) } },
            )
        }
    }
}

@Composable
private fun VoiceSettingPicker(
    selectedVoice: String,
    options: TtsOptions,
    playbackState: ReadAloudState,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String, String, Float) -> Unit,
    onStopPreview: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var previewLanguage by rememberSaveable { mutableStateOf("bg") }
    val bags = remember { TtsPreviewShuffleBags() }
    val selected = TtsVoicePreviewCatalog.voice(selectedVoice)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            if (!open && playbackState.messageKey?.startsWith(VOICE_PREVIEW_KEY_PREFIX) == true) {
                onStopPreview()
            }
        },
    ) {
        OutlinedTextField(
            value = "${selected.id} · ${selected.name}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                if (playbackState.messageKey?.startsWith(VOICE_PREVIEW_KEY_PREFIX) == true) onStopPreview()
            },
        ) {
            Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
                Text(
                    "Preview language",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.s))
                ConnectedToggleRow(
                    options = listOf("bg" to "Български", "en" to "English"),
                    selected = previewLanguage,
                    onSelect = { language ->
                        if (playbackState.messageKey?.startsWith(VOICE_PREVIEW_KEY_PREFIX) == true) {
                            onStopPreview()
                        }
                        previewLanguage = language
                    },
                )
            }
            HorizontalDivider()
            TtsVoicePreviewCatalog.voices.forEach { voice ->
                val key = "$VOICE_PREVIEW_KEY_PREFIX${voice.id}-$previewLanguage"
                val active = playbackState.messageKey == key
                DropdownMenuItem(
                    text = {
                        Text("${voice.id} · ${voice.name}", style = MaterialTheme.typography.bodyLarge)
                    },
                    leadingIcon = {
                        RadioButton(selected = voice.id == selectedVoice, onClick = null)
                    },
                    trailingIcon = {
                        VoicePreviewButton(
                            voice = voice,
                            language = previewLanguage,
                            phase = if (active) playbackState.phase else ReadAloudPhase.Idle,
                            enabled = enabled,
                            onClick = {
                                if (active) {
                                    onStopPreview()
                                } else {
                                    val clip = bags.next(voice, previewLanguage)
                                    onPreview(
                                        key,
                                        clip.assetPath,
                                        options.speed,
                                    )
                                }
                            },
                        )
                    },
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                        if (playbackState.messageKey?.startsWith(VOICE_PREVIEW_KEY_PREFIX) == true) {
                            onStopPreview()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VoicePreviewButton(
    voice: TtsPreviewVoice,
    language: String,
    phase: ReadAloudPhase,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val languageName = if (language == "bg") "Bulgarian" else "English"
    val active = phase != ReadAloudPhase.Idle
    val action = if (active) "Stop ${voice.name} preview" else "Preview ${voice.name} in $languageName"
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        when (phase) {
            ReadAloudPhase.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp).semantics { contentDescription = action },
                strokeWidth = 2.dp,
            )
            ReadAloudPhase.Playing -> Icon(Icons.Default.Stop, action)
            ReadAloudPhase.Idle -> Icon(Icons.Default.PlayArrow, action)
        }
    }
}

@Composable
private fun <T> SettingPicker(
    label: String,
    value: T,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == value }?.second ?: value.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.second) },
                    onClick = {
                        onSelect(choice.first)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

private const val VOICE_PREVIEW_KEY_PREFIX = "tts-voice-preview-"
