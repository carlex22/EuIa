// File: euia/ui/VideoProjectContent.kt
package com.carlex.euia.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.ui.project.EmptyScenesPlaceholder
import com.carlex.euia.ui.project.ErrorPlaceholder
import com.carlex.euia.ui.project.HandleProjectDialogs
import com.carlex.euia.ui.project.LoadingPlaceholder
import com.carlex.euia.ui.project.SceneCard
import com.carlex.euia.viewmodel.VideoProjectViewModel
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log // <<< ADICIONADO: Import Log
import androidx.compose.foundation.background // <<< ADICIONADO: Import background

/**
 * Composable de alto nível para a aba "Cenas" no fluxo de criação de vídeo.
 * Atua como um "gerente de palco", decidindo qual estado geral de UI exibir
 * (carregando, erro, vazio ou a lista de cenas) e delegando a renderização
 * de componentes específicos para outros Composables especializados.
 *
 * @param innerPadding Padding fornecido pelo Scaffold pai.
 * @param projectViewModel O ViewModel que gerencia os dados e o estado do projeto de vídeo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoProjectContent(
    innerPadding: PaddingValues,
    projectViewModel: VideoProjectViewModel = viewModel()
) {
    val context = LocalContext.current
    val isUiReady by projectViewModel.isUiReady.collectAsState()
    val sceneLinkDataList by projectViewModel.sceneLinkDataList.collectAsState()
    val showConfirmationDialog by projectViewModel.showSceneGenerationConfirmationDialog.collectAsState()
    val isProcessingGlobalScenes by projectViewModel.isProcessingGlobalScenes.collectAsState()
    val globalSceneError by projectViewModel.globalSceneError.collectAsState()
    val allProjectReferenceImages by projectViewModel.currentImagensReferenciaStateFlow.collectAsState()
    val currentlyPlayingSceneId by projectViewModel.currentlyPlayingSceneId.collectAsState()
    val isGeneratingPreviewForScene by projectViewModel.isGeneratingPreviewForSceneId.collectAsState()

    // Estados de diálogo que ainda são controlados diretamente pelo ViewModel e passados para o HandleProjectDialogs
    val showImageBatchCostDialog by projectViewModel.showImageBatchCostConfirmationDialog.collectAsState()
    val imageBatchCost by projectViewModel.pendingImageBatchCost.collectAsState()
    val imageBatchCount by projectViewModel.pendingImageBatchCount.collectAsState()
    val sceneIdToRecreateImage by projectViewModel.sceneIdToRecreateImage.collectAsState()
    val promptForRecreateImage by projectViewModel.promptForRecreateImage.collectAsState()
    val sceneIdForMediaExplorer by projectViewModel.showPixabaySearchDialogForSceneId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by projectViewModel.snackbarMessage.collectAsState()
    val scope = rememberCoroutineScope()

    // Observa mensagens do ViewModel para exibir no Snackbar
    LaunchedEffect(snackbarMessage) {
        if (!snackbarMessage.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(snackbarMessage!!)
                projectViewModel.onSnackbarMessageShown()
            }
        }
    }

    // Gerencia todos os diálogos da tela de cenas através de um único Composable
    HandleProjectDialogs(projectViewModel)

    // Diálogo para confirmar a geração de um novo roteiro de cenas (apenas se houver cenas existentes)
    if (showConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelSceneGenerationDialog() },
            title = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_title)) },
            text = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_message)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithSceneGeneration() }, enabled = !isProcessingGlobalScenes) { Text(stringResource(R.string.video_project_dialog_action_generate_new)) } },
            dismissButton = { OutlinedButton(onClick = { projectViewModel.cancelSceneGenerationDialog() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    // Diálogo para confirmar o custo da geração de imagens em lote
    if (showImageBatchCostDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelImageBatchGeneration() },
            title = { Text(text = stringResource(R.string.dialog_title_confirm_generation)) },
            text = { Text(text = stringResource(R.string.dialog_message_image_batch_cost, imageBatchCount, imageBatchCost)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmImageBatchGeneration() }) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Botão para buscar vídeos gratuitos na Pixabay
                    Button(
                        onClick = { projectViewModel.triggerBatchPixabayVideoSearch() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) { Text(stringResource(R.string.dialog_action_search_free_videos)) }
                    // Botão de Cancelar
                    TextButton(onClick = { projectViewModel.cancelImageBatchGeneration() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        )
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)) {

        when {
            !isUiReady -> {
                LoadingPlaceholder(message = stringResource(R.string.video_project_status_loading_data))
            }
            isProcessingGlobalScenes && sceneLinkDataList.isEmpty() -> {
                LoadingPlaceholder(message = stringResource(R.string.video_project_status_generating_scene_structure))
            }
            globalSceneError != null -> {
                ErrorPlaceholder(
                    message = globalSceneError!!,
                    onDismiss = { projectViewModel.clearGlobalSceneError() }
                )
            }
            sceneLinkDataList.isEmpty() -> {
                EmptyScenesPlaceholder()
            }
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
        // O SnackbarHost permanece no componente de mais alto nível da tela
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * Composable auxiliar para exibir um vídeo.
 * NOTA: Esta implementação é um wrapper básico para o Android VideoView.
 * Para uso em produção, considere uma biblioteca mais robusta (ex: ExoPlayer).
 * Esta função foi movida para cá porque `SceneCard` não a chamará mais diretamente.
 *
 * @param videoPath Caminho absoluto do arquivo de vídeo ou URL.
 * @param isPlaying Controla se o vídeo deve estar tocando ou pausado.
 * @param onPlaybackStateChange Callback para notificar a UI sobre mudanças no estado de reprodução.
 * @param invalidPathErrorText Mensagem de erro a ser exibida se o vídeo não puder ser carregado.
 */
