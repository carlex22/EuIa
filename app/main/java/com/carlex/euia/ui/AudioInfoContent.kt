// File: ui/AudioInfoContent.kt
package com.carlex.euia.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.viewmodel.AudioViewModel
import kotlinx.coroutines.*
import java.io.File

// Cores para os ícones de gênero no diálogo de seleção de voz.
private val FemaleIconColorAudio = Color(0xFF800080) // Roxo
private val MaleIconColorAudio = Color(0xFF00008B)   // Azul Escuro
private val NeutralIconColorAudio = Color.Gray      // Cinza para Neutro
private const val TAG_AUDIO_CONTENT = "AudioInfoContent"

private fun playAudioLocal(
    context: Context,
    path: String,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    if (path.isBlank()) {
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_no_audio_path), duration = SnackbarDuration.Short) }
        return
    }

    val audioFile = File(path)
    val audioUri: Uri? = try {
        if (audioFile.exists() && audioFile.isFile) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", audioFile)
        } else {
            Uri.parse(path)
        }
    } catch (e: Exception) {
        Log.e(TAG_AUDIO_CONTENT, "Erro ao obter URI para reprodução de áudio: $path", e)
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_invalid_audio_path), duration = SnackbarDuration.Short) }
        null
    }

    audioUri?.let { uri ->
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_no_app_audio), duration = SnackbarDuration.Short) }
        } catch (e: Exception) {
            Log.e(TAG_AUDIO_CONTENT, "Erro ao tentar reproduzir áudio com URI: $uri", e)
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_play_audio_failed), duration = SnackbarDuration.Short) }
        }
    }
}

