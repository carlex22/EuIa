// File: euia/viewmodel/VideoProjectViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.data.*
import com.carlex.euia.utils.OverlayManager
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.viewmodel.helper.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enum para definir o propósito do diálogo de seleção de assets,
 * permitindo que um único diálogo seja usado para múltiplas ações.
 */
enum class AssetSelectionPurpose {
    REPLACE_GENERATED_ASSET, // Para substituir o asset principal da cena
    UPDATE_REFERENCE_IMAGE,  // Para apenas atualizar a imagem de referência
    SELECT_CLOTHING_IMAGE    // Para selecionar o figurino para a troca de roupa
}

class VideoProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VideoProjectViewModel"
    private val applicationContext: Context = application.applicationContext

    // Managers e Serviços
    private val authViewModel = AuthViewModel(application)
    private val workManager = WorkManager.getInstance(applicationContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(application)

    // Classes Auxiliares (Helpers/Services)
    private val projectAssetManager = ProjectAssetManager(applicationContext, videoPreferencesDataStoreManager)
    private val sceneRepository = SceneRepository(VideoProjectDataStoreManager(application), viewModelScope)
    private val pixabayHelper = PixabayHelper(applicationContext, workManager, videoPreferencesDataStoreManager)
    private val sceneGenerationService = SceneGenerationService(
        applicationContext,
        UserInfoDataStoreManager(application),
        AudioDataStoreManager(application),
        VideoDataStoreManager(application),
        VideoProjectDataStoreManager(application)
    )
    private val sceneWorkerManager = SceneWorkerManager(applicationContext, workManager, json)
    private val sceneOrchestrator = SceneOrchestrator(
        context = applicationContext,
        scope = viewModelScope,
        sceneGenerationService = sceneGenerationService,
        sceneRepository = sceneRepository,
        sceneWorkerManager = sceneWorkerManager,
        creditChecker = { cost -> authViewModel.checkAndDeductCredits(TaskType.IMAGE) }
    )
    private val scenePreviewOrchestrator: ScenePreviewOrchestrator

    // --- StateFlows para a UI ---
    val sceneLinkDataList: StateFlow<List<SceneLinkData>> = sceneRepository.sceneLinkDataList
    private val _isUiReady = MutableStateFlow(true)
    val isUiReady: StateFlow<Boolean> = _isUiReady.asStateFlow()
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    private val _isProcessingGlobalScenes = MutableStateFlow(false)
    val isProcessingGlobalScenes: StateFlow<Boolean> = _isProcessingGlobalScenes.asStateFlow()
    val isGeneratingScene: StateFlow<Boolean> = isProcessingGlobalScenes
    private val _globalSceneError = MutableStateFlow<String?>(null)
    val globalSceneError: StateFlow<String?> = _globalSceneError.asStateFlow()
    private val _showSceneConfirmationDialogVM = MutableStateFlow(false)
    val showSceneGenerationConfirmationDialog: StateFlow<Boolean> = _showSceneConfirmationDialogVM.asStateFlow()
    val showImageBatchCostConfirmationDialog: StateFlow<Boolean> = sceneOrchestrator.batchState.map { it is BatchGenerationState.AwaitingCostConfirmation }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
    val pendingImageBatchCount: StateFlow<Int> = sceneOrchestrator.batchState.map {
        if (it is BatchGenerationState.AwaitingCostConfirmation) it.count else 0
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val pendingImageBatchCost: StateFlow<Long> = sceneOrchestrator.batchState.map {
        if (it is BatchGenerationState.AwaitingCostConfirmation) it.cost else 0L
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)
    val pixabayUnifiedResults: StateFlow<List<PixabayAsset>> = pixabayHelper.searchResults
    val isSearchingPixabay: StateFlow<Boolean> = pixabayHelper.isSearching
    private val _showPixabaySearchDialogForSceneId = MutableStateFlow<String?>(null)
    val showPixabaySearchDialogForSceneId: StateFlow<String?> = _showPixabaySearchDialogForSceneId.asStateFlow()
    private val _pixabaySearchQuery = MutableStateFlow("")
    val pixabaySearchQuery: StateFlow<String> = _pixabaySearchQuery.asStateFlow()
    val currentImagensReferenciaStateFlow: StateFlow<List<ImagemReferencia>> = VideoDataStoreManager(application).imagensReferenciaJson.map { jsonString ->
        try {
            if (jsonString.isNotBlank() && jsonString != "[]") Json.decodeFromString<List<ImagemReferencia>>(jsonString) else emptyList()
        } catch (e: Exception) { emptyList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    private val _currentlyPlayingSceneId = MutableStateFlow<String?>(null)
    val currentlyPlayingSceneId: StateFlow<String?> = _currentlyPlayingSceneId.asStateFlow()
    private val _isGeneratingPreviewForSceneId = MutableStateFlow<String?>(null)
    val isGeneratingPreviewForSceneId: StateFlow<String?> = _isGeneratingPreviewForSceneId.asStateFlow()
    private val _availableProjectAssets = MutableStateFlow<List<ProjectAsset>>(emptyList())
    val availableProjectAssets: StateFlow<List<ProjectAsset>> = _availableProjectAssets.asStateFlow()
    private val _availableReferenceImageAssets = MutableStateFlow<List<ProjectAsset>>(emptyList())
    val availableReferenceImageAssets: StateFlow<List<ProjectAsset>> = _availableReferenceImageAssets.asStateFlow()
    private val _sceneIdToRecreateImage = MutableStateFlow<String?>(null)
    val sceneIdToRecreateImage: StateFlow<String?> = _sceneIdToRecreateImage.asStateFlow()
    private val _promptForRecreateImage = MutableStateFlow<String?>(null)
    val promptForRecreateImage: StateFlow<String?> = _promptForRecreateImage.asStateFlow()
    private val _sceneForPromptEdit = MutableStateFlow<SceneLinkData?>(null)
    val sceneForPromptEdit: StateFlow<SceneLinkData?> = _sceneForPromptEdit.asStateFlow()
    private val _assetSelectionState = MutableStateFlow<Pair<String, AssetSelectionPurpose>?>(null)
    val assetSelectionState: StateFlow<Pair<String, AssetSelectionPurpose>?> = _assetSelectionState.asStateFlow()

   /*private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
        val isAnyWorkRunning = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (!isAnyWorkRunning ){
            OverlayManager.hideOverlay(applicationContext)
        } else {
            val runningCount = workInfos.count { it.state == WorkInfo.State.RUNNING }
            val enqueuedCount = workInfos.count { it.state == WorkInfo.State.ENQUEUED }
            val totalActiveCount = runningCount + enqueuedCount
            val quantidadeDeRegistros = sceneLinkDataList.value.size
            val quantidadeOk = sceneLinkDataList.value.count { it.imagemGeradaPath != null }
            val progresso = ((quantidadeOk.toFloat() / quantidadeDeRegistros.toFloat()) * 100).coerceIn(0f, 100f)
            OverlayManager.showOverlay(applicationContext, "$totalActiveCount/$quantidadeDeRegistros",  progresso.toInt())
        }
    }*/
    
    

    init {
                
       /* workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING)
            .observeForever(workInfoObserver)
        workManager.getWorkInfosByTagLiveData(WorkerTags.SCENE_PREVIEW_WORK)
            .observeForever(previewWorkObserver)*/
        
        
        scenePreviewOrchestrator = ScenePreviewOrchestrator(applicationContext, sceneRepository, sceneWorkerManager, viewModelScope)
        scenePreviewOrchestrator.startObserving()
        
        loadProjectAssets()

        viewModelScope.launch {
            sceneOrchestrator.batchState.collect { state ->
                _isProcessingGlobalScenes.value = false
                when (state) {
                    is BatchGenerationState.Error -> _globalSceneError.value = state.message
                    is BatchGenerationState.Finished -> postSnackbarMessage("Processo de geração concluído ou enfileirado.")
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        scenePreviewOrchestrator.stopObserving()
         
    }

    fun onSnackbarMessageShown() { _snackbarMessage.value = null }
    fun postSnackbarMessage(message: String) { _snackbarMessage.value = message }

    fun requestFullSceneGenerationProcess() {
        if (isProcessingGlobalScenes.value) {
            postSnackbarMessage("Aguarde, um processo global de cenas já está em andamento.")
            return
        }
        if (sceneLinkDataList.value.isNotEmpty()) {
             postSnackbarMessage("novas cenas")
            _showSceneConfirmationDialogVM.value = true
            performFullSceneGeneration()
        } else {
            performFullSceneGeneration()
        }
    }

    fun confirmAndProceedWithSceneGeneration() {
        postSnackbarMessage("novas cenas")
        _showSceneConfirmationDialogVM.value = true
        viewModelScope.launch {
            performFullSceneGeneration()
        }
    }

    private fun performFullSceneGeneration() {
        _isProcessingGlobalScenes.value = true
        OverlayManager.showOverlay(applicationContext, "🎬", 0)
        sceneOrchestrator.generateFullSceneStructure()
    }

    fun cancelSceneGenerationProcess() {
        sceneOrchestrator.cancelFullSceneStructureGeneration()
        _isProcessingGlobalScenes.value = false
        postSnackbarMessage("Geração de estrutura de cenas cancelada.")
    }

    fun confirmImageBatchGeneration() = sceneOrchestrator.confirmAndEnqueueBatchGeneration()
    fun cancelImageBatchGeneration() = sceneOrchestrator.cancelBatchGeneration()
    fun cancelSceneGenerationDialog() { _showSceneConfirmationDialogVM.value = false }

    fun triggerBatchPixabayVideoSearch() {
        viewModelScope.launch {
            val currentState = sceneOrchestrator.batchState.value
            if (currentState is BatchGenerationState.AwaitingCostConfirmation) {
                sceneOrchestrator.cancelBatchGeneration()
                postSnackbarMessage(applicationContext.getString(R.string.toast_batch_video_search_started))
                
                currentState.scenes.forEach { scene ->
                    scenePreviewOrchestrator.requestPreviewForScene(scene.id)
                    delay(200)
                }
            }
        }
    }

    fun generateImageForScene(sceneId: String, prompt: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (isSceneBusy(scene)) {
                postSnackbarMessage(applicationContext.getString(R.string.status_scene_already_processing, scene.cena ?: sceneId.take(4)))
                return@launch
            }
            if (prompt.isBlank()) {
                postSnackbarMessage(applicationContext.getString(R.string.error_empty_prompt_for_image_gen))
                return@launch
            }
            
            val refImages = findReferenceImagesForScene(scene)
            sceneWorkerManager.enqueueImageGeneration(sceneId, prompt, refImages)
            sceneRepository.updateScene(sceneId) { it.copy(isGenerating = true, generationAttempt = it.generationAttempt + 1, generationErrorMessage = null) }
        }
    }

    fun changeClothesForSceneWithSpecificReference(sceneId: String, chosenReferenceImagePath: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (isSceneBusy(scene)) {
                postSnackbarMessage("A cena já está processando.")
                return@launch
            }
            
            sceneWorkerManager.enqueueClothesChange(sceneId, chosenReferenceImagePath)
            sceneRepository.updateScene(sceneId) { it.copy(isChangingClothes = true, clothesChangeAttempt = it.clothesChangeAttempt + 1, generationErrorMessage = null) }
            postSnackbarMessage("Troca de figurino iniciada para a cena ${scene.cena}.")
        }
    }

    fun generateVideoForScene(sceneId: String, videoPromptFromDialog: String, sourceImagePathFromSceneParameter: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (isSceneBusy(scene)) return@launch

            sceneWorkerManager.enqueueVideoGeneration(sceneId, videoPromptFromDialog, sourceImagePathFromSceneParameter)
            sceneRepository.updateScene(sceneId) {
                it.copy(isGeneratingVideo = true, promptVideo = videoPromptFromDialog, generationErrorMessage = null)
            }
        }
    }

    fun cancelClothesChangeForScene(sceneId: String) {
        sceneWorkerManager.cancelAllProcessingForScene(sceneId)
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) {
                it.copy(isChangingClothes = false, generationErrorMessage = applicationContext.getString(R.string.message_cancelled_by_user))
            }
        }
    }

    fun cancelGenerationForScene(sceneId: String) {
        sceneWorkerManager.cancelAllProcessingForScene(sceneId)
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) {
                it.copy(
                    isGenerating = false, isGeneratingVideo = false, isChangingClothes = false,
                    generationErrorMessage = applicationContext.getString(R.string.message_cancelled_by_user)
                )
            }
            postSnackbarMessage(applicationContext.getString(R.string.toast_generation_cancelled_for_scene, sceneId.take(4)))
        }
    }

    fun onShowPixabaySearchDialog(sceneId: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId }
            _pixabaySearchQuery.value = scene?.promptVideo ?: ""
            pixabayHelper.clearSearchResults()
            _showPixabaySearchDialogForSceneId.value = sceneId
            if (_pixabaySearchQuery.value.isNotBlank()) searchPixabayAssets()
        }
    }

    fun onDismissPixabaySearchDialog() {
        _showPixabaySearchDialogForSceneId.value = null
        pixabayHelper.clearSearchResults()
    }

    fun onPixabaySearchQueryChanged(query: String) { _pixabaySearchQuery.value = query }
    fun searchPixabayAssets() { viewModelScope.launch { pixabayHelper.search(_pixabaySearchQuery.value) } }

    fun onPixabayAssetSelected(sceneId: String, asset: PixabayAsset) {
        viewModelScope.launch {
            pixabayHelper.downloadAndAssignAssetToScene(sceneId, asset)
            onDismissPixabaySearchDialog()
            postSnackbarMessage("Download do asset iniciado em segundo plano...")
        }
    }

    fun onPlayPausePreviewClicked(scene: SceneLinkData) {
        viewModelScope.launch {
            if (_currentlyPlayingSceneId.value == scene.id) {
                stopPlayback()
            } else if (!scene.videoPreviewPath.isNullOrBlank()){
                _currentlyPlayingSceneId.value = scene.id
            } else {
                scenePreviewOrchestrator.requestPreviewForScene(scene.id)
                postSnackbarMessage(applicationContext.getString(R.string.toast_preview_generation_queued))
            }
        }
    }

    fun stopPlayback() {
        if (_currentlyPlayingSceneId.value != null) _currentlyPlayingSceneId.value = null
    }
    
    fun updatePromptAndGenerateImage(sceneId: String, newPrompt: String) {
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) {
                it.copy(
                    promptGeracao = newPrompt,
                    imagemGeradaPath = null, pathThumb = null, videoPreviewPath = null,
                    isGenerating = false, aprovado = false, generationErrorMessage = null
                )
            }
            generateImageForScene(sceneId, newPrompt)
            postSnackbarMessage(applicationContext.getString(R.string.toast_image_generation_started_for_scene))
        }
    }
   
    private fun replaceGeneratedImageWithReference(sceneId: String, chosenAsset: ProjectAsset) {
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) { scene ->
                scene.copy(
                    imagemGeradaPath = chosenAsset.finalAssetPath,
                    pathThumb = chosenAsset.thumbnailPath,
                    isGenerating = false,
                    isGeneratingVideo = false,
                    generationErrorMessage = null,
                    videoPreviewPath = null,
                    aprovado = true
                )
            }
            postSnackbarMessage(applicationContext.getString(R.string.scene_item_toast_asset_replaced))
        }
    }
    
    private fun updateSceneReferenceImage(sceneId: String, newReferenceAsset: ProjectAsset) {
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) {
                it.copy(
                    imagemReferenciaPath = newReferenceAsset.thumbnailPath,
                    descricaoReferencia = newReferenceAsset.displayName
                )
            }
            postSnackbarMessage("Imagem de referência atualizada.")
        }
    }
    
    fun loadProjectAssets() {
        viewModelScope.launch {
            _availableProjectAssets.value = projectAssetManager.loadAllProjectAssets()
            _availableReferenceImageAssets.value = projectAssetManager.loadReferenceImageAssets()
        }
    }

    private suspend fun findReferenceImagesForScene(scene: SceneLinkData?): List<ImagemReferencia> {
        if (scene == null || scene.imagemReferenciaPath.isBlank()) return emptyList()
        val globalImages = currentImagensReferenciaStateFlow.first()
        return globalImages.filter { it.path == scene.imagemReferenciaPath }
    }
    
    private fun isSceneBusy(scene: SceneLinkData): Boolean {
        return scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo
    }

    fun clearGlobalSceneError() {
        _globalSceneError.value = null
    }

    fun requestImageGenerationWithConfirmation(sceneId: String, prompt: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId }
            if (scene?.imagemGeradaPath?.isNotBlank() == true && scene.pathThumb == null ) {
                _sceneIdToRecreateImage.value = sceneId
                _promptForRecreateImage.value = prompt
            } else {
                generateImageForScene(sceneId, prompt)
            }
        }
    }

    fun dismissRecreateImageDialog() {
        _sceneIdToRecreateImage.value = null
        _promptForRecreateImage.value = null
    }

    fun confirmAndProceedWithImageRecreation() {
        val sceneId = _sceneIdToRecreateImage.value
        val prompt = _promptForRecreateImage.value
        if (sceneId != null && prompt != null) {
            generateImageForScene(sceneId, prompt)
        }
        dismissRecreateImageDialog()
    }
    
    fun triggerPromptEditForScene(scene: SceneLinkData) {
        _sceneForPromptEdit.value = scene
    }

    fun dismissPromptEditDialog() {
        _sceneForPromptEdit.value = null
    }

    fun triggerAssetSelectionForScene(sceneId: String, purpose: AssetSelectionPurpose) {
        _assetSelectionState.value = Pair(sceneId, purpose)
    }

    fun dismissAssetSelectionDialog() {
        _assetSelectionState.value = null
    }

    fun handleAssetSelection(sceneId: String, selectedAsset: ProjectAsset, purpose: AssetSelectionPurpose) {
        when (purpose) {
            AssetSelectionPurpose.REPLACE_GENERATED_ASSET -> replaceGeneratedImageWithReference(sceneId, selectedAsset)
            AssetSelectionPurpose.UPDATE_REFERENCE_IMAGE -> updateSceneReferenceImage(sceneId, selectedAsset)
            AssetSelectionPurpose.SELECT_CLOTHING_IMAGE -> {
                changeClothesForSceneWithSpecificReference(sceneId, selectedAsset.finalAssetPath)
            }
        }
        dismissAssetSelectionDialog()
    }
    
    fun triggerClothesChangeForScene(sceneId: String) {
        _assetSelectionState.value = Pair(sceneId, AssetSelectionPurpose.SELECT_CLOTHING_IMAGE)
    }
}