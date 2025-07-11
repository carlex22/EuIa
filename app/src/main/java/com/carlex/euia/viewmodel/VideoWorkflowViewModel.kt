// File: euia/viewmodel/VideoWorkflowViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.AppDestinations
import com.carlex.euia.R
import com.carlex.euia.WorkflowStage
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log

class VideoWorkflowViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val TAG = "VideoWorkflowVM"

    private val audioViewModel by lazy { AudioViewModel(application) }
    private val refImageInfoViewModel by lazy { RefImageViewModel(application) }
    private val videoProjectViewModel by lazy { VideoProjectViewModel(application) }
    private val videoGeneratorViewModel by lazy { VideoGeneratorViewModel(application) }


    val workflowStages: List<WorkflowStage> = listOf(
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_context), AppDestinations.WORKFLOW_STAGE_CONTEXT),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_images), AppDestinations.WORKFLOW_STAGE_IMAGES),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_information), AppDestinations.WORKFLOW_STAGE_INFORMATION),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_narrative), AppDestinations.WORKFLOW_STAGE_NARRATIVE),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_scenes), AppDestinations.WORKFLOW_STAGE_SCENES),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_music), AppDestinations.WORKFLOW_STAGE_MUSIC),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_finalize), AppDestinations.WORKFLOW_STAGE_FINALIZE)
    )

    private val _selectedWorkflowTabIndex = MutableStateFlow(0)
    val selectedWorkflowTabIndex: StateFlow<Int> = _selectedWorkflowTabIndex.asStateFlow()

    private val _isCurrentStageProcessing = MutableStateFlow(false)
    val isCurrentStageProcessing: StateFlow<Boolean> = _isCurrentStageProcessing.asStateFlow()

    private val _currentStageProgressText = MutableStateFlow("")
    val currentStageProgressText: StateFlow<String> = _currentStageProgressText.asStateFlow()

    private val _currentStageNumericProgress = MutableStateFlow(0f)
    val currentStageNumericProgress: StateFlow<Float> = _currentStageNumericProgress.asStateFlow()

    private val _currentStageLaunchPickerAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageLaunchPickerAction: StateFlow<(() -> Unit)?> = _currentStageLaunchPickerAction.asStateFlow()

    // REMOVIDO: _currentStageLaunchMusicAction e sua versão pública

    private val _currentStageAnalyzeAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageAnalyzeAction: StateFlow<(() -> Unit)?> = _currentStageAnalyzeAction.asStateFlow()

    private val _currentStageCreateNarrativeAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageCreateNarrativeAction: StateFlow<(() -> Unit)?> = _currentStageCreateNarrativeAction.asStateFlow()

    private val _currentStageGenerateAudioAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageGenerateAudioAction: StateFlow<(() -> Unit)?> = _currentStageGenerateAudioAction.asStateFlow()

    private val _currentStageGenerateScenesAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageGenerateScenesAction: StateFlow<(() -> Unit)?> = _currentStageGenerateScenesAction.asStateFlow()

    private val _currentStageRecordVideoAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageRecordVideoAction: StateFlow<(() -> Unit)?> = _currentStageRecordVideoAction.asStateFlow()

    private val _currentShareVideoAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentShareVideoAction: StateFlow<(() -> Unit)?> = _currentShareVideoAction.asStateFlow()

    private val _actualSaveContextAction = MutableStateFlow<(() -> Unit)?>(null)
    val actualSaveContextAction: StateFlow<(() -> Unit)?> = _actualSaveContextAction.asStateFlow()

    private val _isSaveContextEnabled = MutableStateFlow(false)
    val isSaveContextEnabled: StateFlow<Boolean> = _isSaveContextEnabled.asStateFlow()

    private val _isContextScreenDirty = MutableStateFlow(false)
    val isContextScreenDirty: StateFlow<Boolean> = _isContextScreenDirty.asStateFlow()

    private val _pendingNavigationAction = MutableStateFlow<(() -> Unit)?>(null)
    val showConfirmExitContextDialog: StateFlow<(() -> Unit)?> = _pendingNavigationAction.asStateFlow()

    init {
        Log.d(TAG, "VideoWorkflowViewModel Inicializado.")
    }

    fun cancelCurrentStageProcessing() {
        val currentStageIdentifier = workflowStages.getOrNull(_selectedWorkflowTabIndex.value)?.identifier
        Log.d(TAG, "Cancelamento solicitado para a aba: $currentStageIdentifier")

        when (currentStageIdentifier) {
            AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                Log.w(TAG, "A lógica de cancelamento para o processamento de imagens precisa ser implementada.")
            }
            AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                refImageInfoViewModel.cancelAnalysis()
            }
            AppDestinations.WORKFLOW_STAGE_NARRATIVE, AppDestinations.WORKFLOW_STAGE_AUDIO -> {
                audioViewModel.cancelAudioGeneration()
            }
            AppDestinations.WORKFLOW_STAGE_SCENES -> {
                videoProjectViewModel.cancelSceneGenerationProcess()
            }
            AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                videoGeneratorViewModel.cancelVideoGeneration()
            }
            else -> {
                Log.d(TAG, "Nenhuma ação de cancelamento definida para a aba atual: $currentStageIdentifier")
            }
        }
    }

    fun updateSelectedTabIndex(newIndex: Int) {
        _selectedWorkflowTabIndex.value = newIndex
    }

    fun attemptToChangeTab(targetIndex: Int) {
        if (_isCurrentStageProcessing.value && targetIndex != _selectedWorkflowTabIndex.value) {
            Log.d(TAG, "Tentativa de mudar de aba enquanto processando foi bloqueada.")
            return
        }

        val currentIndex = _selectedWorkflowTabIndex.value
        val isCurrentlyOnContextTab = workflowStages.getOrNull(currentIndex)?.identifier == AppDestinations.WORKFLOW_STAGE_CONTEXT

        if (isCurrentlyOnContextTab && _isContextScreenDirty.value && targetIndex != currentIndex) {
            Log.d(TAG, "Contexto sujo. Definindo ação pendente para mudar para aba $targetIndex e mostrando diálogo.")
            _pendingNavigationAction.value = { _selectedWorkflowTabIndex.value = targetIndex }
        } else {
            _selectedWorkflowTabIndex.value = targetIndex
        }
    }

    fun onContextSaveActionProvided(action: () -> Unit, isEnabled: Boolean) {
        _actualSaveContextAction.value = action
        _isSaveContextEnabled.value = isEnabled
    }

    fun onContextDirtyStateChanged(isDirty: Boolean) {
        _isContextScreenDirty.value = isDirty
    }

    fun confirmExitContextDialogAction() {
        _pendingNavigationAction.value?.invoke()
        _pendingNavigationAction.value = null
    }

    fun dismissExitContextDialog() {
        _pendingNavigationAction.value = null
    }

    fun markContextAsSaved() {
        _isContextScreenDirty.value = false
    }

    fun setPendingNavigationAction(action: () -> Unit) {
        viewModelScope.launch {
            _pendingNavigationAction.value = action
        }
    }

    fun setLaunchPickerAction(action: (() -> Unit)?) { _currentStageLaunchPickerAction.value = action }
    // REMOVIDO: setLaunchMusicAction

    fun setAnalyzeAction(action: (() -> Unit)?) { _currentStageAnalyzeAction.value = action }
    fun setCreateNarrativeAction(action: (() -> Unit)?) { _currentStageCreateNarrativeAction.value = action }
    fun setGenerateAudioAction(action: (() -> Unit)?) { _currentStageGenerateAudioAction.value = action }
    fun setGenerateScenesAction(action: (() -> Unit)?) { _currentStageGenerateScenesAction.value = action }
    fun setRecordVideoAction(action: (() -> Unit)?) { _currentStageRecordVideoAction.value = action }
    fun setShareVideoAction(action: (() -> Unit)?) { _currentShareVideoAction.value = action }

    fun updateProcessingState(isProcessing: Boolean, progress: Float) {
        _isCurrentStageProcessing.value = isProcessing
        _currentStageNumericProgress.value = progress
    }

    fun updateProgressText(text: String) {
        _currentStageProgressText.value = text
    }

    fun triggerProjectSave() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(appContext)
                Log.i(TAG, "Estado do projeto salvo via VideoWorkflowViewModel.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao salvar projeto via VideoWorkflowViewModel.", e)
            }
        }
    }
}