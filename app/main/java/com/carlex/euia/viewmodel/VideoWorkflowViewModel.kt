// File: viewmodel/VideoWorkflowViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.AppDestinations
import com.carlex.euia.R // Para strings
import com.carlex.euia.WorkflowStage
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log

/**
 * ViewModel compartilhado que gerencia o estado e a lógica do fluxo de trabalho (workflow)
 * de criação de vídeo.
 *
 * Responsabilidades:
 * - Manter a lista de etapas ([WorkflowStage]) do fluxo.
 * - Controlar o índice da aba atualmente selecionada ([selectedWorkflowTabIndex]).
 * - Consolidar o estado de processamento da aba ativa ([isCurrentStageProcessing], [currentStageProgressText], [currentStageNumericProgress]).
 * - Armazenar e fornecer os callbacks para as ações dos botões da BottomAppBar do workflow,
 *   que são definidos pela [VideoCreationWorkflowScreen] com base na aba ativa.
 * - Gerenciar o estado de "dirty" da tela de Contexto e a lógica para exibir o diálogo de confirmação
 *   ao tentar sair dela com alterações não salvas.
 * - Acionar o salvamento do estado do projeto através do [ProjectPersistenceManager].
 *
 * @param application A instância da aplicação, necessária para [AndroidViewModel] e acesso ao contexto.
 */
class VideoWorkflowViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val TAG = "VideoWorkflowVM"

    val workflowStages: List<WorkflowStage> = listOf(
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_context), AppDestinations.WORKFLOW_STAGE_CONTEXT),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_images), AppDestinations.WORKFLOW_STAGE_IMAGES),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_information), AppDestinations.WORKFLOW_STAGE_INFORMATION),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_narrative), AppDestinations.WORKFLOW_STAGE_NARRATIVE),
        WorkflowStage(appContext.getString(R.string.workflow_stage_title_scenes), AppDestinations.WORKFLOW_STAGE_SCENES),
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

    // Callbacks para ações dos botões das abas
    private val _currentStageLaunchPickerAction = MutableStateFlow<(() -> Unit)?>(null)
    val currentStageLaunchPickerAction: StateFlow<(() -> Unit)?> = _currentStageLaunchPickerAction.asStateFlow()

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

    // Para a aba de Contexto
    private val _actualSaveContextAction = MutableStateFlow<(() -> Unit)?>(null)
    val actualSaveContextAction: StateFlow<(() -> Unit)?> = _actualSaveContextAction.asStateFlow()

    private val _isSaveContextEnabled = MutableStateFlow(false)
    val isSaveContextEnabled: StateFlow<Boolean> = _isSaveContextEnabled.asStateFlow()

    private val _isContextScreenDirty = MutableStateFlow(false)
    val isContextScreenDirty: StateFlow<Boolean> = _isContextScreenDirty.asStateFlow()

    // Guarda a ação de navegação pendente se o diálogo de confirmação for exibido.
    private val _pendingNavigationAction = MutableStateFlow<(() -> Unit)?>(null)
    val showConfirmExitContextDialog: StateFlow<(() -> Unit)?> = _pendingNavigationAction.asStateFlow() // Renomeado para clareza na UI

    init {
        Log.d(TAG, "VideoWorkflowViewModel Inicializado.")
    }

    fun updateSelectedTabIndex(newIndex: Int) {
        _selectedWorkflowTabIndex.value = newIndex
    }

    /**
     * Tenta mudar para a [targetIndex] da aba.
     * Se a aba atual for a de Contexto e estiver com alterações não salvas,
     * armazena a ação de mudança de aba e sinaliza para a UI mostrar um diálogo de confirmação.
     * Caso contrário, atualiza o índice da aba diretamente.
     */
    fun attemptToChangeTab(targetIndex: Int) {
        if (_isCurrentStageProcessing.value && targetIndex != _selectedWorkflowTabIndex.value) {
            // Não permite mudar de aba se uma operação estiver em andamento na aba atual.
            // Poderia mostrar um Snackbar aqui.
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

    /**
     * Chamado pela [ContextInfoContent] para fornecer a ação de salvamento e seu estado de habilitação.
     */
    fun onContextSaveActionProvided(action: () -> Unit, isEnabled: Boolean) {
        _actualSaveContextAction.value = action
        _isSaveContextEnabled.value = isEnabled
    }

    /**
     * Chamado pela [ContextInfoContent] para atualizar o estado "dirty" (se há alterações não salvas).
     */
    fun onContextDirtyStateChanged(isDirty: Boolean) {
        _isContextScreenDirty.value = isDirty
    }

    /**
     * Executa a ação de navegação pendente (que estava aguardando a resolução do diálogo)
     * e limpa a ação pendente.
     */
    fun confirmExitContextDialogAction() {
        _pendingNavigationAction.value?.invoke()
        _pendingNavigationAction.value = null
    }

    /**
     * Limpa a ação de navegação pendente sem executá-la (usuário cancelou o diálogo).
     */
    fun dismissExitContextDialog() {
        _pendingNavigationAction.value = null
    }

    /**
     * Marca a tela de Contexto como não "dirty", geralmente após um salvamento.
     */
    fun markContextAsSaved() {
        _isContextScreenDirty.value = false
    }

    /**
     * Define a ação de navegação pendente. Usado quando uma navegação externa
     * (do Drawer ou botão Voltar do sistema) é solicitada enquanto a tela de Contexto está "dirty".
     */
    fun setPendingNavigationAction(action: () -> Unit) {
        viewModelScope.launch {
            _pendingNavigationAction.value = action
        }
    }

    // --- Funções para definir os callbacks das ações da BottomBar ---
    fun setLaunchPickerAction(action: (() -> Unit)?) { _currentStageLaunchPickerAction.value = action }
    fun setAnalyzeAction(action: (() -> Unit)?) { _currentStageAnalyzeAction.value = action }
    fun setCreateNarrativeAction(action: (() -> Unit)?) { _currentStageCreateNarrativeAction.value = action }
    fun setGenerateAudioAction(action: (() -> Unit)?) { _currentStageGenerateAudioAction.value = action }
    fun setGenerateScenesAction(action: (() -> Unit)?) { _currentStageGenerateScenesAction.value = action }
    fun setRecordVideoAction(action: (() -> Unit)?) { _currentStageRecordVideoAction.value = action }
    fun setShareVideoAction(action: (() -> Unit)?) { _currentShareVideoAction.value = action }

    /**
     * Atualiza o estado de processamento e progresso numérico da aba atual.
     */
    fun updateProcessingState(isProcessing: Boolean, progress: Float) {
        _isCurrentStageProcessing.value = isProcessing
        _currentStageNumericProgress.value = progress
    }

    /**
     * Atualiza o texto de progresso da aba atual.
     */
    fun updateProgressText(text: String) {
        _currentStageProgressText.value = text
    }

    /**
     * Aciona o salvamento do estado completo do projeto.
     */
    fun triggerProjectSave() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(appContext)
                Log.i(TAG, "Estado do projeto salvo via VideoWorkflowViewModel.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao salvar projeto via VideoWorkflowViewModel.", e)
                // O feedback ao usuário (Toast) pode ser emitido por um SharedFlow
                // para ser observado pela UI (AppNavigationHost).
                // Por ora, o Toast está no AppNavigationHost.
            }
        }
    }
}