// File: euia/viewmodel/VideoViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
// import android.content.Context // Não é mais necessário aqui se apenas Application for usado
import android.net.Uri // Mantido para processImages
import android.util.Log
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
import com.carlex.euia.utils.WorkerTags // <<<< Adicionado para consistência
import com.carlex.euia.worker.ImageProcessingWorker
// import java.util.UUID // Não usado diretamente aqui
import java.io.File // Importado para exclusão de arquivo
import androidx.lifecycle.Observer

// Define TAG for logging
private const val TAG = "VideoViewModel"
// <<<< Removida constante duplicada, usaremos a do WorkerTags

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = VideoDataStoreManager(application)
    private val workManager = WorkManager.getInstance(application)

    val imagensReferenciaList: StateFlow<List<ImagemReferencia>> =
        dataStoreManager.imagensReferenciaJson
            .map { json ->
                try {
                    Log.d(TAG, "Deserializando imagensReferenciaJson. Comprimento JSON: ${json.length}")
                    if (json.isNotBlank() && json != "[]") {
                        val list = Json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), json)
                        Log.d(TAG, "Desserializadas ${list.size} imagens do DataStore.")
                        list
                    } else {
                        Log.d(TAG, "imagensReferenciaJson está vazio ou é um array vazio. Retornando lista vazia.")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao desserializar imagensReferenciaJson para List<ImagemReferencia>", e)
                    viewModelScope.launch { _toastMessage.emit("Erro ao carregar lista de imagens.") }
                    emptyList()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // <<<< Alterado para usar o Flow do DataStoreManager diretamente
    val isAnyImageProcessing: StateFlow<Boolean> = dataStoreManager.isProcessingImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val workInfoObserver = Observer<List<WorkInfo>> { workInfos ->
        Log.d(TAG, "Lista WorkInfo atualizada para tag ${WorkerTags.IMAGE_PROCESSING_WORK}. Contagem: ${workInfos.size}")
        val isProcessing = workInfos.any {
            it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
        }
        
        // <<<< Lógica de atualização do DataStore movida para o worker ou para o ponto de enfileiramento/conclusão
        if (!isProcessing && isAnyImageProcessing.value) {
            viewModelScope.launch {
                dataStoreManager.setIsProcessingImages(false)
            }
        }
    }

    init {
        Log.d(TAG, "VideoViewModel init. Observando work tag ${WorkerTags.IMAGE_PROCESSING_WORK}.")
        workManager.getWorkInfosByTagLiveData(WorkerTags.IMAGE_PROCESSING_WORK)
            .observeForever(workInfoObserver)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun saveImagensReferenciaList(updatedList: List<ImagemReferencia>) {
         Log.d(TAG, "Tentando salvar lista de imagens no DataStore. Tamanho da lista: ${updatedList.size}")
         val imagesJson = try {
            Json.encodeToString(ListSerializer(ImagemReferencia.serializer()), updatedList)
         } catch (e: Exception) {
             Log.e(TAG, "Erro ao codificar lista de imagens atualizada para JSON", e)
             _toastMessage.emit("Erro ao salvar a lista de imagens.")
             return
         }
         dataStoreManager.setImagensReferenciaJson(imagesJson)
         Log.d(TAG, "Lista de imagens salva no DataStore. Tamanho: ${updatedList.size}")
    }

    fun processImages(uris: List<Uri>) {
        Log.d(TAG, "processImages chamado com ${uris.size} URIs. Enfileirando tarefa com tag ${WorkerTags.IMAGE_PROCESSING_WORK}.")
        if (isAnyImageProcessing.value) { // <<<< Usa o StateFlow
             Log.d(TAG, "Processamento de imagem já em andamento. Ignorando nova requisição.")
             viewModelScope.launch { _toastMessage.emit("Processamento de imagem já em andamento.") }
             return
        }
        if (uris.isEmpty()) {
            Log.w(TAG, "processImages chamado com lista de URIs vazia.")
            viewModelScope.launch { _toastMessage.emit("Nenhuma imagem selecionada para processar.") }
            return
        }
        
        viewModelScope.launch {
            // <<<< Define a flag como true ANTES de enfileirar
            dataStoreManager.setIsProcessingImages(true) 
            
            val uriStrings = uris.map { it.toString() }.toTypedArray()
            val inputData = Data.Builder()
                .putStringArray(ImageProcessingWorker.KEY_MEDIA_URIS, uriStrings)
                .build()
            val imageProcessingRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
                .setInputData(inputData)
                .addTag(WorkerTags.IMAGE_PROCESSING_WORK) // <<<< Usa a constante
                .build()
            workManager.enqueue(imageProcessingRequest)
            Log.d(TAG, "Tarefa do WorkManager enfileirada com ID: ${imageProcessingRequest.id} e tag: ${WorkerTags.IMAGE_PROCESSING_WORK}")
            _toastMessage.emit("Processamento de imagens iniciado em segundo plano.")
        }
    }

     @OptIn(ExperimentalSerializationApi::class)
    fun removeImage(pathToRemove: String) {
         Log.d(TAG, "removeImage chamado para o caminho: $pathToRemove")
         viewModelScope.launch(Dispatchers.IO) { // Usar Dispatchers.IO para operações de arquivo
              val currentList = imagensReferenciaList.first() // Pega o valor atual do StateFlow
              val updatedList = currentList.filterNot { it.path == pathToRemove }

              Log.d(TAG, "Removendo item com caminho $pathToRemove. Novo tamanho da lista: ${updatedList.size}")
              saveImagensReferenciaList(updatedList)

              // --- INÍCIO: LÓGICA DE EXCLUSÃO DO ARQUIVO ---
              if (pathToRemove.isNotBlank()) {
                  try {
                      val fileToDelete = File(pathToRemove)
                      if (fileToDelete.exists()) {
                          if (fileToDelete.delete()) {
                              Log.i(TAG, "Arquivo de imagem excluído com sucesso: $pathToRemove")
                              // Opcional: Emitir toast de sucesso na exclusão do arquivo
                              // launch(Dispatchers.Main) { _toastMessage.emit("Arquivo de imagem excluído.") }
                          } else {
                              Log.w(TAG, "Falha ao excluir arquivo de imagem: $pathToRemove (delete() retornou false)")
                              // Opcional: Emitir toast de falha na exclusão do arquivo
                              // launch(Dispatchers.Main) { _toastMessage.emit("Falha ao excluir arquivo da imagem.") }
                          }
                      } else {
                          Log.w(TAG, "Arquivo de imagem não encontrado para exclusão: $pathToRemove")
                      }
                  } catch (e: SecurityException) {
                      Log.e(TAG, "Erro de segurança ao tentar excluir arquivo: $pathToRemove", e)
                      // Opcional: Emitir toast sobre erro de permissão
                      // launch(Dispatchers.Main) { _toastMessage.emit("Erro de permissão ao excluir arquivo.") }
                  } catch (e: Exception) {
                      Log.e(TAG, "Erro ao excluir arquivo de imagem: $pathToRemove", e)
                      // Opcional: Emitir toast sobre erro genérico na exclusão
                      // launch(Dispatchers.Main) { _toastMessage.emit("Erro ao excluir arquivo da imagem.") }
                  }
              } else {
                  Log.w(TAG, "Caminho para remoção está vazio, nenhum arquivo para excluir.")
              }
              // --- FIM: LÓGICA DE EXCLUSÃO DO ARQUIVO ---

              // Mostrar toast no Main thread sobre a remoção da lista (já existia)
              launch(Dispatchers.Main) {
                _toastMessage.emit("Imagem removida da lista.")
              }
         }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun updateImageDescription(pathToUpdate: String, newDescription: String) {
        Log.d(TAG, "updateImageDescription chamado para: $pathToUpdate com descrição: ${newDescription.take(50)}...")
        viewModelScope.launch {
            val currentList = imagensReferenciaList.first()
            val updatedList = currentList.map { item ->
                if (item.path == pathToUpdate) {
                    item.copy(descricao = newDescription)
                } else {
                    item
                }
            }
            Log.d(TAG, "Atualizando descrição para $pathToUpdate. Salvando lista atualizada.")
            saveImagensReferenciaList(updatedList)
            Log.d(TAG, "Descrição da imagem atualizada e lista salva para: $pathToUpdate.")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun saveAllSettings() {
         Log.d(TAG, "saveAllSettings chamado")
         viewModelScope.launch {
             Log.d(TAG, "Configurações como título e música agora são gerenciadas por seus respectivos ViewModels.")
             Log.d(TAG, "Todas as configurações salvas no DataStore pelo ViewModel.")
             viewModelScope.launch { _toastMessage.emit("Configurações salvas!") }
         }
    }

     override fun onCleared() {
         super.onCleared()
         Log.d(TAG, "VideoViewModel onCleared(). Removendo observador do WorkManager.")
         workManager.getWorkInfosByTagLiveData(WorkerTags.IMAGE_PROCESSING_WORK)
             .removeObserver(workInfoObserver)
     }
}