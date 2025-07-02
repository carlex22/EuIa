// File: euia/ui/ContextInfoContent.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carlex.euia.R
import com.carlex.euia.viewmodel.AudioViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog // Importar o Dialog
import androidx.compose.ui.text.style.TextAlign // Para o texto centralizado no Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextInfoContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    audioViewModel: AudioViewModel,
    snackbarHostState: SnackbarHostState,
    provideSaveActionDetails: (action: () -> Unit, isEnabled: Boolean) -> Unit,
    onDirtyStateChange: (isDirty: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val currentVideoTituloFromVM by audioViewModel.videoTitulo.collectAsState()
    val currentVideoObjectiveIntroduction by audioViewModel.videoObjectiveIntroduction.collectAsState()
    val currentVideoObjectiveVideo by audioViewModel.videoObjectiveVideo.collectAsState()
    val currentVideoObjectiveOutcome by audioViewModel.videoObjectiveOutcome.collectAsState()
    val currentUserTargetAudienceAudio by audioViewModel.userTargetAudienceAudio.collectAsState()
    val currentUserLanguageToneAudio by audioViewModel.userLanguageToneAudio.collectAsState()
    val currentVideoTimeSeconds by audioViewModel.videoTimeSeconds.collectAsState()
    // CORREÇÃO: Especificar o tipo explicitamente para ajudar o compilador
    val isUrlImporting: Boolean by audioViewModel.isUrlImporting.collectAsState()
    val isChatNarrative by audioViewModel.isChatNarrative.collectAsState()


    var editingTitleText by remember(currentVideoTituloFromVM) { mutableStateOf(currentVideoTituloFromVM) }
    var localObjectiveIntroduction by remember(currentVideoObjectiveIntroduction) { mutableStateOf(currentVideoObjectiveIntroduction) }
    var localObjectiveVideo by remember(currentVideoObjectiveVideo) { mutableStateOf(currentVideoObjectiveVideo) }
    var localObjectiveOutcome by remember(currentVideoObjectiveOutcome) { mutableStateOf(currentVideoObjectiveOutcome) }
    var localTargetAudience by remember(currentUserTargetAudienceAudio) { mutableStateOf(currentUserTargetAudienceAudio) }
    var localLanguageTone by remember(currentUserLanguageToneAudio) { mutableStateOf(currentUserLanguageToneAudio) }
    var localVideoTime by remember(currentVideoTimeSeconds) { mutableStateOf(currentVideoTimeSeconds) }

    var timeDropdownExpanded by remember { mutableStateOf(false) }
    var showUrlImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    val anythingChanged = remember(
        editingTitleText, currentVideoTituloFromVM,
        localObjectiveIntroduction, currentVideoObjectiveIntroduction,
        localObjectiveVideo, currentVideoObjectiveVideo,
        localObjectiveOutcome, currentVideoObjectiveOutcome,
        localTargetAudience, currentUserTargetAudienceAudio,
        localLanguageTone, currentUserLanguageToneAudio,
        localVideoTime, currentVideoTimeSeconds
    ) {
        editingTitleText != currentVideoTituloFromVM ||
        localObjectiveIntroduction != currentVideoObjectiveIntroduction ||
        localObjectiveVideo != currentVideoObjectiveVideo ||
        localObjectiveOutcome != currentVideoObjectiveOutcome ||
        localTargetAudience != currentUserTargetAudienceAudio ||
        localLanguageTone != currentUserLanguageToneAudio ||
        localVideoTime != currentVideoTimeSeconds
    }

    // A operação '!' em um Boolean funciona normalmente. O problema pode ter sido de inferência anterior.
    val isUiEnabled = !isUrlImporting


    LaunchedEffect(anythingChanged, editingTitleText, localObjectiveIntroduction, localObjectiveVideo, localObjectiveOutcome, localTargetAudience, localLanguageTone, localVideoTime) {
        val saveAction = {
            audioViewModel.setVideoTitulo(editingTitleText)
            audioViewModel.setVideoObjectiveIntroduction(localObjectiveIntroduction)
            audioViewModel.setVideoObjectiveVideo(localObjectiveVideo)
            audioViewModel.setVideoObjectiveOutcome(localObjectiveOutcome)
            audioViewModel.setUserTargetAudienceAudio(localTargetAudience)
            audioViewModel.setUserLanguageToneAudio(localLanguageTone)
            audioViewModel.setVideoTimeSeconds(localVideoTime)
            keyboardController?.hide()
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.context_info_snackbar_context_saved),
                    duration = SnackbarDuration.Short
                )
            }; Unit
        }
        // A habilitação do botão salvar agora depende de 'isUiEnabled' também
        provideSaveActionDetails(saveAction, anythingChanged && isUiEnabled)
        onDirtyStateChange(anythingChanged)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.context_info_import_data_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            // --- INÍCIO DA MODIFICAÇÃO DO BOTÃO DE IMPORTAR/CANCELAR ---
            val iconButtonSize = 48.dp // Tamanho padrão do IconButton
            val progressIndicatorSize = 32.dp // Tamanho do CircularProgressIndicator
            val cancelIconSize = 24.dp // Tamanho do ícone de Cancelar

            if (isUrlImporting) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(iconButtonSize)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(progressIndicatorSize),
                        strokeWidth = 2.dp, // Ajuste a espessura conforme necessário
                        color = MaterialTheme.colorScheme.primary // Cor do indicador
                    )
                    IconButton(
                        onClick = { audioViewModel.cancelUrlImport() },
                        modifier = Modifier.size(iconButtonSize) // O IconButton ocupa o mesmo espaço
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = stringResource(R.string.context_info_cancel_import_button_desc),
                            tint = MaterialTheme.colorScheme.error, // Cor do ícone de cancelar
                            modifier = Modifier.size(cancelIconSize)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = { showUrlImportDialog = true },
                    modifier = Modifier.size(iconButtonSize),
                    enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o botão >>>>>
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = stringResource(R.string.context_info_import_data_button_desc),
                        tint = if (isUiEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), // <<<<< ADICIONADO: Muda a cor se desabilitado >>>>>
                        modifier = Modifier.size(progressIndicatorSize) // Usa o mesmo tamanho base do indicador
                    )
                }
            }
            // --- FIM DA MODIFICAÇÃO DO BOTÃO DE IMPORTAR/CANCELAR ---
        }
        Divider(modifier = Modifier.padding(bottom = 16.dp))

  

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.audio_info_label_narrative_mode), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isChatNarrative) stringResource(R.string.audio_info_mode_dialogue)
                    else stringResource(R.string.audio_info_mode_single_narrator)
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isChatNarrative,
                    onCheckedChange = { audioViewModel.setIsChatNarrative(it) },
                    enabled = isUiEnabled, // <<<<< ADICIONADO: Habilita/desabilita o switch >>>>>
                    thumbContent = if (isChatNarrative) {
                        { Icon(imageVector = Icons.Filled.Chat, contentDescription = stringResource(R.string.audio_info_mode_dialogue)) }
                    } else {
                        { Icon(imageVector = Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.audio_info_mode_single_narrator)) }
                    }
                )
            }
        }
        
        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_title_video))
        OutlinedTextField(
            value = editingTitleText,
            onValueChange = { editingTitleText = it },
            label = { Text(stringResource(R.string.context_info_label_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_narrative_objectives))
        SettingTextFieldItemInternal(
            label = stringResource(R.string.context_info_label_introduction),
            value = localObjectiveIntroduction,
            onValueChange = { newText -> localObjectiveIntroduction = newText },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 8.dp),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )
        // CORREÇÃO: Substituir a referência incorreta a R.org pelo seu próprio recurso de string
        SettingTextFieldItemInternal(
            label = stringResource(R.string.context_info_label_main_content),
            value = localObjectiveVideo,
            onValueChange = { newText -> localObjectiveVideo = newText },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 8.dp),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )
        SettingTextFieldItemInternal(
            label = stringResource(R.string.context_info_label_desired_outcome),
            value = localObjectiveOutcome,
            onValueChange = { newText -> localObjectiveOutcome = newText },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 16.dp),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_narrative_details))
        SettingTextFieldItemInternal(
            label = stringResource(R.string.context_info_label_target_audience),
            value = localTargetAudience,
            onValueChange = { newText -> localTargetAudience = newText },
            placeholder = stringResource(R.string.context_info_placeholder_target_audience),
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 8.dp),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )
        SettingTextFieldItemInternal(
            label = stringResource(R.string.context_info_label_language_tone),
            value = localLanguageTone,
            onValueChange = { newText -> localLanguageTone = newText },
            placeholder = stringResource(R.string.context_info_placeholder_language_tone),
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 16.dp),
            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
        )

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_estimated_duration))
        val timeOptions = listOf("30", "60", "180", "300", "600")
        Box(modifier = Modifier.padding(bottom = 16.dp)) {
            ExposedDropdownMenuBox(
                expanded = timeDropdownExpanded,
                onExpandedChange = { timeDropdownExpanded = !timeDropdownExpanded },
            ) {
                OutlinedTextField(
                    value = if (localVideoTime in timeOptions) stringResource(R.string.context_info_dropdown_time_unit_seconds, localVideoTime) else if (localVideoTime.isBlank()) stringResource(R.string.context_info_dropdown_select_time) else stringResource(R.string.context_info_dropdown_time_unit_seconds, localVideoTime),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.context_info_label_time_seconds)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeDropdownExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo >>>>>
                )
                ExposedDropdownMenu(
                    expanded = timeDropdownExpanded && isUiEnabled, // <<<<< ADICIONADO: Desabilita o menu se a UI estiver desabilitada >>>>>
                    onDismissRequest = { timeDropdownExpanded = false }
                ) {
                    timeOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.context_info_dropdown_time_unit_seconds, selectionOption)) },
                            onClick = {
                                localVideoTime = selectionOption
                                timeDropdownExpanded = false
                            },
                            enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita os itens do menu >>>>>
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showUrlImportDialog) {
        AlertDialog(
            onDismissRequest = { showUrlImportDialog = false },
            title = { Text(stringResource(R.string.context_info_dialog_import_url_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        label = { Text(stringResource(R.string.context_info_dialog_import_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (importUrl.isNotBlank()) {
                                audioViewModel.startUrlImport(importUrl)
                                showUrlImportDialog = false
                                importUrl = ""
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.context_info_snackbar_please_enter_url),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                            keyboardController?.hide()
                        }),
                        enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o campo no diálogo >>>>>
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.context_info_dialog_import_url_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importUrl.isNotBlank()) {
                            audioViewModel.startUrlImport(importUrl)
                            showUrlImportDialog = false
                            importUrl = ""
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.context_info_snackbar_please_enter_url),
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    },
                    enabled = isUiEnabled // <<<<< ADICIONADO: Habilita/desabilita o botão no diálogo >>>>>
                ) {
                    Text(stringResource(R.string.context_info_dialog_action_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlImportDialog = false }) {
                    Text(stringResource(R.string.context_info_dialog_action_cancel))
                }
            }
        )
    }

    // <<<<< ADICIONADO: Overlay de progresso para bloquear a UI >>>>>
    if (isUrlImporting) {
        Dialog(onDismissRequest = { /* Não permite fechar */ }) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.context_info_dialog_loading_message), // Adicione esta string ao strings.xml
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    // Opcional: Um botão de cancelamento aqui se a operação for cancelável
                    TextButton(onClick = { audioViewModel.cancelUrlImport() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
    // <<<<< FIM DO OVERLAY DE PROGRESSO >>>>>
}

@Composable
internal fun SettingsSectionTitleInternal(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp)
    )
}

@Composable
internal fun SettingTextFieldItemInternal(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
    enabled: Boolean = true // <<<<< ADICIONADO: Parâmetro 'enabled' >>>>>
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { if (placeholder != null) Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = if (singleLine) keyboardOptions.copy(imeAction = ImeAction.Done) else keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            enabled = enabled // <<<<< ADICIONADO: Usa o parâmetro 'enabled' >>>>>
        )
    }
}