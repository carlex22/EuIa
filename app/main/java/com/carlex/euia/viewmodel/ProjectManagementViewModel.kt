package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination // Import correto
import androidx.navigation.NavHostController
import com.carlex.euia.AppDestinations
import com.carlex.euia.data.*
import com.carlex.euia.sanitizeDirName // Import da função top-level (assumindo que está no pacote com.carlex.euia)
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

    fun openProject(projectName: String, navController: NavHostController) {
        viewModelScope.launch {
            _isLoading.value = true
            val success = ProjectPersistenceManager.loadProjectState(context, projectName)
            _isLoading.value = false
            if (success) {
                _operationEvent.emit("Projeto '$projectName' carregado.")
                selectedWorkflowTabIndex = 0 
                navController.navigate(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        // inclusive = true // Descomente se quiser remover a tela de gerenciamento do backstack
                    }
                    launchSingleTop = true
                }
            } else {
                _operationEvent.emit("Falha ao carregar projeto '$projectName'.")
            }
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
        val sanitizedProjectName = sanitizeDirName(rawProjectName) // Usando a função importada
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
                videoProjectDataStoreManager.clearProjectState() // Limpa dados de cenas e outros estados do projeto
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
                    popUpTo(navController.graph.findStartDestination().id) {
                        // inclusive = true 
                    }
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

// Factory (mantido como antes, mas certifique-se que SceneDataStoreManager foi removido se não for usado)
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
