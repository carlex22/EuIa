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
import com.carlex.euia.worker.VideoDownloadWorker
import com.carlex.euia.worker.PixabayVideoSearchWorker

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

    private val _pixabaySearchResults = MutableStateFlow<List<PixabayVideo>>(emptyList())
    val pixabaySearchResults: StateFlow<List<PixabayVideo>> = _pixabaySearchResults.asStateFlow()

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

    private val previewGenerationMutex = Mutex()
    private val _isGeneratingPreviewForSceneId = MutableStateFlow<String?>(null)
    val isGeneratingPreviewForSceneId: StateFlow<String?> = _isGeneratingPreviewForSceneId.asStateFlow()

    private val _isAudioLoadingForScene = MutableStateFlow<String?>(null)
    val isAudioLoadingForScene: StateFlow<String?> = _isAudioLoadingForScene.asStateFlow()


    init {
        Log.d(TAG, "VideoProjectViewModel inicializado.")
        viewModelScope.launch {
            projectDataStoreManager.sceneLinkDataList.collect {
                _sceneLinkDataList.value = it
            }
        }
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_PROCESSING)
            .observeForever(workInfoObserver)
    }

    public fun generateScenePreviewHash(scene: SceneLinkData, prefs: VideoPreferencesDataStoreManager): String {
        val stringToHash = buildString {
            append(scene.cena)
            append(scene.imagemGeradaPath)
            append(scene.tempoInicio)
            append(scene.tempoFim)
            append(runBlocking { prefs.enableZoomPan.first() })
            append(runBlocking { prefs.videoHdMotion.first() })
            append(runBlocking { prefs.videoLargura.first() })
            append(runBlocking { prefs.videoAltura.first() })
            append(runBlocking { prefs.videoFps.first() })
        }
        Log.i(TAG, "hashview ${scene.cena} ${scene.imagemGeradaPath}, ${scene.tempoInicio}, ${scene.tempoFim}")
        
        val bytes = stringToHash.toByteArray()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        Log.i(TAG, "hashview ${scene.cena} ${scene.imagemGeradaPath}, ${scene.tempoInicio}, ${scene.tempoFim}")
        
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun onPlayPausePreviewClicked(scene: SceneLinkData) {
        viewModelScope.launch {
            if (_isGeneratingPreviewForSceneId.value == scene.id) return@launch

            if (_currentlyPlayingSceneId.value == scene.id) {
                stopPlayback()
            } else {
                _currentlyPlayingSceneId.value = scene.id
              //  generateAndSetPlayingState(scene)
            }
        }
    }

    suspend fun generateScenePreview(scene: SceneLinkData) {
        

        // <<< INÍCIO DA MODIFICAÇÃO >>>
        val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
        val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
        val previewsDir = File(baseProjectDir, "scene_previews")
        previewsDir.mkdirs() // Garante que o diretório de prévias do projeto exista
        
        val hash = generateScenePreviewHash(scene, videoPreferencesDataStoreManager)
        Log.i(TAG, "hashview $hash")
      
        val previewFile = File(previewsDir, "scene_${scene.cena}_$hash.mp4")
        // <<< FIM DA MODIFICAÇÃO >>>

        if (previewFile.exists() && previewFile.length() > 100) {
            Log.d(TAG, "Prévia encontrada no diretório do projeto para cena ${scene.id}.")
            
        }

        
        var audioSnippetPath: String? = null
        try {
            _isGeneratingPreviewForSceneId.value = scene.id
            _isAudioLoadingForScene.value = scene.id
            val mainAudioPath = audioDataStoreManager.audioPath.first()
            if (mainAudioPath.isBlank()) throw IOException("Áudio principal não encontrado.")

            audioSnippetPath = createAudioSnippetForPreview(mainAudioPath, scene)
            if (audioSnippetPath == null) throw IOException("Falha ao criar trecho de áudio.")
            _isAudioLoadingForScene.value = null
            
            
            
            val sceneWithAsset = scene.copy(
                tempoFim = if (videoPreferencesDataStoreManager.enableSceneTransitions.first()) {
                    scene.tempoFim!! + 0.5
                } else {
                    scene.tempoFim!!
                }
            )

            val success = VideoEditorComTransicoes.gerarPreviaDeCenaUnica(
                context = applicationContext,
                scene = sceneWithAsset,
                audioSnippetPath = audioSnippetPath,
                outputPreviewPath = previewFile.absolutePath,
                videoPreferences = videoPreferencesDataStoreManager,
                logCallback = { Log.v("FFmpegPreview", it) }
            )

            if (success) {
                updateSceneWithPreviewPath(scene.id, previewFile.absolutePath)
                _currentlyPlayingSceneId.value = scene.id
            } else {
                previewFile.delete()
                throw IOException("FFmpeg falhou ao gerar a prévia.")
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.w(TAG, "Geração de prévia cancelada.", e)
            } else {
                Log.e(TAG, "Erro ao gerar prévia para cena ${scene.id}", e)
                Toast.makeText(applicationContext, "Erro ao gerar prévia: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } 
    }

    suspend fun createAudioSnippetForPreview(mainAudioPath: String, scene: SceneLinkData): String? = withContext(Dispatchers.IO) {
        val tempDir = File(applicationContext.cacheDir, "audio_snippets")
        tempDir.mkdirs()
        val outputFile = File.createTempFile("snippet_${scene.id}_", ".mp3", tempDir)

        val startTime = scene.tempoInicio ?: 0.0
        val endTime = scene.tempoFim ?: 0.0
        val duration = (endTime - startTime).coerceAtLeast(0.1)

        val command = "-y -i \"$mainAudioPath\" -ss $startTime -t $duration -c:a libmp3lame -q:a 4 \"${outputFile.absolutePath}\""
        
        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputFile.absolutePath
        } else {
            Log.e(TAG, "Falha ao cortar áudio para prévia: ${session.allLogsAsString}")
            null
        }
    }

    suspend fun updateSceneWithPreviewPath(sceneId: String, previewPath: String) {
        internalUpdateSceneState(sceneId) { scene ->
            scene.copy(videoPreviewPath = previewPath)
        }
    }

    fun stopPlayback() {
        if (_currentlyPlayingSceneId.value != null) {
            _currentlyPlayingSceneId.value = null
            Log.d(TAG, "Playback state stopped.")
        }
    }

   /*public fun generateScenePreviewHash(scene: SceneLinkData): String {
        return generateScenePreviewHash(scene, videoPreferencesDataStoreManager)
    }*/

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
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
            Toast.makeText(applicationContext, applicationContext.getString(R.string.status_scene_generation_in_progress), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(applicationContext, "Estrutura de cenas criada, nenhuma imagem nova a ser gerada.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(applicationContext, applicationContext.getString(R.string.error_scene_not_found_for_image_gen), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (sceneToUpdate.isGenerating || sceneToUpdate.isChangingClothes || sceneToUpdate.isGeneratingVideo) {
                Toast.makeText(applicationContext, applicationContext.getString(R.string.status_scene_already_processing, sceneToUpdate.cena ?: sceneId.take(4)), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (prompt.isBlank()) {
                Toast.makeText(applicationContext, applicationContext.getString(R.string.error_empty_prompt_for_image_gen), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(applicationContext, applicationContext.getString(R.string.toast_generation_cancelled_for_scene, sceneId.take(4)), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(applicationContext, applicationContext.getString(R.string.error_saving_scene_list), Toast.LENGTH_LONG).show()
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
                 Toast.makeText(applicationContext, "Não é possível trocar roupa em um vídeo.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(applicationContext, applicationContext.getString(R.string.toast_generation_cancelled_for_scene, sceneId.take(4)), Toast.LENGTH_SHORT).show()
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

    fun updateSceneReferenceImage1(sceneId: String, newReferenceImage: ImagemReferencia) {
        viewModelScope.launch(Dispatchers.IO) {
            internalUpdateSceneState(sceneId) { scene ->
                val newImagemGeradaPath: String?
                val newPathThumb: String?
                val newIsGenerating: Boolean
                val newAprovado: Boolean
                if (newReferenceImage.pathVideo != null) {
                    
                    newImagemGeradaPath = newReferenceImage.pathVideo
                    newPathThumb = newReferenceImage.path
                    newIsGenerating = false
                    newAprovado = true
                    Log.i(TAG, "Trocando referência da cena $sceneId para um VÍDEO: ${newReferenceImage.pathVideo}")
                } else {
                    newImagemGeradaPath = null
                    newPathThumb = null
                    newIsGenerating = !scene.promptGeracao.isNullOrBlank()
                    newAprovado = false
                    Log.i(TAG, "Trocando referência da cena $sceneId para uma IMAGEM ESTÁTICA: ${newReferenceImage.path}. Gerar nova: $newIsGenerating")
                }
                scene.copy(
                    imagemReferenciaPath = newReferenceImage.path, descricaoReferencia = newReferenceImage.descricao,
                    similaridade = null, imagemGeradaPath = newImagemGeradaPath, pathThumb = newPathThumb,
                    isGenerating = newIsGenerating, isGeneratingVideo = false, generationAttempt = if (newIsGenerating) 1 else 0,
                    isChangingClothes = false, clothesChangeAttempt = 0, aprovado = newAprovado,
                    generationErrorMessage = null, promptVideo = null, audioPathSnippet = null,
                    videoPreviewPath = null
                )
            }
            val scenesToRegenerate = _sceneLinkDataList.value.filter { it.id == sceneId && it.isGenerating }
            if (scenesToRegenerate.isNotEmpty()) {
                Log.d(TAG, "Disparando worker para regenerar imagem da cena $sceneId após troca de referência.")
                triggerBatchImageGenerationForScenes(scenesToRegenerate)
            }
        }
    }
    
    
    private suspend fun generatePreviewForScene(sceneId: String, generatedAssetPath: String?): String? {
        if (generatedAssetPath.isNullOrBlank()) {
            Log.w(TAG, "Não é possível gerar prévia para a cena $sceneId: caminho do asset gerado é nulo ou vazio.")
            return null
        }

        var audioSnippetPath: String? = null
        try {
            val scene = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }
                ?: throw IllegalStateException("Cena $sceneId não encontrada no DataStore para gerar prévia.")

            val mainAudioPath = audioDataStoreManager.audioPath.first()
            if (mainAudioPath.isBlank()) throw IllegalStateException("Áudio principal não encontrado.")

            audioSnippetPath = createAudioSnippetForPreview(mainAudioPath, scene)
                ?: throw IOException("Falha ao criar trecho de áudio para prévia.")

            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
            val previewsDir = File(baseProjectDir, "scene_previews")
            previewsDir.mkdirs() // Garante que o diretório de prévias do projeto exista
        
            val hash = generateScenePreviewHash(scene, videoPreferencesDataStoreManager)
            
            Log.i(TAG, "hashwork $hash")
        
            val previewFile = File(previewsDir, "scene_${scene.cena}_$hash.mp4")

            
            //val sceneWithAsset = scene.copy(imagemGeradaPath = generatedAssetPath)
            
            
            val sceneWithAsset = scene.copy(
                imagemGeradaPath = generatedAssetPath,
                tempoFim = if (videoPreferencesDataStoreManager.enableSceneTransitions.first()) {
                    scene.tempoFim!! + 0.5
                } else {
                    scene.tempoFim!!
                }
            )
            

            val success = VideoEditorComTransicoes.gerarPreviaDeCenaUnica(
                context = applicationContext,
                scene = sceneWithAsset,
                audioSnippetPath = audioSnippetPath,
                outputPreviewPath = previewFile.absolutePath,
                videoPreferences = videoPreferencesDataStoreManager,
                logCallback = { Log.v("$TAG-FFmpegPreview", it) }
            )

            return if (success) previewFile.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar prévia para a cena $sceneId", e)
            return null
        } finally {
            audioSnippetPath?.let { File(it).delete() }
        }
    }
    
    
        fun updateSceneReferenceImage(sceneId: String, newReferenceImage: ImagemReferencia) {
        viewModelScope.launch(Dispatchers.IO) {
            // Primeiro, obtemos o estado atual da cena para ter seus dados, se necessário.
            val sceneToUpdate = _sceneLinkDataList.value.find { it.id == sceneId }
            if (sceneToUpdate == null) {
                Log.e(TAG, "Cena com ID $sceneId não encontrada para atualizar a imagem de referência.")
                return@launch
            }

            var generatedPreviewPath: String? = null
            var newIsGenerating = false

            // Passo 1: Determinar o que fazer e executar tarefas suspendíveis (como gerar prévia) PRIMEIRO.
            if (newReferenceImage.pathVideo != null) {
                // A nova referência é um VÍDEO. Vamos gerar sua prévia.
                Log.d(TAG, "A referência é um vídeo. Gerando pré-visualização para a cena $sceneId...")
                generatedPreviewPath = generatePreviewForScene(sceneId, newReferenceImage.pathVideo)
                if(generatedPreviewPath == null){
                     Log.w(TAG, "Falha ao gerar a pré-visualização para o vídeo da cena $sceneId. O caminho da pré-visualização será nulo.")
                }
            } else {
                // A nova referência é uma IMAGEM. Verificamos se precisa ser gerada pela IA.
                newIsGenerating = !sceneToUpdate.promptGeracao.isNullOrBlank()
            }

            // Passo 2: Agora, com todos os dados prontos, atualizamos o estado da cena de uma vez.
            internalUpdateSceneState(sceneId) { scene ->
                if (newReferenceImage.pathVideo != null) {
                    // Atualiza o estado para um VÍDEO
                    Log.i(TAG, "Trocando referência da cena $sceneId para um VÍDEO: ${newReferenceImage.pathVideo}")
                    scene.copy(
                        imagemReferenciaPath = newReferenceImage.path,
                        descricaoReferencia = newReferenceImage.descricao,
                        imagemGeradaPath = newReferenceImage.pathVideo, // O "asset gerado" é o próprio vídeo
                        pathThumb = newReferenceImage.path, // A "thumb" é a imagem de capa do vídeo
                        isGenerating = false, // Não está gerando imagem de IA
                        aprovado = true, // Consideramos aprovado, pois é um asset final
                        videoPreviewPath = generatedPreviewPath, // <<< SALVA O CAMINHO DA PRÉVIA GERADA
                        // Reseta outros campos para um estado limpo
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
                    // Atualiza o estado para uma IMAGEM ESTÁTICA
                    Log.i(TAG, "Trocando referência da cena $sceneId para uma IMAGEM ESTÁTICA: ${newReferenceImage.path}. Gerar nova: $newIsGenerating")
                    scene.copy(
                        imagemReferenciaPath = newReferenceImage.path,
                        descricaoReferencia = newReferenceImage.descricao,
                        similaridade = null,
                        imagemGeradaPath = null, // Limpa o asset antigo
                        pathThumb = null, // Limpa a thumb antiga
                        isGenerating = newIsGenerating, // Define se a regeneração pela IA é necessária
                        aprovado = false,
                        videoPreviewPath = null, // Limpa a prévia antiga
                        // Reseta outros campos
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

            // Passo 3: Dispara a regeneração da imagem, se necessário (apenas para o caso de imagem estática).
            val scenesToRegenerate = _sceneLinkDataList.value.filter { it.id == sceneId && it.isGenerating }
            if (scenesToRegenerate.isNotEmpty()) {
                Log.d(TAG, "Disparando worker para regenerar imagem da cena $sceneId após troca de referência.")
                triggerBatchImageGenerationForScenes(scenesToRegenerate)
            }
        }
    }

    fun replaceGeneratedImageWithReference(sceneId: String, chosenReferenceImage: ImagemReferencia) {
        viewModelScope.launch(Dispatchers.IO) {
            internalUpdateSceneState(sceneId) { scene ->
                scene.copy(
                    imagemGeradaPath = chosenReferenceImage.pathVideo ?: chosenReferenceImage.path,
                    pathThumb = if (chosenReferenceImage.pathVideo != null) chosenReferenceImage.path else null,
                    imagemReferenciaPath = chosenReferenceImage.path, descricaoReferencia = chosenReferenceImage.descricao,
                    similaridade = 100, isGenerating = false, generationAttempt = 0,
                    isChangingClothes = false, clothesChangeAttempt = 0, aprovado = true,
                    generationErrorMessage = null, isGeneratingVideo = false, promptVideo = null,
                    audioPathSnippet = null, videoPreviewPath = null
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, R.string.scene_item_toast_generated_image_replaced, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun clearProjectScenesAndWorkers() {
        Log.d(TAG, "clearProjectScenesAndWorkers: Iniciando limpeza de cenas e workers.")
        imageBatchMonitoringJob?.cancel()
        imageBatchMonitoringJob = null
        workManager.cancelAllWorkByTag(WorkerTags.VIDEO_PROCESSING)
        _sceneLinkDataList.value = emptyList() // Limpa o estado interno
        projectDataStoreManager.setSceneLinkDataList(emptyList()) // Persiste a lista vazia
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
            Toast.makeText(applicationContext, applicationContext.getString(R.string.toast_scene_prompts_and_images_queued), Toast.LENGTH_SHORT).show()
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

            Toast.makeText(applicationContext, applicationContext.getString(R.string.toast_batch_video_search_started), Toast.LENGTH_SHORT).show()

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
                Toast.makeText(applicationContext, "Cena não encontrada.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                Toast.makeText(applicationContext, "A cena já está processando.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (scene.promptVideo.isNullOrBlank()) {
                Toast.makeText(applicationContext, "A cena não tem um prompt de busca definido.", Toast.LENGTH_LONG).show()
                return@launch
            }
            internalUpdateSceneState(sceneId) { it.copy(isGeneratingVideo = true, generationErrorMessage = null) }
            val workRequest = OneTimeWorkRequestBuilder<PixabayVideoSearchWorker>()
                .setInputData(workDataOf(PixabayVideoSearchWorker.KEY_SCENE_ID to sceneId))
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .addTag("pixabay_search_${sceneId}")
                .build()
            workManager.enqueue(workRequest)
            Toast.makeText(applicationContext, "Iniciando busca automática de vídeo...", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearSceneGenerationError(sceneId: String) {
        viewModelScope.launch {
            internalUpdateSceneState(sceneId) {
                it.copy(generationErrorMessage = null)
            }
        }
    }

    fun generateVideoForScene(sceneId: String, videoPromptFromDialog: String, sourceImagePathFromSceneParameter: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId } ?: return@launch
            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                Toast.makeText(applicationContext, "A cena já está processando.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (videoPromptFromDialog.isBlank()){
                 Toast.makeText(applicationContext, "Prompt para vídeo não pode ser vazio.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val currentSourceImagePathForVideo = sourceImagePathFromSceneParameter
            if (currentSourceImagePathForVideo.isBlank()){
                 Toast.makeText(applicationContext, "Imagem base para vídeo não pode ser vazia.", Toast.LENGTH_SHORT).show()
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

    fun onPixabayVideoSelected(sceneId: String, video: PixabayVideo) {
        val videoUrl = video.videoFiles.small.url
        if (videoUrl.isBlank()) {
            Toast.makeText(applicationContext, "URL do vídeo selecionado é inválida.", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            internalUpdateSceneState(sceneId) { it.copy(isGeneratingVideo = true, generationErrorMessage = null) }
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            val workRequest = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(workDataOf(
                    VideoDownloadWorker.KEY_SCENE_ID to sceneId,
                    VideoDownloadWorker.KEY_VIDEO_URL to videoUrl,
                    VideoDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                ))
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .addTag("video_download_${sceneId}")
                .build()

            workManager.enqueue(workRequest)
            onDismissPixabaySearchDialog()
        }
    }

    fun onDismissPixabaySearchDialog() {
        _showPixabaySearchDialogForSceneId.value = null
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

    fun onShowPixabaySearchDialog(sceneId: String) {
        viewModelScope.launch {
            val scene = sceneLinkDataList.value.find { it.id == sceneId }
            _pixabaySearchQuery.value = scene?.promptVideo ?: ""
            _pixabaySearchResults.value = emptyList()
            _isSearchingPixabay.value = false
            _showPixabaySearchDialogForSceneId.value = sceneId
            if (pixabaySearchQuery.value.isNotBlank()){
                searchPixabayVideos()
            }
        }
    }

    fun onPixabaySearchQueryChanged(query: String) {
        _pixabaySearchQuery.value = query
    }

    fun searchPixabayVideos() {
        if (_isSearchingPixabay.value) return
        viewModelScope.launch {
            _isSearchingPixabay.value = true
            _pixabaySearchResults.value = emptyList()
            val result = PixabayApiClient.searchVideos(_pixabaySearchQuery.value)
            result.onSuccess { videos ->
                _pixabaySearchResults.value = videos
            }.onFailure { error ->
                Toast.makeText(applicationContext, "Erro na busca: ${error.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Erro ao buscar vídeos da Pixabay", error)
            }
            _isSearchingPixabay.value = false
        }
    }
}