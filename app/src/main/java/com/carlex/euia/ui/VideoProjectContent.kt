// File: ui/VideoProjectContent.kt
package com.carlex.euia.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.ui.unit.Dp
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carlex.euia.R
import com.carlex.euia.api.PixabayVideo
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.VideoProjectViewModel
import java.io.File
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView


private const val TAG_CONTENT = "VideoProjectContent"

@Composable
private fun PixabaySearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onDismiss: () -> Unit,
    onVideoSelected: (PixabayVideo) -> Unit,
    searchResults: List<PixabayVideo>,
    isSearching: Boolean
) {
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pixabay_search_dialog_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.pixabay_search_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearchClick()
                    }),
                    trailingIcon = {
                        IconButton(onClick = onSearchClick, enabled = !isSearching) {
                            Icon(Icons.Default.Search, stringResource(R.string.pixabay_search_action_desc))
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.pixabay_search_no_results), textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(searchResults, key = { it.id }) { video ->
                            Card(
                                modifier = Modifier
                                    .aspectRatio(9f / 16f)
                                    .clickable { onVideoSelected(video) },
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AsyncImage(
                                        model = video.videoFiles.small.thumbnail,
                                        contentDescription = video.tags,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Icon(
                                        Icons.Default.PlayCircleOutline,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}


@Composable
private fun ReferenceImageSelectionDialog(
    onDismissRequest: () -> Unit,
    availableReferenceImages: List<ImagemReferencia>,
    onReferenceImageSelected: (ImagemReferencia) -> Unit,
    dialogTitle: String
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(dialogTitle) },
        text = {
            if (availableReferenceImages.isEmpty()) {
                Text(stringResource(R.string.scene_item_dialog_no_ref_images_available))
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(availableReferenceImages, key = { it.path }) { refImage ->
                        Card(
                            modifier = Modifier
                                .size(100.dp, 150.dp)
                                .clickable { onReferenceImageSelected(refImage) },
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(File(refImage.path))
                                    .crossfade(true)
                                    .placeholder(R.drawable.ic_placeholder_image)
                                    .error(R.drawable.ic_broken_image)
                                    .build(),
                                contentDescription = refImage.descricao,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}


@Composable
private fun SprocketHolesRow(
    holeColor: Color = Color.White,
    holeCount: Int = 4,
    holeWidth: Dp = 21.dp,
    holeRectangleHeight: Dp = 22.dp,
    rowHeight: Dp = 45.dp,
    shadowOffsetX: Dp = (2).dp,
    shadowOffsetY: Dp = (2).dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cornerRadiusValue = 3.dp
        repeat(holeCount) {
            Box(
                modifier = Modifier
                    .size(width = holeWidth, height = holeRectangleHeight)
                    .clip(RoundedCornerShape(cornerRadiusValue))
                    .drawBehind {
                        val rectWidthPx = this.size.width
                        val rectHeightPx = this.size.height
                        val offsetXpx = shadowOffsetX.toPx()
                        val offsetYpx = shadowOffsetY.toPx()
                        val defaultCenterX = rectWidthPx / 2f
                        val defaultCenterY = rectHeightPx / 2f
                        val gradientCenter = Offset(
                            x = defaultCenterX + offsetXpx,
                            y = defaultCenterY + offsetYpx
                        )
                        val gradientEffectRadius = (rectWidthPx + rectHeightPx) / 2f * 0.85f

                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    holeColor.copy(alpha = 0.6f),
                                    holeColor.copy(alpha = 0.8f),
                                    Color.Black.copy(alpha = 0.2f)
                                ),
                                center = gradientCenter,
                                radius = gradientEffectRadius
                            ),
                            size = this.size,
                            cornerRadius = CornerRadius(cornerRadiusValue.toPx())
                        )
                    }
                    .border(0.5.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(cornerRadiusValue))
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFCCCCCC)
@Composable
fun PreviewSprocketHolesRowDefault() { SprocketHolesRow() }

@Preview(showBackground = true, backgroundColor = 0xFFCCCCCC)
@Composable
fun PreviewSprocketHolesRowShadowOffsetPositive() { SprocketHolesRow() }


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneLinkItem(
    sceneLinkData: SceneLinkData,
    projectViewModel: VideoProjectViewModel,
    allProjectReferenceImages: List<ImagemReferencia>,
    currentlyPlayingSceneId: String?,
    isAudioLoadingForScene: String?,
    onPlayAudioSnippet: (SceneLinkData) -> Unit,
    onStopAudioSnippet: () -> Unit,
    onGenerateImageWithConfirmation: (sceneId: String, prompt: String) -> Unit
) {
    val context = LocalContext.current
    var promptStateInDialog by remember(sceneLinkData.promptGeracao) { mutableStateOf(TextFieldValue(sceneLinkData.promptGeracao ?: "")) }
    var showPromptEditDialog by remember { mutableStateOf(false) }
    var refImageDialogTitle by remember { mutableStateOf("") }
    var showGenericRefImageSelectionDialog by remember { mutableStateOf(false) }
    var onRefImageSelectedAction by remember { mutableStateOf<(ImagemReferencia) -> Unit>({}) }

    var selectedNewRefImageForChange by remember { mutableStateOf<ImagemReferencia?>(null) }
    var showConfirmChangeRefImageDialog by remember { mutableStateOf(false) }
    var refImageToReplaceGeneratedWith by remember { mutableStateOf<ImagemReferencia?>(null) }
    var showConfirmReplaceGeneratedWithRefDialog by remember { mutableStateOf(false) }
    var refImageForClothesChange by remember { mutableStateOf<ImagemReferencia?>(null) }
    var showConfirmChangeClothesWithRefDialog by remember { mutableStateOf(false) }

    var showVideoPromptDialog by remember { mutableStateOf(false) }
    var editableVideoPromptState by remember { mutableStateOf(TextFieldValue("")) }


    val filmFrameColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    val photoAreaBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val filmFrameShape = RoundedCornerShape(0.dp)
    val photoAreaShape = RoundedCornerShape(6.dp)

    val isCurrentlyPlayingThisSceneAudio = currentlyPlayingSceneId == sceneLinkData.id
    val isAudioLoadingForThisScene = isAudioLoadingForScene == sceneLinkData.id

    val defaultEnabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val primaryActionIconTint = MaterialTheme.colorScheme.primary
    val disabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    val originalReferenceImageDetails = allProjectReferenceImages.find { it.path == sceneLinkData.imagemReferenciaPath }
    val refImageIsVideo = originalReferenceImageDetails?.pathVideo != null
    val refImageContainsPeople = originalReferenceImageDetails?.containsPeople ?: false

    var isVideoPlayingThisScene by remember(sceneLinkData.id, sceneLinkData.pathThumb) { mutableStateOf(false) }


    Box(
        modifier = Modifier
            .width(350.dp)
            .fillMaxHeight()
            .background(filmFrameColor, filmFrameShape)
            .clip(filmFrameShape)
            .padding(start = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            SprocketHolesRow()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), photoAreaShape)
                    .background(photoAreaBackgroundColor, photoAreaShape)
                    .clip(photoAreaShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        sceneLinkData.cena?.let {
                            Text(text = stringResource(R.string.scene_item_label_scene_number, it), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row {
                            sceneLinkData.tempoInicio?.let { Text(stringResource(R.string.scene_item_label_time_start, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(modifier = Modifier.width(4.dp))
                            sceneLinkData.tempoFim?.let { Text(stringResource(R.string.scene_item_label_time_end, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                            .clickable(enabled = !sceneLinkData.pathThumb.isNullOrBlank()) {
                                if (!sceneLinkData.pathThumb.isNullOrBlank()) {
                                    isVideoPlayingThisScene = !isVideoPlayingThisScene
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val isGeneratingImage = sceneLinkData.isGenerating
                        val isChangingClothes = sceneLinkData.isChangingClothes
                        val isGeneratingVideo = sceneLinkData.isGeneratingVideo
                        val isGeneratingAnything = isGeneratingImage || isChangingClothes || isGeneratingVideo

                        val displayPath = sceneLinkData.pathThumb ?: sceneLinkData.imagemGeradaPath

                        when {
                            isGeneratingImage -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.scene_item_status_generating_attempt, sceneLinkData.generationAttempt), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (sceneLinkData.generationAttempt > 1 && !sceneLinkData.generationErrorMessage.isNullOrBlank()) {
                                        Text(text = stringResource(R.string.scene_item_error_previous_attempt, sceneLinkData.generationErrorMessage!!), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
                                    }
                                }
                            }
                            isChangingClothes -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.scene_item_status_changing_clothes_attempt, sceneLinkData.clothesChangeAttempt), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (sceneLinkData.clothesChangeAttempt > 1 && !sceneLinkData.generationErrorMessage.isNullOrBlank()) {
                                        Text(text = stringResource(R.string.scene_item_error_previous_attempt, sceneLinkData.generationErrorMessage!!), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
                                    }
                                }
                            }
                             isGeneratingVideo -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.scene_item_status_generating_video_attempt, sceneLinkData.generationAttempt), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (sceneLinkData.generationAttempt > 1 && !sceneLinkData.generationErrorMessage.isNullOrBlank()) {
                                        Text(text = stringResource(R.string.scene_item_error_previous_attempt, sceneLinkData.generationErrorMessage!!), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
                                    }
                                }
                            }
                            !displayPath.isNullOrBlank() -> {
                                if (!sceneLinkData.pathThumb.isNullOrBlank() && isVideoPlayingThisScene && !sceneLinkData.imagemGeradaPath.isNullOrBlank()) {
                                    VideoPlayerInternal(
                                        videoPath = sceneLinkData.imagemGeradaPath!!,
                                        isPlaying = true,
                                        onPlaybackStateChange = { playing -> isVideoPlayingThisScene = playing },
                                        invalidPathErrorText = stringResource(R.string.error_video_file_not_found_scene)
                                    )
                                }

                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(File(displayPath)).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                                    contentDescription = stringResource(if (!sceneLinkData.pathThumb.isNullOrBlank()) R.string.content_desc_video_thumbnail else R.string.content_desc_generated_image),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alpha = if (!sceneLinkData.pathThumb.isNullOrBlank() && isVideoPlayingThisScene) 0.2f else 1.0f
                                )

                                if (!sceneLinkData.pathThumb.isNullOrBlank()) {
                                    Icon(
                                        if (isVideoPlayingThisScene) Icons.Filled.PauseCircleOutline else Icons.Filled.PlayCircleOutline,
                                        contentDescription = stringResource(if (isVideoPlayingThisScene) R.string.content_desc_pause_video_overlay else R.string.content_desc_play_video_overlay),
                                        modifier = Modifier.align(Alignment.Center).size(60.dp),
                                        tint = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }
                            else -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (!sceneLinkData.generationErrorMessage.isNullOrBlank()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                                .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
                                                .padding(8.dp)
                                                .clickable { projectViewModel.clearSceneGenerationError(sceneLinkData.id) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Error,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = sceneLinkData.generationErrorMessage!!,
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = stringResource(R.string.status_tap_to_clear_error),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(16.dp))
                                    }
                                    
                                    Text(
                                        text = stringResource(R.string.scene_placeholder_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.scene_placeholder_instructions),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoFixHigh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = " " + stringResource(R.string.scene_placeholder_action_generate),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = " " + stringResource(R.string.scene_placeholder_action_select),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        if (!displayPath.isNullOrBlank() && !sceneLinkData.generationErrorMessage.isNullOrBlank() && !isGeneratingAnything) {
                            Text(
                                text = stringResource(R.string.scene_item_error_prefix, sceneLinkData.generationErrorMessage!!),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .clickable { projectViewModel.clearSceneGenerationError(sceneLinkData.id) }
                            )
                        }


                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(width = 100.dp, height = 120.dp)
                                .offset(x = (-12).dp, y = (-12).dp)
                                .zIndex(1f)
                                .background(Color.DarkGray.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                                .clickable {
                                    if (allProjectReferenceImages.isNotEmpty()) {
                                        projectViewModel.triggerSelectReferenceImageForScene(sceneLinkData.id)
                                    } else {
                                        Toast.makeText(context, R.string.scene_item_dialog_no_ref_images_available, Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            if (sceneLinkData.imagemReferenciaPath.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(File(sceneLinkData.imagemReferenciaPath)).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                                    contentDescription = stringResource(R.string.content_desc_reference_image), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Filled.ImageNotSupported, contentDescription = stringResource(R.string.scene_item_placeholder_no_ref_image_desc), modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                }
                            }
                            Text(text = stringResource(R.string.scene_item_label_ref_image_caption), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(vertical = 1.dp))
                        }
                        sceneLinkData.similaridade?.let {
                             Text(stringResource(R.string.scene_item_label_similarity, it), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(2.dp)).padding(horizontal = 2.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val iconButtonSize = 36.dp
                    val iconSize = 20.dp
                    val generalActionsEnabled = !sceneLinkData.isGenerating && !sceneLinkData.isChangingClothes && !sceneLinkData.isGeneratingVideo
                    val playAudioButtonEnabled = generalActionsEnabled && sceneLinkData.tempoInicio != null && sceneLinkData.tempoFim != null && sceneLinkData.tempoFim > sceneLinkData.tempoInicio && !isAudioLoadingForThisScene
                    val replaceGenEnabled = generalActionsEnabled && allProjectReferenceImages.isNotEmpty()
                    val generateImageButtonEnabled = generalActionsEnabled && (sceneLinkData.promptGeracao?.isNotBlank() == true)

                    val changeClothesEnabled = generalActionsEnabled &&
                                               sceneLinkData.pathThumb.isNullOrBlank() &&
                                               !sceneLinkData.imagemGeradaPath.isNullOrBlank() &&
                                               !refImageIsVideo &&
                                               refImageContainsPeople &&
                                               allProjectReferenceImages.any { it.pathVideo == null }

                    val generateVideoEnabled = generalActionsEnabled &&
                                               !refImageIsVideo &&
                                               !refImageContainsPeople &&
                                               sceneLinkData.pathThumb.isNullOrBlank() &&
                                               !sceneLinkData.imagemGeradaPath.isNullOrBlank()


                    IconButton(
                        onClick = {
                            if (isCurrentlyPlayingThisSceneAudio) onStopAudioSnippet()
                            else onPlayAudioSnippet(sceneLinkData)
                        },
                        modifier = Modifier.size(iconButtonSize),
                        enabled = playAudioButtonEnabled
                    ) {
                        val currentPlayIconTint = if (playAudioButtonEnabled) primaryActionIconTint else disabledIconTint
                        when {
                            isAudioLoadingForThisScene -> CircularProgressIndicator(modifier = Modifier.size(iconSize), strokeWidth = 2.dp, color = primaryActionIconTint)
                            isCurrentlyPlayingThisSceneAudio -> Icon(Icons.Filled.StopCircle, contentDescription = stringResource(R.string.scene_item_action_stop_audio), modifier = Modifier.size(iconSize), tint = primaryActionIconTint)
                            else -> Icon(Icons.Filled.PlayCircleOutline, contentDescription = stringResource(R.string.scene_item_action_play_audio_snippet), modifier = Modifier.size(iconSize), tint = currentPlayIconTint)
                        }
                    }
                    IconButton(
                        onClick = {
                            if (allProjectReferenceImages.isNotEmpty()) {
                                refImageDialogTitle = context.getString(R.string.scene_item_dialog_title_replace_gen)
                                onRefImageSelectedAction = { newRefImg -> refImageToReplaceGeneratedWith = newRefImg; showGenericRefImageSelectionDialog = false; showConfirmReplaceGeneratedWithRefDialog = true }
                                showGenericRefImageSelectionDialog = true
                            } else { Toast.makeText(context, R.string.scene_item_dialog_no_ref_images_available, Toast.LENGTH_SHORT).show() }
                        },
                        enabled = replaceGenEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) { Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.scene_item_action_replace_generated_with_ref), modifier = Modifier.size(iconSize), tint = if (replaceGenEnabled) defaultEnabledIconTint else disabledIconTint) }

                    IconButton(
                        onClick = { onGenerateImageWithConfirmation(sceneLinkData.id, sceneLinkData.promptGeracao ?: "") },
                        enabled = generalActionsEnabled && generateImageButtonEnabled && sceneLinkData.pathThumb.isNullOrBlank(),
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        if (sceneLinkData.isGenerating) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                                IconButton(onClick = { projectViewModel.cancelGenerationForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                    Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                                }
                            }
                        } else Icon(Icons.Outlined.AutoFixHigh, contentDescription = stringResource(R.string.scene_item_action_generate), modifier = Modifier.size(iconSize), tint = if (generateImageButtonEnabled && sceneLinkData.pathThumb.isNullOrBlank()) primaryActionIconTint else disabledIconTint)
                    }
                    IconButton(
                        onClick = { promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: ""); showPromptEditDialog = true },
                        enabled = generalActionsEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scene_item_action_edit_prompt), modifier = Modifier.size(iconSize), tint = if (generalActionsEnabled) defaultEnabledIconTint else disabledIconTint) }

                    IconButton(
                        onClick = {
                            if (allProjectReferenceImages.any { it.pathVideo == null }) {
                                refImageDialogTitle = context.getString(R.string.scene_item_dialog_title_change_clothes)
                                onRefImageSelectedAction = { selectedRef ->
                                    if (selectedRef.pathVideo == null) {
                                        refImageForClothesChange = selectedRef
                                        showGenericRefImageSelectionDialog = false
                                        showConfirmChangeClothesWithRefDialog = true
                                    } else {
                                        Toast.makeText(context, R.string.error_clothes_change_figurine_must_be_static, Toast.LENGTH_LONG).show()
                                    }
                                }
                                showGenericRefImageSelectionDialog = true
                            } else { Toast.makeText(context, R.string.error_no_static_images_for_figurine, Toast.LENGTH_SHORT).show() }
                        },
                        enabled = changeClothesEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        if (sceneLinkData.isChangingClothes) {
                             Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                                IconButton(onClick = { projectViewModel.cancelClothesChangeForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                    Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_clothes_change), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                                }
                            }
                        } else Icon(Icons.Filled.Checkroom, contentDescription = stringResource(R.string.scene_item_desc_change_clothes_icon), modifier = Modifier.size(iconSize), tint = if (changeClothesEnabled) defaultEnabledIconTint else disabledIconTint)
                    }
                    IconButton(
                        onClick = {
                            val initialPromptForVideoDialog =
                                if (!sceneLinkData.promptVideo.isNullOrBlank()) {
                                    sceneLinkData.promptVideo
                                } else if (!sceneLinkData.promptGeracao.isNullOrBlank()) {
                                    "Vídeo animado baseado em: ${sceneLinkData.promptGeracao}"
                                } else {
                                    context.getString(R.string.scene_item_default_video_prompt, sceneLinkData.cena ?: sceneLinkData.id.take(4))
                                }
                            editableVideoPromptState = TextFieldValue(initialPromptForVideoDialog ?: "")
                            showVideoPromptDialog = true
                        },
                        enabled = generateVideoEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        if (sceneLinkData.isGeneratingVideo) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                                IconButton(onClick = {
                                    projectViewModel.cancelGenerationForScene(sceneLinkData.id)
                                }, modifier = Modifier.size(iconSize)) {
                                    Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_video_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                                }
                            }
                        } else {
                            Icon(Icons.Filled.MovieCreation, contentDescription = stringResource(R.string.scene_item_action_generate_video_desc), modifier = Modifier.size(iconSize), tint = if (generateVideoEnabled) primaryActionIconTint else disabledIconTint)
                        }
                    }
                    // <<< BOTÃO DE BUSCA AUTOMÁTICA >>>
                    IconButton(
                        onClick = { projectViewModel.findAndSetStockVideoForScene(sceneLinkData.id) },
                        enabled = generalActionsEnabled && !sceneLinkData.promptVideo.isNullOrBlank(),
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TravelExplore, // Ícone de busca/exploração
                            contentDescription = stringResource(R.string.scene_item_action_find_stock_video_desc),
                            modifier = Modifier.size(iconSize),
                            tint = if (generalActionsEnabled && !sceneLinkData.promptVideo.isNullOrBlank()) primaryActionIconTint else disabledIconTint
                        )
                    }
                }
            }
            SprocketHolesRow()
        }
    }

    if (showGenericRefImageSelectionDialog) {
        val staticFigurines = allProjectReferenceImages.filter { it.pathVideo == null }
        ReferenceImageSelectionDialog(
            onDismissRequest = { showGenericRefImageSelectionDialog = false },
            availableReferenceImages = staticFigurines,
            onReferenceImageSelected = onRefImageSelectedAction,
            dialogTitle = refImageDialogTitle
        )
    }
    if (showPromptEditDialog) { AlertDialog(onDismissRequest = { promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: ""); showPromptEditDialog = false }, title = { Text(stringResource(R.string.scene_item_dialog_edit_prompt_title)) }, text = { Column { OutlinedTextField(value = promptStateInDialog, onValueChange = { promptStateInDialog = it }, label = { Text(stringResource(R.string.scene_item_label_image_generation_prompt)) }, modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 8, textStyle = MaterialTheme.typography.bodyLarge, enabled = !sceneLinkData.isGenerating && !sceneLinkData.isChangingClothes && !sceneLinkData.isGeneratingVideo ) } }, confirmButton = { Button( onClick = { projectViewModel.updateScenePrompt(sceneLinkData.id, promptStateInDialog.text); Toast.makeText(context, context.getString(R.string.scene_item_toast_prompt_updated, sceneLinkData.cena ?: sceneLinkData.id.take(4)), Toast.LENGTH_SHORT).show(); showPromptEditDialog = false }, enabled = (promptStateInDialog.text != (sceneLinkData.promptGeracao ?: "")) && promptStateInDialog.text.isNotBlank() ) { Text(stringResource(R.string.action_save)) } }, dismissButton = { OutlinedButton(onClick = { promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: ""); showPromptEditDialog = false }) { Text(stringResource(R.string.action_cancel)) } } ) }
    if (showConfirmChangeRefImageDialog && selectedNewRefImageForChange != null) { AlertDialog(onDismissRequest = { showConfirmChangeRefImageDialog = false; selectedNewRefImageForChange = null }, title = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_title)) }, text = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_message)) }, confirmButton = { Button(onClick = { projectViewModel.updateSceneReferenceImage(sceneLinkData.id, selectedNewRefImageForChange!!); Toast.makeText(context, R.string.scene_item_toast_ref_image_changed, Toast.LENGTH_SHORT).show(); showConfirmChangeRefImageDialog = false; selectedNewRefImageForChange = null }) { Text(stringResource(R.string.action_confirm)) } }, dismissButton = { OutlinedButton(onClick = { showConfirmChangeRefImageDialog = false; selectedNewRefImageForChange = null }) { Text(stringResource(R.string.action_cancel)) } } ) }
    if (showConfirmChangeClothesWithRefDialog && refImageForClothesChange != null) { AlertDialog(onDismissRequest = { showConfirmChangeClothesWithRefDialog = false; refImageForClothesChange = null }, title = { Text(stringResource(R.string.scene_item_dialog_confirm_change_clothes_with_ref_title)) }, text = { Text(stringResource(R.string.scene_item_dialog_confirm_change_clothes_with_ref_message)) }, confirmButton = { Button(onClick = { projectViewModel.changeClothesForSceneWithSpecificReference(sceneId = sceneLinkData.id, chosenReferenceImagePath = refImageForClothesChange!!.path ); val sceneIdentifier = sceneLinkData.cena ?: sceneLinkData.id.take(4); Toast.makeText(context, context.getString(R.string.scene_item_toast_clothes_change_queued_with_ref, sceneIdentifier), Toast.LENGTH_SHORT).show(); showConfirmChangeClothesWithRefDialog = false; refImageForClothesChange = null }) { Text(stringResource(R.string.action_confirm)) } }, dismissButton = { OutlinedButton(onClick = { showConfirmChangeClothesWithRefDialog = false; refImageForClothesChange = null }) { Text(stringResource(R.string.action_cancel)) } } ) }
    if (showConfirmReplaceGeneratedWithRefDialog && refImageToReplaceGeneratedWith != null) { AlertDialog(onDismissRequest = { showConfirmReplaceGeneratedWithRefDialog = false; refImageToReplaceGeneratedWith = null }, title = { Text(stringResource(R.string.scene_item_dialog_confirm_replace_generated_title)) }, text = { Text(stringResource(R.string.scene_item_dialog_confirm_replace_generated_message)) }, confirmButton = { Button(onClick = { projectViewModel.replaceGeneratedImageWithReference(sceneLinkData.id, refImageToReplaceGeneratedWith!!); Toast.makeText(context, R.string.scene_item_toast_generated_image_replaced, Toast.LENGTH_SHORT).show(); showConfirmReplaceGeneratedWithRefDialog = false; refImageToReplaceGeneratedWith = null }) { Text(stringResource(R.string.action_confirm)) } }, dismissButton = { OutlinedButton(onClick = { showConfirmReplaceGeneratedWithRefDialog = false; refImageToReplaceGeneratedWith = null }) { Text(stringResource(R.string.action_cancel)) } } ) }

    if (showVideoPromptDialog) {
        AlertDialog(
            onDismissRequest = { showVideoPromptDialog = false },
            title = { Text(stringResource(R.string.scene_item_dialog_generate_video_title, sceneLinkData.cena ?: sceneLinkData.id.take(4))) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.scene_item_dialog_generate_video_warning),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = editableVideoPromptState,
                        onValueChange = { editableVideoPromptState = it },
                        label = { Text(stringResource(R.string.scene_item_label_video_generation_prompt)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp),
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalVideoPrompt = editableVideoPromptState.text
                        if (finalVideoPrompt.isNotBlank()) {
                            val sourceImageForVideo = sceneLinkData.imagemGeradaPath
                            if (!sourceImageForVideo.isNullOrBlank()) {
                                projectViewModel.generateVideoForScene(
                                    sceneId = sceneLinkData.id,
                                    videoPromptFromDialog = finalVideoPrompt,
                                    sourceImagePathFromSceneParameter = sourceImageForVideo
                                )
                                showVideoPromptDialog = false
                            } else {
                                Toast.makeText(context, R.string.error_no_base_image_for_video, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, R.string.error_empty_prompt_for_video_gen, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = editableVideoPromptState.text.isNotBlank()
                ) {
                    Text(stringResource(R.string.scene_item_dialog_action_generate_video))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showVideoPromptDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
                    Log.e(TAG_CONTENT, "VideoPlayer: Erro ao obter URI para: $videoPath", e)
                    null
                }
            } else {
                Log.w(TAG_CONTENT, "VideoPlayer: Arquivo de vídeo não encontrado: $videoPath")
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
                        Log.d(TAG_CONTENT, "VideoPlayer (Scene): Vídeo preparado. URI: $videoUri")
                        if (isPlaying) {
                            mediaPlayer.start()
                            onPlaybackStateChange(true)
                        } else {
                             onPlaybackStateChange(false)
                        }
                        mediaPlayer.isLooping = false
                    }
                    setOnCompletionListener {
                        Log.d(TAG_CONTENT, "VideoPlayer (Scene): Reprodução completa. URI: $videoUri")
                        onPlaybackStateChange(false)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG_CONTENT, "VideoPlayer (Scene): Erro. What: $what, Extra: $extra. URI: $videoUri")
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
    val allProjectReferenceImages by projectViewModel.currentImagensReferenciaStateFlow.collectAsState()
    val currentlyPlayingSceneId by projectViewModel.currentlyPlayingSceneId.collectAsState()
    val isAudioLoadingForScene by projectViewModel.isAudioLoadingForScene.collectAsState()
    val sceneIdToRecreateImage by projectViewModel.sceneIdToRecreateImage.collectAsState()
    val promptForRecreateImage by projectViewModel.promptForRecreateImage.collectAsState()
    val currentSceneIdForDialogRefChange by projectViewModel.sceneIdForReferenceChangeDialog.collectAsState()
    
    val showImageBatchCostDialog by projectViewModel.showImageBatchCostConfirmationDialog.collectAsState()
    val imageBatchCost by projectViewModel.pendingImageBatchCost.collectAsState()
    
    val globalSceneError by projectViewModel.globalSceneError.collectAsState()
    
    val sceneIdForPixabaySearch by projectViewModel.showPixabaySearchDialogForSceneId.collectAsState()
    val searchQuery by projectViewModel.pixabaySearchQuery.collectAsState()
    val searchResults by projectViewModel.pixabaySearchResults.collectAsState()
    val isSearching by projectViewModel.isSearchingPixabay.collectAsState()

    sceneIdForPixabaySearch?.let { sceneId ->
        PixabaySearchDialog(
            query = searchQuery,
            onQueryChange = { projectViewModel.onPixabaySearchQueryChanged(it) },
            onSearchClick = { projectViewModel.searchPixabayVideos() },
            onDismiss = { projectViewModel.onDismissPixabaySearchDialog() },
            onVideoSelected = { video -> projectViewModel.onPixabayVideoSelected(sceneId, video) },
            searchResults = searchResults,
            isSearching = isSearching
        )
    }


    if (sceneIdToRecreateImage != null && promptForRecreateImage != null) {
         AlertDialog(
            onDismissRequest = { projectViewModel.dismissRecreateImageDialog() },
            title = { Text(stringResource(R.string.scene_item_dialog_recreate_image_title)) },
            text = { Text(stringResource(R.string.scene_item_dialog_recreate_image_message)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithImageRecreation() }) { Text(stringResource(R.string.action_recreate)) } },
            dismissButton = { OutlinedButton(onClick = { projectViewModel.dismissRecreateImageDialog() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    currentSceneIdForDialogRefChange?.let { sceneId ->
        var tempSelectedRefImageForChange by remember { mutableStateOf<ImagemReferencia?>(null) }
        var showConfirmForThisSpecificChange by remember { mutableStateOf(false) }

        if (!showConfirmForThisSpecificChange && projectViewModel.sceneIdForReferenceChangeDialog.collectAsState().value == sceneId) {
            ReferenceImageSelectionDialog(
                onDismissRequest = { projectViewModel.dismissReferenceImageSelectionDialog() },
                availableReferenceImages = allProjectReferenceImages,
                onReferenceImageSelected = { newRefImg -> tempSelectedRefImageForChange = newRefImg; showConfirmForThisSpecificChange = true },
                dialogTitle = stringResource(R.string.scene_item_dialog_title_change_ref_for_scene, sceneId.take(4))
            )
        }

        if (showConfirmForThisSpecificChange && tempSelectedRefImageForChange != null) {
            AlertDialog(
                onDismissRequest = { showConfirmForThisSpecificChange = false; tempSelectedRefImageForChange = null; projectViewModel.dismissReferenceImageSelectionDialog() },
                title = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_title)) },
                text = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_message)) },
                confirmButton = { Button(onClick = { projectViewModel.updateSceneReferenceImage(sceneId, tempSelectedRefImageForChange!!); Toast.makeText(context, R.string.scene_item_toast_ref_image_changed, Toast.LENGTH_SHORT).show(); showConfirmForThisSpecificChange = false; tempSelectedRefImageForChange = null; projectViewModel.dismissReferenceImageSelectionDialog() }) { Text(stringResource(R.string.action_confirm)) } },
                dismissButton = { OutlinedButton(onClick = { showConfirmForThisSpecificChange = false; tempSelectedRefImageForChange = null; projectViewModel.dismissReferenceImageSelectionDialog() }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }

    if (showConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelSceneGenerationDialog() },
            title = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_title)) },
            text = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_message)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithSceneGeneration() }, enabled = !isProcessingGlobalScenes ) { Text(stringResource(R.string.video_project_dialog_action_generate_new)) } },
            dismissButton = { OutlinedButton(onClick = { projectViewModel.cancelSceneGenerationDialog() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showImageBatchCostDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelImageBatchGeneration() },
            title = { Text(text = stringResource(R.string.dialog_title_confirm_generation)) },
            text = { Text(text = stringResource(R.string.dialog_message_image_batch_cost, imageBatchCost)) },
            confirmButton = {
                Button(onClick = { projectViewModel.confirmImageBatchGeneration() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { projectViewModel.cancelImageBatchGeneration() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally // Centraliza o conteúdo da Column
    ) {
        if (!isUiReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(modifier = Modifier.height(8.dp)); Text(stringResource(R.string.video_project_status_loading_data)) }
            }
        } else {
            if (isProcessingGlobalScenes && sceneLinkDataList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(modifier = Modifier.height(8.dp)); Text(stringResource(R.string.video_project_status_generating_scene_structure)) }
                }
            }
            else if (sceneLinkDataList.isNotEmpty()) {
                LazyRow(modifier = Modifier.fillMaxSize()) {
                    items(sceneLinkDataList, key = { it.id }) { sceneLinkData ->
                        SceneLinkItem(
                            sceneLinkData = sceneLinkData,
                            projectViewModel = projectViewModel,
                            allProjectReferenceImages = allProjectReferenceImages,
                            currentlyPlayingSceneId = currentlyPlayingSceneId,
                            isAudioLoadingForScene = isAudioLoadingForScene,
                            onPlayAudioSnippet = { scene -> projectViewModel.playAudioSnippetForScene(scene) },
                            onStopAudioSnippet = { projectViewModel.stopAudioSnippet() },
                            onGenerateImageWithConfirmation = { sceneIdVal, promptVal -> projectViewModel.requestImageGenerationWithConfirmation(sceneIdVal, promptVal) }
                        )
                    }
                }
            } else if (globalSceneError != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
                            .padding(16.dp)
                            .clickable { projectViewModel.clearGlobalSceneError() }, // Ação de clique para limpar
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = globalSceneError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = stringResource(R.string.status_tap_to_clear_error),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.MovieFilter, contentDescription = stringResource(R.string.video_project_icon_desc_no_scenes), modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = stringResource(R.string.video_project_placeholder_no_scenes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        Text(text = stringResource(R.string.video_project_placeholder_no_scenes_instructions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}