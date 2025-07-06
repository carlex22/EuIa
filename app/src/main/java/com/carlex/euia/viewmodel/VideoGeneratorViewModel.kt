// File: euia/viewmodel/VideoGeneratorViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.GeminiImageApi
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.api.YouTubeUploadService
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.VideoGeneratorDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.prompts.CreateYouTubeMetadataPrompt
import com.carlex.euia.worker.VideoRenderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class VideoGeneratorViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VideoGeneratorVM"
    private val appContext = application.applicationContext

    // DataStore Managers e WorkManager
    private val audioDataStoreManager = AudioDataStoreManager(application)
    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(application)
    private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(application)
    private val workManager = WorkManager.getInstance(application)
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- Estados para Geração de Vídeo ---
    val isGeneratingVideo: StateFlow<Boolean> = videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val generatedVideoPath: StateFlow<String> = videoGeneratorDataStoreManager.finalVideoPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val generatedVideoTitle: StateFlow<String> = videoGeneratorDataStoreManager.generatedVideoTitle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val generatedAudioPrompt: StateFlow<String> = videoGeneratorDataStoreManager.generatedAudioPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    private val _generationProgress = MutableStateFlow(0.0f)
    val generationProgress: StateFlow<Float> = _generationProgress.asStateFlow()

    // --- Estados para o Diálogo de Upload ---
    private val _showUploadDialog = MutableStateFlow(false)
    val showUploadDialog: StateFlow<Boolean> = _showUploadDialog.asStateFlow()
    private val _uploadTitle = MutableStateFlow("")
    val uploadTitle: StateFlow<String> = _uploadTitle.asStateFlow()
    private val _uploadDescription = MutableStateFlow("")
    val uploadDescription: StateFlow<String> = _uploadDescription.asStateFlow()
    private val _uploadHashtags = MutableStateFlow("")
    val uploadHashtags: StateFlow<String> = _uploadHashtags.asStateFlow()
    private val _selectedThumbnailPath = MutableStateFlow<String?>(null)
    val selectedThumbnailPath: StateFlow<String?> = _selectedThumbnailPath.asStateFlow()
    private val _isGeneratingMetadata = MutableStateFlow(false)
    val isGeneratingMetadata: StateFlow<Boolean> = _isGeneratingMetadata.asStateFlow()
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()
    private val _uploadStatusMessage = MutableStateFlow("")
    val uploadStatusMessage: StateFlow<String> = _uploadStatusMessage.asStateFlow()

    // --- Lógica de Observação do Worker ---
    private var workInfoLiveData: LiveData<WorkInfo>? = null
    private val workObserver = Observer<WorkInfo> { info ->
        info ?: return@Observer
        _generationProgress.value = info.progress.getFloat(VideoRenderWorker.KEY_PROGRESS, _generationProgress.value)
        if (info.state.isFinished) {
            if (info.state == WorkInfo.State.SUCCEEDED) {
                viewModelScope.launch {
                    info.outputData.getString(VideoRenderWorker.KEY_OUTPUT_VIDEO_PATH)?.let {
                        videoGeneratorDataStoreManager.setFinalVideoPath(it)
                    }
                }
            }
            cleanupObserver()
        }
    }

    private fun observeWork(workId: UUID) {
        cleanupObserver()
        workInfoLiveData = workManager.getWorkInfoByIdLiveData(workId)
        workInfoLiveData?.observeForever(workObserver)
    }

    private fun cleanupObserver() {
        workInfoLiveData?.removeObserver(workObserver)
        workInfoLiveData = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanupObserver()
    }

    // --- Funções de Controle da UI e Lógica de Negócio ---
    private fun cleanGeminiJsonResponse(rawResponse: String): String {
        return rawResponse.trim().removePrefix("```json").removeSuffix("```").trim()
    }

    fun onOpenUploadDialog() {
        viewModelScope.launch {
            _uploadTitle.value = generatedVideoTitle.first().ifBlank { "" }
            _uploadDescription.value = generatedAudioPrompt.first().ifBlank { "" }
            _uploadHashtags.value = ""
            _selectedThumbnailPath.value = null
            _isGeneratingMetadata.value = false
            _isUploading.value = false
            _uploadStatusMessage.value = ""
            _showUploadDialog.value = true
        }
    }

    fun onDismissUploadDialog() { _showUploadDialog.value = false }
    fun updateUploadTitle(newTitle: String) { _uploadTitle.value = newTitle }
    fun updateUploadDescription(newDescription: String) { _uploadDescription.value = newDescription }
    fun updateUploadHashtags(newHashtags: String) { _uploadHashtags.value = newHashtags }
    fun onThumbnailSelected(path: String) { _selectedThumbnailPath.value = path }

    fun generateYouTubeMetadata() {
        viewModelScope.launch {
            _isGeneratingMetadata.value = true
            Toast.makeText(appContext, appContext.getString(R.string.toast_generating_metadata_started), Toast.LENGTH_SHORT).show()
            try {
                val narrative = audioDataStoreManager.prompt.first()
                val originalTitle = audioDataStoreManager.videoTitulo.first()
                val scenes = videoProjectDataStoreManager.sceneLinkDataList.first()
                val visualStyleDesc = scenes.take(3).mapNotNull { it.promptGeracao }.joinToString(separator = ". ").ifBlank { "Um vídeo informativo e dinâmico." }
                val prompt = CreateYouTubeMetadataPrompt(narrative, originalTitle, visualStyleDesc).prompt
                val result = GeminiTextAndVisionProRestApi.perguntarAoGemini(prompt, emptyList())

                if (result.isSuccess) {
                    val cleanedJson = cleanGeminiJsonResponse(result.getOrThrow())
                    val jsonElement = jsonParser.parseToJsonElement(cleanedJson)
                    val metadata: JsonObject = if (jsonElement is JsonArray && jsonElement.isNotEmpty()) jsonElement.first().jsonObject else jsonElement.jsonObject
                    _uploadTitle.value = metadata["title"]?.jsonPrimitive?.content ?: ""
                    _uploadDescription.value = metadata["description"]?.jsonPrimitive?.content ?: ""
                    _uploadHashtags.value = metadata["hashtags"]?.jsonPrimitive?.content ?: ""
                    
                    val thumbnailPrompt = metadata["thumbnail_prompt"]?.jsonPrimitive?.content
                    if (!thumbnailPrompt.isNullOrBlank()) {
                        generateThumbnailFromPrompt(thumbnailPrompt)
                    } else {
                        Toast.makeText(appContext, appContext.getString(R.string.toast_metadata_generated_no_thumb_prompt), Toast.LENGTH_SHORT).show()
                        _isGeneratingMetadata.value = false
                    }
                } else {
                    throw result.exceptionOrNull() ?: Exception(appContext.getString(R.string.error_generating_metadata_unknown))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao gerar metadados do YouTube", e)
                Toast.makeText(appContext, appContext.getString(R.string.error_generating_metadata_toast, e.message), Toast.LENGTH_LONG).show()
                _isGeneratingMetadata.value = false
            }
        }
    }

    private suspend fun generateThumbnailFromPrompt(prompt: String) {
        try {
            Toast.makeText(appContext, appContext.getString(R.string.toast_generating_thumbnail_started), Toast.LENGTH_SHORT).show()
            val result = GeminiImageApi.gerarImagem("youtube_thumb_${UUID.randomUUID().toString().take(6)}", prompt, appContext, emptyList())
            if (result.isSuccess) {
                _selectedThumbnailPath.value = result.getOrThrow()
                Toast.makeText(appContext, appContext.getString(R.string.toast_thumbnail_generated_success), Toast.LENGTH_SHORT).show()
            } else {
                throw result.exceptionOrNull() ?: Exception(appContext.getString(R.string.error_generating_thumbnail_unknown))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar thumbnail", e)
            Toast.makeText(appContext, appContext.getString(R.string.error_generating_thumbnail_toast, e.message), Toast.LENGTH_LONG).show()
        } finally {
            _isGeneratingMetadata.value = false
        }
    }

    fun finalizeYouTubeUpload(oauthAccessToken: String?) {
        if (_isUploading.value) return
        if (oauthAccessToken.isNullOrBlank()) { Toast.makeText(appContext, R.string.youtube_upload_auth_token_missing, Toast.LENGTH_LONG).show(); return }
        val videoPath = generatedVideoPath.value
        if (videoPath.isBlank()) { Toast.makeText(appContext, R.string.youtube_upload_video_file_not_found_toast, Toast.LENGTH_SHORT).show(); return }
        val thumbPath = _selectedThumbnailPath.value
        if (thumbPath.isNullOrBlank()) { Toast.makeText(appContext, R.string.youtube_upload_thumbnail_placeholder, Toast.LENGTH_SHORT).show(); return }

        viewModelScope.launch {
            _isUploading.value = true
            try {
                _uploadStatusMessage.value = "Enviando vídeo (Etapa 1 de 2)..."
                val videoUploadResult = YouTubeUploadService.uploadVideo(oauthAccessToken, File(videoPath), _uploadTitle.value, "${_uploadDescription.value}\n\n${_uploadHashtags.value}")
                
                if (videoUploadResult.isFailure) throw videoUploadResult.exceptionOrNull()!!
                val videoId = videoUploadResult.getOrThrow()

                _uploadStatusMessage.value = "Vídeo enviado! Enviando thumbnail (Etapa 2 de 2)..."
                val thumbnailResult = YouTubeUploadService.setThumbnail(oauthAccessToken, videoId, File(thumbPath))

                if (thumbnailResult.isFailure) {
                    _uploadStatusMessage.value = "Vídeo publicado (ID: $videoId), mas falha ao enviar thumbnail."
                } else {
                    _uploadStatusMessage.value = "Sucesso! Vídeo e thumbnail enviados. ID: $videoId"
                }

            } catch (e: Exception) {
                val error = e.message ?: appContext.getString(R.string.error_upload_unknown)
                _uploadStatusMessage.value = "Falha no processo: $error"
            } finally {
                _isUploading.value = false
            }
        }
    }
    
    fun generateVideo() {
        if (isGeneratingVideo.value) { Toast.makeText(appContext, R.string.video_gen_vm_status_already_generating, Toast.LENGTH_SHORT).show(); return }
        viewModelScope.launch {
            videoGeneratorDataStoreManager.clearFinalVideoPath()
            _generationProgress.value = 0f
            val scenesToInclude = videoProjectDataStoreManager.sceneLinkDataList.first().filter { it.imagemGeradaPath?.isNotBlank() == true && it.tempoInicio != null && it.tempoFim != null && it.tempoFim > it.tempoInicio }
            if (scenesToInclude.isEmpty()) { Toast.makeText(appContext, R.string.video_gen_vm_error_no_valid_scenes, Toast.LENGTH_LONG).show(); return@launch }
            val audioFilePath = audioDataStoreManager.audioPath.first()
            if(audioFilePath.isBlank()){ Toast.makeText(appContext, R.string.video_gen_vm_error_main_audio_missing, Toast.LENGTH_LONG).show(); return@launch }
            val musicFilePath = audioDataStoreManager.videoMusicPath.first()
            val legendFilePath = audioDataStoreManager.legendaPath.first()
            val inputData = workDataOf(VideoRenderWorker.KEY_AUDIO_PATH to audioFilePath, VideoRenderWorker.KEY_MUSIC_PATH to musicFilePath, VideoRenderWorker.KEY_LEGEND_PATH to legendFilePath)

            // CORREÇÃO: A chamada para enfileirar o trabalho é a mesma.
            // O WorkManager usará a configuração do AndroidManifest para decidir o processo.
            val videoRenderRequest = OneTimeWorkRequestBuilder<VideoRenderWorker>()
                .setInputData(inputData)
                .addTag(VideoRenderWorker.TAG_VIDEO_RENDER)
                .build()
                
            workManager.enqueue(videoRenderRequest)
            
            observeWork(videoRenderRequest.id)
            Toast.makeText(appContext, R.string.video_gen_vm_toast_generation_started_background, Toast.LENGTH_LONG).show()
        }
    }
}