// File: euia/viewmodel/ProjectManagementViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.carlex.euia.AppDestinations
import com.carlex.euia.data.*
import com.carlex.euia.sanitizeDirName
import com.carlex.euia.ui.selectedWorkflowTabIndex
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ProjectManagementViewModel(
    application: Application,
    private val videoPreferencesDataStoreManager: VideoPreferencesDataStoreManager,
    private val audioDataStoreManager: AudioDataStoreManager,
    private val refImageDataStoreManager: RefImageDataStoreManager,
    private val videoDataStoreManager: VideoDataStoreManager,
    private val videoGeneratorDataStoreManager: VideoGeneratorDataStoreManager,
    private val videoProjectDataStoreManager: VideoProjectDataStoreManager
) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: StateFlow<List<String>> = _projects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _operationEvent = MutableSharedFlow<String>()
    val operationEvent: SharedFlow<String> = _operationEvent.asSharedFlow()

    init {
        loadProjectList()
    }

    fun loadProjectList() {
        viewModelScope.launch {
            _isLoading.value = true
            _projects.value = ProjectPersistenceManager.listProjectNames(context)
            _isLoading.value = false
            Log.d("ProjectVM", "Lista de projetos carregada: ${_projects.value}")
        }
    }

    // <<< FUNÇÃO MODIFICADA E CORRIGIDA >>>
    fun openProject(projectName: String, navController: NavHostController) {
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Verifica qual projeto está ativo atualmente no DataStore
            val currentActiveProject = videoPreferencesDataStoreManager.videoProjectDir.first()

            // 2. Compara com o projeto que o usuário clicou
            if (currentActiveProject == projectName) {
                Log.i("ProjectVM", "Projeto '$projectName' já está ativo. Apenas navegando para o workflow.")
              //  _operationEvent.emit("Projeto '$projectName' já está carregado.")
                // Apenas navega, sem recarregar nada
            } else {
                Log.i("ProjectVM", "Abrindo um novo projeto: '$projectName'. Projeto antigo: '$currentActiveProject'.")
                val success = ProjectPersistenceManager.loadProjectState(context, projectName)
                if (success) {
                 //   _operationEvent.emit("Projeto '$projectName' carregado.")
                } else {
                    _isLoading.value = false
                  //  _operationEvent.emit("Falha ao carregar projeto '$projectName'.")
                    return@launch // Interrompe a execução se o carregamento falhar
                }
            }

            // 3. A navegação acontece em ambos os casos (seja para o projeto já aberto ou para o recém-carregado)
            selectedWorkflowTabIndex = 0 
            navController.navigate(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
            
            _isLoading.value = false
        }
    }

    fun deleteProject(projectName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val success = ProjectPersistenceManager.deleteProject(context, projectName)
            _isLoading.value = false
            if (success) {
                _operationEvent.emit("Projeto '$projectName' excluído.")
                loadProjectList() 
            } else {
                _operationEvent.emit("Falha ao excluir projeto '$projectName'.")
            }
        }
    }

    fun createNewProject(rawProjectName: String, navController: NavHostController) {
        if (rawProjectName.isBlank()) {
            viewModelScope.launch { _operationEvent.emit("O nome do projeto não pode estar vazio.") }
            return
        }
        val sanitizedProjectName = sanitizeDirName(rawProjectName)
        if (sanitizedProjectName.isBlank() || sanitizedProjectName == "default_project_if_blank") {
            viewModelScope.launch { _operationEvent.emit("Nome de projeto inválido ou reservado.") }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val projectDirToCheck = ProjectPersistenceManager.getProjectDirectory(context, sanitizedProjectName)
                val projectStateFile = File(projectDirToCheck, "euia_project_data.json")
                if (projectStateFile.exists()) {
                     _isLoading.value = false
                    _operationEvent.emit("Já existe um projeto com o nome '$sanitizedProjectName'.")
                    return@launch
                }

                Log.i("ProjectVM", "Limpando DataStores para novo projeto: $sanitizedProjectName")
                audioDataStoreManager.clearAllAudioPreferences()
                refImageDataStoreManager.clearAllRefImagePreferences()
                videoProjectDataStoreManager.clearProjectState()
                videoDataStoreManager.clearAllSettings()
                videoGeneratorDataStoreManager.clearGeneratorState()
                
                videoPreferencesDataStoreManager.setVideoProjectDir(sanitizedProjectName)
                Log.i("ProjectVM", "Novo diretório do projeto '$sanitizedProjectName' definido como ativo.")

                ProjectPersistenceManager.saveProjectState(context)
                Log.i("ProjectVM", "Estado inicial salvo para o novo projeto '$sanitizedProjectName'.")

                _isLoading.value = false
                _operationEvent.emit("Novo projeto '$sanitizedProjectName' iniciado.")
                selectedWorkflowTabIndex = 0
                navController.navigate(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _operationEvent.emit("Erro ao criar projeto: ${e.message}")
                Log.e("ProjectVM", "Erro ao criar projeto $sanitizedProjectName", e)
            }
        }
    }
}

class ProjectManagementViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProjectManagementViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProjectManagementViewModel(
                application,
                VideoPreferencesDataStoreManager(application.applicationContext),
                AudioDataStoreManager(application.applicationContext),
                RefImageDataStoreManager(application.applicationContext),
                VideoDataStoreManager(application.applicationContext),
                VideoGeneratorDataStoreManager(application.applicationContext),
                VideoProjectDataStoreManager(application.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class for ProjectManagementViewModelFactory")
    }
}