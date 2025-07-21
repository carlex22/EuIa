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

class VideoProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VideoProjectViewModel"
    private val applicationContext: Context = application.applicationContext

    // Managers e Serviços
    private val authViewModel = AuthViewModel(application)
    private val workManager = WorkManager.getInstance(applicationContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(application)

    // Classes Auxiliares (Helpers/Services)
    private val sceneRepository = SceneRepository(VideoProjectDataStoreManager(application), viewModelScope)
    private val pixabayHelper = PixabayHelper(applicationContext, workManager, videoPreferencesDataStoreManager)
    private val sceneGenerationService = SceneGenerationService(
        applicationContext,
        UserInfoDataStoreManager(application),
        AudioDataStoreManager(application),
        VideoDataStoreManager(application)
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
    private val _sceneIdToRecreateImage = MutableStateFlow<String?>(null)
    val sceneIdToRecreateImage: StateFlow<String?> = _sceneIdToRecreateImage.asStateFlow()
    private val _promptForRecreateImage = MutableStateFlow<String?>(null)
    val promptForRecreateImage: StateFlow<String?> = _promptForRecreateImage.asStateFlow()
    private val _sceneIdForReferenceChangeDialog = MutableStateFlow<String?>(null)
    val sceneIdForReferenceChangeDialog: StateFlow<String?> = _sceneIdForReferenceChangeDialog.asStateFlow()
    
    // --- Lógica de Observação ---
    private val workInfoObserver = Observer<List<WorkInfo>> { /* ... */ }
    private val previewWorkObserver = Observer<List<WorkInfo>> { /* ... */ }

    init {
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING).observeForever(workInfoObserver)
        workManager.getWorkInfosByTagLiveData(WorkerTags.SCENE_PREVIEW_WORK).observeForever(previewWorkObserver)
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
        workManager.getWorkInfosByTagLiveData(WorkerTags.SCENE_PREVIEW_WORK).removeObserver(previewWorkObserver)
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING).removeObserver(workInfoObserver)
    }

    // --- MÉTODOS PÚBLICOS PARA A UI ---
    fun onSnackbarMessageShown() { _snackbarMessage.value = null }
    fun postSnackbarMessage(message: String) { _snackbarMessage.value = message }

    fun requestFullSceneGenerationProcess() {
        if (isProcessingGlobalScenes.value) {
            postSnackbarMessage("Aguarde, um processo global de cenas já está em andamento.")
            return
        }
        if (sceneLinkDataList.value.isNotEmpty()) {
            _showSceneConfirmationDialogVM.value = true
        } else {
            performFullSceneGeneration()
        }
    }

    fun confirmAndProceedWithSceneGeneration() {
        _showSceneConfirmationDialogVM.value = false
        viewModelScope.launch {
            sceneRepository.clearAllScenes()
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
    
    // <<< INÍCIO DA CORREÇÃO 1 >>>
    fun cancelSceneGenerationDialog() {
        _showSceneConfirmationDialogVM.value = false
    }
    
    fun triggerBatchPixabayVideoSearch() {
        viewModelScope.launch {
            val currentState = sceneOrchestrator.batchState.value
            if (currentState is BatchGenerationState.AwaitingCostConfirmation) {
                sceneOrchestrator.cancelBatchGeneration() // Cancela o diálogo de custo
                postSnackbarMessage(applicationContext.getString(R.string.toast_batch_video_search_started))
                
                currentState.scenes.forEach { scene ->
                    sceneWorkerManager.enqueuePreviewGeneration(scene.id) // Simplificado, poderia ser um worker específico
                    delay(200)
                }
            }
        }
    }
    // <<< FIM DA CORREÇÃO 1 >>>
    
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
            if (isSceneBusy(scene)) return@launch
            
            sceneWorkerManager.enqueueClothesChange(sceneId, chosenReferenceImagePath)
            sceneRepository.updateScene(sceneId) { it.copy(isChangingClothes = true, clothesChangeAttempt = it.clothesChangeAttempt + 1, generationErrorMessage = null) }
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
            sceneRepository.updateScene(sceneId) { it.copy(isGeneratingVideo = true, generationErrorMessage = null) }
            pixabayHelper.downloadAndAssignAssetToScene(sceneId, asset)
            onDismissPixabaySearchDialog()
            postSnackbarMessage("Download do asset iniciado...")
        }
    }

    fun onPlayPausePreviewClicked(scene: SceneLinkData) {
        viewModelScope.launch {
            if (_currentlyPlayingSceneId.value == scene.id) {
                stopPlayback()
            } else if (!scene.videoPreviewPath.isNullOrBlank()){
                _currentlyPlayingSceneId.value = scene.id
            } else {
                sceneWorkerManager.enqueuePreviewGeneration(scene.id)
                postSnackbarMessage(applicationContext.getString(R.string.toast_preview_generation_queued))
            }
        }
    }

    fun stopPlayback() {
        if (_currentlyPlayingSceneId.value != null) _currentlyPlayingSceneId.value = null
    }

    fun updateScenePrompt(id: String, newPrompt: String) {
       viewModelScope.launch {
           sceneRepository.updateScene(id) {
               it.copy(
                   promptGeracao = newPrompt,
                   imagemGeradaPath = null, pathThumb = null, videoPreviewPath = null,
                   isGenerating = false, aprovado = false, generationErrorMessage = null
               )
           }
       }
   }
   
    fun replaceGeneratedImageWithReference(sceneId: String, chosenAsset: ProjectAsset) {
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
    
    fun updateSceneReferenceImage(sceneId: String, newReferenceImage: ImagemReferencia) {
        viewModelScope.launch {
            sceneRepository.updateScene(sceneId) { scene ->
                val isVideo = newReferenceImage.pathVideo != null
                scene.copy(
                    imagemReferenciaPath = newReferenceImage.path,
                    descricaoReferencia = newReferenceImage.descricao,
                    imagemGeradaPath = if (isVideo) newReferenceImage.pathVideo else null,
                    pathThumb = if (isVideo) newReferenceImage.path else null,
                    isGenerating = if (!isVideo) scene.promptGeracao?.isNotBlank() == true else false,
                    aprovado = isVideo,
                    generationErrorMessage = null, videoPreviewPath = null
                )
            }
        }
    }
    
    fun loadProjectAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
                if (projectDirName.isBlank()) {
                    _availableProjectAssets.value = emptyList()
                    return@launch
                }
                
                val projectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
                val assetList = mutableListOf<ProjectAsset>()

                val subDirsToScan = listOf("ref_images", "pixabay_images", "pixabay_videos", "downloaded_videos", "gemini_generated_images")
                
                for (subDirName in subDirsToScan) {
                    val dir = File(projectDir, subDirName)
                    if (!dir.exists() || !dir.isDirectory) continue
                    
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            if (subDirName == "ref_images" && file.name.startsWith("thumb_", true)) {
                                // Ignora thumbnails
                            } else {
                                val isVideo = file.extension.equals("mp4", true) || file.extension.equals("webm", true)
                                var thumbPath = file.absolutePath
                                
                                if (isVideo) {
                                    val expectedThumbName = "thumb_${file.nameWithoutExtension}.webp"
                                    val thumbFileInRefDir = File(File(projectDir, "ref_images"), expectedThumbName)
                                    if(thumbFileInRefDir.exists()) {
                                        thumbPath = thumbFileInRefDir.absolutePath
                                    }
                                }
                                
                                assetList.add(ProjectAsset(
                                    displayName = file.name,
                                    finalAssetPath = file.absolutePath,
                                    thumbnailPath = thumbPath,
                                    isVideo = isVideo
                                ))
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    _availableProjectAssets.value = assetList.sortedBy { it.displayName }
                }
                Log.d(TAG, "Carregados ${_availableProjectAssets.value.size} assets do projeto '$projectDirName'.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar assets do projeto.", e)
                withContext(Dispatchers.Main) {
                    _availableProjectAssets.value = emptyList()
                }
            }
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
    
    fun triggerSelectReferenceImageForScene(sceneId: String) {
        _sceneIdForReferenceChangeDialog.value = sceneId
    }

    fun dismissReferenceImageSelectionDialog() {
        _sceneIdForReferenceChangeDialog.value = null
    }
}