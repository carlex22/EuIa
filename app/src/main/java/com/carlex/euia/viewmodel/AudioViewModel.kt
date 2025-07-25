// File: viewmodel/AudioViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalView
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer // Import Observer
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo // Import WorkInfo
import androidx.work.WorkManager
import com.carlex.euia.R
import com.carlex.euia.api.Audio
import com.carlex.euia.api.GeminiAudio
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.UserInfoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.worker.AudioNarrativeWorker
import com.carlex.euia.worker.KEY_IS_CHAT_NARRATIVE
import com.carlex.euia.worker.KEY_IS_NEW_NARRATIVE
import com.carlex.euia.worker.KEY_PROMPT_TO_USE
import com.carlex.euia.worker.KEY_VOICE_OVERRIDE
import com.carlex.euia.worker.KEY_VOICE_SPEAKER_1
import com.carlex.euia.worker.KEY_VOICE_SPEAKER_2
import com.carlex.euia.worker.KEY_VOICE_SPEAKER_3
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.worker.UrlImportWorker // Import UrlImportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    private val audioDataStoreManager = AudioDataStoreManager(application)
    private val userInfoDataStoreManager = UserInfoDataStoreManager(application)
    private val refImageDataStoreManager = RefImageDataStoreManager(application)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(application)
    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(application)

    private val appContext: Context = application.applicationContext
    private val TAG = "AudioViewModel"
    private val workManager = WorkManager.getInstance(application)

    private val _sexo = MutableStateFlow("Female")
    val sexo: StateFlow<String> = _sexo.asStateFlow()

    private val _emocao = MutableStateFlow("Neutro")
    val emocao: StateFlow<String> = _emocao.asStateFlow()

    private val _idade = MutableStateFlow(30)
    val idade: StateFlow<Int> = _idade.asStateFlow()

    val voz: StateFlow<String> = audioDataStoreManager.voz
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val voiceSpeaker1: StateFlow<String> = audioDataStoreManager.voiceSpeaker1
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val voiceSpeaker2: StateFlow<String?> = audioDataStoreManager.voiceSpeaker2
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val voiceSpeaker3: StateFlow<String?> = audioDataStoreManager.voiceSpeaker3
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val isChatNarrative: StateFlow<Boolean> = audioDataStoreManager.isChatNarrative
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val narrativeContextFilePath: StateFlow<String> = audioDataStoreManager.narrativeContextFilePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val prompt: StateFlow<String> = audioDataStoreManager.prompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val audioPath: StateFlow<String> = audioDataStoreManager.audioPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val legendaPath: StateFlow<String> = audioDataStoreManager.legendaPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val isAudioProcessing: StateFlow<Boolean> = audioDataStoreManager.isAudioProcessing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val generationProgressText: StateFlow<String> = audioDataStoreManager.generationProgressText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val generationError: StateFlow<String?> = audioDataStoreManager.generationError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val userNameCompanyAudio: StateFlow<String> = audioDataStoreManager.userNameCompanyAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val userProfessionSegmentAudio: StateFlow<String> = audioDataStoreManager.userProfessionSegmentAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val userAddressAudio: StateFlow<String> = audioDataStoreManager.userAddressAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val userLanguageToneAudio: StateFlow<String> = audioDataStoreManager.userLanguageToneAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val userTargetAudienceAudio: StateFlow<String> = audioDataStoreManager.userTargetAudienceAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoTitulo: StateFlow<String> = audioDataStoreManager.videoTitulo.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoExtrasAudio: StateFlow<String> = audioDataStoreManager.videoExtrasAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoImagensReferenciaJsonAudio: StateFlow<String> = audioDataStoreManager.videoImagensReferenciaJsonAudio.stateIn(viewModelScope, SharingStarted.Eagerly, "[]")
    val videoMusicPath: StateFlow<String> = audioDataStoreManager.videoMusicPath.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoObjectiveIntroduction: StateFlow<String> = audioDataStoreManager.videoObjectiveIntroduction.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoObjectiveVideo: StateFlow<String> = audioDataStoreManager.videoObjectiveVideo.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoObjectiveOutcome: StateFlow<String> = audioDataStoreManager.videoObjectiveOutcome.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val videoTimeSeconds: StateFlow<String> = audioDataStoreManager.videoTimeSeconds.stateIn(viewModelScope, SharingStarted.Eagerly, "")


    val referenceImageCount: StateFlow<Int> = audioDataStoreManager.videoImagensReferenciaJsonAudio.map { jsonString ->
        try {
            if (jsonString.isNotBlank() && jsonString.trim().startsWith("[")) JSONArray(jsonString).length() else 0
        } catch (e: JSONException) { 0 }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val refObjetoDetalhesCount: StateFlow<Int> = refImageDataStoreManager.refObjetoDetalhesJson.map { jsonString ->
            try {
                if (jsonString.isNotBlank() && jsonString.trim().startsWith("{") && jsonString.trim().endsWith("}")) JSONObject(jsonString).length() else 0
            } catch (e: JSONException) { 0 }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _availableVoices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableVoices: StateFlow<List<Pair<String, String>>> = _availableVoices.asStateFlow()

    private val _isLoadingVoices = MutableStateFlow(false)
    val isLoadingVoices: StateFlow<Boolean> = _isLoadingVoices.asStateFlow()
    private val _voiceLoadingError = MutableStateFlow<String?>(null)
    val voiceLoadingError: StateFlow<String?> = _voiceLoadingError.asStateFlow()

    private val _showClearScenesDialog = MutableStateFlow(false)
    val showClearScenesDialog: StateFlow<Boolean> = _showClearScenesDialog.asStateFlow()

    private val _showSavePromptConfirmationDialog = MutableStateFlow(false)
    val showSavePromptConfirmationDialog: StateFlow<Boolean> = _showSavePromptConfirmationDialog.asStateFlow()

    private var pendingPromptToUse: String? = null
    private var pendingIsNewNarrative: Boolean = false
    private var pendingVoiceToUseOverride: String? = null
    private var pendingPromptToSaveAndGenerate: String? = null

    // Novo estado para controlar a importação da URL
    private val _isUrlImporting = MutableStateFlow(false)
    val isUrlImporting: StateFlow<Boolean> = _isUrlImporting.asStateFlow() // Este é o Flow que a UI vai observar
    
    
    // Observer para os workers de importação de URL
    private val urlImportWorkObserver = Observer<List<WorkInfo>> { workInfos ->
        val isStillImporting = workInfos.any {
            it.tags.contains(UrlImportWorker.TAG_URL_IMPORT_WORK_PRE_CONTEXT) || it.tags.contains(UrlImportWorker.TAG_URL_IMPORT_WORK_CONTENT_DETAILS)
        } && workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED} // Adicionado BLOCKED
        
        // CORREÇÃO: Esta linha estava faltando!
        _isUrlImporting.value = isStillImporting
        // FIM DA CORREÇÃO

        // Considerar a lógica de erro aqui, se um worker falhou
        /*val failedWork = workInfos.firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failedWork != null && !isStillImporting) {
            val errorMessage = failedWork.outputData.getString(UrlImportWorker.KEY_OUTPUT_ERROR_MESSAGE)
            viewModelScope.launch {
                // Atualiza o estado de erro global para que o UI possa reagir
                audioDataStoreManager.setGenerationError(errorMessage) 
                showToastOverlay("Importação de URL falhou: $errorMessage")
            }
        }*/
    }
    

    init {
       workManager.getWorkInfosByTagLiveData(UrlImportWorker.TAG_URL_IMPORT_WORK_PRE_CONTEXT)
            .observeForever(urlImportWorkObserver)
        workManager.getWorkInfosByTagLiveData(UrlImportWorker.TAG_URL_IMPORT_WORK_CONTENT_DETAILS)
            .observeForever(urlImportWorkObserver)
    }
    
    fun loadingFirst(){
        Log.d(TAG, "--- AudioViewModel Init ---")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initialSexo = audioDataStoreManager.sexo.first()
                _sexo.value = initialSexo
                _emocao.value = audioDataStoreManager.emocao.first()
                _idade.value = audioDataStoreManager.idade.first()
                val initialVoiceSpeaker1 = audioDataStoreManager.voiceSpeaker1.first()
                val initialVozLegada = audioDataStoreManager.voz.first()
                var effectiveInitialVoice1 = if (initialVoiceSpeaker1.isBlank()) {
                    if (initialSexo.equals("Male", ignoreCase = true)) {
                            videoPreferencesDataStoreManager.preferredMaleVoice.first()
                        } else {
                            videoPreferencesDataStoreManager.preferredFemaleVoice.first()
                        }
                    } else {
                            initialVoiceSpeaker1
                    }
                setVoiceSpeaker1(effectiveInitialVoice1)
                fetchAvailableVoices(genderForApi = initialSexo, preSelectedVoiceNameCandidate = effectiveInitialVoice1, voice  = "speaker1")
            } catch (e: Exception) {
                showToastOverlay("Erro ao carregar preferências de áudio")
            }
        }
    }

    fun setVoiceSpeaker1(voiceName: String) {
        viewModelScope.launch {
            audioDataStoreManager.setVoiceSpeaker1(voiceName)
            //audioDataStoreManager.setVoz(voiceName)
        }
    }

    fun setVoiceSpeaker2(voiceName: String) {
        viewModelScope.launch { 
            audioDataStoreManager.setVoiceSpeaker2(voiceName) 
            setIsChatNarrative(!voiceName.isNullOrBlank())
        }
    }

    fun setVoiceSpeaker3(voiceName: String?) {
        viewModelScope.launch {
            audioDataStoreManager.setVoiceSpeaker3(voiceName)
            setIsChatNarrative(voiceName.isNullOrBlank())
         }
    }

    fun setIsChatNarrative(isChat: Boolean) {
        viewModelScope.launch {
            audioDataStoreManager.setIsChatNarrative(isChat)
        }
    }

    fun setNarrativeContextFile(uri: Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                audioDataStoreManager.setNarrativeContextFilePath("")
                showToastOverlay(appContext.getString(R.string.audio_info_toast_context_file_cleared))
            } else {
                val uriString = uri.toString()
                audioDataStoreManager.setNarrativeContextFilePath(uriString)
                val fileName = getFileNameFromUri(appContext, uri)
                showToastOverlay(appContext.getString(R.string.audio_info_toast_context_file_selected, fileName))
            }
        }
    }

    fun setSexo(novoSexo: String) {
       // if (_sexo.value.equals(novoSexo, ignoreCase = true)) return
        _sexo.value = novoSexo
        viewModelScope.launch(Dispatchers.IO) {
            audioDataStoreManager.setSexo(novoSexo)
        }
    }

    fun setVoz(novaVozNome: String) {
        viewModelScope.launch {
            audioDataStoreManager.setVoz(novaVozNome)
            if (!isChatNarrative.value && voiceSpeaker1.value != novaVozNome) {
                 audioDataStoreManager.setVoiceSpeaker1(novaVozNome)
            }
        }
    }

    fun setEmocao(novaEmocao: String) {
       // if (_emocao.value == novaEmocao) return
        _emocao.value = novaEmocao
        viewModelScope.launch { audioDataStoreManager.setEmocao(novaEmocao) }
    }

    fun setIdade(novaIdade: Int) {
      //  if (_idade.value == novaIdade) return
        _idade.value = novaIdade
        viewModelScope.launch { audioDataStoreManager.setIdade(novaIdade) }
    }
    
    fun clearVoice(voice: String? = null) {
        when (voice) {
            "speaker1" -> setVoiceSpeaker1("")
            "speaker2" -> {
                setVoiceSpeaker2("")
                setIsChatNarrative(false)
            }
            "speaker3" -> {
                setVoiceSpeaker3("") 
                setIsChatNarrative(false)
            }
            else -> { setVoiceSpeaker1("") }
        }
        
    }

    fun setPrompt(novoPrompt: String) {
         viewModelScope.launch { audioDataStoreManager.setPrompt(novoPrompt) }
    }
    
    private val _snackbarMessage = MutableStateFlow<String?>(null) // Usando StateFlow
    val snackbarMessage: StateFlow<String?> = _snackbarMessage

    fun setSnackbarMessage(message: String) {
        _snackbarMessage.value = message
    }
    

    public fun showToast(message: String) {
        setSnackbarMessage(message)
    }
    
    
    public fun showToastOverlay(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun fetchAvailableVoices(genderForApi: String, localeParam: String = "pt-BR", preSelectedVoiceNameCandidate: String? = null, voice: String) {
        viewModelScope.launch {
            val useGeminiVoices = true

            val result: kotlin.Result<List<Pair<String, String>>> = if (useGeminiVoices) {
                GeminiAudio.getAvailableVoices(gender = genderForApi, locale = localeParam)
            } else {
                Audio.getAvailableVoices(idioma = localeParam, gender = genderForApi).let { stringListResult ->
                    if (stringListResult.isSuccess){
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
            
            showToastOverlay(preSelectedVoiceNameCandidate!!)

            result.onSuccess { voicePairList ->
                _availableVoices.value = voicePairList
                var voiceToConsiderForSetting = preSelectedVoiceNameCandidate!!
                
                if (voiceToConsiderForSetting.isNotBlank() && voicePairList.any { it.first == voiceToConsiderForSetting }) {
                    when (voice) {
                        "speaker1" -> {
                            setVoiceSpeaker1(voiceToConsiderForSetting)
                        }
                        "speaker2" -> {
                            setVoiceSpeaker2(voiceToConsiderForSetting)
                        }
                        "speaker3" -> {
                            setVoiceSpeaker3(voiceToConsiderForSetting)
                        }
                        else -> { setVoiceSpeaker1(voiceToConsiderForSetting) }
                    }
                }
            }.onFailure {  exception ->
                 //_voiceLoadingError.value = errorMsg
                 showToastOverlay("Erro ao obter vozes")
            }
        }
    }

    fun requestSavePromptAndGenerateAudio(newPrompt: String) {
        viewModelScope.launch {
            if (isAudioProcessing.value) {
                showToast(appContext.getString(R.string.progress_already_processing))
                return@launch
            }
            pendingPromptToSaveAndGenerate = newPrompt
            val currentAudio = audioPath.value
            val currentScenes = videoProjectDataStoreManager.sceneLinkDataList.first()
            if (currentAudio.isNotBlank() || currentScenes.isNotEmpty()) {
                _showSavePromptConfirmationDialog.value = true
            } else {
                proceedWithSaveAndGenerate()
            }
        }
    }

    fun confirmSavePromptAndGenerate() {
        viewModelScope.launch {
            _showSavePromptConfirmationDialog.value = false
            proceedWithSaveAndGenerate()
        }
    }

    fun cancelSavePromptConfirmation() {
        _showSavePromptConfirmationDialog.value = false
        pendingPromptToSaveAndGenerate = null
    }

    private suspend fun proceedWithSaveAndGenerate() {
        val newPrompt = pendingPromptToSaveAndGenerate
        if (newPrompt == null) {
            updateAudioGenerationStatus(isProcessing = false, error = appContext.getString(R.string.error_internal_prompt_undefined))
            return
        }
        updateAudioGenerationStatus(isProcessing = true, progressText = appContext.getString(R.string.progress_clearing_data))
        val oldAudioPath = audioDataStoreManager.audioPath.first()
        val oldLegendaPath = audioDataStoreManager.legendaPath.first()
        if (oldAudioPath.isNotBlank()) {
            withContext(Dispatchers.IO) { File(oldAudioPath).delete() }
            audioDataStoreManager.setAudioPath("")
        }
        if (oldLegendaPath.isNotBlank()) {
            withContext(Dispatchers.IO) { File(oldLegendaPath).delete() }
            audioDataStoreManager.setLegendaPath("")
        }
        val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
        if (projectDirName.isNotBlank()) {
            deleteProjectSubDirectory(projectDirName, "gemini_generated_images")
            deleteProjectSubDirectory(projectDirName, "gemini_img3_generated_images")
        }
        videoProjectDataStoreManager.clearProjectState()
        audioDataStoreManager.setPrompt(newPrompt)
        showToast(appContext.getString(R.string.audio_info_prompt_saved_and_generating))

        startAudioGenerationInternal(
            promptToUse = newPrompt,
            isNewNarrative = false,
            voiceToUseOverride = if (isChatNarrative.value) null else voiceSpeaker1.value,
            isChat = isChatNarrative.value,
            speaker1Voice = voiceSpeaker1.value,
            speaker2Voice = voiceSpeaker2.value!!,
            speaker3Voice = ""
        )
        pendingPromptToSaveAndGenerate = null
    }

    fun startAudioGeneration(promptToUse: String, isNewNarrative: Boolean, voiceToUseOverride: String? = null) {
        viewModelScope.launch {
            if (isAudioProcessing.value) {
                showToast(appContext.getString(R.string.progress_already_processing))
                return@launch
            }
            pendingPromptToUse = promptToUse
            pendingIsNewNarrative = isNewNarrative
            pendingVoiceToUseOverride = voiceToUseOverride

            val currentSceneLinks: List<SceneLinkData> = videoProjectDataStoreManager.sceneLinkDataList.first()
            if (currentSceneLinks.isNotEmpty()) {
                _showClearScenesDialog.value = true
            } else {
                proceedWithAudioGenerationFromExternalTrigger()
            }
        }
    }

    fun confirmAndProceedWithAudioGeneration() {
        _showClearScenesDialog.value = false
        viewModelScope.launch(Dispatchers.IO) {
            updateAudioGenerationStatus(isProcessing = true, progressText = appContext.getString(R.string.progress_clearing_data))
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            if (projectDirName.isNotBlank()) {
                deleteProjectSubDirectory(projectDirName, "gemini_generated_images")
                deleteProjectSubDirectory(projectDirName, "gemini_img3_generated_images")
            }
            videoProjectDataStoreManager.clearProjectState()
            proceedWithAudioGenerationFromExternalTrigger()
        }
    }

    fun cancelClearScenesDialog() {
        _showClearScenesDialog.value = false
        clearPendingExternalTriggerParams()
    }

    private suspend fun proceedWithAudioGenerationFromExternalTrigger() {
        val prompt = pendingPromptToUse
        val isNew = pendingIsNewNarrative
        val voiceOverride = pendingVoiceToUseOverride

        if (prompt == null) {
            updateAudioGenerationStatus(isProcessing = false, error = appContext.getString(R.string.error_internal_prompt_undefined))
            clearPendingExternalTriggerParams()
            return
        }
        audioDataStoreManager.setPrompt(prompt)
        startAudioGenerationInternal(
            promptToUse = prompt,
            isNewNarrative = isNew,
            voiceToUseOverride = if (isChatNarrative.value) null else (voiceOverride ?: voiceSpeaker1.value),
            isChat = isChatNarrative.value,
            speaker1Voice = voiceSpeaker1.value ?: "",
            speaker2Voice = voiceSpeaker2.value ?: "",
            speaker3Voice = ""
        )
        clearPendingExternalTriggerParams()
    }

    private fun clearPendingExternalTriggerParams() {
        pendingPromptToUse = null
        pendingIsNewNarrative = false
        pendingVoiceToUseOverride = null
    }

    private suspend fun startAudioGenerationInternal(
        promptToUse: String,
        isNewNarrative: Boolean,
        voiceToUseOverride: String?,
        isChat: Boolean,
        speaker1Voice: String,
        speaker2Voice: String,
        speaker3Voice: String?
    ) {
        updateAudioGenerationStatus(isProcessing = true, progressText = appContext.getString(R.string.progress_starting_generation))

        val inputDataBuilder = Data.Builder()
            .putString(KEY_PROMPT_TO_USE, promptToUse)
            .putBoolean(KEY_IS_NEW_NARRATIVE, isNewNarrative)
            .putBoolean(KEY_IS_CHAT_NARRATIVE, isChat)

        if (isChat) {
            if (speaker1Voice.isNotBlank()) inputDataBuilder.putString(KEY_VOICE_SPEAKER_1, speaker1Voice) else {
                updateAudioGenerationStatus(false, null, appContext.getString(R.string.error_chat_speaker1_voice_mandatory))
                return
            }
            if (speaker2Voice.isNotBlank()) inputDataBuilder.putString(KEY_VOICE_SPEAKER_2, speaker2Voice) else {
                 updateAudioGenerationStatus(false, null, appContext.getString(R.string.error_chat_speaker2_voice_mandatory))
                return
            }
            if (!speaker3Voice.isNullOrBlank()) inputDataBuilder.putString(KEY_VOICE_SPEAKER_3, speaker3Voice)
        } else {
            val finalSingleVoiceToUse = voiceToUseOverride ?: speaker1Voice
            if (finalSingleVoiceToUse.isNotBlank()) {
                inputDataBuilder.putString(KEY_VOICE_OVERRIDE, finalSingleVoiceToUse)
            } else {
                updateAudioGenerationStatus(false, null, appContext.getString(R.string.error_narrator_voice_not_selected_internal))
                return
            }
        }

        val audioGenWorkRequest = OneTimeWorkRequestBuilder<AudioNarrativeWorker>()
            .setInputData(inputDataBuilder.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WorkerTags.AUDIO_NARRATIVE)
            .build()
        workManager.enqueue(audioGenWorkRequest)
    }

    private suspend fun updateAudioGenerationStatus(isProcessing: Boolean? = null, progressText: String? = null, error: String? = null) {
        isProcessing?.let { audioDataStoreManager.setIsAudioProcessing(it) }
        progressText?.let { audioDataStoreManager.setGenerationProgressText(it) }
        if (error != null) { 
           audioDataStoreManager.setGenerationError(error)
           setSnackbarMessage(error!!)
        }
        if (progressText != null) { 
           audioDataStoreManager.setGenerationProgressText(progressText)
           showToastOverlay(progressText!!)
        }
    }


    fun clearGenerationError() {
         viewModelScope.launch { audioDataStoreManager.setGenerationError(null) }
    }
    
    fun clearAudioGenerationStatus() {
         viewModelScope.launch { audioDataStoreManager.setGenerationProgressText("") }
    }

    private suspend fun deleteProjectSubDirectory(projectDirName: String, subDirToDeleteName: String) {
        if (projectDirName.isBlank() || subDirToDeleteName.isBlank()) {
            Log.d(TAG, "Tentativa de deletar subdiretório com nome de projeto ou subdiretório em branco. Operação ignorada.")
            return
        }
        suspend fun tryDelete(dir: File) {
            if (dir.exists() && dir.isDirectory) {
                val success = withContext(Dispatchers.IO) { dir.deleteRecursively() }
                if (success) {
                    Log.d(TAG, "Subdiretório '$subDirToDeleteName' em '${dir.parent}' excluído com sucesso.")
                } else {
                    Log.d(TAG, "Falha ao excluir subdiretório '$subDirToDeleteName' em '${dir.parent}'.")
                }
            } else {
                Log.d(TAG, "Subdiretório '$subDirToDeleteName' não encontrado em '${dir.parent}' para exclusão.")
            }
        }
        appContext.getExternalFilesDir(null)?.let { externalBaseDir ->
            val subDirExternal = File(File(externalBaseDir, projectDirName), subDirToDeleteName)
            tryDelete(subDirExternal)
        }
        val internalSubDir = File(File(appContext.filesDir, projectDirName), subDirToDeleteName)
        tryDelete(internalSubDir)
    }

    fun setVideoObjectiveIntroduction(objective: String) { viewModelScope.launch { audioDataStoreManager.setVideoObjectiveIntroduction(objective) } }
    fun setVideoObjectiveVideo(objective: String) { viewModelScope.launch { audioDataStoreManager.setVideoObjectiveVideo(objective) } }
    fun setVideoObjectiveOutcome(objective: String) { viewModelScope.launch { audioDataStoreManager.setVideoObjectiveOutcome(objective) } }
    fun setVideoTimeSeconds(time: String) { viewModelScope.launch { audioDataStoreManager.setVideoTimeSeconds(time) } }
    fun setVideoTitulo(titulo: String) { viewModelScope.launch { audioDataStoreManager.setVideoTitulo(titulo) } }
    fun setUserTargetAudienceAudio(audience: String) { viewModelScope.launch { audioDataStoreManager.setUserTargetAudienceAudio(audience) } }
    fun setUserLanguageToneAudio(tone: String) { viewModelScope.launch { audioDataStoreManager.setUserLanguageToneAudio(tone) } }

     fun startUrlImport(url: String) {
        if (url.isBlank()) {
            showToastOverlay(appContext.getString(R.string.error_url_not_provided_toast))
            return
        }
        if (_isUrlImporting.value) {
            showToast(appContext.getString(R.string.url_import_already_in_progress))
            return
        }
        _isUrlImporting.value = true // Define o estado de importação
        viewModelScope.launch { showToast(appContext.getString(R.string.toast_starting_url_import)) }

        val inputData = Data.Builder()
            .putString(UrlImportWorker.KEY_URL_INPUT, url)
            .putString(UrlImportWorker.KEY_ACTION, UrlImportWorker.ACTION_PRE_CONTEXT_EXTRACTION)
            .build()
        val request = OneTimeWorkRequestBuilder<UrlImportWorker>()
            .setInputData(inputData)
            .addTag(UrlImportWorker.TAG_URL_IMPORT_WORK_PRE_CONTEXT) // Tag para a primeira fase
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueue(request)
    }

    // Nova função para cancelar a importação da URL
    fun cancelUrlImport() {
        showToastOverlay("Cancelando importação de URL...")
        workManager.cancelAllWorkByTag(UrlImportWorker.TAG_URL_IMPORT_WORK_PRE_CONTEXT)
        workManager.cancelAllWorkByTag(UrlImportWorker.TAG_URL_IMPORT_WORK_CONTENT_DETAILS)
        // O _isUrlImporting será resetado pelo urlImportWorkObserver quando os trabalhos forem cancelados/finalizados.
        viewModelScope.launch { showToast(appContext.getString(R.string.url_import_cancelled_toast)) }
    }


    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val columnIndex = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        name = it.getString(columnIndex)
                    }
                }
            } catch (e: Exception) { showToastOverlay("Erro ao obter arquivo: $uri") }
        }
        val resultName = name ?: uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
        return resultName.replace("[^a-zA-Z0-9._\\-]".toRegex(), "_")
    }

    private fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: uri.lastPathSegment?.substringAfterLast('.', "").takeIf { it?.isNotEmpty() == true }
        } catch (e: Exception) { showToastOverlay("Erro ao obter arwuivo URI: $uri"); null }
    }

    private fun getProjectSpecificMusicDirectory(context: Context, projectDirName: String): File {
        val subDirMusicasNoProjeto = "project_musics"
        val defaultDirMusicasGeral = "audio_general_musics"
        val baseDirToUse: File
        if (projectDirName.isNotBlank()) {
            val externalAppFilesDir = context.getExternalFilesDir(null)
            if (externalAppFilesDir != null) {
                baseDirToUse = File(externalAppFilesDir, projectDirName)
            } else {
                baseDirToUse = File(context.filesDir, projectDirName)
            }
        } else {
            baseDirToUse = File(context.filesDir, defaultDirMusicasGeral)
        }
        val finalMusicDir = File(baseDirToUse, subDirMusicasNoProjeto)
        if (!finalMusicDir.exists() && !finalMusicDir.mkdirs()){
            if (!baseDirToUse.exists() && !baseDirToUse.mkdirs()){
                 return context.cacheDir
            }
            return baseDirToUse
        }
        return finalMusicDir
    }

    private suspend fun copyUriToInternalStorage(uri: Uri, projectDirName: String): String? = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                val musicStorageDir = getProjectSpecificMusicDirectory(appContext, projectDirName)
                val baseName = getFileNameFromUri(appContext, uri)
                val extension = getFileExtensionFromUri(appContext, uri) ?: "mp3"
                val finalFileName = if (baseName.endsWith(".$extension", ignoreCase = true)) baseName else "$baseName.$extension"
                val outputFile = File(musicStorageDir, finalFileName)
                FileOutputStream(outputFile).use { outputStream -> inputStream.copyTo(outputStream) }
                return@withContext outputFile.absolutePath
            } ?: return@withContext null
        } catch (e: IOException) {
            Log.e(TAG, "IOException ao copiar música: ${e.message}", e)
            return@withContext null
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException ao copiar música: ${e.message}", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado ao copiar música: ${e.message}", e)
            return@withContext null
        }
    }

    private suspend fun deletePreviousMusicFile() {
        val previousPath = audioDataStoreManager.videoMusicPath.first()
        if (previousPath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val previousFile = File(previousPath)
                if (previousFile.exists() && previousFile.isFile) {
                    if (!previousFile.delete()) {
                        Log.w(TAG, "Falha ao deletar arquivo de música anterior: $previousPath")
                    } else {
                        Log.d(TAG, "Arquivo de música anterior deletado: $previousPath")
                    }
                }
            }
        }
    }

    fun setVideoMusicPath(uriString: String) {
        viewModelScope.launch {
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            if (uriString.isBlank()) {
                deletePreviousMusicFile()
                audioDataStoreManager.setVideoMusicPath("")
                showToast(appContext.getString(R.string.toast_music_removed))
                return@launch
            }
            val uri = try { Uri.parse(uriString) } catch (e: Exception) {
                showToast(appContext.getString(R.string.toast_invalid_music_link))
                return@launch
            }
            var localFilePath: String? = null
            var operationSuccess = false
            var permissionErrorOccurred = false
            try {
                localFilePath = copyUriToInternalStorage(uri, projectDirName)
                operationSuccess = (localFilePath != null)
            } catch (e: SecurityException) {
                permissionErrorOccurred = true
            } catch (e: Exception) {
                // Outros erros já logados em copyUriToInternalStorage
            }
            if (operationSuccess && localFilePath != null) {
                deletePreviousMusicFile()
                audioDataStoreManager.setVideoMusicPath(localFilePath)
                showToast(appContext.getString(R.string.toast_music_selected_and_saved))
            } else {
                if (permissionErrorOccurred) {
                    showToast(appContext.getString(R.string.error_music_permission_denied))
                } else {
                    showToast(appContext.getString(R.string.error_saving_selected_music))
                }
            }
        }
    }

    suspend fun loadSubtitleContent(path: String): String? = withContext(Dispatchers.IO) {
         if (path.isBlank()) return@withContext null
         return@withContext try {
             val file = File(path)
             if (file.exists() && file.isFile && file.canRead()) file.readText() else null
         } catch (e: Exception) { showToastOverlay("Erro ao ler arquivo legenda: $path"); null }
     }

     suspend fun saveSubtitleContent(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
         if (path.isBlank()) return@withContext false
         try {
             val file = File(path)
             file.parentFile?.mkdirs()
             file.writeText(content)
             true
         } catch (e: Exception) { showToastOverlay("Erro ao salvar legenda: $path"); false }
     }

    suspend fun readNarrativeContextFileContent(): String? = withContext(Dispatchers.IO) {
        val filePath = narrativeContextFilePath.first()
        if (filePath.isBlank()) return@withContext null
        try {
            val uri = Uri.parse(filePath)
            appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    return@withContext reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler conteúdo do arquivo de contexto da narrativa: $filePath", e)
            null
        }
    }
    
    // --- CÓDIGO NOVO: Função de cancelamento ---
    fun cancelAudioGeneration() {
        showToastOverlay("Cancelamento geração de áudio")
        workManager.cancelAllWorkByTag(WorkerTags.AUDIO_NARRATIVE)
        viewModelScope.launch {
            audioDataStoreManager.setIsAudioProcessing(false)
            audioDataStoreManager.setGenerationProgressText(appContext.getString(R.string.status_cancelled_by_user))
        }
    }


    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "AudioViewModel onCleared() called. Removendo observers.")
        // Remover observers se eles foram adicionados com um LifecycleOwner específico.
        // Se foram adicionados com observeForever, precisam ser removidos explicitamente.
        // No entanto, o AndroidViewModel e o viewModelScope cuidam do ciclo de vida das coroutines.
        // A remoção manual do observer do LiveData (se usado com observeForever) seria aqui.
        // Para o workManager.getWorkInfosByTagLiveData, se usado com observeForever,
        // ele precisa ser removido:
        workManager.getWorkInfosByTagLiveData(UrlImportWorker.TAG_URL_IMPORT_WORK_PRE_CONTEXT)
            .removeObserver(urlImportWorkObserver)
        workManager.getWorkInfosByTagLiveData(UrlImportWorker.TAG_URL_IMPORT_WORK_CONTENT_DETAILS)
            .removeObserver(urlImportWorkObserver)
    }
}



