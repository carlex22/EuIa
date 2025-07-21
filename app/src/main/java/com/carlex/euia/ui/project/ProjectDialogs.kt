// File: euia/ui/project/ProjectDialogs.kt
package com.carlex.euia.ui.project

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
import androidx.compose.material.icons.filled.PlayCircleFilled // <<< ADICIONADO: Import PlayCircleFilled
import androidx.compose.material.icons.filled.PlayCircleOutline
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
import com.carlex.euia.viewmodel.VideoProjectViewModel
import com.carlex.euia.viewmodel.helper.PixabayAsset
import com.carlex.euia.viewmodel.helper.PixabayAssetType
import java.io.File

/**
 * Um Composable gerenciador que observa os estados do ViewModel e renderiza
 * os diálogos apropriados para a tela de gerenciamento de cenas, mantendo a
 * UI principal (`VideoProjectContent` e `SceneCard`) limpa.
 *
 * @param projectViewModel O ViewModel que controla a visibilidade e os dados dos diálogos.
 */
@Composable
fun HandleProjectDialogs(
    projectViewModel: VideoProjectViewModel
) {
    val sceneIdForMediaExplorer by projectViewModel.showPixabaySearchDialogForSceneId.collectAsState()
    val sceneIdToRecreateImage by projectViewModel.sceneIdToRecreateImage.collectAsState()
    val sceneIdForRefChange by projectViewModel.sceneIdForReferenceChangeDialog.collectAsState()
    val availableAssets by projectViewModel.availableProjectAssets.collectAsState()

    // Diálogo para buscar mídias na Pixabay
    sceneIdForMediaExplorer?.let { sceneId ->
        MediaExplorerDialog(
            sceneId = sceneId,
            projectViewModel = projectViewModel
        )
    }

    // Diálogo para confirmar a recriação de uma imagem
    if (sceneIdToRecreateImage != null) {
        AlertDialog(
            onDismissRequest = { projectViewModel.dismissRecreateImageDialog() },
            title = { Text(stringResource(R.string.scene_item_dialog_recreate_image_title)) },
            text = { Text(stringResource(R.string.scene_item_dialog_recreate_image_message)) },
            confirmButton = { Button(onClick = { projectViewModel.confirmAndProceedWithImageRecreation() }) { Text(stringResource(R.string.action_recreate)) } },
            dismissButton = { OutlinedButton(onClick = { projectViewModel.dismissRecreateImageDialog() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    
    // Diálogo para selecionar um asset (imagem/vídeo) local do projeto
    sceneIdForRefChange?.let { sceneId ->
        AssetSelectionDialog(
            onDismissRequest = { projectViewModel.dismissReferenceImageSelectionDialog() },
            availableAssets = availableAssets,
            onAssetSelected = { selectedAsset ->
                // TODO: Ação de selecionar asset local e atualizar a cena no ViewModel
                // Por exemplo: projectViewModel.replaceGeneratedImageWithReference(sceneId, selectedAsset)
                projectViewModel.dismissReferenceImageSelectionDialog()
            },
            dialogTitle = stringResource(R.string.scene_item_dialog_title_select_ref_image)
        )
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
                                    Icon(Icons.Default.PlayCircleFilled, contentDescription = null) // <<< CORRIGIDO: Icon.Default.PlayCircleFilled
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