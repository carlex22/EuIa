// File: euia/ui/VideoProjectContent.kt
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
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
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.ProjectAsset
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.PixabayAsset
import com.carlex.euia.viewmodel.PixabayAssetType
import com.carlex.euia.viewmodel.VideoProjectViewModel
import java.io.File
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


private const val TAG_CONTENT = "VideoProjectContent"

@Composable
private fun MediaExplorerDialog(
    sceneId: String,
    projectViewModel: VideoProjectViewModel
) {
    val query by projectViewModel.pixabaySearchQuery.collectAsState()
    val searchResults by projectViewModel.pixabayUnifiedResults.collectAsState()
    val isSearching by projectViewModel.isSearchingPixabay.collectAsState()

    var selectedAssetForPreview by remember { mutableStateOf<PixabayAsset?>(null) }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = { projectViewModel.onDismissPixabaySearchDialog() },
        title = { Text(stringResource(R.string.media_explorer_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 600.dp)) {
                if (selectedAssetForPreview == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { projectViewModel.onPixabaySearchQueryChanged(it) },
                        label = { Text(stringResource(R.string.pixabay_search_field_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            projectViewModel.searchPixabayAssets()
                        })
                    )
                    Spacer(Modifier.height(16.dp))
                }

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedAssetForPreview != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (selectedAssetForPreview!!.type == PixabayAssetType.VIDEO) {
                                var isPlaying by remember { mutableStateOf(true) }
                                VideoPlayerInternal(
                                    videoPath = selectedAssetForPreview!!.downloadUrl,
                                    isPlaying = isPlaying,
                                    onPlaybackStateChange = { isPlaying = it },
                                    invalidPathErrorText = "Erro ao carregar prévia"
                                )
                            } else {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(selectedAssetForPreview!!.downloadUrl)
                                        .crossfade(true).build(),
                                    contentDescription = stringResource(R.string.media_explorer_preview_title),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            IconButton(
                                onClick = { selectedAssetForPreview = null },
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.media_explorer_back_to_grid_desc))
                            }
                            Button(
                                onClick = {
                                    projectViewModel.onPixabayAssetSelected(sceneId, selectedAssetForPreview!!)
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                            ) {
                                Text(stringResource(R.string.media_explorer_use_this))
                            }
                        }
                    } else {
                        if (isSearching) {
                            CircularProgressIndicator()
                        } else if (searchResults.isEmpty()) {
                            Text(stringResource(R.string.pixabay_search_no_results), textAlign = TextAlign.Center)
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(searchResults, key = { it.id }) { asset ->
                                    Card(
                                        modifier = Modifier
                                            .aspectRatio(9f / 16f)
                                            .clickable { selectedAssetForPreview = asset },
                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            AsyncImage(
                                                model = asset.thumbnailUrl,
                                                contentDescription = asset.tags,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (asset.type == PixabayAssetType.VIDEO) {
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
                    }
                }
            }
        },
        confirmButton = {
            if (selectedAssetForPreview == null) {
                Button(onClick = {
                    focusManager.clearFocus()
                    projectViewModel.searchPixabayAssets()
                }, enabled = !isSearching) {
                    Text(stringResource(id = R.string.pixabay_search_action_desc))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { projectViewModel.onDismissPixabaySearchDialog() }) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun AssetSelectionDialog(
    onDismissRequest: () -> Unit,
    availableAssets: List<ProjectAsset>,
    onAssetSelected: (ProjectAsset) -> Unit,
    dialogTitle: String
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(dialogTitle) },
        text = {
            if (availableAssets.isEmpty()) {
                Text(stringResource(R.string.scene_item_dialog_no_assets_available))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(availableAssets, key = { it.finalAssetPath }) { asset ->
                        Card(
                            modifier = Modifier
                                .aspectRatio(9f / 16f)
                                .clickable { onAssetSelected(asset) },
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                var img = asset.finalAssetPath
                                if (asset.isVideo)
                                    img = asset.thumbnailPath
                                
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(File(img))
                                        .crossfade(true)
                                        .placeholder(R.drawable.ic_placeholder_image)
                                        .error(R.drawable.ic_broken_image)
                                        .build(),
                                    contentDescription = asset.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (asset.isVideo) {
                                    Icon(
                                        Icons.Filled.PlayCircleFilled,
                                        contentDescription = stringResource(R.string.content_desc_video_indicator),
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                    )
                                }
                            }
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
    isGeneratingPreviewForScene: String?,
    availableProjectAssets: List<ProjectAsset>,
    onGenerateImageWithConfirmation: (sceneId: String, prompt: String) -> Unit
) {
    val context = LocalContext.current
    var promptStateInDialog by remember(sceneLinkData.promptGeracao) { mutableStateOf(TextFieldValue(sceneLinkData.promptGeracao ?: "")) }
    var showPromptEditDialog by remember { mutableStateOf(false) }
    var showAssetSelectionDialog by remember { mutableStateOf(false) }
    var showRefImageSelectionDialog by remember { mutableStateOf(false) }


    var selectedNewRefImageForChange by remember { mutableStateOf<ImagemReferencia?>(null) }
    var showConfirmChangeRefImageDialog by remember { mutableStateOf(false) }
    var refImageForClothesChange by remember { mutableStateOf<ImagemReferencia?>(null) }
    var showConfirmChangeClothesWithRefDialog by remember { mutableStateOf(false) }

    var showVideoPromptDialog by remember { mutableStateOf(false) }
    var editableVideoPromptState by remember { mutableStateOf(TextFieldValue("")) }


    val filmFrameColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    val photoAreaBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val filmFrameShape = RoundedCornerShape(0.dp)
    val photoAreaShape = RoundedCornerShape(6.dp)

    val isCurrentlyPlayingThisScene = currentlyPlayingSceneId == sceneLinkData.id
    val isGeneratingPreviewForThisScene = isGeneratingPreviewForScene == sceneLinkData.id

    val defaultEnabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val primaryActionIconTint = MaterialTheme.colorScheme.primary
    val disabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    val originalReferenceImageDetails = allProjectReferenceImages.find { it.path == sceneLinkData.imagemReferenciaPath }
    val refImageIsVideo = originalReferenceImageDetails?.pathVideo != null
    val refImageContainsPeople = originalReferenceImageDetails?.containsPeople ?: false

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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 8.dp),
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
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val isGeneratingAsset = sceneLinkData.isGenerating || sceneLinkData.isChangingClothes || sceneLinkData.isGeneratingVideo

                        if (isCurrentlyPlayingThisScene && !sceneLinkData.videoPreviewPath.isNullOrBlank()) {
                            VideoPlayerInternal(
                                videoPath = sceneLinkData.videoPreviewPath!!,
                                isPlaying = true,
                                onPlaybackStateChange = { isPlaying ->
                                    if (!isPlaying) {
                                        projectViewModel.stopPlayback()
                                    }
                                },
                                invalidPathErrorText = stringResource(id = R.string.error_loading_preview)
                            )
                        } else if (!sceneLinkData.pathThumb.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(sceneLinkData.pathThumb!!)).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                                contentDescription = stringResource(R.string.content_desc_generated_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (!sceneLinkData.generationErrorMessage.isNullOrBlank() && !isGeneratingAsset) {
                                     Column(
                                         modifier = Modifier
                                             .fillMaxWidth()
                                             .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                             .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
                                             .padding(8.dp)
                                             .clickable { projectViewModel.clearGlobalSceneError() }, // <<< MUDANÇA: Chamada corrigida
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


                        if (!sceneLinkData.imagemGeradaPath.isNullOrBlank() && !sceneLinkData.generationErrorMessage.isNullOrBlank() && !isGeneratingAsset) {
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
                                     .clickable { projectViewModel.clearGlobalSceneError() } // <<< MUDANÇA: Chamada corrigida
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
                                        projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_dialog_no_ref_images_available))
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
                            Text(text = stringResource(R.string.scene_item_label_ref_image_caption), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(vertical = 1.dp))
                        }
                        sceneLinkData.similaridade?.let {
                             Text(stringResource(R.string.scene_item_label_similarity, it), style = MaterialTheme.typography.labelSmall, modifier = Modifier
                                 .align(Alignment.BottomStart)
                                 .padding(4.dp)
                                 .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                                 .padding(horizontal = 2.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val iconButtonSize = 40.dp
                    val iconSize = 22.dp
                    val generalActionsEnabled = !sceneLinkData.isGenerating && !sceneLinkData.isChangingClothes && !sceneLinkData.isGeneratingVideo
                    
                    val isPreviewReady = !sceneLinkData.videoPreviewPath.isNullOrBlank()
                    val isProcessing = sceneLinkData.previewQueuePosition == 0
                    val que = "${sceneLinkData.previewQueuePosition}".toIntOrNull() ?: 99
                    val inQuee = if (que > 0) true else false 
                    
                    val isPlayButtonClickable = (isPreviewReady) 

                    IconButton(
                        onClick = { projectViewModel.onPlayPausePreviewClicked(sceneLinkData) },
                        modifier = Modifier.size(iconButtonSize),
                        enabled = isPlayButtonClickable
                    ) {
                        when {
                            isProcessing -> {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(iconSize), strokeWidth = 2.dp, color = primaryActionIconTint)
                                    Text(
                                        text = sceneLinkData.previewQueuePosition.toString()!!,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            inQuee -> {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(iconSize), strokeWidth = 2.dp, color = primaryActionIconTint)
                                    Text(
                                        text = sceneLinkData.previewQueuePosition.toString()!!,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            isCurrentlyPlayingThisScene -> {
                                Icon(
                                    imageVector = Icons.Default.StopCircle,
                                    contentDescription = stringResource(R.string.scene_item_action_stop_audio),
                                    modifier = Modifier.size(iconSize),
                                    tint = primaryActionIconTint
                                )
                            }
                            isPreviewReady -> {
                                Icon(
                                    imageVector = Icons.Default.PlayCircleOutline,
                                    contentDescription = stringResource(R.string.scene_item_action_play_audio_snippet),
                                    modifier = Modifier.size(iconSize),
                                    tint = primaryActionIconTint
                                )
                            }
                            else -> {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "⏳",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    val replaceGenEnabled = generalActionsEnabled && availableProjectAssets.isNotEmpty()
                    val generateImageButtonEnabled = generalActionsEnabled && (sceneLinkData.promptGeracao?.isNotBlank() == true)

                    val changeClothesEnabled = generalActionsEnabled &&
                                               
                                               !sceneLinkData.imagemGeradaPath.isNullOrBlank() &&
                                               !refImageIsVideo &&
                                               refImageContainsPeople &&
                                               allProjectReferenceImages.any { it.pathVideo == null }

                    val generateVideoEnabled = generalActionsEnabled &&
                                               !refImageIsVideo &&
                                               !refImageContainsPeople &&
                                               
                                               !sceneLinkData.imagemGeradaPath.isNullOrBlank()

                    IconButton(
                        onClick = {
                            projectViewModel.loadProjectAssets()
                            showAssetSelectionDialog = true
                        },
                        enabled = replaceGenEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) { Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.scene_item_action_replace_generated_with_ref), modifier = Modifier.size(iconSize), tint = if (replaceGenEnabled) defaultEnabledIconTint else disabledIconTint) }

                    IconButton(
                        onClick = { onGenerateImageWithConfirmation(sceneLinkData.id, sceneLinkData.promptGeracao ?: "") },
                        enabled = generalActionsEnabled && generateImageButtonEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        if (sceneLinkData.isGenerating) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                                IconButton(onClick = { projectViewModel.cancelGenerationForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                    Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                                }
                            }
                        } else Icon(Icons.Outlined.AutoFixHigh, contentDescription = stringResource(R.string.scene_item_action_generate), modifier = Modifier.size(iconSize), tint = if (generateImageButtonEnabled) primaryActionIconTint else disabledIconTint)
                    }
                    IconButton(
                        onClick = { promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: ""); showPromptEditDialog = true },
                        enabled = generalActionsEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scene_item_action_edit_prompt), modifier = Modifier.size(iconSize), tint = if (generalActionsEnabled) defaultEnabledIconTint else disabledIconTint) }

                    IconButton(
                        onClick = {
                            if (allProjectReferenceImages.any { it.pathVideo == null }) {
                                showAssetSelectionDialog = true
                            } else { projectViewModel.postSnackbarMessage(context.getString(R.string.error_no_static_images_for_figurine)) }
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
                    IconButton(
                        onClick = { projectViewModel.onShowPixabaySearchDialog(sceneLinkData.id) },
                        enabled = generalActionsEnabled && !sceneLinkData.promptVideo.isNullOrBlank(),
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TravelExplore,
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
    
    // <<< INÍCIO DA CORREÇÃO: Bloco de Diálogos foi corrigido e reestruturado >>>
    if (showAssetSelectionDialog) {
        AssetSelectionDialog(
            onDismissRequest = { showAssetSelectionDialog = false },
            availableAssets = availableProjectAssets,
            onAssetSelected = { selectedAsset ->
                projectViewModel.replaceGeneratedImageWithReference(sceneLinkData.id, selectedAsset)
                showAssetSelectionDialog = false
            },
            dialogTitle = stringResource(R.string.scene_item_dialog_title_select_asset)
        )
    }
    
    if (showPromptEditDialog) {
        AlertDialog(
            onDismissRequest = {
                promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: "")
                showPromptEditDialog = false
            },
            title = { Text(stringResource(R.string.scene_item_dialog_edit_prompt_title)) },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .clickable {
                                if (allProjectReferenceImages.isNotEmpty()) {
                                    showRefImageSelectionDialog = true
                                } else {
                                    projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_dialog_no_ref_images_available))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (sceneLinkData.imagemReferenciaPath.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(sceneLinkData.imagemReferenciaPath)).crossfade(true).build(),
                                contentDescription = stringResource(R.string.content_desc_reference_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.scene_item_action_select_ref_image_desc))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = promptStateInDialog,
                        onValueChange = { promptStateInDialog = it },
                        label = { Text(stringResource(R.string.scene_item_label_image_generation_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        enabled = !sceneLinkData.isGenerating && !sceneLinkData.isChangingClothes && !sceneLinkData.isGeneratingVideo
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        projectViewModel.updateScenePrompt(sceneLinkData.id, promptStateInDialog.text)
                        onGenerateImageWithConfirmation(sceneLinkData.id, promptStateInDialog.text)
                        showPromptEditDialog = false
                    },
                    enabled = (promptStateInDialog.text != (sceneLinkData.promptGeracao ?: "")) && promptStateInDialog.text.isNotBlank()
                ) { Text(stringResource(R.string.action_save_and_generate)) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    promptStateInDialog = TextFieldValue(sceneLinkData.promptGeracao ?: "")
                    showPromptEditDialog = false
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showRefImageSelectionDialog) {
        AssetSelectionDialog(
            onDismissRequest = { showRefImageSelectionDialog = false },
            availableAssets = allProjectReferenceImages.map {
                ProjectAsset(
                    displayName = File(it.path).name,
                    finalAssetPath = it.pathVideo ?: it.path,
                    thumbnailPath = it.path,
                    isVideo = it.pathVideo != null
                )
            },
            onAssetSelected = { selectedAsset ->
                val selectedRefImage = allProjectReferenceImages.find { ref -> ref.path == selectedAsset.thumbnailPath }
                if (selectedRefImage != null) {
                    projectViewModel.updateSceneReferenceImage(sceneLinkData.id, selectedRefImage)
                }
                showRefImageSelectionDialog = false
            },
            dialogTitle = stringResource(R.string.scene_item_dialog_title_select_ref_image)
        )
    }
    
    if (showConfirmChangeRefImageDialog && selectedNewRefImageForChange != null) {
        AlertDialog(
            onDismissRequest = { showConfirmChangeRefImageDialog = false; selectedNewRefImageForChange = null },
            title = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_title)) },
            text = { Text(stringResource(R.string.scene_item_dialog_confirm_change_ref_image_message)) },
            confirmButton = {
                Button(onClick = {
                    projectViewModel.updateSceneReferenceImage(sceneLinkData.id, selectedNewRefImageForChange!!)
                    projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_toast_ref_image_changed))
                    showConfirmChangeRefImageDialog = false
                    selectedNewRefImageForChange = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmChangeRefImageDialog = false; selectedNewRefImageForChange = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showConfirmChangeClothesWithRefDialog && refImageForClothesChange != null) {
        AlertDialog(
            onDismissRequest = { showConfirmChangeClothesWithRefDialog = false; refImageForClothesChange = null },
            title = { Text(stringResource(R.string.scene_item_dialog_confirm_change_clothes_with_ref_title)) },
            text = { Text(stringResource(R.string.scene_item_dialog_confirm_change_clothes_with_ref_message)) },
            confirmButton = {
                Button(onClick = {
                    projectViewModel.changeClothesForSceneWithSpecificReference(sceneId = sceneLinkData.id, chosenReferenceImagePath = refImageForClothesChange!!.path)
                    val sceneIdentifier = sceneLinkData.cena ?: sceneLinkData.id.take(4)
                    projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_toast_clothes_change_queued_with_ref, sceneIdentifier))
                    showConfirmChangeClothesWithRefDialog = false
                    refImageForClothesChange = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmChangeClothesWithRefDialog = false; refImageForClothesChange = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
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
                                projectViewModel.postSnackbarMessage(context.getString(R.string.error_no_base_image_for_video))
                            }
                        } else {
                            projectViewModel.postSnackbarMessage(context.getString(R.string.error_empty_prompt_for_video_gen))
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
    // <<< FIM DA CORREÇÃO >>>
}

@Composable
private fun VideoPlayerInternal(
    videoPath: String,
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String
) {
    val context = LocalContext.current
    // 1. O estado da Uri agora é inicializado como nulo.
    var videoUri by remember { mutableStateOf<Uri?>(null) }

    // 2. LaunchedEffect executa a operação de I/O em uma corrotina de background
    //    sempre que o videoPath mudar.
    LaunchedEffect(videoPath) {
        videoUri = withContext(Dispatchers.IO) {
            try {
                // Lógica para obter a Uri, agora segura para a thread principal.
                if (videoPath.startsWith("http")) {
                    Uri.parse(videoPath)
                } else if (videoPath.isNotBlank()) {
                    val file = File(videoPath)
                    if (file.exists()) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else {
                        Log.w(TAG_CONTENT, "VideoPlayer: Arquivo de vídeo não encontrado: $videoPath")
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG_CONTENT, "VideoPlayer: Erro ao obter URI para: $videoPath", e)
                null
            }
        }
    }

    // 3. O restante do código lida com o ciclo de vida e a exibição da VideoView.
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }
    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)

    DisposableEffect(videoUri) {
        onDispose {
            // Garante que o player pare e libere recursos ao sair do Composable
            videoViewInstance?.apply {
                stopPlayback()
            }
            videoViewInstance = null
        }
    }

    // 4. A VideoView só é criada quando a `videoUri` está pronta (não é nula).
    val currentVideoUri = videoUri
    if (currentVideoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    setVideoURI(currentVideoUri)
                    setOnPreparedListener { mediaPlayer ->
                        Log.d(TAG_CONTENT, "VideoPlayer (Scene): Vídeo preparado. URI: $currentVideoUri")
                        if (isPlaying) {
                            mediaPlayer.start()
                            currentOnPlaybackStateChange(true)
                        } else {
                            currentOnPlaybackStateChange(false)
                        }
                        mediaPlayer.isLooping = true // Loop para prévias
                    }
                    setOnCompletionListener {
                        Log.d(TAG_CONTENT, "VideoPlayer (Scene): Reprodução completa. URI: $currentVideoUri")
                        currentOnPlaybackStateChange(false)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG_CONTENT, "VideoPlayer (Scene): Erro. What: $what, Extra: $extra. URI: $currentVideoUri")
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
        // Exibe um placeholder enquanto a URI está sendo carregada ou se houve um erro.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (videoPath.isNotBlank()) { // Mostra o indicador de progresso se um caminho válido foi fornecido
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            } else {
                Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
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
    val isProcessingGlobalScenes by projectViewModel.isGeneratingScene.collectAsState()
    val allProjectReferenceImages by projectViewModel.currentImagensReferenciaStateFlow.collectAsState()
    val currentlyPlayingSceneId by projectViewModel.currentlyPlayingSceneId.collectAsState()
    val isGeneratingPreviewForScene by projectViewModel.isGeneratingPreviewForSceneId.collectAsState()
    val sceneIdToRecreateImage by projectViewModel.sceneIdToRecreateImage.collectAsState()
    val promptForRecreateImage by projectViewModel.promptForRecreateImage.collectAsState()

    val showImageBatchCostDialog by projectViewModel.showImageBatchCostConfirmationDialog.collectAsState()
    val imageBatchCost by projectViewModel.pendingImageBatchCost.collectAsState()
    val imageBatchCount by projectViewModel.pendingImageBatchCount.collectAsState()

    val globalSceneError by projectViewModel.globalSceneError.collectAsState()
    
    val sceneIdForMediaExplorer by projectViewModel.showPixabaySearchDialogForSceneId.collectAsState()
    
    val availableProjectAssets by projectViewModel.availableProjectAssets.collectAsState()

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

    sceneIdForMediaExplorer?.let { sceneId ->
        MediaExplorerDialog(
            sceneId = sceneId,
            projectViewModel = projectViewModel
        )
    }

    if (showImageBatchCostDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelImageBatchGeneration() },
            title = { Text(text = stringResource(R.string.dialog_title_confirm_generation)) },
            text = { Text(text = stringResource(R.string.dialog_message_image_batch_cost, imageBatchCount, imageBatchCost)) },
            confirmButton = {
                Button(onClick = { projectViewModel.confirmImageBatchGeneration() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { projectViewModel.triggerBatchPixabayVideoSearch() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(stringResource(R.string.dialog_action_search_free_videos))
                    }
                    TextButton(onClick = { projectViewModel.cancelImageBatchGeneration() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
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

    if (showConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { projectViewModel.cancelSceneGenerationDialog() },
            title = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_title)) },
            text = { Text(text = stringResource(R.string.video_project_dialog_confirm_new_generation_message)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithSceneGeneration() }, enabled = !isProcessingGlobalScenes ) { Text(stringResource(R.string.video_project_dialog_action_generate_new)) } },
            dismissButton = { OutlinedButton(onClick = { projectViewModel.cancelSceneGenerationDialog() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (!isUiReady) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(modifier = Modifier.height(8.dp)); Text(stringResource(R.string.video_project_status_loading_data)) }
                }
            } else {
                if (isProcessingGlobalScenes && sceneLinkDataList.isEmpty()) {
                    Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(16.dp)
                        ) {
                        Box(modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(modifier = Modifier.height(8.dp)); Text(stringResource(R.string.video_project_status_generating_scene_structure)) }
                        }
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
                                isGeneratingPreviewForScene = isGeneratingPreviewForScene,
                                availableProjectAssets = availableProjectAssets,
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
                                .clickable { projectViewModel.clearGlobalSceneError() },
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
                
                    Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(16.dp)
                        ) {
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ){
                                    Icon(Icons.Filled.MovieFilter, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    Text(stringResource(R.string.video_project_placeholder_no_scenes_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                    Text(stringResource(R.string.video_project_placeholder_no_scenes_instructions), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.context_info_project_import_text_click), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            
                        }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    
    
    
    
    
}
