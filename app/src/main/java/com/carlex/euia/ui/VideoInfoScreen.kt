// File: euia/ui/VideoInfoScreen.kt
package com.carlex.euia.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carlex.euia.R
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.viewmodel.VideoViewModel
import java.io.File
// <<< INÍCIO DA CORREÇÃO 1: IMPORTS FALTANDO >>>
import androidx.compose.ui.unit.dp
// <<< FIM DA CORREÇÃO 1 >>>

private const val TAG_VIDEO_INFO = "VideoInfoScreen"


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoInfoContent(
    innerPadding: PaddingValues,
    videoViewModel: VideoViewModel,
    onReadyToLaunchPicker: (action: () -> Unit) -> Unit
) {
    val imagensReferenciaList by videoViewModel.imagensReferenciaList.collectAsState()
    val isAnyImageProcessing by videoViewModel.isAnyImageProcessing.collectAsState()
    val context = LocalContext.current

    var originalPathForEdit by remember { mutableStateOf<String?>(null) }
    val editorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val newPath = result.data?.getStringExtra(ImageEditorActivity.EXTRA_EDITED_IMAGE_PATH)
            if (originalPathForEdit != null && !newPath.isNullOrBlank()) {
                videoViewModel.onImageEdited(originalPathForEdit!!, newPath)
            } else {
                 Log.w(TAG_VIDEO_INFO, "Editor retornou OK, mas o caminho original ou novo está nulo.")
            }
        } else {
            Log.d(TAG_VIDEO_INFO, "Edição de imagem cancelada ou falhou.")
        }
        originalPathForEdit = null
    }

    val launchEditor = { path: String ->
        originalPathForEdit = path
        val intent = Intent(context, ImageEditorActivity::class.java).apply {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG_VIDEO_INFO, "Arquivo para edição não encontrado em: $path")
                return@apply
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            putExtra(ImageEditorActivity.EXTRA_IMAGE_URI, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        editorLauncher.launch(intent)
    }
    
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) {
            Log.w(TAG_VIDEO_INFO, "Nenhuma mídia foi selecionada pelo usuário.")
            return@rememberLauncherForActivityResult
        }
        videoViewModel.processImages(uris)
    }
    
        
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbarMessage by videoViewModel.snackbarMessage.collectAsState() // Observa a mensagem
    
    
    
    
    
    LaunchedEffect(Unit) {
        onReadyToLaunchPicker {
            mediaPickerLauncher.launch("*/*")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
    
    
    
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        
            

            if (imagensReferenciaList.isNotEmpty()) {
                items(imagensReferenciaList, key = { it.path }) { imageRef ->
                    ImageReferenceItem(
                        imageReference = imageRef,
                        isGlobalProcessing = isAnyImageProcessing,
                        onRemoveClick = { videoViewModel.removeImage(it) },
                        onRotateClick = { launchEditor(it) },
                        onRemoveBackgroundClick = { Log.d(TAG_VIDEO_INFO, "Remover Fundo clicado para: $it") },
                        onAddTextClick = { launchEditor(it) },
                        onCropClick = { launchEditor(it) }
                    )
                }
            } else {
                 item {
                    Card(
                        modifier = Modifier.fillMaxWidth().wrapContentHeight().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ){
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ){
                                Icon(Icons.Default.Collections, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                Text(stringResource(R.string.context_info_imagem_import_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                Text(stringResource(R.string.context_info_imagem_import_context), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.context_info_imagem_import_text_click), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            //modifier = Modifier.align(Alignment.BottomCenter)
        )
        
    }
}

// <<< INÍCIO DA CORREÇÃO 2: ANOTAÇÃO CORRIGIDA >>>
@OptIn(ExperimentalMaterial3Api::class)
// <<< FIM DA CORREÇÃO 2 >>>
@Composable
private fun ImageReferenceItem(
    imageReference: ImagemReferencia,
    isGlobalProcessing: Boolean,
    onRemoveClick: (String) -> Unit,
    onRotateClick: (String) -> Unit,
    onRemoveBackgroundClick: (String) -> Unit,
    onAddTextClick: (String) -> Unit,
    onCropClick: (String) -> Unit
) {
    val isVideo = imageReference.path.endsWith(".mp4", ignoreCase = true)  
    var isVideoPlaying by remember(imageReference.path) { mutableStateOf(false) }


    var videoViewModel: VideoViewModel = viewModel()
    
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.fillMaxWidth(1f).height(400.dp).align(Alignment.CenterHorizontally)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    
            ) {
            
                 if (imageReference.path.isNotBlank()) {
                    var displayImageFile = File(imageReference.path)
                    
                    if (isVideo)
                        displayImageFile = File(imageReference.pathThumb)

                    if (displayImageFile.exists()) {
                        if (isVideo && isVideoPlaying) {
                            VideoPlayerInternal(
                                videoPath = imageReference.path,
                                isPlaying = isVideoPlaying,
                                onPlaybackStateChange = { playing -> isVideoPlaying = playing },
                                invalidPathErrorText = stringResource(R.string.error_video_file_not_found)
                            )
                        }

                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(displayImageFile).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                            contentDescription = imageReference.descricao.ifBlank { stringResource(R.string.content_desc_reference_image) },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alpha = if (isVideo && isVideoPlaying) 0.0f else 1.0f
                        )

                        if (isVideo) {
                            Box(modifier = Modifier.matchParentSize()
                            .clickable { 
                                isVideoPlaying = !isVideoPlaying
                                //videoViewModel.mostrarToast("isVideoPlaying $isVideoPlaying")
                            },
                            contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isVideoPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(if (isVideoPlaying) R.string.content_desc_pause_video else R.string.content_desc_play_video),
                                    modifier = Modifier.size(72.dp),
                                    tint = Color.White.copy(alpha = 0.8f)
                                )
                                
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.ImageNotSupported, stringResource(R.string.content_desc_image_placeholder), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.ImageNotSupported, stringResource(R.string.content_desc_image_placeholder), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val editButtonsEnabled = !isGlobalProcessing && imageReference.path.isNotBlank() && !isVideo

                ImageActionButton(Icons.Filled.RotateRight, R.string.video_info_action_rotate, { onRotateClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.Crop, R.string.video_info_action_crop, { onCropClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.TextFields, R.string.video_info_action_add_text, { onAddTextClick(imageReference.path) }, enabled = editButtonsEnabled)
                //ImageActionButton(Icons.Filled.AutoFixHigh, R.string.video_info_action_remove_background, { onRemoveBackgroundClick(imageReference.path) }, enabled = editButtonsEnabled)
                ImageActionButton(Icons.Filled.DeleteForever, R.string.action_remove_image, { onRemoveClick(imageReference.path) }, enabled = !isGlobalProcessing)
            }
        }
    }
    
    
    
    
}

@Composable
private fun ImageActionButton(icon: ImageVector, contentDescriptionRes: Int, onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
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
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String
) {

    val context = LocalContext.current
    val videoUri = remember(videoPath) {
        if (videoPath.isNotBlank()) {
            val file = File(videoPath)
            if (file.exists() && file.isFile) {
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    //Log.e(TAG_CONTENT, "VideoPlayer: Erro ao obter URI para: $videoPath!!", e!!)
                    null
                }
            } else {
                //Log.w(TAG_CONTENT, "VideoPlayer: Arquivo de vídeo não encontrado: $videoPath!!")
                null
            }
        } else null
    }

    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoUri) {
        onDispose {
            videoViewInstance?.apply {
                stopPlayback()
            }
            videoViewInstance = null
        }
    }

    if (videoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    setVideoURI(videoUri)
                    setOnPreparedListener { mediaPlayer ->
                        //Log.d(TAG_CONTENT, "VideoPlayer (Scene): Vídeo preparado. URI: $videoUri!!")
                        if (isPlaying) {
                            mediaPlayer.start()
                            onPlaybackStateChange(true)
                        } else {
                             onPlaybackStateChange(false)
                        }
                        mediaPlayer.isLooping = false
                    }
                    setOnCompletionListener {
                       // Log.d(TAG_CONTENT, "VideoPlayer (Scene): Reprodução completa. URI: $videoUri!!")
                        onPlaybackStateChange(false)
                    }
                    setOnErrorListener { _, what, extra ->
                        //Log.e(TAG_CONTENT, "VideoPlayer (Scene): Erro. What: $what!!, Extra: $extra!!. URI: $videoUri!!")
                        onPlaybackStateChange(false)
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
            Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}



@Composable
private fun VideoPlayerInternal1(videoPath: String, isPlaying: Boolean, onPlaybackStateChange: (Boolean) -> Unit, invalidPathErrorText: String) {
    val context = LocalContext.current
    val videoUri = remember(videoPath) {
        if (videoPath.isNotBlank()) {
            val file = File(videoPath)
            if (file.exists() && file.isFile) {
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    Log.e(TAG_VIDEO_INFO, "VideoPlayer: Erro ao obter URI para: $videoPath", e)
                    null
                }
            } else {
                Log.w(TAG_VIDEO_INFO, "VideoPlayer: Arquivo de vídeo não encontrado: $videoPath")
                null
            }
        } else null
    }

    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoUri) {
        onDispose {
            videoViewInstance?.stopPlayback()
            videoViewInstance = null
        }
    }

    if (videoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    setVideoURI(videoUri)
                    setOnPreparedListener { mp ->
                        if (isPlaying) mp.start()
                        onPlaybackStateChange(this.isPlaying)
                    }
                    setOnCompletionListener { onPlaybackStateChange(false) }
                    setOnErrorListener { _, _, _ -> onPlaybackStateChange(false); true }
                }
            },
            update = { view ->
                if (isPlaying && !view.isPlaying) view.start()
                else if (!isPlaying && view.isPlaying) view.pause()
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
    
    
    
}