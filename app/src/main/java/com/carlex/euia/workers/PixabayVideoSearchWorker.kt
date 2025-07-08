// File: euia/workers/PixabayVideoSearchWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.api.PixabayApiClient
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.NotificationUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
// <<< IMPORTS CORRIGIDOS/ADICIONADOS >>>
import android.app.NotificationManager
import com.carlex.euia.utils.WorkerTags

private const val TAG = "PixabaySearchWorker"

class PixabayVideoSearchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStore = VideoProjectDataStoreManager(applicationContext)
    private val videoPreferencesDataStore = VideoPreferencesDataStoreManager(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_SCENE_ID = "key_scene_id_for_pixabay_search"
        const val KEY_ERROR_MESSAGE = "key_pixabay_search_error"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(applicationContext.getString(R.string.notification_content_pixabay_starting))
        return ForegroundInfo(System.currentTimeMillis().toInt(), notification)
    }

    override suspend fun doWork(): Result = coroutineScope {
        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "ID da cena não fornecido."))

        try {
            // 1. Obter o prompt da cena
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_getting_prompt, sceneId.take(4)))
            val scene = videoProjectDataStore.sceneLinkDataList.first().find { it.id == sceneId }
            val searchQuery = scene?.promptVideo
            if (searchQuery.isNullOrBlank()) {
                throw IllegalStateException("Prompt de vídeo (TAG_SEARCH_WEB) está vazio para a cena $sceneId.")
            }

            // 2. Buscar vídeos na Pixabay
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_searching, searchQuery))
            val pixabayResult = PixabayApiClient.searchVideos(searchQuery)
            if (pixabayResult.isFailure || pixabayResult.getOrNull().isNullOrEmpty()) {
                throw IllegalStateException("Nenhum vídeo encontrado na Pixabay para '$searchQuery' ou a API falhou.")
            }
            val videoOptions = pixabayResult.getOrThrow()

            // 3. Montar prompt para o Gemini escolher o melhor vídeo
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_ai_selection))
            val optionsText = videoOptions.joinToString("\n") {
                // Usamos a URL da thumbnail como um ID único
                "ID: ${it.videoFiles.small.thumbnail}\nTags: ${it.tags}\n"
            }
            val geminiPrompt = """
                Analisando a intenção criativa de um prompt, escolha o melhor vídeo de estoque.
                Prompt Original: "$searchQuery"

                Opções Disponíveis (analise as tags para relevância):
                $optionsText

                Sua resposta DEVE ser apenas o ID (a URL da thumbnail) da melhor opção, e nada mais.
            """.trimIndent()

            // 4. Chamar a Gemini para escolher
            val geminiResult = GeminiTextAndVisionProRestApi.perguntarAoGemini(geminiPrompt, emptyList())
            if (geminiResult.isFailure) {
                throw geminiResult.exceptionOrNull() ?: IllegalStateException("A IA falhou em escolher um vídeo.")
            }
            val chosenThumbnailUrl = geminiResult.getOrThrow().trim()

            if (!chosenThumbnailUrl.startsWith("https://cdn.pixabay.com")) {
                 throw IllegalStateException("A IA retornou uma URL inválida: $chosenThumbnailUrl")
            }

            // 5. Converter URL da thumbnail para URL do vídeo
            val videoUrlToDownload = chosenThumbnailUrl.replace(".jpg", ".mp4", ignoreCase = true)
            Log.i(TAG, "IA escolheu o vídeo: $videoUrlToDownload")

            // 6. Enfileirar o VideoDownloadWorker para baixar o vídeo
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_queuing_download))
            val projectDirName = videoPreferencesDataStore.videoProjectDir.first()
            val downloadWorkRequest = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(workDataOf(
                    VideoDownloadWorker.KEY_SCENE_ID to sceneId,
                    VideoDownloadWorker.KEY_VIDEO_URL to videoUrlToDownload,
                    VideoDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                ))
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .addTag("video_download_$sceneId")
                .build()

            WorkManager.getInstance(applicationContext).enqueue(downloadWorkRequest)

            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_success), isFinished = true)
            return@coroutineScope Result.success()

        } catch (e: Exception) {
            val errorMessage = if (e is CancellationException) "Busca cancelada." else e.message ?: "Erro desconhecido."
            Log.e(TAG, "Falha no PixabayVideoSearchWorker para cena $sceneId: $errorMessage", e)
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_failed, errorMessage.take(40)), isFinished = true, isError = true)
            // Atualiza o estado da cena para remover o "processando"
            videoProjectDataStore.sceneLinkDataList.first().find { it.id == sceneId }?.let {
                val updatedList = videoProjectDataStore.sceneLinkDataList.first().map { scene ->
                    if (scene.id == sceneId) scene.copy(isGeneratingVideo = false, generationErrorMessage = errorMessage) else scene
                }
                videoProjectDataStore.setSceneLinkDataList(updatedList)
            }
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        }
    }

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val builder = NotificationCompat.Builder(applicationContext, NotificationUtils.CHANNEL_ID_VIDEO_PROCESSING)
            .setContentTitle(applicationContext.getString(R.string.notification_title_pixabay_search))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!isFinished && !isError)
            .setAutoCancel(isFinished || isError)
        return builder.build()
    }

    private fun updateNotification(message: String, isFinished: Boolean = false, isError: Boolean = false) {
        // <<< CORREÇÃO AQUI >>> A chamada agora é feita no `notificationManager`
        notificationManager.notify(System.currentTimeMillis().toInt(), createNotification(message, isFinished, isError))
    }
}