// File: euia/ui/AddGeminiApiKeyScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.data.ChaveApiInfo
import com.carlex.euia.viewmodel.AddGeminiApiViewModel
import com.carlex.euia.viewmodel.ConfigState
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGeminiApiKeyScreen(
    adminViewModel: AddGeminiApiViewModel = viewModel()
) {
    // --- Estados para Gerenciamento de Chaves ---
    val keys by adminViewModel.apiKeys.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var newApiKey by remember { mutableStateOf("") }
    var keyToDelete by remember { mutableStateOf<ChaveApiInfo?>(null) }

    // --- Estados para Configuração do App ---
    val configState by adminViewModel.appConfigState.collectAsState()
    val editableConfig by adminViewModel.editableConfig.collectAsState()
    val isLoading by adminViewModel.isLoading.collectAsState()

    // --- Componentes da UI (Snackbar, Scope) ---
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // --- Efeito para mostrar mensagens da UI ---
    LaunchedEffect(Unit) {
        adminViewModel.uiEvent.collect { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }
    }

    // --- Diálogos (mantidos como antes) ---
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Adicionar Nova Chave API") },
            text = {
                OutlinedTextField(
                    value = newApiKey,
                    onValueChange = { newApiKey = it },
                    label = { Text("Chave API do Gemini") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newApiKey.isNotBlank()) {
                        adminViewModel.addApiKey(newApiKey)
                        newApiKey = ""
                        showAddDialog = false
                    }
                }) { Text("Adicionar") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
            }
        )
    }

    keyToDelete?.let { keyInfo ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text("Confirmar Exclusão") },
            text = { Text("Tem certeza que deseja excluir a chave que termina em '${keyInfo.apikey.takeLast(4)}'? Esta ação não pode ser desfeita.") },
            confirmButton = {
                Button(
                    onClick = {
                        adminViewModel.deleteApiKey(keyInfo)
                        keyToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Adicionar Chave") },
                icon = { Icon(Icons.Default.Add, contentDescription = "Adicionar nova chave API") },
                onClick = {
                    newApiKey = ""
                    showAddDialog = true
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Seção 1: Gerenciamento de Chaves de API ---
            item {
                Text(
                    "Pool de Chaves de API",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (isLoading && keys.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (keys.isEmpty()) {
                item {
                    Text("Nenhuma chave API no pool.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                items(keys, key = { it.apikey }) { apiKeyInfo ->
                    ApiKeyItemCard(
                        apiKeyInfo = apiKeyInfo,
                        onResetClick = { adminViewModel.resetApiKey(it) },
                        onDeleteClick = { keyToDelete = it }
                    )
                }
            }

            // --- Seção 2: Configuração do App ---
            item {
                Divider(modifier = Modifier.padding(vertical = 24.dp))
                Text(
                    "Configuração do Aplicativo (Data_app)",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                when (val state = configState) {
                    is ConfigState.Loading -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is ConfigState.Error -> {
                        Text("Erro: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                    is ConfigState.Success -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Renderiza os itens da lista editável
                            editableConfig.forEach { (key, value) ->
                                ConfigItem(
                                    label = key,
                                    value = value,
                                    onValueChange = { newValue ->
                                        adminViewModel.updateEditableConfigValue(key, newValue)
                                    }
                                )
                            }
                            // Botão para salvar as configurações
                            Button(
                                onClick = { adminViewModel.saveEditableConfig() },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                            ) {
                                Text("Salvar Configuração")
                            }
                        }
                    }
                }
            }
             item {
                // Adiciona um espaço no final para o FAB não cobrir o último item
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

// Composables auxiliares (ApiKeyItemCard, KeyStatusInfo, ConfigItem) permanecem os mesmos.
// ... (código dos composables auxiliares aqui, sem alterações) ...
@Composable
private fun ApiKeyItemCard(
    apiKeyInfo: ChaveApiInfo,
    onResetClick: (ChaveApiInfo) -> Unit,
    onDeleteClick: (ChaveApiInfo) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Chave: ...${apiKeyInfo.apikey.takeLast(6)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = { onResetClick(apiKeyInfo) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Resetar chave")
                    }
                    IconButton(onClick = { onDeleteClick(apiKeyInfo) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Deletar chave", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                KeyStatusInfo("Áudio", apiKeyInfo.emUsoAudio, apiKeyInfo.bloqueadaEmAudio)
                KeyStatusInfo("Imagem", apiKeyInfo.emUsoImg, apiKeyInfo.bloqueadaEmImg)
                KeyStatusInfo("Texto", apiKeyInfo.emUsoText, apiKeyInfo.bloqueadaEmText)
            }
        }
    }
}

@Composable
private fun KeyStatusInfo(
    type: String,
    inUse: Boolean,
    blockedTimestamp: Timestamp?
) {
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(type, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        when {
            inUse -> {
                Icon(Icons.Default.Sync, "Em uso", tint = Color(0xFFFFA000))
                Text("Em Uso", fontSize = 12.sp, color = Color(0xFFFFA000))
            }
            blockedTimestamp != null -> {
                Icon(Icons.Default.Lock, "Bloqueada", tint = MaterialTheme.colorScheme.error)
                Text(
                    text = "Bloq: ${formatter.format(blockedTimestamp.toDate())}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> {
                Icon(Icons.Default.CheckCircle, "Disponível", tint = Color(0xFF388E3C))
                Text("Disponível", fontSize = 12.sp, color = Color(0xFF388E3C))
            }
        }
    }
}

@Composable
fun ConfigItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true
    )
}