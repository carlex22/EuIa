// File: ui/ProjectManagementScreen.kt
package com.carlex.euia.ui

import android.app.Application
import android.widget.Toast // Mantido para Toasts do operationEvent (poderia ser Snackbar também)
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign // Importado para o Text de "Nenhum projeto"
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
// import com.carlex.euia.AppDestinations // Não usado diretamente aqui
import com.carlex.euia.R
import com.carlex.euia.viewmodel.ProjectManagementViewModel
import com.carlex.euia.viewmodel.ProjectManagementViewModelFactory
import kotlinx.coroutines.flow.collectLatest

// A variável global `selectedWorkflowTabIndex` idealmente não seria modificada diretamente daqui.
// A navegação para o workflow e o reset do índice deveriam ser coordenados de forma mais robusta,
// por exemplo, passando um argumento para a rota VIDEO_CREATION_WORKFLOW ou usando um ViewModel compartilhado.
// Por ora, mantendo como estava no código original fornecido.
var selectedWorkflowTabIndex = 0

/**
 * Tela para gerenciar projetos de vídeo.
 * Permite ao usuário visualizar projetos existentes, criar novos projetos,
 * abrir projetos para edição e excluir projetos.
 *
 * @param navController O [NavHostController] para navegação.
 * @param projectManagementViewModel O [ProjectManagementViewModel] que lida com a lógica de gerenciamento de projetos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagementScreen(
    navController: NavHostController,
    projectManagementViewModel: ProjectManagementViewModel = viewModel(
        factory = ProjectManagementViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    val projects by projectManagementViewModel.projects.collectAsState()
    val isLoading by projectManagementViewModel.isLoading.collectAsState()
    val context = LocalContext.current

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectNameState by remember { mutableStateOf(TextFieldValue("")) }
    var projectToDelete by remember { mutableStateOf<String?>(null) }

    // Observa eventos do ViewModel para mostrar feedback (Toasts)
    LaunchedEffect(key1 = projectManagementViewModel.operationEvent) {
        projectManagementViewModel.operationEvent.collectLatest { message ->
            // Idealmente, as mensagens formatadas viriam prontas do ViewModel
            // ou teríamos um sistema de eventos mais estruturado.
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Diálogo para confirmar exclusão de projeto
    projectToDelete?.let { projectName -> // Usando 'let' para garantir que não é nulo
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text(stringResource(R.string.project_management_dialog_delete_title)) },
            text = { Text(stringResource(R.string.project_management_dialog_delete_message, projectName)) },
            confirmButton = {
                Button(
                    onClick = {
                        projectManagementViewModel.deleteProject(projectName)
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.project_management_dialog_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // Diálogo para criar novo projeto
    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewProjectDialog = false
                newProjectNameState = TextFieldValue("") // Limpa o estado ao fechar
            },
            title = { Text(stringResource(R.string.project_management_dialog_create_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.project_management_dialog_create_warning))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newProjectNameState,
                        onValueChange = { newProjectNameState = it },
                        label = { Text(stringResource(R.string.project_management_label_new_project_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newProjectNameState.text.isNotBlank()) {
                        projectManagementViewModel.createNewProject(newProjectNameState.text, navController)
                        showNewProjectDialog = false
                        newProjectNameState = TextFieldValue("")
                    } else {
                        Toast.makeText(context, R.string.project_management_toast_name_empty, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.project_management_dialog_action_create)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewProjectDialog = false
                    newProjectNameState = TextFieldValue("")
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newProjectNameState = TextFieldValue("") // Limpa o nome antes de mostrar o diálogo
                showNewProjectDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.project_management_fab_new_project_desc))
            }
        },
        floatingActionButtonPosition = FabPosition.End // Posição padrão, pode ser omitido
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (isLoading && projects.isEmpty()) {
                // Indicador de carregamento centralizado se estiver carregando e não houver projetos
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (projects.isEmpty()) {
                // Mensagem para quando não há projetos
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${stringResource(R.string.project_management_no_projects_found_title)}\n${stringResource(R.string.project_management_no_projects_found_instructions)}",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center // Adicionado para melhor formatação
                    )
                }
            } else {
                // Lista de projetos
                LazyColumn(
                    modifier = Modifier.weight(1f), // Permite que o indicador de progresso abaixo seja visível
                    contentPadding = PaddingValues(all = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            stringResource(R.string.project_management_label_saved_projects),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(projects, key = { it }) { projectName ->
                        ProjectItemCard(
                            projectName = projectName,
                            onOpen = { projectManagementViewModel.openProject(projectName, navController) },
                            onDelete = { projectToDelete = projectName } // Define o projeto para exclusão (mostra diálogo)
                        )
                    }
                }
            }
            // Indicador de carregamento no rodapé se estiver carregando E já houver projetos na lista
            if (isLoading && projects.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Composable para exibir um item de projeto individual em um Card.
 *
 * @param projectName O nome do projeto.
 * @param onOpen Callback invocado quando o projeto é clicado para ser aberto.
 * @param onDelete Callback invocado quando o ícone de lixeira é clicado para excluir o projeto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectItemCard(projectName: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        // colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // Exemplo de cor
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 20.dp), // Aumentado padding vertical para melhor toque
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween // Garante que o botão de exclusão fique à direita
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = stringResource(R.string.project_management_item_icon_desc),
                modifier = Modifier.size(36.dp).padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = projectName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f) // Permite que o nome ocupe o espaço disponível
            )
            IconButton(onClick = onDelete) { // Ação de clique passada para o IconButton
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.project_management_item_delete_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}