// Função auxiliar para obter o nome do arquivo. O ViewModel agora tem uma pública.
// Se precisar de uma versão local por algum motivo, pode usar esta.
private fun getDisplayFileNameLocal(context: Context, uriString: String, audioViewModel: AudioViewModel): String {
    if (uriString.isBlank()) return ""
    return try {
        val uri = Uri.parse(uriString)
        audioViewModel.getFileNameFromUri(context, uri) // Chamando a função pública do ViewModel
    } catch (e: Exception) {
        uriString.takeLast(30) + "..." // Fallback
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioInfoContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    audioViewModel: AudioViewModel = viewModel()
) {
    val currentSexo by audioViewModel.sexo.collectAsState()
    val voiceSpeaker1 by audioViewModel.voiceSpeaker1.collectAsState()
    val voiceSpeaker2 by audioViewModel.voiceSpeaker2.collectAsState()
    val voiceSpeaker3 by audioViewModel.voiceSpeaker3.collectAsState()
    val isChatNarrative by audioViewModel.isChatNarrative.collectAsState()
    val narrativeContextFilePath by audioViewModel.narrativeContextFilePath.collectAsState()

    val currentAudioPath by audioViewModel.audioPath.collectAsState()
    val currentLegendaPath by audioViewModel.legendaPath.collectAsState()
    val availableVoicePairs by audioViewModel.availableVoices.collectAsState()
    val isLoadingVoices by audioViewModel.isLoadingVoices.collectAsState()
    val voiceLoadingError by audioViewModel.voiceLoadingError.collectAsState()
    val currentPromptFromVm by audioViewModel.prompt.collectAsState()
    val isAudioProcessing by audioViewModel.isAudioProcessing.collectAsState()
    val currentMusicPath by audioViewModel.videoMusicPath.collectAsState()
    val generationProgressText by audioViewModel.generationProgressText.collectAsState()
    val generationError by audioViewModel.generationError.collectAsState()
    val showClearScenesDialog by audioViewModel.showClearScenesDialog.collectAsState()
    val showSavePromptDialogState by audioViewModel.showSavePromptConfirmationDialog.collectAsState()

    var localPrompt by remember(currentPromptFromVm) { mutableStateOf(currentPromptFromVm) }
    val promptHasChanged = localPrompt != currentPromptFromVm

    var showSubtitleEditDialog by remember { mutableStateOf(false) }
    var editingSubtitleText by remember { mutableStateOf("") }
    var showVoiceSelectionDialogFor: String? by remember { mutableStateOf(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isUiEnabled = !isLoadingVoices && !isAudioProcessing && generationError == null

    val contextFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            audioViewModel.setNarrativeContextFile(uri)
        }
    )

    LaunchedEffect(generationError) {
        generationError?.let { errorMsg ->
            snackbarHostState.showSnackbar(errorMsg, duration = SnackbarDuration.Long)
            audioViewModel.clearGenerationError()
        }
    }

    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                audioViewModel.setVideoMusicPath(uri.toString())
            } else {
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.audio_info_no_music_selected), duration = SnackbarDuration.Short) }
            }
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.audio_info_label_narrative_mode), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isChatNarrative) stringResource(R.string.audio_info_mode_dialogue) else stringResource(R.string.audio_info_mode_single_narrator))
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = isChatNarrative,
                        onCheckedChange = { audioViewModel.setIsChatNarrative(it) },
                        enabled = isUiEnabled,
                        thumbContent = if (isChatNarrative) {
                            { Icon(imageVector = Icons.Filled.Chat, contentDescription = stringResource(R.string.audio_info_mode_dialogue)) }
                        } else {
                            { Icon(imageVector = Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.audio_info_mode_single_narrator)) }
                        }
                    )
                }
            }
            Divider()

            Text(
                text = if (isChatNarrative) stringResource(R.string.audio_info_section_speaker_voices) else stringResource(R.string.audio_info_section_narrator_voice),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            SpeakerVoiceSelector(
                speakerLabel = if(isChatNarrative) stringResource(R.string.audio_info_label_speaker_1) else stringResource(R.string.audio_info_label_narrator),
                selectedVoiceName = voiceSpeaker1,
                availableVoices = availableVoicePairs,
                isLoadingVoices = isLoadingVoices,
                onSelectVoiceClicked = { showVoiceSelectionDialogFor = "speaker1" },
                isUiEnabled = isUiEnabled
            )

            if (isChatNarrative) {
                Spacer(modifier = Modifier.height(12.dp))
                SpeakerVoiceSelector(
                    speakerLabel = stringResource(R.string.audio_info_label_speaker_2),
                    selectedVoiceName = voiceSpeaker2,
                    availableVoices = availableVoicePairs,
                    isLoadingVoices = isLoadingVoices,
                    onSelectVoiceClicked = {
                        if (voiceSpeaker1.isNotBlank()) showVoiceSelectionDialogFor = "speaker2"
                        else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.audio_info_select_speaker1_first)) }
                    },
                    isUiEnabled = isUiEnabled && voiceSpeaker1.isNotBlank(),
                    canBeRemoved = false
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (voiceSpeaker2.isNotBlank()) {
                    SpeakerVoiceSelector(
                        speakerLabel = stringResource(R.string.audio_info_label_speaker_3_optional),
                        selectedVoiceName = voiceSpeaker3 ?: "",
                        availableVoices = availableVoicePairs,
                        isLoadingVoices = isLoadingVoices,
                        onSelectVoiceClicked = { showVoiceSelectionDialogFor = "speaker3" },
                        onRemoveVoiceClicked = { audioViewModel.setVoiceSpeaker3(null) },
                        isUiEnabled = isUiEnabled,
                        canBeRemoved = !voiceSpeaker3.isNullOrBlank()
                    )
                } else if (voiceSpeaker1.isNotBlank()){
                     OutlinedButton(
                        onClick = {
                            // A lógica de habilitar o botão de adicionar voz 3 deve garantir que voz2 já foi selecionada.
                            // Se o botão está clicável, significa que voz2 já tem valor.
                             showVoiceSelectionDialogFor = "speaker3"
                        },
                        enabled = isUiEnabled && voiceSpeaker2.isNotBlank(), // Habilita se S1 e S2 tiverem voz
                        modifier = Modifier.fillMaxWidth().padding(top=8.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.audio_info_action_add_speaker_3))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.audio_info_action_add_speaker_3))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider()

                // Seletor de Arquivo de Contexto para Narrativa
                Text(
                    text = stringResource(R.string.audio_info_label_narrative_context_file),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { contextFilePickerLauncher.launch("*/*") },
                        enabled = isUiEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.audio_info_action_select_context_file_desc))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.audio_info_action_select_context_file))
                    }
                    if (narrativeContextFilePath.isNotBlank()) {
                        IconButton(
                            onClick = { audioViewModel.setNarrativeContextFile(null) },
                            enabled = isUiEnabled
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.audio_info_action_clear_context_file_desc))
                        }
                    } else {
                         Spacer(Modifier.size(48.dp)) // Para manter o alinhamento se não houver botão de limpar
                    }
                }
                if (narrativeContextFilePath.isNotBlank()) {
                    val fileName = getDisplayFileNameLocal(context, narrativeContextFilePath, audioViewModel)
                    Text(
                        text = stringResource(R.string.audio_info_label_selected_file, fileName),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
            } // Fim do if (isChatNarrative)

            Spacer(modifier = Modifier.height(16.dp))

            if (isAudioProcessing || generationError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .background(
                            color = when {
                                isAudioProcessing -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                generationError != null -> MaterialTheme.colorScheme.errorContainer
                                else -> Color.Transparent
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                isAudioProcessing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                generationError != null -> MaterialTheme.colorScheme.error
                                else -> Color.Transparent
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                        .clickable(enabled = generationError != null) {
                            if (generationError != null) audioViewModel.clearGenerationError()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isAudioProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp).padding(bottom = 16.dp))
                    } else if (generationError != null) {
                        Icon(Icons.Default.Error, stringResource(R.string.content_desc_error_icon), tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(48.dp).padding(bottom = 16.dp))
                    }
                    Text(
                        text = generationError ?: generationProgressText,
                        textAlign = TextAlign.Center,
                        color = if (generationError != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    if (generationError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.status_tap_to_clear_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight() // Altura fixa para a caixa de prompt
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    OutlinedTextField(
                        value = localPrompt,
                        onValueChange = { localPrompt = it },
                        placeholder = { Text(stringResource(R.string.audio_info_placeholder_narrative_prompt)) },
                        singleLine = false,
                        enabled = isUiEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .padding(bottom = 60.dp) // Mais espaço para os botões
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canGenerate = isUiEnabled && localPrompt.isNotBlank() &&
                                          ( (isChatNarrative && voiceSpeaker1.isNotBlank() && voiceSpeaker2.isNotBlank()) ||
                                            (!isChatNarrative && voiceSpeaker1.isNotBlank()) )

                        IconButton(
                            onClick = {
                                if (promptHasChanged) {
                                    audioViewModel.requestSavePromptAndGenerateAudio(localPrompt)
                                } else if (canGenerate) {
                                    audioViewModel.startAudioGeneration(
                                        promptToUse = localPrompt,
                                        isNewNarrative = false,
                                        voiceToUseOverride = if (isChatNarrative) null else voiceSpeaker1
                                    )
                                } else {
                                    val errorMsg = if(localPrompt.isBlank()) context.getString(R.string.audio_error_prompt_empty)
                                                   else if (isChatNarrative && (voiceSpeaker1.isBlank() || voiceSpeaker2.isBlank())) context.getString(R.string.audio_error_chat_voices_missing)
                                                   else context.getString(R.string.audio_error_narrator_voice_missing)
                                    scope.launch { snackbarHostState.showSnackbar(message = errorMsg, duration = SnackbarDuration.Short) }
                                }
                            },
                            enabled = isUiEnabled && (promptHasChanged || canGenerate),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription = stringResource(if (promptHasChanged) R.string.audio_info_action_save_prompt_and_generate else R.string.audio_info_action_generate_audio),
                                tint = if (isUiEnabled && (promptHasChanged || canGenerate)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = { showVoiceSelectionDialogFor = "speaker1" }, enabled = isUiEnabled, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.SettingsVoice, stringResource(R.string.audio_info_action_configure_voice_speaker1), tint = if (isUiEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { playAudioLocal(context, currentAudioPath, snackbarHostState, scope) }, enabled = isUiEnabled && currentAudioPath.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.audio_info_action_play_generated_audio), tint = if (isUiEnabled && currentAudioPath.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        IconButton(
                            onClick = {
                                if (currentLegendaPath.isNotEmpty()) {
                                    scope.launch {
                                        editingSubtitleText = audioViewModel.loadSubtitleContent(currentLegendaPath) ?: ""
                                        showSubtitleEditDialog = true
                                    }
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.audio_info_no_subtitle_to_edit), duration = SnackbarDuration.Short) }
                                }
                            },
                            enabled = isUiEnabled && currentLegendaPath.isNotEmpty(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Description, stringResource(R.string.audio_info_action_edit_subtitle), tint = if (isUiEnabled && currentLegendaPath.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            /*Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.audio_info_label_background_music), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = getDisplayFileNameLocal(context, currentMusicPath, audioViewModel) ?: stringResource(R.string.audio_info_label_no_music),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (currentMusicPath.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { playAudioLocal(context, currentMusicPath, snackbarHostState, scope) }, enabled = isUiEnabled && currentMusicPath.isNotEmpty(), modifier = Modifier.padding(horizontal = 2.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.audio_info_action_play_selected_music), tint = if (isUiEnabled && currentMusicPath.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                IconButton(onClick = { audioViewModel.setVideoMusicPath("") }, enabled = isUiEnabled && currentMusicPath.isNotEmpty(), modifier = Modifier.padding(horizontal = 2.dp)) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.audio_info_action_clear_music))
                }
                IconButton(onClick = { musicPickerLauncher.launch("audio/*") }, enabled = isUiEnabled, modifier = Modifier.padding(horizontal = 2.dp)) {
                    Icon(Icons.Filled.MusicNote, stringResource(R.string.audio_info_action_select_music))
                }
            }"/"*/
            Spacer(modifier = Modifier.height(16.dp))*/
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showSubtitleEditDialog) {
            Dialog(onDismissRequest = { showSubtitleEditDialog = false }) {
                Surface(shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 500.dp)) {
                        Text(stringResource(R.string.audio_info_dialog_edit_subtitle_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
                        OutlinedTextField(
                            value = editingSubtitleText,
                            onValueChange = { editingSubtitleText = it },
                            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 200.dp),
                            label = { Text(stringResource(R.string.audio_info_dialog_edit_subtitle_label)) },
                            singleLine = false,
                            enabled = true
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showSubtitleEditDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                scope.launch {
                                    val success = audioViewModel.saveSubtitleContent(currentLegendaPath, editingSubtitleText)
                                    val message = if (success) context.getString(R.string.audio_info_subtitle_saved)
                                                  else context.getString(R.string.toast_subtitle_generation_failed)
                                    snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                                    showSubtitleEditDialog = false
                                }
                            }) { Text(stringResource(R.string.audio_info_dialog_action_save)) }
                        }
                    }
                }
            }
        }

        showVoiceSelectionDialogFor?.let { speakerKey ->
            val currentSpeakerVoice = when(speakerKey) {
                "speaker1" -> voiceSpeaker1
                "speaker2" -> voiceSpeaker2
                "speaker3" -> voiceSpeaker3 ?: ""
                else -> voiceSpeaker1
            }
            VoiceSelectionDialogInternal(
                onDismissRequest = { showVoiceSelectionDialogFor = null },
                currentSexo = currentSexo,
                currentVozName = currentSpeakerVoice,
                availableVoicePairs = availableVoicePairs,
                isLoadingVoices = isLoadingVoices,
                voiceLoadingError = voiceLoadingError,
                isUiEnabled = isUiEnabled,
                onSexoSelected = { newGender ->
                    audioViewModel.setSexo(newGender)
                },
                onVozSelected = { voiceName ->
                    when(speakerKey) {
                        "speaker1" -> audioViewModel.setVoiceSpeaker1(voiceName)
                        "speaker2" -> audioViewModel.setVoiceSpeaker2(voiceName)
                        "speaker3" -> audioViewModel.setVoiceSpeaker3(voiceName)
                    }
                },
                onConfirmSelectionClicked = {
                    val previouslySelectedKey = showVoiceSelectionDialogFor
                    showVoiceSelectionDialogFor = null // Fecha o diálogo

                    // Usa o valor do ViewModel APÓS a seleção para o Snackbar
                    val newlySelectedVoice = when(previouslySelectedKey) {
                        "speaker1" -> audioViewModel.voiceSpeaker1.value
                        "speaker2" -> audioViewModel.voiceSpeaker2.value
                        "speaker3" -> audioViewModel.voiceSpeaker3.value
                        else -> ""
                    }
                    if (!newlySelectedVoice.isNullOrBlank()) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.audio_info_voice_selected_for_speaker, newlySelectedVoice, previouslySelectedKey ?: ""), duration = SnackbarDuration.Long) }
                    }
                }
            )
        }

        if (showClearScenesDialog) {
            AlertDialog(
                onDismissRequest = { audioViewModel.cancelClearScenesDialog() },
                title = { Text(stringResource(R.string.audio_info_dialog_clear_scenes_title)) },
                text = { Text(stringResource(R.string.audio_info_dialog_clear_scenes_message)) },
                confirmButton = {
                    Button(onClick = { audioViewModel.confirmAndProceedWithAudioGeneration() }) {
                        Text(stringResource(R.string.audio_info_dialog_action_clear_and_generate))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { audioViewModel.cancelClearScenesDialog() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        if (showSavePromptDialogState) {
            AlertDialog(
                onDismissRequest = { audioViewModel.cancelSavePromptConfirmation() },
                title = { Text(stringResource(R.string.audio_info_dialog_save_prompt_title)) },
                text = { Text(stringResource(R.string.audio_info_dialog_save_prompt_message)) },
                confirmButton = {
                    Button(onClick = { audioViewModel.confirmSavePromptAndGenerate() }) {
                        Text(stringResource(R.string.audio_info_dialog_action_save_and_regenerate))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { audioViewModel.cancelSavePromptConfirmation() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SpeakerVoiceSelector(
    speakerLabel: String,
    selectedVoiceName: String,
    availableVoices: List<Pair<String, String>>,
    isLoadingVoices: Boolean,
    onSelectVoiceClicked: () -> Unit,
    onRemoveVoiceClicked: (() -> Unit)? = null,
    isUiEnabled: Boolean,
    canBeRemoved: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(speakerLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))

        val displayName = if (isLoadingVoices && selectedVoiceName.isBlank()) stringResource(R.string.audio_info_placeholder_loading_voices) // Mostra loading apenas se não houver voz selecionada
                          else if (selectedVoiceName.isNotBlank()) {
                              val selectedPair = availableVoices.find { it.first == selectedVoiceName }
                              if (selectedPair != null) {
                                  "${selectedPair.first} (${selectedPair.second.take(15)}${if (selectedPair.second.length > 15) "..." else ""})"
                              } else {
                                  selectedVoiceName // Fallback se não encontrar o par
                              }
                          } else {
                              stringResource(R.string.audio_info_action_select_voice_generic)
                          }
        Button(
            onClick = onSelectVoiceClicked,
            enabled = isUiEnabled && !isLoadingVoices,
            modifier = Modifier.weight(2f).padding(horizontal = 8.dp)
        ) {
            if (isLoadingVoices && selectedVoiceName.isBlank()) {
                 CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (canBeRemoved && onRemoveVoiceClicked != null) {
            IconButton(onClick = onRemoveVoiceClicked, enabled = isUiEnabled) {
                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = stringResource(R.string.audio_info_action_remove_voice_for, speakerLabel.takeLast(1)))
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelectionDialogInternal(
    onDismissRequest: () -> Unit,
    currentSexo: String,
    currentVozName: String,
    availableVoicePairs: List<Pair<String, String>>,
    isLoadingVoices: Boolean,
    voiceLoadingError: String?,
    isUiEnabled: Boolean,
    onSexoSelected: (String) -> Unit,
    onVozSelected: (String) -> Unit,
    onConfirmSelectionClicked: () -> Unit
) {
    var dialogVozDropdownExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(16.dp).widthIn(max=320.dp).verticalScroll(rememberScrollState())) { // Max width
                Text(
                    text = stringResource(R.string.audio_info_dialog_voice_selection_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)
                )
                Text(stringResource(R.string.audio_info_label_filter_gender), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly, // Espaçar igualmente
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    IconButton(onClick = { onSexoSelected("Female") }, enabled = isUiEnabled) {
                        Icon(Icons.Filled.Person, stringResource(R.string.audio_info_action_gender_female), tint = if (currentSexo.equals("Female", ignoreCase = true)) FemaleIconColorAudio else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { onSexoSelected("Male") }, enabled = isUiEnabled) {
                        Icon(Icons.Filled.PersonOutline, stringResource(R.string.audio_info_action_gender_male), tint = if (currentSexo.equals("Male", ignoreCase = true)) MaleIconColorAudio else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { onSexoSelected("Neutral") }, enabled = isUiEnabled) {
                        Icon(Icons.Filled.AccessibilityNew, stringResource(R.string.audio_info_action_gender_neutral), tint = if (currentSexo.equals("Neutral", ignoreCase = true)) NeutralIconColorAudio else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.audio_info_label_select_voice), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                ExposedDropdownMenuBox(
                    expanded = dialogVozDropdownExpanded,
                    onExpandedChange = { if (isUiEnabled && !isLoadingVoices && availableVoicePairs.isNotEmpty()) dialogVozDropdownExpanded = !dialogVozDropdownExpanded },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    OutlinedTextField(
                        value = when {
                            isLoadingVoices -> stringResource(R.string.audio_info_placeholder_loading_voices)
                            voiceLoadingError != null -> voiceLoadingError
                            currentVozName.isNotBlank() -> {
                                val selectedPair = availableVoicePairs.find { it.first == currentVozName }
                                if (selectedPair != null) {
                                    "${selectedPair.first} (${selectedPair.second.take(25)}${if (selectedPair.second.length > 25) "..." else ""})"
                                } else {
                                    currentVozName // Mostra o nome mesmo se não estiver na lista filtrada (pode ter mudado o filtro de gênero)
                                }
                            }
                            else -> stringResource(R.string.audio_info_placeholder_select_voice)
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.audio_info_label_selected_voice), style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isLoadingVoices) CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp))
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dialogVozDropdownExpanded)
                            }
                        },
                        isError = voiceLoadingError != null,
                        enabled = isUiEnabled && !isLoadingVoices,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    ExposedDropdownMenu(
                        expanded = dialogVozDropdownExpanded && isUiEnabled && !isLoadingVoices && availableVoicePairs.isNotEmpty(),
                        onDismissRequest = { dialogVozDropdownExpanded = false }
                    ) {
                        if (availableVoicePairs.isEmpty() && !isLoadingVoices && voiceLoadingError == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.audio_info_error_no_voices_for_gender, currentSexo)) },
                                onClick = { dialogVozDropdownExpanded = false },
                                enabled = false
                            )
                        } else {
                            availableVoicePairs.forEach { voicePair ->
                                DropdownMenuItem(
                                    text = { Text("${voicePair.first} (${voicePair.second.take(30)}${if (voicePair.second.length > 30) "..." else ""})") },
                                    onClick = {
                                        onVozSelected(voicePair.first)
                                        dialogVozDropdownExpanded = false
                                    },
                                    enabled = isUiEnabled,
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest, enabled = isUiEnabled) { Text(stringResource(R.string.action_cancel)) } // Alterado para "Cancelar"
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirmSelectionClicked, enabled = isUiEnabled && currentVozName.isNotBlank()) {
                        Text(stringResource(R.string.audio_info_dialog_action_confirm_voice))
                    }
                }
            }
        }
    }
}
