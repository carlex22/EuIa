// File: app/src/main/java/com/carlex/euia/viewmodel/VideoPreferencesViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.api.Audio // Para vozes do HF Space
import com.carlex.euia.api.GeminiAudio // Para vozes do Gemini
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

class VideoPreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = VideoPreferencesDataStoreManager(application)
    private val appContext = application.applicationContext
    private val TAG = "VideoPrefsVM"

    val preferredMaleVoice: StateFlow<String> = dataStoreManager.preferredMaleVoice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val preferredFemaleVoice: StateFlow<String> = dataStoreManager.preferredFemaleVoice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val voicePitch: StateFlow<Float> = dataStoreManager.voicePitch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1.0f)

    val voiceRate: StateFlow<Float> = dataStoreManager.voiceRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1.0f)

    val videoAspectRatio: StateFlow<String> = dataStoreManager.videoAspectRatio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "9:16")

    val videoLargura: StateFlow<Int?> = dataStoreManager.videoLargura
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), dataStoreManager.calculateDimensions("9:16").first)

    val videoAltura: StateFlow<Int?> = dataStoreManager.videoAltura
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), dataStoreManager.calculateDimensions("9:16").second)


    val enableSubtitles: StateFlow<Boolean> = dataStoreManager.enableSubtitles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val enableSceneTransitions: StateFlow<Boolean> = dataStoreManager.enableSceneTransitions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val videoFps: StateFlow<Int> = dataStoreManager.videoFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 30)

    val videoHdMotion: StateFlow<Boolean> = dataStoreManager.videoHdMotion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val enableZoomPan: StateFlow<Boolean> = dataStoreManager.enableZoomPan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val defaultSceneDurationSeconds: StateFlow<Float> = dataStoreManager.defaultSceneDurationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5.0f)

    private val _availableMaleVoices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableMaleVoices: StateFlow<List<Pair<String, String>>> = _availableMaleVoices.asStateFlow()

    private val _availableFemaleVoices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableFemaleVoices: StateFlow<List<Pair<String, String>>> = _availableFemaleVoices.asStateFlow()

    private val _isLoadingVoices = MutableStateFlow(false)
    val isLoadingVoices: StateFlow<Boolean> = _isLoadingVoices.asStateFlow()

    private val _voiceLoadingError = MutableStateFlow<String?>(null)
    val voiceLoadingError: StateFlow<String?> = _voiceLoadingError.asStateFlow()

    val aspectRatioOptions: List<String> = listOf(
        "9:16", "16:9", "1:1", "2:1", "1:2", "4:3", "3:2", "2:3"
    ).distinct()

    val fpsOptions: List<String> = listOf("24", "30", "60")

    // <<< INÍCIO DAS NOVAS PREFERÊNCIAS >>>
    val defaultSceneType: StateFlow<String> = dataStoreManager.defaultSceneType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "Vídeo")
    val defaultImageStyle: StateFlow<String> = dataStoreManager.defaultImageStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "Foto realista")
    val preferredAiModel: StateFlow<String> = dataStoreManager.preferredAiModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "Gemini 2.5 Pro")

    val sceneTypeOptions: List<String> = listOf("Vídeo", "Imagem")
    val imageStyleOptions: List<String> = listOf("Foto realista", "Cinematográfico", "Cartoon", "Anime", "Arte Digital", "Aquarela", "Desenho a Lápis")
    val aiModelOptions: List<String> = listOf("Gemini 2.5 Pro", "Gemini 2.0 Flash", "DALL-E 3", "Midjourney (via API)", "Claude 3 Opus")
    // <<< FIM DAS NOVAS PREFERÊNCIAS >>>


    init {
        loadInitialVoices()
        viewModelScope.launch {
            dataStoreManager.videoAspectRatio.collect { ratio ->
                Log.d(TAG, "Aspect Ratio alterado para: $ratio. Largura: ${videoLargura.value}, Altura: ${videoAltura.value}")
            }
        }
    }

    private fun loadInitialVoices() {
        fetchAvailableVoices("Male")
        fetchAvailableVoices("Female")
    }

    fun fetchAvailableVoices(genderForApi: String) {
        if (_isLoadingVoices.value) {
            val currentList = if (genderForApi == "Male") _availableMaleVoices.value else _availableFemaleVoices.value
            if (currentList.isNotEmpty()) return
        }

        _isLoadingVoices.value = true
        _voiceLoadingError.value = null
        viewModelScope.launch {
            Log.d(TAG, "Buscando vozes para gênero: $genderForApi")

            val useGeminiVoices = true // Defina como true para usar Gemini, false para HF Space

            val result: kotlin.Result<List<Pair<String, String>>> = if (useGeminiVoices) {
                GeminiAudio.getAvailableVoices(gender = genderForApi) // 'locale' tem valor padrão em GeminiAudio
            } else {
                Audio.getAvailableVoices(idioma = "pt-BR", gender = genderForApi).let { stringListResult ->
                    if (stringListResult.isSuccess) {
                        kotlin.Result.success(
                            stringListResult.getOrThrow().map { voiceName ->
                                Pair(voiceName, appContext.getString(R.string.voice_style_default))
                            }
                        )
                    } else {
                        kotlin.Result.failure(stringListResult.exceptionOrNull() ?: Exception("Unknown error from Audio.getAvailableVoices"))
                    }
                }
            }

            result.onSuccess { voicePairList ->
                Log.d(TAG, "Vozes (Pares) para $genderForApi recebidas: ${voicePairList.size}")
                if (genderForApi == "Male") {
                    _availableMaleVoices.value = voicePairList
                    if (preferredMaleVoice.value.isNotBlank() && voicePairList.none { it.first == preferredMaleVoice.value }) {
                        Log.d(TAG, "Voz masculina preferida ('${preferredMaleVoice.value}') não encontrada na nova lista. Resetando.")
                        setPreferredMaleVoice("")
                    }
                } else if (genderForApi == "Female") {
                    _availableFemaleVoices.value = voicePairList
                    if (preferredFemaleVoice.value.isNotBlank() && voicePairList.none { it.first == preferredFemaleVoice.value }) {
                        Log.d(TAG, "Voz feminina preferida ('${preferredFemaleVoice.value}') não encontrada na nova lista. Resetando.")
                        setPreferredFemaleVoice("")
                    }
                }
            }.onFailure { exception ->
                val errorMsgResource = when (exception) {
                    is IOException -> R.string.error_network_voices
                    else -> R.string.error_fetch_voices_failed
                }
                val errorMsg = appContext.getString(errorMsgResource, exception.localizedMessage ?: appContext.getString(R.string.unknown_error))
                Log.e(TAG, "Erro ao buscar vozes para $genderForApi: $errorMsg", exception)
                _voiceLoadingError.value = "Erro ($genderForApi): $errorMsg"
                if (genderForApi == "Male") _availableMaleVoices.value = emptyList()
                else if (genderForApi == "Female") _availableFemaleVoices.value = emptyList()
            }
            _isLoadingVoices.value = false
        }
    }

    fun setPreferredMaleVoice(voiceName: String) {
        viewModelScope.launch { dataStoreManager.setPreferredMaleVoice(voiceName) }
    }

    fun setPreferredFemaleVoice(voiceName: String) {
        viewModelScope.launch { dataStoreManager.setPreferredFemaleVoice(voiceName) }
    }

    fun setVoicePitch(pitch: Float) {
        viewModelScope.launch { dataStoreManager.setVoicePitch(pitch) }
    }
    fun setVoicePitch(pitchString: String) {
        val pitch = pitchString.replace(",", ".").toFloatOrNull() ?: 1.0f
        setVoicePitch(pitch)
    }

    fun setVoiceRate(rate: Float) {
        viewModelScope.launch { dataStoreManager.setVoiceRate(rate) }
    }
    fun setVoiceRate(rateString: String) {
        val rate = rateString.replace(",", ".").toFloatOrNull() ?: 1.0f
        setVoiceRate(rate)
    }

    fun setVideoFps(fpsString: String) {
        viewModelScope.launch { dataStoreManager.setVideoFps(fpsString.toIntOrNull() ?: 30) }
    }

    fun setVideoHdMotion(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setVideoHdMotion(enabled) }
    }

    fun setVideoAspectRatio(aspectRatio: String) {
        viewModelScope.launch { dataStoreManager.setVideoAspectRatio(aspectRatio) }
    }

    fun setEnableSubtitles(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setEnableSubtitles(enabled) }
    }

    fun setEnableSceneTransitions(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setEnableSceneTransitions(enabled) }
    }

    fun setEnableZoomPan(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setEnableZoomPan(enabled) }
    }

    fun setDefaultSceneDurationSeconds(durationString: String) {
        val duration = durationString.replace(",", ".").toFloatOrNull() ?: 5.0f
        viewModelScope.launch {
            dataStoreManager.setDefaultSceneDurationSeconds(duration.coerceAtLeast(0.1f))
        }
    }
    
    // <<< INÍCIO DAS NOVAS FUNÇÕES DE ATUALIZAÇÃO >>>
    fun setDefaultSceneType(type: String) {
        viewModelScope.launch { dataStoreManager.setDefaultSceneType(type) }
    }

    fun setDefaultImageStyle(style: String) {
        viewModelScope.launch { dataStoreManager.setDefaultImageStyle(style) }
    }

    fun setPreferredAiModel(model: String) {
        viewModelScope.launch { dataStoreManager.setPreferredAiModel(model) }
    }
    // <<< FIM DAS NOVAS FUNÇÕES DE ATUALIZAÇÃO >>>


    fun clearVoiceLoadingError() {
        _voiceLoadingError.value = null
    }
}