// File: ui/RefImageInfoContent.kt
package com.carlex.euia.ui

import android.util.Log // Mantido para logs de ciclo de vida/erros importantes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext // Adicionado para getString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.viewmodel.JsonDetail
import com.carlex.euia.viewmodel.RefImageViewModel
import kotlinx.coroutines.launch

private const val TAG = "RefImageInfoContent" // Tag para logging específico desta tela

/**
 * Composable que exibe um item de detalhe individual (par chave-valor) do objeto de referência.
 * Permite a edição do valor e a remoção do detalhe.
 *
 * @param detail O objeto [JsonDetail] a ser exibido e editado.
 * @param isAnalyzing Indica se uma análise global está em progresso, desabilitando a edição.
 * @param onValueChange Callback invocado quando o valor do detalhe é alterado pelo usuário.
 * @param onRemoveClick Callback invocado quando o usuário clica para remover este detalhe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObjectDetailItem(
    detail: JsonDetail,
    isAnalyzing: Boolean,
    onValueChange: (String) -> Unit,
    onRemoveClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = detail.value,
                onValueChange = onValueChange,
                label = {
                    // Formata a chave para ser mais legível como label
                    Text(detail.key.replace("_", " ").replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    })
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !isAnalyzing,
            )
            IconButton(
                onClick = onRemoveClick,
                enabled = !isAnalyzing
            ) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.ref_image_info_action_remove_detail_desc, detail.key))
            }
        }
    }
}

/**
 * Composable principal para a aba "Informações" do fluxo de criação de vídeo.
 * Exibe e permite a edição dos detalhes extraídos (ou a serem extraídos) das imagens de referência.
 * Dispara a análise das imagens de referência e gerencia o estado de "sujo" para saídas não salvas.
 *
 * @param modifier [Modifier] para este Composable.
 * @param innerPadding [PaddingValues] fornecido pelo Scaffold pai, usado para o padding principal do conteúdo.
 * @param refImageViewModel ViewModel que gerencia o estado e a lógica para os dados da imagem de referência.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefImageInfoContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    refImageViewModel: RefImageViewModel = viewModel()
) {
    val initialRefObjetoDetalhes by refImageViewModel.refObjetoDetalhes.collectAsState()
    val isAnalyzing by refImageViewModel.isAnalyzing.collectAsState()
    val videoImageReferencesJson by refImageViewModel.currentVideoImagensReferenciaJson.collectAsState() // Usado para lógica de auto-trigger
    val errorMessageFlow by refImageViewModel.errorMessage.collectAsState(initial = null)
    val currentVideoTitulo by refImageViewModel.currentVideoTitulo.collectAsState()
    val isTriggerDataLoaded by refImageViewModel.isTriggerDataLoaded.collectAsState()

    var currentRefObjetoDetalhes by remember { mutableStateOf<List<JsonDetail>>(emptyList()) }
    var showAddDetailDialog by remember { mutableStateOf(false) }
    var newDetailAttribute by remember { mutableStateOf("") }
    var newDetailValue by remember { mutableStateOf("") }
    var hasChanges by remember { mutableStateOf(false) }
    var showExitConfirmationDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current // Para usar context.getString

    // Sincroniza a lista local editável com os dados do ViewModel
    LaunchedEffect(initialRefObjetoDetalhes) {
        if (currentRefObjetoDetalhes != initialRefObjetoDetalhes) {
            currentRefObjetoDetalhes = initialRefObjetoDetalhes.map { JsonDetail(it.key, it.value) }
            // Não reseta 'hasChanges' aqui; isso é feito ao salvar ou descartar explicitamente.
        }
    }

    // Exibe mensagens de erro/status do ViewModel via Snackbar
    LaunchedEffect(errorMessageFlow) {
        errorMessageFlow?.let { message ->
            if (message.isNotBlank()) {
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                // O ViewModel deve ter uma forma de consumir/limpar a mensagem após exibição
                // para evitar reexibições em reconfigurações (ex: refImageViewModel.clearErrorMessage()).
            }
        }
    }

    // Lógica para disparo automático da análise de imagens
    LaunchedEffect(isTriggerDataLoaded, videoImageReferencesJson, initialRefObjetoDetalhes, isAnalyzing, currentVideoTitulo) {
        if (isTriggerDataLoaded) {
            val hasImages = videoImageReferencesJson.isNotBlank() && videoImageReferencesJson != "[]"
            val detailsAreEmpty = initialRefObjetoDetalhes.isEmpty()

            if (!isAnalyzing && hasImages && detailsAreEmpty && currentVideoTitulo.isNotBlank()) {
                Log.i(TAG, "Disparando análise automática de imagens de referência.")
                refImageViewModel.analyzeImages()
            }
        }
    }

    // Manipula o botão "voltar" do sistema se houver alterações não salvas
    BackHandler(enabled = hasChanges) {
        showExitConfirmationDialog = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding) // Aplica o padding do Scaffold pai
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp) // Padding lateral para o conteúdo
        ) {
            // Lista rolável dos detalhes do objeto de referência
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // Ocupa o espaço vertical disponível
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(currentRefObjetoDetalhes, key = { _, detail -> detail.key }) { index, detail ->
                    ObjectDetailItem(
                        detail = detail,
                        isAnalyzing = isAnalyzing,
                        onValueChange = { newValue ->
                            val updatedList = currentRefObjetoDetalhes.toMutableList()
                            updatedList[index] = detail.copy(value = newValue)
                            currentRefObjetoDetalhes = updatedList
                            hasChanges = true
                        },
                        onRemoveClick = {
                            val updatedList = currentRefObjetoDetalhes.toMutableList()
                            updatedList.removeAt(index)
                            currentRefObjetoDetalhes = updatedList
                            hasChanges = true // Marcar como alterado
                            // Salva imediatamente ao remover um item (mantendo comportamento original)
                            scope.launch {
                                refImageViewModel.saveRefObjetoDetalhes(currentRefObjetoDetalhes)
                                snackbarHostState.showSnackbar(context.getString(R.string.ref_image_info_toast_detail_removed_and_saved), duration = SnackbarDuration.Short)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Botão para adicionar um novo detalhe
            Button(
                onClick = {
                    newDetailAttribute = ""
                    newDetailValue = ""
                    showAddDetailDialog = true
                },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp) // Espaço abaixo do botão
            ) {
                Text(stringResource(R.string.ref_image_info_button_add_detail))
            }
        }

        // Host para Snackbars locais desta tela
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Diálogo para adicionar novo detalhe
        if (showAddDetailDialog) {
            AlertDialog(
                onDismissRequest = { showAddDetailDialog = false },
                title = { Text(stringResource(R.string.ref_image_info_dialog_add_detail_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newDetailAttribute,
                            onValueChange = { newDetailAttribute = it },
                            label = { Text(stringResource(R.string.ref_image_info_dialog_label_attribute)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newDetailValue,
                            onValueChange = { newDetailValue = it },
                            label = { Text(stringResource(R.string.ref_image_info_dialog_label_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newDetailAttribute.isNotBlank()) {
                                val newDetail = JsonDetail(newDetailAttribute.trim(), newDetailValue.trim())
                                if (currentRefObjetoDetalhes.none { it.key.equals(newDetail.key, ignoreCase = true) }) {
                                    currentRefObjetoDetalhes = currentRefObjetoDetalhes + newDetail
                                    hasChanges = true
                                    showAddDetailDialog = false
                                    // Salva imediatamente ao adicionar (mantendo comportamento original)
                                    scope.launch {
                                        refImageViewModel.saveRefObjetoDetalhes(currentRefObjetoDetalhes)
                                        snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.ref_image_info_toast_detail_added_and_saved, newDetail.key),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.ref_image_info_toast_attribute_exists, newDetail.key),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.ref_image_info_toast_attribute_empty),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.ref_image_info_dialog_action_add)) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAddDetailDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        // Diálogo de confirmação ao tentar sair com alterações não salvas
        if (showExitConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showExitConfirmationDialog = false },
                title = { Text(stringResource(R.string.ref_image_info_dialog_exit_title)) },
                text = { Text(stringResource(R.string.ref_image_info_dialog_exit_message)) },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            refImageViewModel.saveRefObjetoDetalhes(currentRefObjetoDetalhes)
                            snackbarHostState.showSnackbar(context.getString(R.string.ref_image_info_toast_settings_saved), duration = SnackbarDuration.Short)
                            hasChanges = false // Reseta após salvar
                            showExitConfirmationDialog = false
                            // A navegação real para trás seria tratada pelo sistema após 'hasChanges' ser false.
                        }
                    }) { Text(stringResource(R.string.ref_image_info_dialog_action_save_and_exit)) }
                },
                dismissButton = { // Este botão agora é o "Descartar e Sair"
                    TextButton(onClick = {
                        hasChanges = false // Descarta alterações
                        showExitConfirmationDialog = false
                        // A navegação real para trás seria tratada pelo sistema.
                    }) { Text(stringResource(R.string.ref_image_info_dialog_action_discard_and_exit)) }
                },
                // Removido o terceiro botão "CANCELAR" do diálogo de saída, pois o onDismissRequest já cobre isso.
            )
        }

        // Overlay de progresso durante a análise
        if (isAnalyzing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.ref_image_info_analyzing_progress_text), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}