@file:OptIn(ExperimentalMaterial3Api::class)

package com.echoflow.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.echoflow.data.TtsOptions
import com.echoflow.ui.SettingsViewModel
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
internal fun TextToSpeechPage(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val options by viewModel.ttsOptions.collectAsState()
    fun save(update: TtsOptions.() -> TtsOptions) = viewModel.saveTtsOptions(options.update())

    SettingsPageScaffold(
        title = "Read aloud",
        subtitle = "Supertonic speech playback",
        onBack = onBack,
    ) {
        PageSection("Voice", "These choices remain portable when the backend moves on-device")
        SettingPicker(
            label = "Voice",
            value = options.voice,
            choices = (1..5).map { "M$it" to "M$it" } + (1..5).map { "F$it" to "F$it" },
            onSelect = { save { copy(voice = it) } },
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
