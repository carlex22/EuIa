// File: euia/ui/VideoCreationWorkflowScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.carlex.euia.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp // <<< IMPORT ADICIONADO AQUI
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.carlex.euia.AppDestinations
import com.carlex.euia.R
import com.carlex.euia.viewmodel.*
import com.carlex.euia.utils.shareVideoFile // Verifique se esta importação está correta

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
    authViewModel: AuthViewModel = viewModel(),
    musicViewModel: MusicViewModel = viewModel(), // Parâmetro adicionado
    generatedVideoPath: String
) {
    val localContext = LocalContext.current
    val selectedStageIndex by videoWorkflowViewModel.selectedWorkflowTabIndex.collectAsState()
    val workflowStages = videoWorkflowViewModel.workflowStages

    val isImageProcessing by videoInfoViewModel.isAnyImageProcessing.collectAsState()
    val isRefInfoAnalyzing by refImageInfoViewModel.isAnalyzing.collectAsState()
    val isAudioProcessing by audioViewModel.isAudioProcessing.collectAsState()
    val audioGenerationProgressText by audioViewModel.generationProgressText.collectAsState()
    val isGeneratingGlobalScenes by videoProjectViewModel.isProcessingGlobalScenes.collectAsState()
    val isGeneratingVideo by videoGeneratorViewModel.isGeneratingVideo.collectAsState()
    val generationVideoProgress by videoGeneratorViewModel.generationProgress.collectAsState()

    // O LaunchedEffect para gerenciar as ações da BottomBar permanece o mesmo
    LaunchedEffect(
        selectedStageIndex, isImageProcessing, isRefInfoAnalyzing, isAudioProcessing,
        isGeneratingGlobalScenes, isGeneratingVideo, generatedVideoPath,
        audioGenerationProgressText, generationVideoProgress
    ) {
        val currentStageId = workflowStages.getOrNull(selectedStageIndex)?.identifier ?: return@LaunchedEffect

        // Reseta as ações
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

        when (currentStageId) {
            AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                // Ação de salvar é tratada de forma especial pela `ContextInfoContent`
            }
            AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                currentTabIsProcessing = isImageProcessing
                currentTabText = if (isImageProcessing) localContext.getString(R.string.status_processing_images_general) else ""
            }
            AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                videoWorkflowViewModel.setAnalyzeAction { refImageInfoViewModel.analyzeImages() }
                currentTabIsProcessing = isRefInfoAnalyzing
                currentTabText = if (isRefInfoAnalyzing) localContext.getString(R.string.status_analyzing_details) else ""
            }
            AppDestinations.WORKFLOW_STAGE_NARRATIVE -> {
                videoWorkflowViewModel.setCreateNarrativeAction {
                     audioViewModel.startAudioGeneration(
                         promptToUse = audioViewModel.prompt.value,
                         isNewNarrative = true
                     )
                }
                videoWorkflowViewModel.setGenerateAudioAction {
                     audioViewModel.startAudioGeneration(
                         promptToUse = audioViewModel.prompt.value,
                         isNewNarrative = false
                     )
                }
                currentTabIsProcessing = isAudioProcessing
                currentTabText = if (isAudioProcessing) {
                    audioGenerationProgressText.ifBlank { localContext.getString(R.string.status_processing_audio_default) }
                } else ""
            }
            AppDestinations.WORKFLOW_STAGE_SCENES -> {
                videoWorkflowViewModel.setGenerateScenesAction { videoProjectViewModel.requestFullSceneGenerationProcess() }
                currentTabIsProcessing = isGeneratingGlobalScenes
                currentTabText = if (isGeneratingGlobalScenes) localContext.getString(R.string.status_generating_scenes) else ""
            }
            // Ação vazia para a aba de música
            AppDestinations.WORKFLOW_STAGE_MUSIC -> {}

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
                    isGeneratingVideo -> localContext.getString(R.string.status_recording_video_progress, (currentTabProgressFloat * 100).toInt())
                    generatedVideoPath.isNotBlank() -> localContext.getString(R.string.status_video_ready)
                    else -> ""
                }
            }
        }
        videoWorkflowViewModel.updateProcessingState(currentTabIsProcessing, currentTabProgressFloat)
        videoWorkflowViewModel.updateProgressText(currentTabText)
    }

    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        when (workflowStages.getOrNull(selectedStageIndex)?.identifier) {
            AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                ContextInfoContent(
                    modifier = Modifier.fillMaxSize(),
                    innerPadding = PaddingValues(0.dp),
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
                    innerPadding = PaddingValues(0.dp),
                    projectViewModel = videoProjectViewModel
                )
            }
            AppDestinations.WORKFLOW_STAGE_MUSIC -> {
                MusicSelectionContent(
                    modifier = Modifier.fillMaxSize(),
                    musicViewModel = musicViewModel
                )
            }
            AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                VideoGeneratorContent(
                    modifier = Modifier.fillMaxSize(),
                    innerPadding = PaddingValues(0.dp),
                    videoGeneratorViewModel = videoGeneratorViewModel,
                    videoProjectViewModel = videoProjectViewModel,
                    authViewModel = authViewModel
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