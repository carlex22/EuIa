// File: ui/VideoCreationWorkflowScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.carlex.euia.AppDestinations
import com.carlex.euia.R
import com.carlex.euia.WorkflowStage // Certifique-se que esta importação está correta
import com.carlex.euia.utils.shareVideoFile
import com.carlex.euia.viewmodel.*
import kotlinx.coroutines.launch

/**
 * Composable principal que orquestra as diferentes etapas (abas) do fluxo de criação de vídeo.
 *
 * Esta tela é responsável por:
 * - Observar a aba atualmente selecionada no [VideoWorkflowViewModel].
 * - Renderizar o Composable de conteúdo apropriado para a aba ativa (e.g., [ContextInfoContent], [VideoInfoContent]).
 * - Coletar estados de processamento dos ViewModels específicos de cada aba.
 * - Atualizar o [VideoWorkflowViewModel] com:
 *     - O estado de processamento consolidado da aba atual.
 *     - O texto de progresso relevante para a aba atual.
 *     - As ações disponíveis para a aba atual (e.g., "Gerar Áudio", "Gravar Vídeo"),
 *       que serão consumidas pela BottomBar gerenciada em [AppNavigationHostComposable].
 *
 * @param navController O [NavHostController] para navegação, caso alguma sub-tela precise dele.
 * @param snackbarHostState O [SnackbarHostState] global para exibir mensagens de feedback ao usuário.
 * @param innerPadding [PaddingValues] fornecido pelo Scaffold pai, para garantir que o conteúdo
 *                     não se sobreponha a elementos como a TopAppBar ou BottomAppBar.
 * @param videoWorkflowViewModel O ViewModel compartilhado que gerencia o estado geral do fluxo de trabalho
 *                               de criação de vídeo, como a aba selecionada e o estado de processamento
 *                               da aba atual.
 * @param audioViewModel ViewModel para a aba de informações de áudio e geração de narrativa.
 * @param videoInfoViewModel ViewModel para a aba de gerenciamento e processamento de imagens de referência.
 * @param refImageInfoViewModel ViewModel para a aba de análise e extração de detalhes das imagens de referência.
 * @param videoProjectViewModel ViewModel para a aba de geração e gerenciamento de cenas do vídeo.
 * @param videoGeneratorViewModel ViewModel para a aba de finalização e geração do arquivo de vídeo.
 * @param generatedVideoPath O caminho do arquivo de vídeo final gerado. Usado para habilitar a ação de compartilhamento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCreationWorkflowScreen(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    innerPadding: PaddingValues,
    videoWorkflowViewModel: VideoWorkflowViewModel = viewModel(),
    audioViewModel: AudioViewModel = viewModel(),
    videoInfoViewModel: VideoViewModel = viewModel(),
    refImageInfoViewModel: RefImageViewModel = viewModel(),
    videoProjectViewModel: VideoProjectViewModel = viewModel(),
    videoGeneratorViewModel: VideoGeneratorViewModel = viewModel(),
    generatedVideoPath: String
) {
    val localContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedStageIndex by videoWorkflowViewModel.selectedWorkflowTabIndex.collectAsState()
    val workflowStages = videoWorkflowViewModel.workflowStages

    // Coleta estados de processamento dos ViewModels específicos de cada aba
    val isImageProcessing by videoInfoViewModel.isAnyImageProcessing.collectAsState()
    val isRefInfoAnalyzing by refImageInfoViewModel.isAnalyzing.collectAsState()
    val isAudioProcessing by audioViewModel.isAudioProcessing.collectAsState()
    val audioGenerationProgressText by audioViewModel.generationProgressText.collectAsState()
    val isGeneratingGlobalScenes by videoProjectViewModel.isProcessingGlobalScenes.collectAsState()
    val isGeneratingVideo by videoGeneratorViewModel.isGeneratingVideo.collectAsState()
    val generationVideoProgress by videoGeneratorViewModel.generationProgress.collectAsState()
    val availableVoicePairsForNarrative by audioViewModel.availableVoices.collectAsState() // Agora é List<Pair<String, String>>
    val currentVoiceForAudio by audioViewModel.voz.collectAsState() // Continua sendo String (nome da voz)

    // LaunchedEffect para atualizar o estado do VideoWorkflowViewModel (ações, progresso, texto)
    // sempre que a aba selecionada mudar ou o estado de processamento de alguma aba for alterado.
    LaunchedEffect(
        selectedStageIndex,
        isImageProcessing, isRefInfoAnalyzing, isAudioProcessing, isGeneratingGlobalScenes, isGeneratingVideo,
        generatedVideoPath, audioGenerationProgressText, generationVideoProgress,
        availableVoicePairsForNarrative, // Adicionado à lista de chaves
        currentVoiceForAudio, // Adicionado à lista de chaves
        audioViewModel, videoInfoViewModel, refImageInfoViewModel, videoProjectViewModel, videoGeneratorViewModel
    ) {
        val currentStageId = workflowStages.getOrNull(selectedStageIndex)?.identifier ?: return@LaunchedEffect

        // Reseta todas as ações possíveis no ViewModel do workflow antes de configurar as da aba atual
        videoWorkflowViewModel.setLaunchPickerAction(null)
        videoWorkflowViewModel.setAnalyzeAction(null)
        videoWorkflowViewModel.setCreateNarrativeAction(null)
        videoWorkflowViewModel.setGenerateAudioAction(null)
        videoWorkflowViewModel.setGenerateScenesAction(null)
        videoWorkflowViewModel.setRecordVideoAction(null)
        videoWorkflowViewModel.setShareVideoAction(null)

        var currentTabIsProcessing = false
        var currentTabProgressFloat = 0f
        var currentTabText = ""

        // Obtém strings de recursos para evitar chamadas @Composable dentro deste bloco não-Composable
        val processingImagesText = localContext.getString(R.string.status_processing_images_general)
        val analyzingDetailsText = localContext.getString(R.string.status_analyzing_details)
        val noVoiceAvailableText = localContext.getString(R.string.snackbar_no_voice_available_for_narrative)
        val selectVoiceFirstText = localContext.getString(R.string.snackbar_select_voice_for_audio)
        val processingAudioDefaultText = localContext.getString(R.string.status_processing_audio_default)
        val generatingScenesText = localContext.getString(R.string.status_generating_scenes)
        val recordingVideoProgressTextTemplate = localContext.getString(R.string.status_recording_video_progress)
        val videoReadyText = localContext.getString(R.string.status_video_ready)

        when (currentStageId) {
            AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                currentTabIsProcessing = false
                currentTabText = ""
                // A ação de salvar contexto é gerenciada por provideSaveActionDetails e onContextDirtyStateChanged
            }
            AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                // A ação de launchPicker é configurada pelo VideoInfoContent através de onReadyToLaunchPicker
                // e o VideoWorkflowViewModel a armazena.
                currentTabIsProcessing = isImageProcessing
                currentTabText = if (isImageProcessing) processingImagesText else ""
            }
            AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                videoWorkflowViewModel.setAnalyzeAction { refImageInfoViewModel.analyzeImages() }
                currentTabIsProcessing = isRefInfoAnalyzing
                currentTabText = if (isRefInfoAnalyzing) analyzingDetailsText else ""
            }
            AppDestinations.WORKFLOW_STAGE_NARRATIVE -> {
                val firstVoicePair = availableVoicePairsForNarrative.firstOrNull()
                val firstVoiceName = firstVoicePair?.first // Extrai o nome da voz do par

                videoWorkflowViewModel.setCreateNarrativeAction {
                    if (firstVoiceName != null || currentVoiceForAudio.isNotBlank()) {
                        audioViewModel.startAudioGeneration(
                            promptToUse = audioViewModel.prompt.value,
                            isNewNarrative = true,
                            voiceToUseOverride = currentVoiceForAudio.ifBlank { firstVoiceName ?: "" }
                        )
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(message = noVoiceAvailableText, duration = SnackbarDuration.Short)
                        }
                    }
                }
                videoWorkflowViewModel.setGenerateAudioAction {
                    if (currentVoiceForAudio.isNotBlank()) {
                        audioViewModel.startAudioGeneration(
                            promptToUse = audioViewModel.prompt.value,
                            isNewNarrative = false,
                            voiceToUseOverride = currentVoiceForAudio
                        )
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(message = selectVoiceFirstText, duration = SnackbarDuration.Short)
                        }
                    }
                }
                currentTabIsProcessing = isAudioProcessing
                currentTabText = if (isAudioProcessing) {
                    audioGenerationProgressText.ifBlank { processingAudioDefaultText }
                } else ""
            }
            AppDestinations.WORKFLOW_STAGE_SCENES -> {
                videoWorkflowViewModel.setGenerateScenesAction { videoProjectViewModel.requestFullSceneGenerationProcess() }
                currentTabIsProcessing = isGeneratingGlobalScenes
                currentTabText = if (isGeneratingGlobalScenes) generatingScenesText else ""
            }
            AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                videoWorkflowViewModel.setRecordVideoAction(
                    if (!isGeneratingVideo) { { videoGeneratorViewModel.generateVideo() } } else null
                )
                videoWorkflowViewModel.setShareVideoAction(
                    if (generatedVideoPath.isNotBlank() && !isGeneratingVideo) {
                        { shareVideoFile(localContext, generatedVideoPath) }
                    } else null
                )

                currentTabIsProcessing = isGeneratingVideo
                currentTabProgressFloat = if (isGeneratingVideo) generationVideoProgress else 0f
                currentTabText = when {
                    isGeneratingVideo -> String.format(recordingVideoProgressTextTemplate, (currentTabProgressFloat * 100).toInt())
                    generatedVideoPath.isNotBlank() -> videoReadyText
                    else -> ""
                }
            }
        }
        videoWorkflowViewModel.updateProcessingState(currentTabIsProcessing, currentTabProgressFloat)
        videoWorkflowViewModel.updateProgressText(currentTabText)
    }

    // O Box raiz ocupa todo o espaço fornecido pelo NavHost.
    // O padding já é tratado pelo Scaffold principal em AppNavigationHostComposable.
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { // Usa o innerPadding do Scaffold
        when (workflowStages.getOrNull(selectedStageIndex)?.identifier) {
            AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                ContextInfoContent(
                    modifier = Modifier.fillMaxSize(), // Garante que o conteúdo preencha o Box
                    innerPadding = PaddingValues(0.dp), // Conteúdo interno não precisa de padding adicional aqui
                    audioViewModel = audioViewModel,
                    snackbarHostState = snackbarHostState,
                    provideSaveActionDetails = { action, isEnabled ->
                        videoWorkflowViewModel.onContextSaveActionProvided(action, isEnabled)
                    },
                    onDirtyStateChange = { isDirty ->
                        videoWorkflowViewModel.onContextDirtyStateChanged(isDirty)
                    }
                )
            }
            AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                VideoInfoContent(
                    // modifier = Modifier.fillMaxSize(), // VideoInfoContent já tem fillMaxSize
                    innerPadding = PaddingValues(0.dp),
                    videoViewModel = videoInfoViewModel,
                    onReadyToLaunchPicker = { callback ->
                        videoWorkflowViewModel.setLaunchPickerAction(callback)
                    }
                )
            }
            AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                RefImageInfoContent(
                    modifier = Modifier.fillMaxSize(),
                    innerPadding = PaddingValues(0.dp),
                    refImageViewModel = refImageInfoViewModel
                )
            }
            AppDestinations.WORKFLOW_STAGE_NARRATIVE -> {
                AudioInfoContent(
                    modifier = Modifier.fillMaxSize(),
                    innerPadding = PaddingValues(0.dp),
                    audioViewModel = audioViewModel
                )
            }
            AppDestinations.WORKFLOW_STAGE_SCENES -> {
                VideoProjectContent(
                    // modifier = Modifier.fillMaxSize(), // VideoProjectContent já tem fillMaxSize
                    innerPadding = PaddingValues(0.dp),
                    projectViewModel = videoProjectViewModel
                )
            }
            AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                VideoGeneratorContent(
                    modifier = Modifier.fillMaxSize(),
                    innerPadding = PaddingValues(0.dp),
                    videoGeneratorViewModel = videoGeneratorViewModel
                )
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.error_unknown_stage))
                }
            }
        }
    }
}