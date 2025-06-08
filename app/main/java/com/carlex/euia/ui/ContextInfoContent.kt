// File: ui/ContextInfoContent.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext // <<< --- ADICIONADO DE VOLTA --- >>>
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carlex.euia.R
import com.carlex.euia.viewmodel.AudioViewModel
import kotlinx.coroutines.launch

/**
 * Composable para a aba "Contexto" do fluxo de criação de vídeo.
 * Permite ao usuário definir o título do vídeo, objetivos da narrativa,
 * detalhes do público-alvo, tom da linguagem e duração estimada.
 * Também oferece a funcionalidade de importar dados de uma URL.
 *
 * Interage com [AudioViewModel] para ler e persistir esses dados.
 * Notifica o [VideoWorkflowViewModel] (através de callbacks) sobre o estado "sujo"
 * e fornece a ação de salvamento.
 *
 * @param modifier [Modifier] para este Composable.
 * @param innerPadding [PaddingValues] fornecido pelo Scaffold pai.
 * @param audioViewModel ViewModel para gerenciar os dados de áudio e contexto da narrativa.
 * @param snackbarHostState O [SnackbarHostState] para exibir mensagens.
 * @param provideSaveActionDetails Callback para fornecer a ação de salvar e seu estado de habilitação
 *                                 ao componente pai (geralmente a BottomAppBar do workflow).
 * @param onDirtyStateChange Callback para notificar o componente pai se houver alterações não salvas.
 */
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
    val context = LocalContext.current // <<< --- ADICIONADO DE VOLTA --- >>>

    // Coleta estados do AudioViewModel
    val currentVideoTituloFromVM by audioViewModel.videoTitulo.collectAsState()
    val currentVideoObjectiveIntroduction by audioViewModel.videoObjectiveIntroduction.collectAsState()
    val currentVideoObjectiveVideo by audioViewModel.videoObjectiveVideo.collectAsState()
    val currentVideoObjectiveOutcome by audioViewModel.videoObjectiveOutcome.collectAsState()
    val currentUserTargetAudienceAudio by audioViewModel.userTargetAudienceAudio.collectAsState()
    val currentUserLanguageToneAudio by audioViewModel.userLanguageToneAudio.collectAsState()
    val currentVideoTimeSeconds by audioViewModel.videoTimeSeconds.collectAsState()

    // Estados locais para edição, inicializados com os valores do ViewModel
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
                    // <<< --- CORREÇÃO AQUI --- >>>
                    message = context.getString(R.string.context_info_snackbar_context_saved),
                    duration = SnackbarDuration.Short
                )
            }; Unit // <<< --- FIX HERE: Ensure the lambda returns Unit --- >>>
        }
        provideSaveActionDetails(saveAction, anythingChanged)
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
            IconButton(onClick = { showUrlImportDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = stringResource(R.string.context_info_import_data_button_desc),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_title_video))
        OutlinedTextField(
            value = editingTitleText,
            onValueChange = { editingTitleText = it },
            label = { Text(stringResource(R.string.context_info_label_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
        )

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_narrative_objectives))
        SettingTextFieldItemInternal(stringResource(R.string.context_info_label_introduction), localObjectiveIntroduction, { localObjectiveIntroduction = it }, singleLine = false, maxLines = 3, modifier = Modifier.padding(bottom = 8.dp))
        SettingTextFieldItemInternal(stringResource(R.string.context_info_label_main_content), localObjectiveVideo, { localObjectiveVideo = it }, singleLine = false, maxLines = 3, modifier = Modifier.padding(bottom = 8.dp))
        SettingTextFieldItemInternal(stringResource(R.string.context_info_label_desired_outcome), localObjectiveOutcome, { localObjectiveOutcome = it }, singleLine = false, maxLines = 3, modifier = Modifier.padding(bottom = 16.dp))

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_narrative_details))
        SettingTextFieldItemInternal(stringResource(R.string.context_info_label_target_audience), localTargetAudience, { localTargetAudience = it }, placeholder = stringResource(R.string.context_info_placeholder_target_audience), singleLine = false, maxLines = 3, modifier = Modifier.padding(bottom = 8.dp))
        SettingTextFieldItemInternal(stringResource(R.string.context_info_label_language_tone), localLanguageTone, { localLanguageTone = it }, placeholder = stringResource(R.string.context_info_placeholder_language_tone), singleLine = false, maxLines = 3, modifier = Modifier.padding(bottom = 16.dp))

        SettingsSectionTitleInternal(stringResource(R.string.context_info_section_estimated_duration))
        val timeOptions = listOf("30", "60", "180", "300", "600")
        Box(modifier = Modifier.padding(bottom = 16.dp)) {
            ExposedDropdownMenuBox(
                expanded = timeDropdownExpanded,
                onExpandedChange = { timeDropdownExpanded = !timeDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = if (localVideoTime in timeOptions) stringResource(R.string.context_info_dropdown_time_unit_seconds, localVideoTime) else if (localVideoTime.isBlank()) stringResource(R.string.context_info_dropdown_select_time) else stringResource(R.string.context_info_dropdown_time_unit_seconds, localVideoTime),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.context_info_label_time_seconds)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeDropdownExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = timeDropdownExpanded,
                    onDismissRequest = { timeDropdownExpanded = false }
                ) {
                    timeOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.context_info_dropdown_time_unit_seconds, selectionOption)) },
                            onClick = {
                                localVideoTime = selectionOption
                                timeDropdownExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
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
                                        // <<< --- CORREÇÃO AQUI --- >>>
                                        message = context.getString(R.string.context_info_snackbar_please_enter_url),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                            keyboardController?.hide()
                        })
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
                                    // <<< --- CORREÇÃO AQUI --- >>>
                                    message = context.getString(R.string.context_info_snackbar_please_enter_url),
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }
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
    modifier: Modifier = Modifier
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
            enabled = true
        )
    }
}