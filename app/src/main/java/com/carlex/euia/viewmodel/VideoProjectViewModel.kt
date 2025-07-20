// File: euia/viewmodel/VideoProjectViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import com.carlex.euia.BuildConfig
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ExistingWorkPolicy
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.data.*
import com.carlex.euia.prompts.CreateScenes
import com.carlex.euia.prompts.CreateScenesChat
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.worker.VideoProcessingWorker
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_CHOSEN_REFERENCE_IMAGE_PATH
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_IMAGE_GEN_PROMPT
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_SCENE_ID
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_TASK_TYPE
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TASK_TYPE_CHANGE_CLOTHES
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TASK_TYPE_GENERATE_IMAGE
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TASK_TYPE_GENERATE_VIDEO
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TAG_PREFIX_SCENE_PROCESSING
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TAG_PREFIX_SCENE_CLOTHES_PROCESSING
import com.carlex.euia.worker.VideoProcessingWorker.Companion.TAG_PREFIX_SCENE_VIDEO_PROCESSING
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_IMAGENS_REFERENCIA_JSON_INPUT
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_SOURCE_IMAGE_PATH_FOR_VIDEO
import com.carlex.euia.worker.VideoProcessingWorker.Companion.KEY_VIDEO_GEN_PROMPT
import com.carlex.euia.utils.*
import com.carlex.euia.api.PixabayApiClient
import com.carlex.euia.api.PixabayVideo
import com.carlex.euia.managers.AppConfigManager
import com.carlex.euia.worker.ImageDownloadWorker
import com.carlex.euia.worker.VideoDownloadWorker
import com.carlex.euia.worker.PixabayVideoSearchWorker
import com.carlex.euia.worker.ScenePreviewWorker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

// <<< INÍCIO DA MUDANÇA: Estrutura de dados unificada para os resultados da busca >>>
enum class PixabayAssetType { VIDEO, IMAGE }
data class PixabayAsset(
    val type: PixabayAssetType,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val tags: String,
    val id: String = thumbnailUrl // Usar a URL como ID único
)
// <<< FIM DA MUDANÇA >>>

class VideoProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VideoProjectViewModel"

    private val authViewModel = AuthViewModel(application)
    private val userInfoDataStoreManager = UserInfoDataStoreManager(application)
    private val projectDataStoreManager = VideoProjectDataStoreManager(application)
    private val audioDataStoreManager = AudioDataStoreManager(application)
    private val videoDataStoreManager = VideoDataStoreManager(application)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(application)

    private val applicationContext: Context = application.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var sceneGenerationJob: Job? = null
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isUiReady = MutableStateFlow(true)
    val isUiReady: StateFlow<Boolean> = _isUiReady.asStateFlow()

    var isGeneratingScene: StateFlow<Boolean> = projectDataStoreManager.isCurrentlyGeneratingScene
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isProcessingGlobalScenes = MutableStateFlow(false)
    val isProcessingGlobalScenes: StateFlow<Boolean> =  _isProcessingGlobalScenes.asStateFlow()

    private val _globalSceneError = MutableStateFlow<String?>(null)
    val globalSceneError: StateFlow<String?> = _globalSceneError.asStateFlow()

    private val _showSceneConfirmationDialogVM = MutableStateFlow(false)
    val showSceneGenerationConfirmationDialog: StateFlow<Boolean> = _showSceneConfirmationDialogVM.asStateFlow()

    private val _showImageBatchCostConfirmationDialog = MutableStateFlow(false)
    val showImageBatchCostConfirmationDialog: StateFlow<Boolean> = _showImageBatchCostConfirmationDialog.asStateFlow()

    private val _pendingImageBatchCount = MutableStateFlow(0)
    val pendingImageBatchCount: StateFlow<Int> = _pendingImageBatchCount.asStateFlow()

    private val _pendingImageBatchCost = MutableStateFlow(0L)
    val pendingImageBatchCost: StateFlow<Long> = _pendingImageBatchCost.asStateFlow()

    private var _pendingSceneListForGeneration: List<SceneLinkData>? = null

    private val _sceneLinkDataList = MutableStateFlow<List<SceneLinkData>>(emptyList())
    val sceneLinkDataList: StateFlow<List<SceneLinkData>> = _sceneLinkDataList.asStateFlow()

    private val _showPixabaySearchDialogForSceneId = MutableStateFlow<String?>(null)
    val showPixabaySearchDialogForSceneId: StateFlow<String?> = _showPixabaySearchDialogForSceneId.asStateFlow()

    private val _pixabaySearchQuery = MutableStateFlow("")
    val pixabaySearchQuery: StateFlow<String> = _pixabaySearchQuery.asStateFlow()

    // <<< MUDANÇA: StateFlow para a lista de resultados unificada >>>
    private val _pixabayUnifiedResults = MutableStateFlow<List<PixabayAsset>>(emptyList())
    val pixabayUnifiedResults: StateFlow<List<PixabayAsset>> = _pixabayUnifiedResults.asStateFlow()

    private val _isSearchingPixabay = MutableStateFlow(false)
    val isSearchingPixabay: StateFlow<Boolean> = _isSearchingPixabay.asStateFlow()

    private val currentIsChatNarrative: StateFlow<Boolean> = audioDataStoreManager.isChatNarrative
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val currentLegendaPath: StateFlow<String> = audioDataStoreManager.legendaPath
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val currentNarrativa: StateFlow<String> = audioDataStoreManager.prompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val currentVideoTitle: StateFlow<String> = audioDataStoreManager.videoTitulo
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val currentUserLanguageTone: StateFlow<String> = audioDataStoreManager.userLanguageToneAudio
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val currentUserTargetAudience: StateFlow<String> = audioDataStoreManager.userTargetAudienceAudio
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val videoObjectiveIntroduction: StateFlow<String> = audioDataStoreManager.videoObjectiveIntroduction
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val videoObjectiveContent: StateFlow<String> = audioDataStoreManager.videoObjectiveVideo
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val videoObjectiveOutcome: StateFlow<String> = audioDataStoreManager.videoObjectiveOutcome
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val currentImagensReferenciaStateFlow: StateFlow<List<ImagemReferencia>> = videoDataStoreManager.imagensReferenciaJson.map { jsonString ->
        try {
            if (jsonString.isNotBlank() && jsonString != "[]") {
                json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), jsonString)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao parsear imagensReferenciaJsonString no ViewModel: '$jsonString'", e)
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var imageBatchMonitoringJob: Job? = null
    private val _currentlyPlayingSceneId = MutableStateFlow<String?>(null)
    val currentlyPlayingSceneId: StateFlow<String?> = _currentlyPlayingSceneId.asStateFlow()

    private val _sceneIdToRecreateImage = MutableStateFlow<String?>(null)
    val sceneIdToRecreateImage: StateFlow<String?> = _sceneIdToRecreateImage.asStateFlow()
    private val _promptForRecreateImage = MutableStateFlow<String?>(null)
    val promptForRecreateImage: StateFlow<String?> = _promptForRecreateImage.asStateFlow()
    private val _sceneIdForReferenceChangeDialog = MutableStateFlow<String?>(null)
    val sceneIdForReferenceChangeDialog: StateFlow<String?> = _sceneIdForReferenceChangeDialog.asStateFlow()

    var roteiroCenasFinal = JSONArray()

    private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
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
    }

    private val previewWorkObserver = Observer<List<WorkInfo>> { workInfos ->
        viewModelScope.launch {
            updatePreviewQueuePositions(workInfos)
        }
    }

    private val _isGeneratingPreviewForSceneId = MutableStateFlow<String?>(null)
    val isGeneratingPreviewForSceneId: StateFlow<String?> = _isGeneratingPreviewForSceneId.asStateFlow()

    private val _availableProjectAssets = MutableStateFlow<List<ProjectAsset>>(emptyList())
    val availableProjectAssets: StateFlow<List<ProjectAsset>> = _availableProjectAssets.asStateFlow()


    init {
        Log.d(TAG, "VideoProjectViewModel inicializado.")
        viewModelScope.launch {
            projectDataStoreManager.sceneLinkDataList.collect {
                _sceneLinkDataList.value = it
            }
        }
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING)
            .observeForever(workInfoObserver)
        workManager.getWorkInfosByTagLiveData(WorkerTags.SCENE_PREVIEW_WORK)
            .observeForever(previewWorkObserver)
        
        loadProjectAssets()
    }
    
    
    private suspend fun updatePreviewQueuePositions(
    workInfos: List<WorkInfo>,
) {

    val currentList = _sceneLinkDataList.value

    val runningSceneId = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.tags
        ?.firstOrNull { it.startsWith("${WorkerTags.SCENE_PREVIEW_WORK}_") }
        ?.removePrefix("${WorkerTags.SCENE_PREVIEW_WORK}_")

    val pendingWorkers = workInfos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val queueMap = pendingWorkers.mapIndexedNotNull { index, workInfo ->
        val sceneId = workInfo.tags.firstOrNull { it.startsWith("${WorkerTags.SCENE_PREVIEW_WORK}_") }
            ?.removePrefix("${WorkerTags.SCENE_PREVIEW_WORK}_")
        if (sceneId != null) sceneId to (index + 1) else null
    }.toMap()

    val updatedList = currentList.map { scene ->
        val newPosition = when {
            scene.id == runningSceneId -> 0
            queueMap.containsKey(scene.id) -> queueMap[scene.id]!!
            else -> -1
        }

        if (scene.previewQueuePosition != newPosition) {
            scene.copy(previewQueuePosition = newPosition)
        } else {
            scene
        }
    }
    
    if (currentList != updatedList) {
        _sceneLinkDataList.value = updatedList
        projectDataStoreManager.setSceneLinkDataList(updatedList)
    }
    
}

    fun onSnackbarMessageShown() {
        _snackbarMessage.value = null
    }

    fun postSnackbarMessage(message: String) {
        _snackbarMessage.value = message
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

                val subDirsToScan = listOf("ref_images", "pixabay_images", "pixabay_videos", "downloaded_videos")
                
                for (subDirName in subDirsToScan) {
                    val dir = File(projectDir, subDirName)
                    if (!dir.exists() || !dir.isDirectory) continue
                    
                    

                    dir.listFiles()?.forEach { file ->
                        if (file.isFile ) {
                            if (subDirName == "ref_images" && file.name.startsWith("thumb_", true)){
                            } else {
                                
                                val isVideo = file.extension.equals("mp4", true) || file.extension.equals("webm", true)
                                var thumbPath = file.absolutePath
                                
                                if (isVideo) {
                                    val expectedThumbName = "thumb_from_${file.nameWithoutExtension}.webp"
                                    val thumbFile = "$projectDir/ref_images/$expectedThumbName"
                                    thumbPath = thumbFile
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
                _availableProjectAssets.value = assetList.sortedBy { it.displayName }
                Log.d(TAG, "Carregados ${_availableProjectAssets.value.size} assets do projeto.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar assets do projeto.", e)
                _availableProjectAssets.value = emptyList()
            }
        }
    }

    fun onPlayPausePreviewClicked(scene: SceneLinkData) {
        viewModelScope.launch {
            if (_currentlyPlayingSceneId.value == scene.id) {
                stopPlayback()
            } else if (!scene.videoPreviewPath.isNullOrBlank()){
                _currentlyPlayingSceneId.value = scene.id
            } else {
                enqueuePreviewGeneration(scene.id)
            }
        }
    }

    // <<< MUDANÇA: Lógica de enfileiramento paralela para prévias >>>
    private fun enqueuePreviewGeneration(sceneId: String) {
        Log.d(TAG, "Enfileirando ScenePreviewWorker para a cena $sceneId")
        
        val workRequest = OneTimeWorkRequestBuilder<ScenePreviewWorker>()
            .setInputData(workDataOf(ScenePreviewWorker.KEY_SCENE_ID to sceneId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("${WorkerTags.SCENE_PREVIEW_WORK}_$sceneId")
            .addTag(WorkerTags.SCENE_PREVIEW_WORK)
            .build()
        
        // Enfileira como trabalho individual, permitindo que o WorkManager execute em paralelo
        workManager.enqueue(workRequest)
        
        postSnackbarMessage(applicationContext.getString(R.string.toast_preview_generation_queued))
    }

    fun stopPlayback() {
        if (_currentlyPlayingSceneId.value != null) {
            _currentlyPlayingSceneId.value = null
            Log.d(TAG, "Playback state stopped.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        workManager.getWorkInfosByTagLiveData(WorkerTags.SCENE_PREVIEW_WORK)
            .removeObserver(previewWorkObserver)
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING).removeObserver(workInfoObserver)
    }

    fun cancelSceneGenerationProcess() {
        Log.i(TAG, "Cancelando o job de geração de cenas.")
        sceneGenerationJob?.cancel(CancellationException("Processo de geração de cenas cancelado pelo usuário."))
        sceneGenerationJob = null
        _isProcessingGlobalScenes.value = false
        viewModelScope.launch {
            projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
        }
    }

    fun requestFullSceneGenerationProcess() {
        Log.d(TAG, "requestFullSceneGenerationProcess chamado.")
        viewModelScope.launch {
            _isProcessingGlobalScenes.value = false
            projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)

            val scenes = sceneLinkDataList.value
            val legendaPathValue = currentLegendaPath.value
            val narrativaValue = currentNarrativa.value

            if (legendaPathValue.isBlank()) {
                val errorMsg = applicationContext.getString(R.string.error_subtitle_path_not_set_for_scenes)
                Log.w(TAG, "Não é possível gerar cenas: $errorMsg")
                _globalSceneError.value = errorMsg
                return@launch
            }
            if (narrativaValue.isBlank()) {
                val errorMsg = applicationContext.getString(if (currentIsChatNarrative.value) R.string.error_dialog_script_not_set_for_scenes else R.string.error_narrative_not_set_for_scenes)
                Log.w(TAG, "Não é possível gerar cenas: $errorMsg")
                _globalSceneError.value = errorMsg
                return@launch
            }

            if (scenes.isNotEmpty()) {
                Log.d(TAG, "Cenas existentes (${scenes.size}) encontradas. Exibindo diálogo de confirmação.")
                _showSceneConfirmationDialogVM.value = true
            } else {
                Log.d(TAG, "Nenhuma cena existente. Iniciando geração direta.")
                performFullSceneGeneration(
                    isChat = currentIsChatNarrative.value,
                    narrativaOuDialogo = narrativaValue,
                    legendaPathParaApi = legendaPathValue
                )
            }
        }
    }

    fun confirmAndProceedWithSceneGeneration() {
        Log.d(TAG, "Confirmação para gerar novas cenas recebida.")
        if (_isProcessingGlobalScenes.value) {
            postSnackbarMessage(applicationContext.getString(R.string.status_scene_generation_in_progress))
            return
        }
        viewModelScope.launch {
            _showSceneConfirmationDialogVM.value = false
            clearProjectScenesAndWorkers()
            Log.d(TAG, "Cenas do projeto e workers limpos. Iniciando nova geração.")

            val legendaPathValue = currentLegendaPath.value
            val narrativaValue = currentNarrativa.value

            performFullSceneGeneration(
                isChat = currentIsChatNarrative.value,
                narrativaOuDialogo = narrativaValue,
                legendaPathParaApi = legendaPathValue
            )
        }
    }

    fun cancelSceneGenerationDialog() {
        _showSceneConfirmationDialogVM.value = false
    }

    private fun performFullSceneGeneration(
        isChat: Boolean,
        narrativaOuDialogo: String,
        legendaPathParaApi: String
    ) {
        sceneGenerationJob?.cancel()

        sceneGenerationJob = viewModelScope.launch {
            _isProcessingGlobalScenes.value = true
            projectDataStoreManager.setCurrentlyGenerating(isGenerating=true)
            _globalSceneError.value = null
            Log.i(TAG, "performFullSceneGeneration: Iniciando...")

            try {
                if (!isActive) throw CancellationException("Geração cancelada antes de iniciar.")
                if (!isGeneratingScene.value) throw CancellationException("Geração cancelada")
                if (legendaPathParaApi.isBlank()) {
                    _isProcessingGlobalScenes.value = false
                    projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
                    throw IllegalStateException(applicationContext.getString(R.string.error_subtitle_path_not_set_for_scenes))
                }
                Log.d(TAG, "Verificação de legenda OK.")

                val promptGerarCenas = if (isChat) {
                    CreateScenesChat(
                        narrativaOuDialogo, userInfoDataStoreManager.userNameCompany.first(), userInfoDataStoreManager.userProfessionSegment.first(),
                        userInfoDataStoreManager.userAddress.first(), currentUserLanguageTone.value, currentUserTargetAudience.value,
                        currentVideoTitle.value, videoObjectiveIntroduction.value, videoObjectiveContent.value, videoObjectiveOutcome.value
                    ).prompt
                } else {
                    CreateScenes(
                        narrativaOuDialogo, userInfoDataStoreManager.userNameCompany.first(), userInfoDataStoreManager.userProfessionSegment.first(),
                        userInfoDataStoreManager.userAddress.first(), currentUserLanguageTone.value, currentUserTargetAudience.value,
                        currentVideoTitle.value, videoObjectiveIntroduction.value, videoObjectiveContent.value, videoObjectiveOutcome.value
                    ).prompt
                }
                Log.d(TAG, "Prompt para IA preparado. Modo Chat: $isChat")

                val imagensReferenciasAtuais = currentImagensReferenciaStateFlow.first()
                val imagePathsForGemini = imagensReferenciasAtuais.map { it.path }
                
                OverlayManager.showOverlay(applicationContext, "🎬",  0)

                Log.d(TAG, "Chamando a API Gemini para gerar a estrutura de cenas...")
                val respostaResult = GeminiTextAndVisionProRestApi.perguntarAoGemini(
                    pergunta = promptGerarCenas,
                    imagens = imagePathsForGemini,
                    arquivoTexto = "${legendaPathParaApi}.raw_transcript.json"
                )

                if (!isGeneratingScene.value) throw CancellationException("Geração cancelada")
                if (!isActive) throw CancellationException("Geração cancelada durante chamada da API.")

                if (respostaResult.isFailure) {
                    _isProcessingGlobalScenes.value = false
                    projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
                    throw respostaResult.exceptionOrNull() ?: IllegalStateException("Falha na API Gemini sem uma exceção específica.")
                }

                if (!isGeneratingScene.value) throw CancellationException("Geração cancelada")

                val resposta = respostaResult.getOrNull()
                if (resposta.isNullOrBlank()) {
                    _isProcessingGlobalScenes.value = false
                    projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
                    throw IllegalStateException(applicationContext.getString(R.string.error_ai_empty_response_scenes))
                }
                Log.d(TAG, "Resposta da API recebida com sucesso.")

                val respostaAjustada = ajustarRespostaLocalViewModel(resposta)


                if (!isGeneratingScene.value) throw CancellationException("Geração cancelada")

                val generatedSceneDataList = parseSceneData(respostaAjustada, imagensReferenciasAtuais)
                _sceneLinkDataList.value = generatedSceneDataList 
                projectDataStoreManager.setSceneLinkDataList(generatedSceneDataList)
                Log.i(TAG, "Estrutura de ${generatedSceneDataList.size} cenas salva no DataStore.")

                val scenesThatNeedGeneration = generatedSceneDataList.filter {
                    it.promptGeracao?.isNotBlank() == true && it.imagemGeradaPath == null
                }

                if (!isGeneratingScene.value) throw CancellationException("Geração cancelada")

                if (scenesThatNeedGeneration.isNotEmpty()) {
                    val imgCu = AppConfigManager.getInt("task_COST_DEB_IMG") ?: 10
                    val totalCost = scenesThatNeedGeneration.size * imgCu
                    val user = authViewModel.userProfile.value
                    val cred = authViewModel.getDecryptedCredits()

                    if (user != null && !user.isPremium && cred < totalCost) {
                        _globalSceneError.value = applicationContext.getString(R.string.error_insufficient_credits_for_batch, totalCost, user.creditos ?: "0")
                    } else {
                        _pendingImageBatchCount.value = scenesThatNeedGeneration.size
                        _pendingImageBatchCost.value = totalCost.toLong()
                        _pendingSceneListForGeneration = generatedSceneDataList
                        _showImageBatchCostConfirmationDialog.value = true
                    }
                } else {
                    postSnackbarMessage("Estrutura de cenas criada, nenhuma imagem nova a ser gerada.")
                }
            } catch (e: Exception) {
                val errorMsg = if (e is CancellationException) "Geração de cenas cancelada." else "Erro: ${e.message}"
                Log.e(TAG, "Falha em performFullSceneGeneration: $errorMsg", e)
                _globalSceneError.value = errorMsg
                _isProcessingGlobalScenes.value = false
                projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
            } finally {
                if (isActive) {
                    _isProcessingGlobalScenes.value = false
                }
                if (!isGeneratingScene.value)
                    projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)

                Log.d(TAG, "Bloco finally de performFullSceneGeneration executado.")
            }
        }
    }

    private suspend fun internalUpdateSceneState(sceneId: String, updateAction: (SceneLinkData) -> SceneLinkData) {
        val currentList = _sceneLinkDataList.value 
        val newList = currentList.map {
            if (it.id == sceneId) updateAction(it) else it
        }
        if (newList != currentList) {
            _sceneLinkDataList.value = newList
            projectDataStoreManager.setSceneLinkDataList(newList)
        }
    }

    private fun ajustarRespostaLocalViewModel(resposta: String, tag: String = TAG): String {
        var respostaLimpa = resposta.trim()
        if (respostaLimpa.startsWith("```json")) {
            respostaLimpa = respostaLimpa.removePrefix("```json").trimStart()
        } else if (respostaLimpa.startsWith("```")) {
            respostaLimpa = respostaLimpa.removePrefix("```").trimStart()
        }
        if (respostaLimpa.endsWith("```")) {
            respostaLimpa = respostaLimpa.removeSuffix("```").trimEnd()
        }
        val inicioJson = respostaLimpa.indexOfFirst { it == '[' || it == '{' }
        val fimJson = respostaLimpa.indexOfLast { it == ']' || it == '}' }
        if (inicioJson != -1 && fimJson != -1 && fimJson >= inicioJson) {
            val jsonSubstring = respostaLimpa.substring(inicioJson, fimJson + 1)
            try {
                when {
                     jsonSubstring.trimStart().startsWith('[') -> JSONArray(jsonSubstring)
                     jsonSubstring.trimStart().startsWith('{') -> JSONObject(jsonSubstring)
                    else -> Log.w(tag, "Substring não é claramente um JSON Array ou Object: $jsonSubstring")
                }
                return jsonSubstring
            } catch (e: JSONException){
                 Log.w(tag, "Substring extraída não é JSON válido: '$jsonSubstring'. Erro: ${e.message}. Retornando resposta limpa de markdown.")
                 return respostaLimpa
            }
        } else {
             Log.w(tag, "Falha ao isolar JSON, retornando resposta limpa de markdown: $respostaLimpa")
            return respostaLimpa
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun generateImageForScene(sceneId: String, prompt: String) {
        viewModelScope.launch {
            val sceneToUpdate = sceneLinkDataList.value.find { it.id == sceneId }
            if (sceneToUpdate == null) {
                postSnackbarMessage(applicationContext.getString(R.string.error_scene_not_found_for_image_gen))
                return@launch
            }
            if (sceneToUpdate.isGenerating || sceneToUpdate.isChangingClothes || sceneToUpdate.isGeneratingVideo) {
                postSnackbarMessage(applicationContext.getString(R.string.status_scene_already_processing, sceneToUpdate.cena ?: sceneId.take(4)))
                return@launch
            }
            if (prompt.isBlank()) {
                postSnackbarMessage(applicationContext.getString(R.string.error_empty_prompt_for_image_gen))
                return@launch
            }
            enqueueImageGenerationWorker(sceneId, prompt)
        }
     }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun enqueueImageGenerationWorker(sceneId: String, prompt: String) {
        viewModelScope.launch {
            projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
            val globalImages = currentImagensReferenciaStateFlow.first()
            var imagesJsonForWorker = "[]"
            val sceneToUpdate = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (sceneToUpdate.imagemReferenciaPath.isNotBlank()) {
                val specificRefImage = globalImages.find { it.path == sceneToUpdate.imagemReferenciaPath }
                if (specificRefImage != null) {
                    try {
                        imagesJsonForWorker = json.encodeToString(ListSerializer(ImagemReferencia.serializer()), listOf(specificRefImage))
                    } catch (e: Exception) {
                        Log.e(TAG, "Falha ao serializar imagem de referência para cena $sceneId.", e)
                    }
                }
            }
            val inputData = workDataOf(
                KEY_SCENE_ID to sceneId,
                KEY_TASK_TYPE to TASK_TYPE_GENERATE_IMAGE,
                KEY_IMAGE_GEN_PROMPT to prompt,
                KEY_IMAGENS_REFERENCIA_JSON_INPUT to imagesJsonForWorker
            )
            val sceneSpecificTag = TAG_PREFIX_SCENE_PROCESSING + sceneId
            val workRequest = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(sceneSpecificTag)
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .build()
            workManager.enqueue(workRequest)
            internalUpdateSceneState(sceneId) {
                it.copy(
                    isGenerating = true,
                    generationAttempt = 1,
                    generationErrorMessage = null
                )
            }
        }
    }

    fun cancelGenerationForScene(sceneId: String) {
        val sceneSpecificTagImage = TAG_PREFIX_SCENE_PROCESSING + sceneId
        val sceneSpecificTagVideo = TAG_PREFIX_SCENE_VIDEO_PROCESSING + sceneId
        val sceneSpecificTagClothes = TAG_PREFIX_SCENE_CLOTHES_PROCESSING + sceneId
        Log.d(TAG, "Cancelando TODAS as gerações para cena $sceneId (Imagem, Vídeo, Roupas)")
        workManager.cancelAllWorkByTag(sceneSpecificTagImage)
        workManager.cancelAllWorkByTag(sceneSpecificTagVideo)
        workManager.cancelAllWorkByTag(sceneSpecificTagClothes)
        viewModelScope.launch {
            internalUpdateSceneState(sceneId) { it.copy(
                isGenerating = false, isGeneratingVideo = false, isChangingClothes = false,
                generationAttempt = 0, clothesChangeAttempt = 0,
                generationErrorMessage = applicationContext.getString(R.string.message_cancelled_by_user))
            }
            postSnackbarMessage(applicationContext.getString(R.string.toast_generation_cancelled_for_scene, sceneId.take(4)))
        }
    }

    private fun triggerBatchImageGenerationForScenes(scenesToGenerate: List<SceneLinkData>) {
         viewModelScope.launch {
             scenesToGenerate.forEach { sceneLinkData ->
                 if (!viewModelScope.isActive) {
                     Log.w(TAG, "triggerBatchImageGenerationForScenes: Coroutine do ViewModel cancelada, parando de enfileirar workers.")
                     return@forEach
                 }
                 if (sceneLinkData.pathThumb != null) {
                     internalUpdateSceneState(sceneLinkData.id) { it.copy(isGenerating = false, generationAttempt = 0) }
                     return@forEach
                 }
                 if (sceneLinkData.promptGeracao.isNullOrBlank()) {
                     internalUpdateSceneState(sceneLinkData.id) { it.copy(isGenerating = false, generationAttempt = 0) }
                 } else {
                     delay(500)
                     if (!viewModelScope.isActive) return@forEach
                     enqueueImageGenerationWorker(sceneLinkData.id, sceneLinkData.promptGeracao)
                 }
             }
         }
     }

    fun replaceSceneLinkListAndTriggerWorkers(newList: List<SceneLinkData>) {
        viewModelScope.launch {
            imageBatchMonitoringJob?.cancel()
            imageBatchMonitoringJob = null
            val listToSave = newList.map { scene ->
                 if (!scene.isGenerating) {
                    scene.copy(
                        generationAttempt = 0, isChangingClothes = false, clothesChangeAttempt = 0,
                        generationErrorMessage = null, isGeneratingVideo = false
                    )
                } else {
                    scene.copy(
                        isGenerating = true, generationAttempt = 1, isChangingClothes = false,
                        clothesChangeAttempt = 0, generationErrorMessage = null, isGeneratingVideo = false,
                        pathThumb = null, promptVideo = null, audioPathSnippet = null,
                        videoPreviewPath = null
                    )
                }
            }
            _sceneLinkDataList.value = listToSave 
            val success = projectDataStoreManager.setSceneLinkDataList(listToSave)
            if (success) {
                val scenesToActuallyGenerate = listToSave.filter { it.isGenerating }
                if (scenesToActuallyGenerate.isNotEmpty()) {
                    _isProcessingGlobalScenes.value = true
                    triggerBatchImageGenerationForScenes(scenesToActuallyGenerate)
                    monitorBatchImageGeneration(scenesToActuallyGenerate.map { it.id }.toSet())
                } else {
                    _isProcessingGlobalScenes.value = false
                    Log.i(TAG, "Nenhuma cena com prompt para gerar imagem estática após replaceSceneLinkListAndTriggerWorkers.")
                }
            } else {
                postSnackbarMessage(applicationContext.getString(R.string.error_saving_scene_list))
                _isProcessingGlobalScenes.value = false
            }
        }
    }

    private fun monitorBatchImageGeneration(scenesBeingGeneratedIds: Set<String>) {
        if (scenesBeingGeneratedIds.isEmpty()) {
            _isProcessingGlobalScenes.value = false
            return
        }
        imageBatchMonitoringJob = viewModelScope.launch {
            try {
                _sceneLinkDataList.collect { currentScenes -> 
                    if (!isActive) {
                        Log.w(TAG, "monitorBatchImageGeneration: Coroutine de monitoramento cancelada.")
                        _isProcessingGlobalScenes.value = false
                        return@collect
                    }
                    val stillProcessingCount = currentScenes
                        .filter { it.id in scenesBeingGeneratedIds }
                        .count { it.isGenerating }
                    Log.d(TAG, "Monitorando: $stillProcessingCount de ${scenesBeingGeneratedIds.size} cenas ainda processando IMAGEM.")
                    if (stillProcessingCount == 0) {
                        _isProcessingGlobalScenes.value = false
                        imageBatchMonitoringJob?.cancel()
                        imageBatchMonitoringJob = null
                        Log.i(TAG, "Monitoramento de geração de IMAGENS em lote concluído.")
                    }
                }
            } catch (e: CancellationException) {
                _isProcessingGlobalScenes.value = false
                Log.i(TAG, "Monitoramento de geração em lote cancelado via exceção.")
            }
        }
    }

    fun changeClothesForSceneWithSpecificReference(sceneId: String, chosenReferenceImagePath: String) {
        viewModelScope.launch {
            val sceneToUpdate = sceneLinkDataList.value.find { it.id == sceneId }
            if (sceneToUpdate == null) { return@launch}
            if (sceneToUpdate.isGenerating || sceneToUpdate.isChangingClothes || sceneToUpdate.isGeneratingVideo) { return@launch}
            if (sceneToUpdate.imagemGeradaPath.isNullOrBlank()) { return@launch}
            if (sceneToUpdate.pathThumb != null) {
                 postSnackbarMessage("Não é possível trocar roupa em um vídeo.")
                return@launch
            }
            val inputData = workDataOf(
                KEY_SCENE_ID to sceneId,
                KEY_TASK_TYPE to TASK_TYPE_CHANGE_CLOTHES,
                KEY_CHOSEN_REFERENCE_IMAGE_PATH to chosenReferenceImagePath,
            )
            val sceneSpecificTag = TAG_PREFIX_SCENE_CLOTHES_PROCESSING + sceneId
            val workRequest = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(sceneSpecificTag)
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .build()
            workManager.enqueue(workRequest)
            internalUpdateSceneState(sceneId) { it.copy(isChangingClothes = true, clothesChangeAttempt = 1, generationErrorMessage = null) }
        }
    }

    fun cancelClothesChangeForScene(sceneId: String) {
        val sceneSpecificTag = TAG_PREFIX_SCENE_CLOTHES_PROCESSING + sceneId
        Log.d(TAG, "Cancelando troca de roupa para cena $sceneId com tag: $sceneSpecificTag")
        val cancelResult = workManager.cancelAllWorkByTag(sceneSpecificTag)
        viewModelScope.launch {
            internalUpdateSceneState(sceneId) { it.copy(isChangingClothes = false, clothesChangeAttempt = 0, generationErrorMessage = applicationContext.getString(R.string.message_cancelled_by_user)) }
            postSnackbarMessage(applicationContext.getString(R.string.toast_generation_cancelled_for_scene, sceneId.take(4)))
        }
        cancelResult.result.addListener({
            Log.d(TAG, "Resultado do cancelamento para tag $sceneSpecificTag recebido.")
        }, { it.run() })
    }

   fun updateScenePrompt(id: String, newPrompt: String) {
       viewModelScope.launch {
           internalUpdateSceneState(id) {
               it.copy(
                   promptGeracao = newPrompt, isGenerating = false, generationAttempt = 0,
                   aprovado = false, isChangingClothes = false, generationErrorMessage = null,
                   clothesChangeAttempt = 0, isGeneratingVideo = false, pathThumb = null,
                   promptVideo = null, audioPathSnippet = null, videoPreviewPath = null
               )
           }
       }
   }

    fun updateSceneReferenceImage(sceneId: String, newReferenceImage: ImagemReferencia) {
        viewModelScope.launch(Dispatchers.IO) {
            val sceneToUpdate = _sceneLinkDataList.value.find { it.id == sceneId }
            if (sceneToUpdate == null) {
                Log.e(TAG, "Cena com ID $sceneId não encontrada para atualizar a imagem de referência.")
                return@launch
            }

            var newIsGenerating = false
            if (newReferenceImage.pathVideo == null) {
                newIsGenerating = !sceneToUpdate.promptGeracao.isNullOrBlank()
            }

            internalUpdateSceneState(sceneId) { scene ->
                if (newReferenceImage.pathVideo != null) {
                    Log.i(TAG, "Trocando referência da cena $sceneId para um VÍDEO: ${newReferenceImage.pathVideo}")
                    scene.copy(
                        imagemReferenciaPath = newReferenceImage.path,
                        descricaoReferencia = newReferenceImage.descricao,
                        imagemGeradaPath = newReferenceImage.pathVideo,
                        pathThumb = newReferenceImage.path,
                        isGenerating = false,
                        aprovado = true, 
                        videoPreviewPath = null,
                        similaridade = null,
                        isGeneratingVideo = false,
                        generationAttempt = 0,
                        isChangingClothes = false,
                        clothesChangeAttempt = 0,
                        generationErrorMessage = null,
                        promptVideo = null,
                        audioPathSnippet = null
                    )
                } else {
                    Log.i(TAG, "Trocando referência da cena $sceneId para uma IMAGEM ESTÁTICA: ${newReferenceImage.path}. Gerar nova: $newIsGenerating")
                    scene.copy(
                        imagemReferenciaPath = newReferenceImage.path,
                        descricaoReferencia = newReferenceImage.descricao,
                        similaridade = null,
                        imagemGeradaPath = null,
                        pathThumb = null,
                        isGenerating = newIsGenerating,
                        aprovado = false,
                        videoPreviewPath = null,
                        isGeneratingVideo = false,
                        generationAttempt = if (newIsGenerating) 1 else 0,
                        isChangingClothes = false,
                        clothesChangeAttempt = 0,
                        generationErrorMessage = null,
                        promptVideo = null,
                        audioPathSnippet = null
                    )
                }
            }

            val scenesToRegenerate = _sceneLinkDataList.value.filter { it.id == sceneId && it.isGenerating }
            if (scenesToRegenerate.isNotEmpty()) {
                Log.d(TAG, "Disparando worker para regenerar imagem da cena $sceneId após troca de referência.")
                triggerBatchImageGenerationForScenes(scenesToRegenerate)
            }
            if (newReferenceImage.pathVideo != null) {
                enqueuePreviewGeneration(sceneId)
            }
        }
    }
    
    
    fun replaceGeneratedImageWithReference(sceneId: String, chosenAsset: ProjectAsset) {
        viewModelScope.launch {
            Log.i(TAG, "Substituindo asset da cena $sceneId por: ${chosenAsset.finalAssetPath}")
            internalUpdateSceneState(sceneId) { scene ->
                scene.copy(
                    imagemGeradaPath = chosenAsset.finalAssetPath,
                    pathThumb = chosenAsset.thumbnailPath,
                    isGenerating = false,
                    isGeneratingVideo = false,
                    generationAttempt = 0,
                    generationErrorMessage = null,
                    videoPreviewPath = null,
                    previewQueuePosition = -1,
                    aprovado = true
                )
            }
            enqueuePreviewGeneration(sceneId)
            postSnackbarMessage(applicationContext.getString(R.string.scene_item_toast_asset_replaced))
        }
    }

    private suspend fun clearProjectScenesAndWorkers() {
        Log.d(TAG, "clearProjectScenesAndWorkers: Iniciando limpeza de cenas e workers.")
        imageBatchMonitoringJob?.cancel()
        imageBatchMonitoringJob = null
        workManager.cancelAllWorkByTag(WorkerTags.VIDEO_PROCESSING)
        _sceneLinkDataList.value = emptyList()
        projectDataStoreManager.setSceneLinkDataList(emptyList())
        if (_isProcessingGlobalScenes.value) {
            _isProcessingGlobalScenes.value = false
        }
        projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
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

    fun confirmImageBatchGeneration() {
        viewModelScope.launch {
            _showImageBatchCostConfirmationDialog.value = false
            val scenesToProcess = _pendingSceneListForGeneration
            if (scenesToProcess.isNullOrEmpty()) {
                Log.w(TAG, "Confirmação de geração em lote, mas nenhuma cena pendente encontrada.")
                cancelImageBatchGeneration()
                return@launch
            }
            postSnackbarMessage(applicationContext.getString(R.string.toast_scene_prompts_and_images_queued))
            triggerBatchImageGenerationForScenes(scenesToProcess)
            cancelImageBatchGeneration()
        }
    }

    fun cancelImageBatchGeneration() {
        _isProcessingGlobalScenes.value = false
        _showImageBatchCostConfirmationDialog.value = false
        _pendingImageBatchCost.value = 0
        _pendingImageBatchCount.value = 0
        _pendingSceneListForGeneration = null
    }

    fun triggerBatchPixabayVideoSearch() {
        viewModelScope.launch {
             projectDataStoreManager.setCurrentlyGenerating(isGenerating=false)
            _showImageBatchCostConfirmationDialog.value = false
            val scenesToProcess = _pendingSceneListForGeneration
            if (scenesToProcess.isNullOrEmpty()) {
                Log.w(TAG, "Confirmação de busca em lote, mas nenhuma cena pendente encontrada.")
                cancelImageBatchGeneration()
                return@launch
            }

            postSnackbarMessage(applicationContext.getString(R.string.toast_batch_video_search_started))

            scenesToProcess.forEach { scene ->
                findAndSetStockVideoForScene(scene.id)
                delay(200)
            }

            cancelImageBatchGeneration()
        }
    }

    fun findAndSetStockVideoForScene(sceneId: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId }
            if (scene == null) {
                postSnackbarMessage("Cena não encontrada.")
                return@launch
            }
            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                postSnackbarMessage("A cena já está processando.")
                return@launch
            }
            if (scene.promptVideo.isNullOrBlank()) {
                postSnackbarMessage("A cena não tem um prompt de busca definido.")
                return@launch
            }
            internalUpdateSceneState(sceneId) { it.copy(isGeneratingVideo = true, generationErrorMessage = null) }
            val workRequest = OneTimeWorkRequestBuilder<PixabayVideoSearchWorker>()
                .setInputData(workDataOf(PixabayVideoSearchWorker.KEY_SCENE_ID to sceneId))
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .addTag("pixabay_search_${sceneId}")
                .build()
            workManager.enqueue(workRequest)
            postSnackbarMessage("Iniciando busca automática de vídeo...")
        }
    }

    private fun parseSceneData(jsonString: String, allRefs: List<ImagemReferencia>): List<SceneLinkData> {
        val sceneList = mutableListOf<SceneLinkData>()
        val jsonArray = JSONArray(jsonString)
        var lastTimeEnd: Double? = 0.0

        for (i in 0 until jsonArray.length()) {
            val cenaObj = jsonArray.getJSONObject(i)
            val originalImageIndex = cenaObj.optString("FOTO_REFERENCIA", null)?.toIntOrNull()
            var refPath = ""
            var refDesc = ""
            var videoPath: String? = null
            var thumbPath: String? = null
            var isVideo = false

            if (originalImageIndex != null && originalImageIndex > 0 && originalImageIndex <= allRefs.size) {
                val ref = allRefs[originalImageIndex - 1]
                refPath = ref.path
                refDesc = ref.descricao
                if (ref.pathVideo != null) {
                    videoPath = ref.pathVideo
                    thumbPath = ref.path
                    isVideo = true
                }
            }

            sceneList.add(SceneLinkData(
                id = UUID.randomUUID().toString(),
                cena = cenaObj.optString("CENA", null),
                tempoInicio = lastTimeEnd,
                tempoFim = cenaObj.optDouble("TEMPO_FIM", 0.0).takeIf { it > 0 },
                imagemReferenciaPath = refPath,
                descricaoReferencia = refDesc,
                promptGeracao = cenaObj.optString("PROMPT_PARA_IMAGEM", null),
                exibirProduto = cenaObj.optBoolean("EXIBIR_PRODUTO", false),
                imagemGeradaPath = videoPath,
                pathThumb = thumbPath,
                isGenerating = false,
                aprovado = isVideo,
                promptVideo = cenaObj.optString("TAG_SEARCH_WEB", null)
            ))
            lastTimeEnd = cenaObj.optDouble("TEMPO_FIM", 0.0).takeIf { it > 0 }
        }
        return sceneList
    }

    
    fun generateVideoForScene(sceneId: String, videoPromptFromDialog: String, sourceImagePathFromSceneParameter: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                postSnackbarMessage("A cena já está processando.")
                return@launch
            }
            if (videoPromptFromDialog.isBlank()){
                 postSnackbarMessage("Prompt para vídeo não pode ser vazio.")
                return@launch
            }
            val currentSourceImagePathForVideo = sourceImagePathFromSceneParameter
            if (currentSourceImagePathForVideo.isBlank()){
                 postSnackbarMessage("Imagem base para vídeo não pode ser vazia.")
                return@launch
            }
            Log.i(TAG, "Enfileirando Worker para gerar VÍDEO para cena $sceneId. Prompt (do diálogo): $videoPromptFromDialog, Imagem base: $currentSourceImagePathForVideo")
            internalUpdateSceneState(sceneId) {
                it.copy(
                    isGeneratingVideo = true,
                    generationAttempt = 1,
                    promptVideo = videoPromptFromDialog,
                    generationErrorMessage = null
                )
            }
            val inputData = workDataOf(
                KEY_SCENE_ID to sceneId,
                KEY_TASK_TYPE to TASK_TYPE_GENERATE_VIDEO,
                KEY_VIDEO_GEN_PROMPT to videoPromptFromDialog,
                KEY_SOURCE_IMAGE_PATH_FOR_VIDEO to currentSourceImagePathForVideo
            )
            val sceneSpecificTag = TAG_PREFIX_SCENE_VIDEO_PROCESSING + sceneId
            val workRequest = OneTimeWorkRequestBuilder<VideoProcessingWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(sceneSpecificTag)
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .build()
            workManager.enqueue(workRequest)
        }
    }
    
    
    
            // <<< INÍCIO DAS NOVAS FUNÇÕES PÚBLICAS PARA O EXPLORADOR DE MÍDIA >>>
    fun onShowPixabaySearchDialog(sceneId: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId }
            _pixabaySearchQuery.value = scene?.promptVideo ?: ""
            _pixabayUnifiedResults.value = emptyList()
            _isSearchingPixabay.value = false
            _showPixabaySearchDialogForSceneId.value = sceneId
            if (pixabaySearchQuery.value.isNotBlank()){
                searchPixabayAssets()
            }
        }
    }
    
    fun onDismissPixabaySearchDialog() {
        _showPixabaySearchDialogForSceneId.value = null
    }

    fun onPixabaySearchQueryChanged(query: String) {
        _pixabaySearchQuery.value = query
    }

    fun searchPixabayAssets() {
        if (_isSearchingPixabay.value) return
        viewModelScope.launch {
            _isSearchingPixabay.value = true
            _pixabayUnifiedResults.value = emptyList()
            postSnackbarMessage("Buscando em Imagens e Vídeos...")

            val videosDeferred = async { PixabayApiClient.searchVideos(_pixabaySearchQuery.value) }
            val imagesDeferred = async { PixabayApiClient.searchImages(_pixabaySearchQuery.value) }

            val videoResult = videosDeferred.await()
            val imageResult = imagesDeferred.await()
            
            val unifiedList = mutableListOf<PixabayAsset>()
            val videos = videoResult.getOrNull() ?: emptyList()
            val images = imageResult.getOrNull() ?: emptyList()

            val maxSize = maxOf(videos.size, images.size)
            for (i in 0 until maxSize) {
                if (i < videos.size) {
                    val video = videos[i]
                    unifiedList.add(PixabayAsset(
                        type = PixabayAssetType.VIDEO,
                        thumbnailUrl = video.videoFiles.small.thumbnail,
                        downloadUrl = video.videoFiles.small.url,
                        tags = video.tags
                    ))
                }
                if (i < images.size) {
                    val image = images[i]
                    unifiedList.add(PixabayAsset(
                        type = PixabayAssetType.IMAGE,
                        thumbnailUrl = image.previewURL,
                        downloadUrl = image.largeImageURL,
                        tags = image.tags
                    ))
                }
            }

            if (unifiedList.isEmpty()) {
                postSnackbarMessage("Nenhum resultado encontrado para '${_pixabaySearchQuery.value}'")
            }

            _pixabayUnifiedResults.value = unifiedList
            _isSearchingPixabay.value = false
        }
    }

    fun onPixabayAssetSelected(sceneId: String, asset: PixabayAsset) {
        viewModelScope.launch {
            internalUpdateSceneState(sceneId) { it.copy(isGeneratingVideo = true, generationErrorMessage = null) }
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()

            val workRequest = if (asset.type == PixabayAssetType.VIDEO) {
                OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                    .setInputData(workDataOf(
                        VideoDownloadWorker.KEY_SCENE_ID to sceneId,
                        VideoDownloadWorker.KEY_VIDEO_URL to asset.downloadUrl,
                        VideoDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                    ))
                    .addTag(WorkerTags.VIDEO_PROCESSING)
                    .addTag("video_download_${sceneId}")
                    .build()
            } else {
                OneTimeWorkRequestBuilder<ImageDownloadWorker>()
                    .setInputData(workDataOf(
                        ImageDownloadWorker.KEY_SCENE_ID to sceneId,
                        ImageDownloadWorker.KEY_IMAGE_URL to asset.downloadUrl,
                        ImageDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                    ))
                    .addTag(WorkerTags.IMAGE_PROCESSING_WORK)
                    .addTag("image_download_${sceneId}")
                    .build()
            }
            workManager.enqueue(workRequest)
            onDismissPixabaySearchDialog()
            postSnackbarMessage("Download do asset iniciado...")
        }
    }
    // <<< FIM DAS NOVAS FUNÇÕES >>>
    
}