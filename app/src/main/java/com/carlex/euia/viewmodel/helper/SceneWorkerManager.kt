// File: euia/viewmodel/helper/SceneWorkerManager.kt
package com.carlex.euia.viewmodel.helper

import android.content.Context
import android.util.Log
import androidx.work.*
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.worker.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "SceneWorkerManager"

/**
 * Classe auxiliar dedicada a gerenciar a criação e o enfileiramento de workers
 * para processamento de cenas (geração de imagem, vídeo, troca de roupa, prévias).
 * Atua como uma fachada para o WorkManager, simplificando as chamadas do ViewModel.
 *
 * @param context O contexto da aplicação.
 * @param workManager A instância do WorkManager.
 * @param json Instância do serializador Kotlinx.Json.
 */
class SceneWorkerManager(
    private val context: Context,
    private val workManager: WorkManager,
    private val json: Json
) {

    /**
     * Enfileira um worker para gerar a imagem estática de uma cena.
     * @param sceneId O ID da cena a ser processada.
     * @param prompt O prompt de geração de imagem.
     * @param referenceImages A lista de imagens de referência a serem usadas.
     */
    fun enqueueImageGeneration(sceneId: String, prompt: String, referenceImages: List<ImagemReferencia>) {
        val imagesJsonForWorker = try {
            json.encodeToString(ListSerializer(ImagemReferencia.serializer()), referenceImages)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao serializar imagens de referência para a cena $sceneId.", e)
            "[]"
        }

        val inputData = workDataOf(
            VideoProcessingWorker.KEY_SCENE_ID to sceneId,
            VideoProcessingWorker.KEY_TASK_TYPE to VideoProcessingWorker.TASK_TYPE_GENERATE_IMAGE,
            VideoProcessingWorker.KEY_IMAGE_GEN_PROMPT to prompt,
            VideoProcessingWorker.KEY_IMAGENS_REFERENCIA_JSON_INPUT to imagesJsonForWorker
        )

        val workRequest = createWorkRequest(inputData, VideoProcessingWorker.TAG_PREFIX_SCENE_PROCESSING + sceneId)
        workManager.enqueue(workRequest)
        Log.d(TAG, "Worker de GERAÇÃO DE IMAGEM enfileirado para a cena: $sceneId")
    }

    /**
     * Enfileira um worker para aplicar o efeito de "provador virtual" (troca de roupa).
     * @param sceneId O ID da cena a ser processada.
     * @param chosenReferenceImagePath O caminho da imagem da roupa a ser aplicada.
     */
    fun enqueueClothesChange(sceneId: String, chosenReferenceImagePath: String) {
        val inputData = workDataOf(
            VideoProcessingWorker.KEY_SCENE_ID to sceneId,
            VideoProcessingWorker.KEY_TASK_TYPE to VideoProcessingWorker.TASK_TYPE_CHANGE_CLOTHES,
            VideoProcessingWorker.KEY_CHOSEN_REFERENCE_IMAGE_PATH to chosenReferenceImagePath
        )

        val workRequest = createWorkRequest(inputData, VideoProcessingWorker.TAG_PREFIX_SCENE_CLOTHES_PROCESSING + sceneId)
        workManager.enqueue(workRequest)
        Log.d(TAG, "Worker de TROCA DE ROUPA enfileirado para a cena: $sceneId")
    }

    /**
     * Enfileira um worker para gerar um clipe de vídeo para uma cena.
     * @param sceneId O ID da cena a ser processada.
     * @param videoPrompt O prompt para a geração de vídeo.
     * @param sourceImagePath O caminho da imagem estática que servirá de base para o vídeo.
     */
    fun enqueueVideoGeneration(sceneId: String, videoPrompt: String, sourceImagePath: String) {
        val inputData = workDataOf(
            VideoProcessingWorker.KEY_SCENE_ID to sceneId,
            VideoProcessingWorker.KEY_TASK_TYPE to VideoProcessingWorker.TASK_TYPE_GENERATE_VIDEO,
            VideoProcessingWorker.KEY_VIDEO_GEN_PROMPT to videoPrompt,
            VideoProcessingWorker.KEY_SOURCE_IMAGE_PATH_FOR_VIDEO to sourceImagePath
        )

        val workRequest = createWorkRequest(inputData, VideoProcessingWorker.TAG_PREFIX_SCENE_VIDEO_PROCESSING + sceneId)
        workManager.enqueue(workRequest)
        Log.d(TAG, "Worker de GERAÇÃO DE VÍDEO enfileirado para a cena: $sceneId")
    }

    /**
     * Enfileira um worker para gerar a pré-visualização de uma cena.
     * Usa uma fila única (`SCENE_PREVIEW_QUEUE`) com a política `APPEND_OR_REPLACE`
     * para garantir que as prévias sejam geradas sequencialmente, evitando sobrecarga.
     * @param sceneId O ID da cena a ter sua prévia gerada.
     */
    fun enqueuePreviewGeneration(sceneId: String) {
        val workRequest = OneTimeWorkRequestBuilder<ScenePreviewWorker>()
            .setInputData(workDataOf(ScenePreviewWorker.KEY_SCENE_ID to sceneId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("${WorkerTags.SCENE_PREVIEW_WORK}_$sceneId") // Tag específica
            .addTag(WorkerTags.SCENE_PREVIEW_WORK) // Tag geral
            .build()

        workManager.enqueueUniqueWork(
            "SCENE_PREVIEW_QUEUE",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
        Log.d(TAG, "Worker de PRÉVIA enfileirado para a cena: $sceneId")
    }

    /**
     * Cancela todas as tarefas de processamento em andamento (imagem, vídeo, roupa) para uma cena específica.
     * @param sceneId O ID da cena cujas tarefas serão canceladas.
     */
    fun cancelAllProcessingForScene(sceneId: String) {
        val imageTag = VideoProcessingWorker.TAG_PREFIX_SCENE_PROCESSING + sceneId
        val clothesTag = VideoProcessingWorker.TAG_PREFIX_SCENE_CLOTHES_PROCESSING + sceneId
        val videoTag = VideoProcessingWorker.TAG_PREFIX_SCENE_VIDEO_PROCESSING + sceneId
        
        workManager.cancelAllWorkByTag(imageTag)
        workManager.cancelAllWorkByTag(clothesTag)
        workManager.cancelAllWorkByTag(videoTag)
        Log.w(TAG, "Todas as tarefas de processamento para a cena $sceneId foram solicitadas para cancelamento.")
    }

    /**
     * Método auxiliar privado para criar um WorkRequest padrão para o [VideoProcessingWorker],
     * configurando constraints e tags comuns.
     */
    private fun createWorkRequest(inputData: Data, specificTag: String): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<VideoProcessingWorker>()
            .setInputData(inputData)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(specificTag)
            .addTag(WorkerTags.VIDEO_PROCESSING) // Tag genérica para observação geral
            .build()
    }
}