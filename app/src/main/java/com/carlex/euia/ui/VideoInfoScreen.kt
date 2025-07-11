// File: euia/ui/VideoInfoScreen.kt
package com.carlex.euia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carlex.euia.R
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.viewmodel.VideoViewModel
import java.io.File
import androidx.core.content.FileProvider

private const val TAG_VIDEO_INFO = "VideoInfoScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoInfoScreen(
    navController: NavHostController,
    videoViewModel: VideoViewModel = viewModel()
) {
    Scaffold(
        snackbarHost = {
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(videoViewModel.toastMessage) {
                 videoViewModel.toastMessage.collect { message ->
                     snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                 }
            }
            SnackbarHost(snackbarHostState)
        }
    ) { innerPadding ->
        VideoInfoContent(
            innerPadding = innerPadding,
            videoViewModel = videoViewModel,
            onReadyToLaunchPicker = { launchPickerAction ->
                Log.d(TAG_VIDEO_INFO, "onReadyToLaunchPicker chamado em VideoInfoScreen (wrapper).")
            }
        )
    }
}

@Composable
fun VideoInfoContent(
    innerPadding: PaddingValues,
    videoViewModel: VideoViewModel,
    onReadyToLaunchPicker: (action: () -> Unit) -> Unit
) {
    val imagensReferenciaList by videoViewModel.imagensReferenciaList.collectAsState()
    val isAnyImageProcessing by videoViewModel.isAnyImageProcessing.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) {
            Log.w(TAG_VIDEO_INFO, "Nenhuma mídia foi selecionada pelo usuário no seletor.")
            return@rememberLauncherForActivityResult
        }
        videoViewModel.processImages(uris)
    }

    LaunchedEffect(imagePickerLauncher, onReadyToLaunchPicker) {
        onReadyToLaunchPicker {
            imagePickerLauncher.launch("*/*") // Permite selecionar qualquer tipo de mídia (imagens e vídeos)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
    
        LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            
            if (imagensReferenciaList.isNotEmpty()) {
                items(imagensReferenciaList, key = { it.path }) { imageRef -> // path é único (thumb ou imagem)
                    ImageReferenceItem(
                        imageReference = imageRef,
                        isGlobalProcessing = isAnyImageProcessing,
                        onRemoveClick = { pathToRemove ->
                            // Ao remover, se for um vídeo, precisa remover o vídeo E a thumb.
                            // O ViewModel pode lidar com essa lógica baseada no imageReference.pathVideo
                            videoViewModel.removeImage(pathToRemove) // ViewModel decidirá o que excluir
                        },
                        onRotateClick = { path -> Log.d(TAG_VIDEO_INFO, "Ação 'Girar' clicada para: $path (Implementação Pendente)") },
                        onRemoveBackgroundClick = { path -> Log.d(TAG_VIDEO_INFO, "Ação 'Remover Fundo' clicada para: $path (Implementação Pendente)") },
                        onAddTextClick = { path -> Log.d(TAG_VIDEO_INFO, "Ação 'Adicionar Texto' clicada para: $path (Implementação Pendente)") },
                        onCropClick = { path -> Log.d(TAG_VIDEO_INFO, "Ação 'Recortar' clicada para: $path (Implementação Pendente)") }
                    )
                }
            } else {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ){
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ){
                                Icon(
                                    imageVector = Icons.Default.Collections,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = stringResource(R.string.context_info_imagem_import_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(R.string.context_info_imagem_import_context),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.context_info_imagem_import_text_click),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageReferenceItem(
    imageReference: ImagemReferencia,
    isGlobalProcessing: Boolean,
    onRemoveClick: (String) -> Unit, // Passa o path da thumb/imagem
    onRotateClick: (String) -> Unit,
    onRemoveBackgroundClick: (String) -> Unit,
    onAddTextClick: (String) -> Unit,
    onCropClick: (String) -> Unit
) {
    val context = LocalContext.current
    val isVideo = imageReference.pathVideo != null // Determina se é um vídeo pela presença de pathVideo
    var isVideoPlaying by remember(imageReference.path) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(1f)
                    .height(400.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .clickable(enabled = isVideo) {
                        if (isVideo) isVideoPlaying = !isVideoPlaying
                    }
            ) {
                // `imageReference.path` é sempre o caminho da imagem a ser exibida (thumb ou imagem estática)
                if (imageReference.path.isNotBlank()) {
                    val displayImageFile = File(imageReference.path)

                    if (displayImageFile.exists()) {
                        // Renderiza o player de vídeo por baixo se for um vídeo e estiver tocando
                        if (isVideo && isVideoPlaying && imageReference.pathVideo != null) {
                            VideoPlayerInternal(
                                videoPath = imageReference.pathVideo,
                                isPlaying = true, // O player interno deve começar a tocar
                                onPlaybackStateChange = { playing ->
                                    isVideoPlaying = playing // Atualiza o estado externo
                                },
                                invalidPathErrorText = stringResource(R.string.error_video_file_not_found)
                            )
                        }

                        // AsyncImage para exibir a imagem (thumbnail ou imagem estática)
                        // Ficará por cima do VideoPlayer se o vídeo estiver tocando,
                        // ou será a única coisa visível se for uma imagem estática ou vídeo pausado.
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(displayImageFile)
                                .crossfade(true)
                                .placeholder(R.drawable.ic_placeholder_image)
                                .error(R.drawable.ic_broken_image)
                                .build(),
                            contentDescription = imageReference.descricao.ifBlank { stringResource(R.string.content_desc_reference_image) },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            // Torna a thumbnail semi-transparente se o vídeo estiver tocando por baixo
                            alpha = if (isVideo && isVideoPlaying) 0.3f else 1.0f
                        )

                        // Overlay de ícone Play/Pause se for um vídeo
                        if (isVideo) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    // Não precisa de fundo aqui, pois o clique já é no Box externo
                                    .clickable(enabled = false) {}, // Consome cliques para não passar para o AsyncImage
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isVideoPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(if (isVideoPlaying) R.string.content_desc_pause_video else R.string.content_desc_play_video),
                                    modifier = Modifier.size(72.dp),
                                    tint = Color.White.copy(alpha = 0.8f) // Ícone sempre visível se for vídeo
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ImageNotSupported, contentDescription = stringResource(R.string.content_desc_image_placeholder), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ImageNotSupported, contentDescription = stringResource(R.string.content_desc_image_placeholder), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botões de edição são habilitados apenas para imagens estáticas
                val editButtonsEnabled = !isGlobalProcessing && imageReference.path.isNotBlank() && !isVideo
                ImageActionButton(Icons.Filled.RotateRight, R.string.video_info_action_rotate, { onRotateClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.AutoFixHigh, R.string.video_info_action_remove_background, { onRemoveBackgroundClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.TextFields, R.string.video_info_action_add_text, { onAddTextClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.Crop, R.string.video_info_action_crop, { onCropClick(imageReference.path) }, enabled = editButtonsEnabled)

                // Botão de exclusão sempre habilitado se não estiver processando globalmente
                ImageActionButton(Icons.Filled.DeleteForever, R.string.action_remove_image, { onRemoveClick(imageReference.path) }, enabled = !isGlobalProcessing)
            }
        }
    }
}

@Composable
private fun ImageActionButton(
    icon: ImageVector,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
    enabled: Boolean
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionRes),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}

@Composable
private fun VideoPlayerInternal(
    videoPath: String,
    isPlaying: Boolean, // Este estado agora é controlado pelo chamador
    onPlaybackStateChange: (Boolean) -> Unit, // Callback para informar o chamador
    invalidPathErrorText: String
) {
    val context = LocalContext.current
    val videoUri = remember(videoPath) { // Recalcula URI se videoPath mudar
        if (videoPath.isNotBlank()) {
            val file = File(videoPath)
            if (file.exists() && file.isFile) {
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    Log.e(TAG_VIDEO_INFO, "VideoPlayer: Erro ao obter URI com FileProvider para: $videoPath. Verifique a configuração do FileProvider e o caminho do arquivo.", e)
                    null
                }
            } else {
                Log.w(TAG_VIDEO_INFO, "VideoPlayer: Arquivo de vídeo não encontrado em $videoPath")
                null
            }
        } else null
    }

    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoUri) { // Se a URI mudar, reinicia o VideoView
        onDispose {
            videoViewInstance?.apply {
                stopPlayback()
                // setMediaController(null) // MediaController não é usado nesta versão
            }
            videoViewInstance = null
            Log.d(TAG_VIDEO_INFO, "VideoPlayer: Instância do VideoView limpa.")
        }
    }

    if (videoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    // val mediaController = MediaController(ctx) // Desabilitado para esta UI
                    // setMediaController(mediaController)
                    // mediaController.setAnchorView(this)

                    setVideoURI(videoUri)
                    setOnPreparedListener { mediaPlayer ->
                        Log.d(TAG_VIDEO_INFO, "VideoPlayer: Vídeo preparado. Duração: ${mediaPlayer.duration}ms. URI: $videoUri")
                        if (isPlaying) { // Se o estado externo já for true, começa a tocar
                            mediaPlayer.start()
                        }
                        currentOnPlaybackStateChange(this.isPlaying) // Informa estado atual
                    }
                    setOnCompletionListener {
                        Log.d(TAG_VIDEO_INFO, "VideoPlayer: Reprodução completa. URI: $videoUri")
                        currentOnPlaybackStateChange(false) // Informa que parou
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG_VIDEO_INFO, "VideoPlayer: Erro durante a reprodução. What: $what, Extra: $extra. URI: $videoUri")
                        currentOnPlaybackStateChange(false) // Informa que parou devido a erro
                        true // Indica que o erro foi tratado
                    }
                }
            },
            update = { view ->
                // Controla a reprodução/pausa com base no estado `isPlaying` externo.
                if (isPlaying && !view.isPlaying) {
                    // view.requestFocus() // Pode não ser necessário sem MediaController
                    view.start()
                } else if (!isPlaying && view.isPlaying) {
                    view.pause()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                invalidPathErrorText,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}