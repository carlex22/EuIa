// File: euia/viewmodel/VideoViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.VideoDataStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.work.*
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.worker.ImageProcessingWorker
import java.io.File
import androidx.lifecycle.Observer
import kotlinx.coroutines.withContext

private const val TAG = "VideoViewModel"

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = VideoDataStoreManager(application)
    private val workManager = WorkManager.getInstance(application)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val context = application

    val imagensReferenciaList: StateFlow<List<ImagemReferencia>> =
        dataStoreManager.imagensReferenciaJson
            .map { jsonString ->
                try {
                    if (jsonString.isNotBlank() && jsonString != "[]") {
                        Json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), jsonString)
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao desserializar imagensReferenciaJson: $e")
                    emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val isAnyImageProcessing: StateFlow<Boolean> = dataStoreManager.isProcessingImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

     val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
        val isProcessing = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (!isProcessing && isAnyImageProcessing.value) {
            viewModelScope.launch { dataStoreManager.setIsProcessingImages(false) }
        }
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
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    

    init {
        workManager.getWorkInfosByTagLiveData(WorkerTags.IMAGE_PROCESSING_WORK)
            .observeForever(workInfoObserver)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun saveImagensReferenciaList(updatedList: List<ImagemReferencia>) {
        val imagesJson = Json.encodeToString(ListSerializer(ImagemReferencia.serializer()), updatedList)
        dataStoreManager.setImagensReferenciaJson(imagesJson)
    }

    fun processImages(uris: List<Uri>) {
        if (isAnyImageProcessing.value) {
            viewModelScope.launch { _toastMessage.emit("Processamento de imagem já em andamento.") }
            return
        }
        viewModelScope.launch {
            dataStoreManager.setIsProcessingImages(true)
            val uriStrings = uris.map { it.toString() }.toTypedArray()
            val workRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
                .setInputData(workDataOf(ImageProcessingWorker.KEY_MEDIA_URIS to uriStrings))
                .addTag(WorkerTags.IMAGE_PROCESSING_WORK)
                .build()
            workManager.enqueue(workRequest)
            _toastMessage.emit("Processamento de imagens iniciado.")
        }
    }
    
    public fun mostrarToast(mensagem: String) {
        Toast.makeText(context, mensagem, Toast.LENGTH_SHORT).show()
    }

    fun removeImage(pathToRemove: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = imagensReferenciaList.first()
            val updatedList = currentList.filterNot { it.path == pathToRemove }
            saveImagensReferenciaList(updatedList)

            try {
                val fileToDelete = File(pathToRemove)
                
                // Extrai nome sem extensão e diretório pai
                val fileNameWithoutExt = fileToDelete.nameWithoutExtension
                val parentDir = fileToDelete.parent
                
                // Novo nome com sufixo "thumb" e extensão .webp
                val thumbFileName = "thumb_${fileNameWithoutExt}.webp"
                
                // Caminho completo do novo arquivo
                val fileToDeleteY = File(parentDir, thumbFileName)
                
                
                if (fileToDeleteY.exists()) fileToDeleteY.delete()
                if (fileToDelete.exists()) fileToDelete.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao remover arquivo de imagem: $pathToRemove", e)
            }
            
            withContext(Dispatchers.Main) {
                _toastMessage.emit("Imagem removida da lista.")
            }
        }
    }

    fun updateImageDescription(pathToUpdate: String, newDescription: String) {
        viewModelScope.launch {
            val currentList = imagensReferenciaList.first()
            val updatedList = currentList.map { item ->
                if (item.path == pathToUpdate) item.copy(descricao = newDescription) else item
            }
            saveImagensReferenciaList(updatedList)
        }
    }
    
    // <<< INÍCIO DA FUNÇÃO QUE ESTAVA FALTANDO >>>
    /**
     * Atualiza um item da lista de imagens de referência com o novo caminho
     * do arquivo editado e exclui o arquivo antigo.
     * @param originalPath O caminho original da imagem que foi enviada para edição.
     * @param newEditedPath O caminho da nova imagem salva pelo editor.
     */
    fun onImageEdited(originalPath: String, newEditedPath: String) {
        viewModelScope.launch {
            val currentList = imagensReferenciaList.first()
            val updatedList = currentList.map {
                if (it.path == originalPath) {
                    // Atualiza o caminho para o novo arquivo editado
                    it.copy(path = newEditedPath)
                } else {
                    it
                }
            }
            // Salva a lista com o caminho atualizado
            saveImagensReferenciaList(updatedList)

            // Limpa o arquivo antigo para não ocupar espaço desnecessário
            launch(Dispatchers.IO) {
                try {
                    val oldFile = File(originalPath)
                    if (oldFile.exists()) {
                        oldFile.delete()
                        Log.i(TAG, "Arquivo original de imagem editada foi removido: $originalPath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao remover arquivo de imagem original após edição", e)
                }
            }
            _toastMessage.emit("Imagem editada com sucesso!")
        }
    }
    // <<< FIM DA FUNÇÃO QUE ESTAVA FALTANDO >>>

    override fun onCleared() {
        super.onCleared()
        workManager.getWorkInfosByTagLiveData(WorkerTags.IMAGE_PROCESSING_WORK)
            .removeObserver(workInfoObserver)
    }
}