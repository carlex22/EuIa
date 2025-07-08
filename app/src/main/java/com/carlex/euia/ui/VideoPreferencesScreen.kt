// File: ui/VideoPreferencesScreen.kt
package com.carlex.euia.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack // Alterado para AutoMirrored
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carlex.euia.R
import com.carlex.euia.viewmodel.VideoPreferencesViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Tela para configurar as preferências globais de vídeo e voz.
 * Permite ao usuário definir:
 * - Vozes preferenciais (masculina e feminina).
 * - Ajustes globais de voz como tom e velocidade.
 * - Proporção do vídeo (aspect ratio) e duração padrão da cena.
 * - Habilitação de recursos como legendas, transições e efeito Ken Burns.
 *
 * As preferências são persistidas através do [VideoPreferencesViewModel] e [VideoPreferencesDataStoreManager].
 *
 * @param navController O [NavController] para navegação (principalmente para voltar).
 * @param viewModel O [VideoPreferencesViewModel] que gerencia o estado e a lógica desta tela.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPreferencesScreen(
    navController: NavController,
    viewModel: VideoPreferencesViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Coleta estados do ViewModel
    val preferredMaleVoice by viewModel.preferredMaleVoice.collectAsState()
    val preferredFemaleVoice by viewModel.preferredFemaleVoice.collectAsState()
    val voicePitch by viewModel.voicePitch.collectAsState()
    val voiceRate by viewModel.voiceRate.collectAsState()
    val enableZoomPan by viewModel.enableZoomPan.collectAsState()
    val videoAspectRatio by viewModel.videoAspectRatio.collectAsState()
    val enableSubtitles by viewModel.enableSubtitles.collectAsState()
    val enableSceneTransitions by viewModel.enableSceneTransitions.collectAsState()
    val defaultSceneDuration by viewModel.defaultSceneDurationSeconds.collectAsState()
    val videoFps by viewModel.videoFps.collectAsState()
    val videoHdMotion by viewModel.videoHdMotion.collectAsState()

    // Coleta dos novos estados
    val defaultSceneType by viewModel.defaultSceneType.collectAsState()
    val defaultImageStyle by viewModel.defaultImageStyle.collectAsState()
    val preferredAiModel by viewModel.preferredAiModel.collectAsState()

    val availableMaleVoicesPairList by viewModel.availableMaleVoices.collectAsState()
    val availableFemaleVoicesPairList by viewModel.availableFemaleVoices.collectAsState()

    val isLoadingVoices by viewModel.isLoadingVoices.collectAsState()
    val voiceLoadingError by viewModel.voiceLoadingError.collectAsState()

    // Estados locais para campos de texto, formatados para exibição e edição
    var durationInput by remember(defaultSceneDuration) { mutableStateOf(String.format(Locale.US, "%.1f", defaultSceneDuration)) }
    var pitchInput by remember(voicePitch) { mutableStateOf(String.format(Locale.US, "%.2f", voicePitch)) }
    var rateInput by remember(voiceRate) { mutableStateOf(String.format(Locale.US, "%.2f", voiceRate)) }

    // Exibe mensagens de erro do carregamento de vozes
    LaunchedEffect(voiceLoadingError) {
        voiceLoadingError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearVoiceLoadingError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_prefs_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.video_prefs_action_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.video_prefs_section_preferred_voices), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            VoicePreferenceDropdown(
                label = stringResource(R.string.video_prefs_label_male_voice_default),
                selectedVoiceName = preferredMaleVoice,
                availableVoicePairs = availableMaleVoicesPairList,
                isLoading = isLoadingVoices,
                onVoiceSelected = { voiceName -> viewModel.setPreferredMaleVoice(voiceName) },
                onRetryLoad = { viewModel.fetchAvailableVoices("Male") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            VoicePreferenceDropdown(
                label = stringResource(R.string.video_prefs_label_female_voice_default),
                selectedVoiceName = preferredFemaleVoice,
                availableVoicePairs = availableFemaleVoicesPairList,
                isLoading = isLoadingVoices,
                onVoiceSelected = { voiceName -> viewModel.setPreferredFemaleVoice(voiceName) },
                onRetryLoad = { viewModel.fetchAvailableVoices("Female") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.video_prefs_section_global_voice_settings), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pitchInput,
                    onValueChange = { pitchInput = it.replace(Regex("[^0-9.,-]"), "") },
                    label = { Text(stringResource(R.string.video_prefs_label_pitch)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        viewModel.setVoicePitch(pitchInput)
                        focusManager.clearFocus()
                        scope.launch { Toast.makeText(context, R.string.video_prefs_toast_pitch_saved, Toast.LENGTH_SHORT).show() }
                    }),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it.replace(Regex("[^0-9.,-]"), "") },
                    label = { Text(stringResource(R.string.video_prefs_label_rate)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        viewModel.setVoiceRate(rateInput)
                        focusManager.clearFocus()
                        scope.launch { Toast.makeText(context, R.string.video_prefs_toast_rate_saved, Toast.LENGTH_SHORT).show() }
                    }),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                 TextButton(onClick = {
                    viewModel.setVoicePitch(pitchInput)
                    viewModel.setVoiceRate(rateInput)
                    focusManager.clearFocus()
                    scope.launch { Toast.makeText(context, R.string.video_prefs_toast_pitch_rate_saved, Toast.LENGTH_SHORT).show() }
                }) {
                    Text(stringResource(R.string.video_prefs_action_save_pitch_rate))
                }
            }

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            Text(stringResource(R.string.video_prefs_section_generation_defaults), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            SettingExposedDropdown(
                label = stringResource(R.string.video_prefs_label_default_scene_type),
                options = viewModel.sceneTypeOptions,
                selectedOption = defaultSceneType,
                onOptionSelected = { viewModel.setDefaultSceneType(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingExposedDropdown(
                label = stringResource(R.string.video_prefs_label_default_image_style),
                options = viewModel.imageStyleOptions,
                selectedOption = defaultImageStyle,
                onOptionSelected = { viewModel.setDefaultImageStyle(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingExposedDropdown(
                label = stringResource(R.string.video_prefs_label_preferred_ai_model),
                options = viewModel.aiModelOptions,
                selectedOption = preferredAiModel,
                onOptionSelected = { viewModel.setPreferredAiModel(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            Text(stringResource(R.string.video_prefs_section_format_duration), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            SettingExposedDropdown(
                label = stringResource(R.string.video_prefs_label_aspect_ratio),
                options = viewModel.aspectRatioOptions,
                selectedOption = videoAspectRatio,
                onOptionSelected = { viewModel.setVideoAspectRatio(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingExposedDropdown(
                label = stringResource(R.string.video_prefs_label_fps),
                options = viewModel.fpsOptions,
                selectedOption = videoFps.toString(),
                onOptionSelected = { viewModel.setVideoFps(it) },
                displaySuffix = " FPS"
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = durationInput,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*[,.]?\\d*"))) {
                        durationInput = newValue
                    }
                },
                label = { Text(stringResource(R.string.video_prefs_label_default_scene_duration)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.setDefaultSceneDurationSeconds(durationInput)
                    focusManager.clearFocus()
                    scope.launch { Toast.makeText(context, R.string.video_prefs_toast_duration_saved, Toast.LENGTH_SHORT).show() }
                }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    viewModel.setDefaultSceneDurationSeconds(durationInput)
                    focusManager.clearFocus()
                    scope.launch { Toast.makeText(context, R.string.video_prefs_toast_duration_saved, Toast.LENGTH_SHORT).show() }
                },
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                enabled = durationInput.toFloatOrNull()?.let { it >= 0.1f } ?: false
            ) {
                Text(stringResource(R.string.video_prefs_action_save_duration))
            }

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            Text(stringResource(R.string.video_prefs_section_additional_features), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            SettingSwitch(
                label = stringResource(R.string.video_prefs_label_enable_subtitles),
                checked = enableSubtitles,
                onCheckedChange = { viewModel.setEnableSubtitles(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                label = stringResource(R.string.video_prefs_label_enable_transitions),
                checked = enableSceneTransitions,
                onCheckedChange = { viewModel.setEnableSceneTransitions(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                label = stringResource(R.string.video_prefs_label_enable_zoom_pan),
                checked = enableZoomPan,
                onCheckedChange = { viewModel.setEnableZoomPan(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitch(
                label = stringResource(R.string.video_prefs_label_hd_motion),
                checked = videoHdMotion,
                onCheckedChange = { viewModel.setVideoHdMotion(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePreferenceDropdown(
    label: String,
    selectedVoiceName: String,
    availableVoicePairs: List<Pair<String, String>>,
    isLoading: Boolean,
    onVoiceSelected: (String) -> Unit,
    onRetryLoad: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                if (!isLoading) {
                    if (availableVoicePairs.isEmpty()) {
                        onRetryLoad()
                    }
                    expanded = !expanded
                } else {
                    Toast.makeText(context, R.string.video_prefs_toast_loading_voices, Toast.LENGTH_SHORT).show()
                }
            }
        ) {
            OutlinedTextField(
                value = when {
                    isLoading -> stringResource(R.string.video_prefs_placeholder_loading_voices)
                    selectedVoiceName.isNotBlank() -> {
                        val selectedPair = availableVoicePairs.find { it.first == selectedVoiceName }
                        if (selectedPair != null) {
                            "${selectedPair.first} (${selectedPair.second.take(25)}${if (selectedPair.second.length > 25) "..." else ""})"
                        } else {
                            selectedVoiceName
                        }
                    }
                    else -> stringResource(R.string.video_prefs_placeholder_no_voice_selected)
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.video_prefs_label_selected_voice)) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp))
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && !isLoading && availableVoicePairs.isNotEmpty())
                    }
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded && !isLoading && availableVoicePairs.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                availableVoicePairs.forEach { voicePair ->
                    DropdownMenuItem(
                        text = {
                            Text("${voicePair.first} (${voicePair.second.take(30)}${if (voicePair.second.length > 30) "..." else ""})")
                        },
                        onClick = {
                            onVoiceSelected(voicePair.first)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingExposedDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    displaySuffix: String = "",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedOption.ifBlank { options.firstOrNull() ?: "" } + displaySuffix,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.video_prefs_label_selected_option)) }, // Usando uma string genérica
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}