// File: euia/ui/project/ProjectDialogs.kt
package com.carlex.euia.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.carlex.euia.R
import com.carlex.euia.data.ProjectAsset
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.viewmodel.AssetSelectionPurpose
import com.carlex.euia.viewmodel.VideoProjectViewModel
import com.carlex.euia.viewmodel.helper.PixabayAsset
import com.carlex.euia.viewmodel.helper.PixabayAssetType
import java.io.File

/**
 * Um Composable gerenciador que observa os estados do ViewModel e renderiza
 * os diálogos apropriados para a tela de gerenciamento de cenas.
 */
@Composable
fun HandleProjectDialogs(
    projectViewModel: VideoProjectViewModel
) {
    val sceneIdForMediaExplorer by projectViewModel.showPixabaySearchDialogForSceneId.collectAsState()
    val sceneIdToRecreateImage by projectViewModel.sceneIdToRecreateImage.collectAsState()
    val availableAssets by projectViewModel.availableProjectAssets.collectAsState()
    val referenceImageAssets by projectViewModel.availableReferenceImageAssets.collectAsState()
    val sceneToEditPrompt by projectViewModel.sceneForPromptEdit.collectAsState()
    val assetSelectionState by projectViewModel.assetSelectionState.collectAsState()

    // Lógica de Prioridade de Exibição de Diálogos
    when {
        // Prioridade 1: Diálogo de seleção de assets (para qualquer propósito)
        assetSelectionState != null -> {
            val (sceneId, purpose) = assetSelectionState!!
            val dialogTitle: String
            val assetsToShow: List<ProjectAsset>

            when (purpose) {
                AssetSelectionPurpose.REPLACE_GENERATED_ASSET -> {
                    dialogTitle = stringResource(R.string.scene_item_dialog_title_select_asset)
                    assetsToShow = availableAssets
                }
                AssetSelectionPurpose.UPDATE_REFERENCE_IMAGE -> {
                    dialogTitle = stringResource(R.string.scene_item_dialog_title_select_ref_image)
                    assetsToShow = referenceImageAssets
                }
                AssetSelectionPurpose.SELECT_CLOTHING_IMAGE -> {
                    dialogTitle = stringResource(R.string.scene_item_dialog_title_select_figurino)
                    assetsToShow = referenceImageAssets
                }
            }
            
            AssetSelectionDialog(
                onDismissRequest = { projectViewModel.dismissAssetSelectionDialog() },
                availableAssets = assetsToShow,
                onAssetSelected = { selectedAsset ->
                    projectViewModel.handleAssetSelection(sceneId, selectedAsset, purpose)
                },
                dialogTitle = dialogTitle
            )
        }
        
        // Prioridade 2: Diálogo de edição de prompt
        sceneToEditPrompt != null -> {
            val scene = sceneToEditPrompt!!
            PromptEditDialog(
                scene = scene,
                onDismiss = { projectViewModel.dismissPromptEditDialog() },
                onSaveAndGenerate = { sceneId, newPrompt ->
                    projectViewModel.updatePromptAndGenerateImage(sceneId, newPrompt)
                    projectViewModel.dismissPromptEditDialog()
                },
                onChangeReferenceClick = {
                    projectViewModel.triggerAssetSelectionForScene(scene.id, AssetSelectionPurpose.UPDATE_REFERENCE_IMAGE)
                }
            )
        }

        // Prioridade 3: Diálogo de busca na Pixabay
        sceneIdForMediaExplorer != null -> {
            MediaExplorerDialog(
                sceneId = sceneIdForMediaExplorer!!,
                projectViewModel = projectViewModel
            )
        }

        // Prioridade 4: Diálogo para confirmar recriação de imagem
        sceneIdToRecreateImage != null -> {
            AlertDialog(
                onDismissRequest = { projectViewModel.dismissRecreateImageDialog() },
                title = { Text(stringResource(R.string.scene_item_dialog_recreate_image_title)) },
                text = { Text(stringResource(R.string.scene_item_dialog_recreate_image_message)) },
                confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithImageRecreation() }) { Text(stringResource(R.string.action_recreate)) } },
                dismissButton = { OutlinedButton(onClick = { projectViewModel.dismissRecreateImageDialog() }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }
}


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
                        }),
                        trailingIcon = {
                            IconButton(onClick = { projectViewModel.searchPixabayAssets() }, enabled = !isSearching) {
                                Icon(Icons.Default.Search, stringResource(R.string.pixabay_search_action_desc))
                            }
                        }
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
                                AsyncImage(
                                    model = selectedAssetForPreview!!.thumbnailUrl,
                                    contentDescription = stringResource(R.string.media_explorer_preview_title),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(64.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.7f))
                            } else {
                                AsyncImage(
                                    model = selectedAssetForPreview!!.downloadUrl,
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
                                AsyncImage(
                                    model = File(asset.thumbnailPath),
                                    contentDescription = asset.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (asset.isVideo) {
                                    Icon(
                                        Icons.Default.PlayCircleFilled,
                                        contentDescription = stringResource(R.string.content_desc_video_indicator),
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(
                                                Color.Black.copy(alpha = 0.3f),
                                                RoundedCornerShape(50)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PromptEditDialog(
    scene: SceneLinkData,
    onDismiss: () -> Unit,
    onSaveAndGenerate: (sceneId: String, newPrompt: String) -> Unit,
    onChangeReferenceClick: () -> Unit
) {
    var promptText by remember(scene.promptGeracao) { mutableStateOf(scene.promptGeracao ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scene_item_dialog_edit_prompt_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.scene_item_label_ref_image_for_prompt),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(bottom = 16.dp)
                        .clickable { onChangeReferenceClick() },
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (scene.imagemReferenciaPath.isNotBlank()) {
                            AsyncImage(
                                model = File(scene.imagemReferenciaPath),
                                contentDescription = stringResource(R.string.content_desc_reference_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.scene_item_placeholder_no_ref_image_selected_clickable),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text(stringResource(R.string.scene_item_label_image_generation_prompt)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveAndGenerate(scene.id, promptText) },
                enabled = promptText.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save_and_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}