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

    // <<< INÍCIO DA MUDANÇA PRINCIPAL >>>
    // Este StateFlow agora expõe uma lista de ImagemReferencia JÁ VERIFICADA.
    val imagensReferenciaList: StateFlow<List<ImagemReferencia>> =
        dataStoreManager.imagensReferenciaJson
            // 1. O map transforma a string JSON em uma lista de objetos
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
            // 2. O transform subsequente faz a verificação de arquivos em background (IO)
            .transform { list ->
                val verifiedList = withContext(Dispatchers.IO) {
                    list.filter { item ->
                        // Verifica o arquivo principal (vídeo ou imagem)
                        val mainFile = File(item.path)
                        val mainFileExists = mainFile.exists() && mainFile.isFile

                        // Se for um vídeo, verifica também o thumbnail
                        val thumbFileExists = if (item.pathVideo != null) {
                            item.pathThumb?.let { File(it).exists() } ?: false
                        } else {
                            true // Para imagens, não há thumbnail separado a verificar
                        }
                        
                        if (!mainFileExists || !thumbFileExists) {
                            Log.w(TAG, "Item removido da lista por não existir no disco: ${item.path}")
                        }
                        
                        mainFileExists && thumbFileExists
                    }
                }
                // 3. Emite a lista final e verificada para a UI
                emit(verifiedList)
            }
            // 4. Converte o Flow em um StateFlow para a UI observar
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    // <<< FIM DA MUDANÇA PRINCIPAL >>>

    val isAnyImageProcessing: StateFlow<Boolean> = dataStoreManager.isProcessingImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
        val isProcessing = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (!isProcessing && isAnyImageProcessing.value) {
            viewModelScope.launch { dataStoreManager.setIsProcessingImages(false) }
        }
    }
    
    fun setSnackbarMessage(message: String?) {
        _snackbarMessage.value = message
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
            viewModelScope.launch { setSnackbarMessage("Processamento de imagem já em andamento.") }
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
            setSnackbarMessage("Processamento de imagens iniciado.")
        }
    }
    
    fun removeImage(pathToRemove: String) {
        viewModelScope.launch {
            // A lista já está validada, então apenas filtramos e salvamos
            val currentList = imagensReferenciaList.value
            val updatedList = currentList.filterNot { it.path == pathToRemove }
            saveImagensReferenciaList(updatedList)

            // A exclusão do arquivo continua sendo uma operação de IO
            withContext(Dispatchers.IO) {
                try {
                    val fileToDelete = File(pathToRemove)
                    
                    val fileNameWithoutExt = fileToDelete.nameWithoutExtension
                    val parentDir = fileToDelete.parent
                    val thumbFileName = "thumb_${fileNameWithoutExt}.webp"
                    val thumbFileToDelete = File(parentDir, thumbFileName)
                    
                    if (thumbFileToDelete.exists()) thumbFileToDelete.delete()
                    if (fileToDelete.exists()) fileToDelete.delete()

                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao remover arquivo de imagem: $pathToRemove", e)
                }
            }
            
            setSnackbarMessage("Imagem removida da lista.")
        }
    }

    fun updateImageDescription(pathToUpdate: String, newDescription: String) {
        viewModelScope.launch {
            val currentList = imagensReferenciaList.value
            val updatedList = currentList.map { item ->
                if (item.path == pathToUpdate) item.copy(descricao = newDescription) else item
            }
            saveImagensReferenciaList(updatedList)
        }
    }

    fun onImageEdited(originalPath: String, newEditedPath: String) {
        viewModelScope.launch {
            val currentList = imagensReferenciaList.value
            val updatedList = currentList.map {
                if (it.path == originalPath) {
                    it.copy(path = newEditedPath)
                } else {
                    it
                }
            }
            saveImagensReferenciaList(updatedList)

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
            setSnackbarMessage("Imagem editada com sucesso!")
        }
    }

    override fun onCleared() {
        super.onCleared()
        workManager.getWorkInfosByTagLiveData(WorkerTags.IMAGE_PROCESSING_WORK)
            .removeObserver(workInfoObserver)
    }
}