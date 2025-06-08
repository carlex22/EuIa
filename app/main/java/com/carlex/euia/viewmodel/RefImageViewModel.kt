// File: viewmodel/RefImageViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.carlex.euia.R // Import para R.string
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.worker.RefImageAnalysisWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "RefImageViewModel"

@Serializable
data class JsonDetail(
    val key: String,
    var value: String
)

/**
 * ViewModel responsável por gerenciar os dados e a lógica relacionados à análise
 * das imagens de referência do vídeo.
 *
 * Interage com:
 * - [RefImageDataStoreManager] para persistir o prompt de análise e os detalhes JSON extraídos.
 * - [VideoDataStoreManager] para obter o JSON das imagens de referência atuais.
 * - [AudioDataStoreManager] para obter o título do vídeo atual (usado como contexto na análise).
 * - [WorkManager] para enfileirar e observar o [RefImageAnalysisWorker] que realiza a análise em segundo plano.
 *
 * Expõe estados como [isAnalyzing], [refObjetoDetalhes], e fluxos de eventos como [errorMessage].
 *
 * @param application A instância da aplicação, necessária para [AndroidViewModel] e para obter o contexto.
 */
class RefImageViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = RefImageDataStoreManager(application)
    private val videoDataStoreManager = VideoDataStoreManager(application)
    private val audioDataStoreManager = AudioDataStoreManager(application)
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private val workManager = WorkManager.getInstance(application)

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
        val isProcessing = workInfos.any {
            it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
        }
        if (_isAnalyzing.value != isProcessing) {
            _isAnalyzing.value = isProcessing
            Log.d(TAG, "Estado de análise atualizado para: $isProcessing (via WorkInfo)")
        }

        workInfos.firstOrNull { it.state == WorkInfo.State.FAILED }?.let { failedWorkInfo ->
            val errorMsgFromWorker = failedWorkInfo.outputData.getString(RefImageAnalysisWorker.KEY_ERROR_MESSAGE)
            // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
            val displayMessage = if (!errorMsgFromWorker.isNullOrBlank()) {
                getApplication<Application>().getString(R.string.ref_image_worker_analysis_failed_generic, errorMsgFromWorker)
            } else {
                getApplication<Application>().getString(R.string.ref_image_worker_analysis_failed_unknown)
            }
            Log.e(TAG, "Tarefa de análise ${failedWorkInfo.id} falhou: $displayMessage")
            viewModelScope.launch { _errorMessage.emit(displayMessage) }
        }
    }

    init {
        Log.d(TAG, "RefImageViewModel inicializado. Observando tarefas com tag: ${RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK}")
        workManager.getWorkInfosByTagLiveData(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK)
            .observeForever(workInfoObserver)
    }

    val currentVideoTitulo: StateFlow<String> = audioDataStoreManager.videoTitulo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val currentVideoImagensReferenciaJson: StateFlow<String> = videoDataStoreManager.imagensReferenciaJson
         .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "[]")

    val refObjetoPrompt: StateFlow<String> = dataStoreManager.refObjetoPrompt
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000L),
            // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
            getApplication<Application>().getString(R.string.ref_image_default_prompt)
        )

    private val refObjetoDetalhesJsonString: StateFlow<String> = dataStoreManager.refObjetoDetalhesJson
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "{}")

    val refObjetoDetalhes: StateFlow<List<JsonDetail>> = refObjetoDetalhesJsonString.map { jsonString ->
        try {
            if (jsonString.isBlank() || jsonString == "{}") {
                emptyList()
            } else {
                val jsonObject = jsonParser.parseToJsonElement(jsonString).jsonObject
                jsonObject.entries.map { (key, element) ->
                    JsonDetail(key, element.jsonPrimitive.content)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parsear refObjetoDetalhesJson: ${e.message}", e)
            viewModelScope.launch {
                // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
                 _errorMessage.emit(getApplication<Application>().getString(R.string.ref_image_vm_error_loading_saved_details))
            }
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val isTriggerDataLoaded: StateFlow<Boolean> = combine(
        currentVideoTitulo,
        currentVideoImagensReferenciaJson,
        refObjetoDetalhesJsonString
    ) { _, _, _ -> // Os valores em si não são usados, apenas o fato de que emitiram
        true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    fun setRefObjetoPrompt(newPrompt: String) {
        viewModelScope.launch {
            dataStoreManager.setRefObjetoPrompt(newPrompt)
        }
    }

    fun saveRefObjetoDetalhes(details: List<JsonDetail>) {
        viewModelScope.launch {
            try {
                val mapToSave = details.associate { it.key to it.value }
                val jsonStringToSave = jsonParser.encodeToString(mapToSave)
                dataStoreManager.setRefObjetoDetalhesJson(jsonStringToSave)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar refObjetoDetalhesJson: ${e.message}", e)
                // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
                _errorMessage.emit(getApplication<Application>().getString(R.string.ref_image_vm_error_saving_details, e.localizedMessage ?: ""))
            }
        }
    }

    fun analyzeImages() {
         if (_isAnalyzing.value) {
             Log.d(TAG, "analyzeImages() chamado, mas análise já está em progresso.")
             // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
             viewModelScope.launch { _errorMessage.emit(getApplication<Application>().getString(R.string.ref_image_vm_status_analysis_already_running)) }
             return
         }
        Log.d(TAG, "analyzeImages() chamado. Enfileirando RefImageAnalysisWorker.")

        val analysisRequest = OneTimeWorkRequestBuilder<RefImageAnalysisWorker>()
            .addTag(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK)
            .build()

        workManager.enqueue(analysisRequest)
        // <<< --- CORREÇÃO AQUI: Usar getApplication<Application>().getString --- >>>
        viewModelScope.launch { _errorMessage.emit(getApplication<Application>().getString(R.string.ref_image_vm_status_analysis_started)) }
    }

    fun cancelAnalysis() {
        Log.d(TAG, "cancelAnalysis() chamado. Cancelando tarefas com tag: ${RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK}")
        workManager.cancelAllWorkByTag(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK)
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel onCleared(). Removendo observador do WorkManager.")
        workManager.getWorkInfosByTagLiveData(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK)
            .removeObserver(workInfoObserver)
    }
}