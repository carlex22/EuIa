// File: euia/ui/VideoProjectContent.kt
package com.carlex.euia.ui

import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.ui.project.EmptyScenesPlaceholder
import com.carlex.euia.ui.project.ErrorPlaceholder
import com.carlex.euia.ui.project.HandleProjectDialogs
import com.carlex.euia.ui.project.LoadingPlaceholder
import com.carlex.euia.ui.project.SceneCard
import com.carlex.euia.viewmodel.VideoProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A função VideoPlayerInternal será movida para o SceneCard.kt, então pode ser removida daqui.
// A TAG pode ser removida se não for usada em mais nada aqui.
// private const val TAG_CONTENT = "VideoProjectContent"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoProjectContent(
    innerPadding: PaddingValues,
    projectViewModel: VideoProjectViewModel = viewModel()
) {
    // A coleta de todos os estados permanece a mesma...
    val isUiReady by projectViewModel.isUiReady.collectAsState()
    val sceneLinkDataList by projectViewModel.sceneLinkDataList.collectAsState()
    val isProcessingGlobalScenes by projectViewModel.isProcessingGlobalScenes.collectAsState()
    val globalSceneError by projectViewModel.globalSceneError.collectAsState()
    val allProjectReferenceImages by projectViewModel.currentImagensReferenciaStateFlow.collectAsState()
    val currentlyPlayingSceneId by projectViewModel.currentlyPlayingSceneId.collectAsState()
    val isGeneratingPreviewForScene by projectViewModel.isGeneratingPreviewForSceneId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by projectViewModel.snackbarMessage.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(snackbarMessage) {
        if (!snackbarMessage.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(snackbarMessage!!)
                projectViewModel.onSnackbarMessageShown()
            }
        }
    }

    HandleProjectDialogs(projectViewModel)
    // Os diálogos de confirmação também permanecem os mesmos...

    // A estrutura do Box principal é simplificada
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        // A lógica 'when' permanece a mesma
        when {
            !isUiReady -> LoadingPlaceholder(message = stringResource(R.string.video_project_status_loading_data))
            isProcessingGlobalScenes && sceneLinkDataList.isEmpty() -> LoadingPlaceholder(message = stringResource(R.string.video_project_status_generating_scene_structure))
            globalSceneError != null -> ErrorPlaceholder(message = globalSceneError!!, onDismiss = { projectViewModel.clearGlobalSceneError() })
            sceneLinkDataList.isEmpty() -> EmptyScenesPlaceholder()
            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(sceneLinkDataList, key = { it.id }) { sceneData ->
                        SceneCard(
                            sceneLinkData = sceneData,
                            projectViewModel = projectViewModel,
                            allProjectReferenceImages = allProjectReferenceImages,
                            currentlyPlayingSceneId = currentlyPlayingSceneId,
                            isGeneratingPreviewForScene = isGeneratingPreviewForScene
                        )
                    }
                }
            }
        }

        // O player de vídeo global foi REMOVIDO daqui.

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}