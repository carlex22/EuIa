// File: euia/ui/VideoGeneratorContent.kt
package com.carlex.euia.ui

import android.net.Uri
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.carlex.euia.R
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.VideoGeneratorViewModel
import com.carlex.euia.viewmodel.VideoProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TAG_CONTENT = "VideoGeneratorContent"

@Composable
fun VideoGeneratorContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    videoGeneratorViewModel: VideoGeneratorViewModel = viewModel(),
    videoProjectViewModel: VideoProjectViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel()
) {
    // Coleta estados dos ViewModels
    val generatedVideoPath by videoGeneratorViewModel.generatedVideoPath.collectAsState()
    val isGeneratingVideo by videoGeneratorViewModel.isGeneratingVideo.collectAsState()
    val generationProgress by videoGeneratorViewModel.generationProgress.collectAsState()
    val showUploadDialog by videoGeneratorViewModel.showUploadDialog.collectAsState()
    val uploadTitle by videoGeneratorViewModel.uploadTitle.collectAsState()
    val uploadDescription by videoGeneratorViewModel.uploadDescription.collectAsState()
    val uploadHashtags by videoGeneratorViewModel.uploadHashtags.collectAsState()
    val selectedThumbnailPath by videoGeneratorViewModel.selectedThumbnailPath.collectAsState()
    val isGeneratingMetadata by videoGeneratorViewModel.isGeneratingMetadata.collectAsState()
    val isUploading by videoGeneratorViewModel.isUploading.collectAsState()
    val uploadStatusMessage by videoGeneratorViewModel.uploadStatusMessage.collectAsState()
    val sceneLinkDataList by videoProjectViewModel.sceneLinkDataList.collectAsState()
    val youtubeToken by authViewModel.youtubeAccessToken.collectAsState()

    // Estados locais da UI
    var isVideoPlaying by remember { mutableStateOf(false) }
    var showThumbnailSelectionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(generatedVideoPath) {
        isVideoPlaying = false
    }

    if (showUploadDialog) {
        UploadInfoDialog(
            title = uploadTitle,
            onTitleChange = { videoGeneratorViewModel.updateUploadTitle(it) },
            description = uploadDescription,
            onDescriptionChange = { videoGeneratorViewModel.updateUploadDescription(it) },
            hashtags = uploadHashtags,
            onHashtagsChange = { videoGeneratorViewModel.updateUploadHashtags(it) },
            thumbnailPath = selectedThumbnailPath,
            onThumbnailClick = { showThumbnailSelectionDialog = true },
            onDismiss = { videoGeneratorViewModel.onDismissUploadDialog() },
            onGenerateClick = { videoGeneratorViewModel.generateYouTubeMetadata() },
            onUploadClick = { videoGeneratorViewModel.finalizeYouTubeUpload(youtubeToken) },
            isGenerating = isGeneratingMetadata,
            isUploading = isUploading,
            uploadStatus = uploadStatusMessage
        )
    }

    if (showThumbnailSelectionDialog) {
        ThumbnailSelectionDialog(
            scenes = sceneLinkDataList,
            onDismiss = { showThumbnailSelectionDialog = false },
            onThumbnailSelected = { path ->
                videoGeneratorViewModel.onThumbnailSelected(path)
                showThumbnailSelectionDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            isGeneratingVideo -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = stringResource(R.string.video_generator_title_generating), style = MaterialTheme.typography.headlineSmall)
                    LinearProgressIndicator(progress = { generationProgress }, modifier = Modifier.fillMaxWidth(0.8f))
                    Text(text = "${(generationProgress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                }
            }
            generatedVideoPath.isNotBlank() -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { videoGeneratorViewModel.onOpenUploadDialog() },
                        modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.video_generator_action_upload_youtube))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.video_generator_action_upload_youtube))
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(9f / 16f)
                            .background(Color.Black)
                            .clickable(enabled = !isVideoPlaying) { isVideoPlaying = true }
                    ) {
                        VideoPlayerInternal(
                            videoPath = generatedVideoPath,
                            isPlaying = isVideoPlaying,
                            onPlaybackStateChange = { isVideoPlaying = it },
                            invalidPathErrorText = stringResource(R.string.video_generator_error_invalid_path)
                        )
                        if (!isVideoPlaying) {
                            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.video_generator_action_play_video), modifier = Modifier.size(72.dp), tint = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(text = stringResource(R.string.video_generator_label_file_path, generatedVideoPath.takeLast(50)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> {
                Text(stringResource(R.string.video_generator_placeholder_no_video), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun UploadInfoDialog(
    title: String, onTitleChange: (String) -> Unit,
    description: String, onDescriptionChange: (String) -> Unit,
    hashtags: String, onHashtagsChange: (String) -> Unit,
    thumbnailPath: String?, onThumbnailClick: () -> Unit,
    onDismiss: () -> Unit, onGenerateClick: () -> Unit, onUploadClick: () -> Unit,
    isGenerating: Boolean, isUploading: Boolean, uploadStatus: String
) {
    val isSendButtonEnabled = title.isNotBlank() && description.isNotBlank() && !thumbnailPath.isNullOrBlank() && !isGenerating && !isUploading

    AlertDialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        title = { Text(stringResource(R.string.youtube_upload_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onThumbnailClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailPath != null) {
                        Image(
                            painter = rememberAsyncImagePainter(model = File(thumbnailPath)),
                            contentDescription = stringResource(R.string.youtube_upload_thumbnail_preview),
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(stringResource(R.string.youtube_upload_thumbnail_placeholder))
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = title, onValueChange = onTitleChange, label = { Text(stringResource(R.string.youtube_upload_label_title)) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = description, onValueChange = onDescriptionChange, label = { Text(stringResource(R.string.youtube_upload_label_description)) }, maxLines = 5)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = hashtags, onValueChange = onHashtagsChange, label = { Text(stringResource(R.string.youtube_upload_label_hashtags)) })

                if (uploadStatus.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(text = uploadStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUploadClick, enabled = isSendButtonEnabled) {
                Text(stringResource(R.string.youtube_upload_action_send))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onGenerateClick, enabled = !isGenerating && !isUploading) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.youtube_upload_action_generate))
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss, enabled = !isUploading) {
                    Text(if (isUploading) stringResource(R.string.action_wait) else stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
fun ThumbnailSelectionDialog(
    scenes: List<SceneLinkData>,
    onDismiss: () -> Unit,
    onThumbnailSelected: (String) -> Unit
) {
    val validScenes = scenes.filter { !it.imagemGeradaPath.isNullOrBlank() && it.pathThumb == null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_upload_thumbnail_selection_title)) },
        text = {
            if (validScenes.isEmpty()) {
                Text(stringResource(R.string.youtube_upload_thumbnail_selection_no_images))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(validScenes, key = { it.id }) { scene ->
                        Image(
                            painter = rememberAsyncImagePainter(model = File(scene.imagemGeradaPath!!)),
                            contentDescription = "Cena ${scene.cena}",
                            modifier = Modifier
                                .size(120.dp, 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onThumbnailSelected(scene.imagemGeradaPath!!) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun VideoPlayerInternal(
    videoPath: String,
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String
) {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    
    LaunchedEffect(videoPath) {
        launch(Dispatchers.IO) {
            videoUri = try {
                val file = File(videoPath)
                if (file.exists()) FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) else null
            } catch (e: Exception) {
                Log.e(TAG_CONTENT, "Erro ao obter URI do vídeo: $videoPath", e)
                null
            }
        }
    }

    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)
    val currentVideoUri = videoUri
    if (currentVideoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(currentVideoUri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        currentOnPlaybackStateChange(isPlaying)
                    }
                    setOnCompletionListener { currentOnPlaybackStateChange(false) }
                    setOnErrorListener { _, _, _ -> currentOnPlaybackStateChange(false); true }
                }
            },
            update = { view ->
                if (isPlaying) { if (!view.isPlaying) view.start() } else { if (view.isPlaying) view.pause() }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}