@Composable
private fun VideoPlayerInternal(
    videoPath: String,
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String
) {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    
    // Atualiza a Uri sempre que o videoPath muda
    LaunchedEffect(videoPath) {
        videoUri = withContext(Dispatchers.IO) {
            try {
                if (videoPath.startsWith("http")) {
                    Uri.parse(videoPath)
                } else if (videoPath.isNotBlank()) {
                    val file = File(videoPath)
                    if (file.exists() && file.isFile) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else {
                        Log.w("VideoPlayerInternal", "VideoPlayer: Arquivo de vídeo não encontrado: $videoPath")
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerInternal", "VideoPlayer: Erro ao obter URI para: $videoPath", e)
                null
            }
        }
    }

    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)

    DisposableEffect(videoUri) {
        onDispose {
            videoViewInstance?.apply {
                stopPlayback()
                setMediaController(null) // Garante que o MediaController seja removido
            }
            videoViewInstance = null
        }
    }
    
    val currentVideoUri = videoUri
    if (currentVideoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    setVideoURI(currentVideoUri)
                    val mediaController = MediaController(ctx)
                    setMediaController(mediaController)
                    mediaController.setAnchorView(this)

                    setOnPreparedListener { mediaPlayer ->
                        Log.d("VideoPlayerInternal", "VideoPlayer: Vídeo preparado. URI: $currentVideoUri")
                        if (isPlaying) {
                            mediaPlayer.start()
                            currentOnPlaybackStateChange(true)
                        } else {
                            mediaPlayer.seekTo(1) // Mostra o primeiro frame
                            currentOnPlaybackStateChange(false)
                        }
                        mediaPlayer.isLooping = true // Loop para prévias
                    }
                    setOnCompletionListener {
                        Log.d("VideoPlayerInternal", "VideoPlayer: Reprodução completa. URI: $currentVideoUri")
                        currentOnPlaybackStateChange(false)
                        mediaController.hide() // Esconde o controlador ao finalizar
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("VideoPlayerInternal", "VideoPlayer: Erro. What: $what, Extra: $extra. URI: $currentVideoUri")
                        currentOnPlaybackStateChange(false)
                        true
                    }
                }
            },
            update = { view ->
                if (isPlaying && !view.isPlaying) {
                    view.start()
                } else if (!isPlaying && view.isPlaying) {
                    view.pause()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (videoPath.isNotBlank()) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            } else {
                Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
        }
    }
}