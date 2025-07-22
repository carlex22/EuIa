// File: euia/ui/project/SceneCard.kt
package com.carlex.euia.ui.project

import android.net.Uri
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carlex.euia.R
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.AssetSelectionPurpose
import com.carlex.euia.viewmodel.VideoProjectViewModel
import java.io.File

private const val TAG_SCENE_CARD = "SceneCard"

@Composable
fun SceneCard(
    sceneLinkData: SceneLinkData,
    projectViewModel: VideoProjectViewModel,
    allProjectReferenceImages: List<ImagemReferencia>,
    currentlyPlayingSceneId: String?,
    isGeneratingPreviewForScene: String?
) {
    val context = LocalContext.current
    val filmFrameColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    val photoAreaBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val filmFrameShape = RoundedCornerShape(0.dp)
    val photoAreaShape = RoundedCornerShape(6.dp)

    val isCurrentlyPlayingThisScene = currentlyPlayingSceneId == sceneLinkData.id
    val isGeneratingPreviewForThisScene = isGeneratingPreviewForScene == sceneLinkData.id

    val primaryActionIconTint = MaterialTheme.colorScheme.primary
    val defaultEnabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val disabledIconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    DisposableEffect(sceneLinkData.id) {
        onDispose {
            if (isCurrentlyPlayingThisScene) {
                projectViewModel.stopPlayback()
            }
        }
    }

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
                        if (isCurrentlyPlayingThisScene && !sceneLinkData.videoPreviewPath.isNullOrBlank()) {
                            ProjectVideoPlayer(
                                videoPath = sceneLinkData.videoPreviewPath!!,
                                isPlaying = true,
                                onPlaybackStateChange = { isPlaying ->
                                    if (!isPlaying) {
                                        projectViewModel.stopPlayback()
                                    }
                                },
                                invalidPathErrorText = stringResource(id = R.string.error_loading_preview),
                                loopVideo = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            val displayPath = sceneLinkData.pathThumb ?: sceneLinkData.imagemGeradaPath
                            if (!displayPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(File(displayPath)).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                                    contentDescription = stringResource(R.string.content_desc_generated_image),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                EmptyScenesPlaceholder()
                            }
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
                    val generalActionsEnabled = !isSceneBusy(sceneLinkData)

                    // Botão Play/Stop
                    IconButton(
                        onClick = { projectViewModel.onPlayPausePreviewClicked(sceneLinkData) },
                        modifier = Modifier.size(iconButtonSize),
                        enabled = !sceneLinkData.videoPreviewPath.isNullOrBlank() || !isGeneratingPreviewForThisScene
                    ) {
                        when {
                            isGeneratingPreviewForThisScene -> {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 2.dp, color = primaryActionIconTint)
                                    Text(
                                        text = sceneLinkData.previewQueuePosition.toString(),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            isCurrentlyPlayingThisScene -> Icon(Icons.Default.StopCircle, contentDescription = stringResource(R.string.scene_item_action_stop_audio), modifier = Modifier.size(iconSize), tint = primaryActionIconTint)
                            !sceneLinkData.videoPreviewPath.isNullOrBlank() -> Icon(Icons.Default.PlayCircleOutline, contentDescription = stringResource(R.string.scene_item_action_play_audio_snippet), modifier = Modifier.size(iconSize), tint = primaryActionIconTint)
                            else -> Icon(Icons.Default.Timelapse, contentDescription = "Aguardando prévia", modifier = Modifier.size(iconSize), tint = disabledIconTint)
                        }
                    }

                    // Botão Abrir Asset Local
                    IconButton(
                        onClick = {
                            if (projectViewModel.availableProjectAssets.value.isNotEmpty()) {
                                projectViewModel.triggerAssetSelectionForScene(
                                    sceneId = sceneLinkData.id,
                                    purpose = AssetSelectionPurpose.REPLACE_GENERATED_ASSET
                                )
                            } else {
                                projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_dialog_no_assets_available))
                            }
                        },
                        enabled = generalActionsEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = stringResource(R.string.scene_item_action_replace_generated_with_ref),
                            modifier = Modifier.size(iconSize),
                            tint = if (generalActionsEnabled && projectViewModel.availableProjectAssets.value.isNotEmpty()) defaultEnabledIconTint else disabledIconTint
                        )
                    }

                    // Botão Gerar Imagem / Editar Prompt
                    if (sceneLinkData.isGenerating) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(iconButtonSize)) {
                            CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                            IconButton(onClick = { projectViewModel.cancelGenerationForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                            }
                        }
                    } else {
                        IconButton(
                            onClick = {
                                projectViewModel.triggerPromptEditForScene(sceneLinkData)
                            },
                            enabled = generalActionsEnabled,
                            modifier = Modifier.size(iconButtonSize)
                        ) {
                            Icon(
                                Icons.Outlined.AutoFixHigh,
                                contentDescription = stringResource(R.string.scene_item_action_edit_prompt),
                                modifier = Modifier.size(iconSize),
                                tint = if (generalActionsEnabled) primaryActionIconTint else disabledIconTint
                            )
                        }
                    }
                    
                     // Botão Trocar Roupa
                    if (sceneLinkData.isChangingClothes) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(iconButtonSize)) {
                            CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                            IconButton(onClick = { projectViewModel.cancelClothesChangeForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_clothes_change), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                            }
                        }
                    } else {
                        val canChangeClothes = generalActionsEnabled && !sceneLinkData.imagemGeradaPath.isNullOrBlank()
                        IconButton(
                            onClick = {
                                projectViewModel.triggerClothesChangeForScene(sceneLinkData.id)
                            },
                            enabled = canChangeClothes,
                            modifier = Modifier.size(iconButtonSize)
                        ) {
                            Icon(
                                Icons.Filled.Checkroom,
                                contentDescription = stringResource(R.string.scene_item_desc_change_clothes_icon),
                                modifier = Modifier.size(iconSize),
                                tint = if (canChangeClothes) defaultEnabledIconTint else disabledIconTint
                            )
                        }
                    }

                    // Botão Gerar Vídeo / Buscar Vídeo
                    if (sceneLinkData.isGeneratingVideo) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(iconButtonSize)) {
                            CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                            IconButton(onClick = { projectViewModel.cancelGenerationForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { projectViewModel.onShowPixabaySearchDialog(sceneLinkData.id) },
                            enabled = generalActionsEnabled,
                            modifier = Modifier.size(iconButtonSize)
                        ) { Icon(Icons.Default.TravelExplore, contentDescription = stringResource(R.string.scene_item_action_find_stock_video_desc), modifier = Modifier.size(iconSize), tint = primaryActionIconTint) }
                    }
                }
            }
            SprocketHolesRow()
        }
    }
}

/**
 * Composable auxiliar para desenhar a estética de "rolo de filme".
 */
@Composable
private fun SprocketHolesRow(
    holeColor: Color = Color.White,
    holeCount: Int = 4,
    holeWidth: Dp = 21.dp,
    holeRectangleHeight: Dp = 22.dp,
    rowHeight: Dp = 45.dp,
    shadowOffsetX: Dp = 2.dp,
    shadowOffsetY: Dp = 2.dp
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
                        val gradientCenter = Offset(x = defaultCenterX + offsetXpx, y = defaultCenterY + offsetYpx)
                        val gradientEffectRadius = (rectWidthPx + rectHeightPx) / 2f * 0.85f
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(holeColor.copy(alpha = 0.6f), holeColor.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.2f)),
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

/**
 * Função auxiliar para verificar se uma cena está ocupada com alguma operação.
 */
private fun isSceneBusy(scene: SceneLinkData): Boolean {
    return scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo
}