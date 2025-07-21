// File: euia/ui/project/SceneCard.kt
package com.carlex.euia.ui.project

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
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carlex.euia.R
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.VideoProjectViewModel
import java.io.File
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Um Composable que renderiza um único cartão de cena na timeline do projeto.
 * Este componente é responsável por exibir o asset visual (imagem/vídeo),
 * a imagem de referência, informações da cena e todos os botões de ação relevantes.
 *
 * @param sceneLinkData O objeto de dados da cena a ser exibido.
 * @param projectViewModel O ViewModel principal do projeto, usado para disparar ações.
 * @param allProjectReferenceImages A lista completa de imagens de referência do projeto.
 * @param currentlyPlayingSceneId O ID da cena que está tocando a prévia atualmente (se houver).
 * @param isGeneratingPreviewForScene O ID da cena cuja prévia está sendo gerada.
 */
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
                        val displayPath = sceneLinkData.pathThumb ?: sceneLinkData.imagemGeradaPath

                        if (isCurrentlyPlayingThisScene && !sceneLinkData.videoPreviewPath.isNullOrBlank()) {
                            // VideoPlayerInternal(...) -- Assumindo que VideoPlayerInternal será movido para VideoProjectContent
                        } else if (!displayPath.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(displayPath)).crossfade(true).placeholder(R.drawable.ic_placeholder_image).error(R.drawable.ic_broken_image).build(),
                                contentDescription = stringResource(R.string.content_desc_generated_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // Placeholder quando não há imagem
                            Column( // <<< CORRIGIDO: Adicionado lambda content
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // Conteúdo do placeholder
                                Text(
                                    text = stringResource(R.string.scene_placeholder_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.scene_placeholder_instructions),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = " " + stringResource(R.string.scene_placeholder_action_generate),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = " " + stringResource(R.string.scene_placeholder_action_select),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (!sceneLinkData.generationErrorMessage.isNullOrBlank() && !isGeneratingAsset) {
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
                                     .clickable { projectViewModel.clearGlobalSceneError() }
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
                                    model = ImageRequest.Builder(context).data(File(sceneLinkData.imagemReferenciaPath)).crossfade(true).build(),
                                    contentDescription = stringResource(R.string.content_desc_reference_image), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Filled.ImageNotSupported, contentDescription = stringResource(R.string.scene_item_placeholder_no_ref_image_desc), modifier = Modifier.size(24.dp))
                                }
                            }
                            Text(
                                text = stringResource(R.string.scene_item_label_ref_image_caption), 
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), 
                                color = Color.White.copy(alpha = 0.9f), 
                                textAlign = TextAlign.Center, 
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(vertical = 1.dp)
                            )
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

                    // Botão Play/Pause
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
                                        text = sceneLinkData.previewQueuePosition.toString(), // Posição na fila
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            isCurrentlyPlayingThisScene -> {
                                Icon(Icons.Default.StopCircle, contentDescription = stringResource(R.string.scene_item_action_stop_audio), modifier = Modifier.size(iconSize), tint = primaryActionIconTint)
                            }
                            !sceneLinkData.videoPreviewPath.isNullOrBlank() -> { // Prévia pronta
                                Icon(Icons.Default.PlayCircleOutline, contentDescription = stringResource(R.string.scene_item_action_play_audio_snippet), modifier = Modifier.size(iconSize), tint = primaryActionIconTint)
                            }
                            else -> { // Nenhum asset, nem prévia gerada, esperando
                                Icon(Icons.Default.Timelapse, contentDescription = "Aguardando prévia", modifier = Modifier.size(iconSize), tint = disabledIconTint)
                            }
                        }
                    }

                    // Botão Abrir Asset Local
                    IconButton(
                        onClick = {
                            if (projectViewModel.availableProjectAssets.value.isNotEmpty()) {
                                // projectViewModel.showAssetSelectionDialog(sceneLinkData.id) // Exemplo
                                projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_action_replace_generated_with_ref))
                            } else {
                                projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_dialog_no_assets_available))
                            }
                        },
                        enabled = generalActionsEnabled,
                        modifier = Modifier.size(iconButtonSize)
                    ) { Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.scene_item_action_replace_generated_with_ref), modifier = Modifier.size(iconSize), tint = defaultEnabledIconTint) }

                    // Botão Gerar Imagem (com loading)
                    if (sceneLinkData.isGenerating) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                            IconButton(onClick = { projectViewModel.cancelGenerationForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_generation), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { /* projectViewModel.showPromptEditDialog(sceneLinkData.id) */ projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_action_edit_prompt)) }, // TODO: Implementar showPromptEditDialog
                            enabled = generalActionsEnabled,
                            modifier = Modifier.size(iconButtonSize)
                        ) { Icon(Icons.Outlined.AutoFixHigh, contentDescription = stringResource(R.string.scene_item_action_edit_prompt), modifier = Modifier.size(iconSize), tint = defaultEnabledIconTint) }
                    }
                    
                    // Botão Trocar Roupa (com loading)
                     if (sceneLinkData.isChangingClothes) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(iconSize + 4.dp), strokeWidth = 1.5.dp, color = primaryActionIconTint)
                            IconButton(onClick = { projectViewModel.cancelClothesChangeForScene(sceneLinkData.id) }, modifier = Modifier.size(iconSize)) {
                                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.scene_item_action_cancel_clothes_change), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(iconSize - 4.dp))
                            }
                        }
                    } else {
                        val refImageContainsPeople = allProjectReferenceImages.any { it.path == sceneLinkData.imagemReferenciaPath && it.containsPeople }
                        IconButton(
                            onClick = { /* projectViewModel.showClothesChangeDialog(sceneLinkData.id) */ projectViewModel.postSnackbarMessage(context.getString(R.string.scene_item_desc_change_clothes_icon)) }, // TODO: Implementar showClothesChangeDialog
                            enabled = generalActionsEnabled && sceneLinkData.imagemGeradaPath != null && refImageContainsPeople,
                            modifier = Modifier.size(iconButtonSize)
                        ) { Icon(Icons.Filled.Checkroom, contentDescription = stringResource(R.string.scene_item_desc_change_clothes_icon), modifier = Modifier.size(iconSize), tint = if (generalActionsEnabled && sceneLinkData.imagemGeradaPath != null && refImageContainsPeople) defaultEnabledIconTint else disabledIconTint) }
                    }

                    // Botão Gerar Vídeo (com loading)
                    if (sceneLinkData.isGeneratingVideo) {
                        Box(contentAlignment = Alignment.Center) {
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

// Funções auxiliares mantidas neste arquivo, pois são específicas do SceneCard
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

private fun isSceneBusy(scene: SceneLinkData): Boolean {
    return scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo
}