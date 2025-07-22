// File: euia/workers/VideoProcessingWorker.kt
package com.carlex.euia.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import java.io.IOException
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.carlex.euia.MainActivity
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.R
import com.carlex.euia.api.GeminiImageApi
import com.carlex.euia.api.GeminiVideoApi
import com.carlex.euia.api.ProvadorVirtual
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.NotificationUtils
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "VideoProcessingWorker"

// Estrutura para resultado das tarefas internas do worker
data class InternalTaskResult(
    val filePath: String? = null,
    val thumbPath: String? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean = filePath != null && errorMessage == null
)

class VideoProcessingWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_SCENE_ID = "sceneId"
        const val KEY_TASK_TYPE = "taskType"
        const val KEY_IMAGE_GEN_PROMPT = "imageGenPrompt"
        const val KEY_VIDEO_GEN_PROMPT = "videoGenPrompt"
        const val KEY_IMAGENS_REFERENCIA_JSON_INPUT = "imagensReferenciaJsonInput"
        const val KEY_CHOSEN_REFERENCE_IMAGE_PATH = "chosenReferenceImagePath"
        const val KEY_SOURCE_IMAGE_PATH_FOR_VIDEO = "sourceImagePathForVideo"

        const val TASK_TYPE_GENERATE_IMAGE = "generate_image"
        const val TASK_TYPE_CHANGE_CLOTHES = "change_clothes"
        const val TASK_TYPE_GENERATE_VIDEO = "generate_video"

        const val TAG_PREFIX_SCENE_PROCESSING = "scene_processing_task_"
        const val TAG_PREFIX_SCENE_CLOTHES_PROCESSING = "scene_clothes_task_"
        const val TAG_PREFIX_SCENE_VIDEO_PROCESSING = "scene_video_task_"
    }


    val videoPreferences = VideoPreferencesDataStoreManager(applicationContext)
    
    private val projectDataStoreManager = VideoProjectDataStoreManager(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val workManager = WorkManager.getInstance(applicationContext)

    private val tryOnMutex = Mutex()
    private val MAX_ATTEMPTS = 3
    private val RETRY_DELAY_MILLIS = 2000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun getNotificationId(sceneId: String): Int {
        return sceneId.hashCode()
    }

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_VIDEO_PROCESSING)
            .setContentTitle(appContext.getString(R.string.video_processing_notification_title))
            .setTicker(appContext.getString(R.string.video_processing_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(isFinished || isError)

        if (isFinished || isError) {
            builder.setProgress(0, 0, false).setOngoing(false)
        } else {
            builder.setProgress(0, 0, true).setOngoing(true)
        }
        return builder.build()
    }
    
    private fun updateNotificationProgress(notificationId: Int, contentText: String, makeDismissible: Boolean = false, isError: Boolean = false) {
        val notification = createNotification(contentText, isFinished = makeDismissible, isError = isError)
        notificationManager.notify(notificationId, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: "unknown_scene"
        val notificationId = getNotificationId(sceneId)
        val notification = createNotification(appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = coroutineScope {
        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_scene_id_missing)))
        val taskType = inputData.getString(KEY_TASK_TYPE)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_task_type_missing)))
        
        val notificationId = getNotificationId(sceneId)
        val initialNotificationContent = when (taskType) {
            TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.video_processing_notification_generating_image, sceneId.take(8))
            TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.video_processing_notification_changing_clothes, sceneId.take(8))
            TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.video_processing_notification_generating_video_for_scene, sceneId.take(8))
            else -> appContext.getString(R.string.video_processing_notification_starting)
        }
        updateNotificationProgress(notificationId, initialNotificationContent)
        
        var lastError: String? = null
        var finalResult: InternalTaskResult? = null

        try {
            val imageStaticGenPromptFromInput = inputData.getString(KEY_IMAGE_GEN_PROMPT)
            val videoGenPromptFromInput = inputData.getString(KEY_VIDEO_GEN_PROMPT)
            val chosenReferenceImagePathForClothesChange = inputData.getString(KEY_CHOSEN_REFERENCE_IMAGE_PATH)
            val sourceImagePathForVideo = inputData.getString(KEY_SOURCE_IMAGE_PATH_FOR_VIDEO)
            val imagensReferenciaJsonFromInput = inputData.getString(KEY_IMAGENS_REFERENCIA_JSON_INPUT) ?: "[]"
            val listaImagensParaApi: List<ImagemReferencia> = try {
                if (imagensReferenciaJsonFromInput.isNotBlank() && imagensReferenciaJsonFromInput != "[]") {
                    json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), imagensReferenciaJsonFromInput)
                } else { emptyList() }
            } catch (e: Exception) {
                throw IllegalStateException(appContext.getString(R.string.error_invalid_reference_images_input), e)
            }
            
            var currentAttempt = 1
            var success = false

            while (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                updateSceneStateGeneral(sceneId, taskType, attempt = currentAttempt, success = null, generatedAssetPath = null, videoPreviewPath = null, errorMessage = null)

                val notificationContent = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.notification_generating_image_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.notification_changing_clothes_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.notification_generating_video_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    else -> "Processando..."
                }
                updateNotificationProgress(notificationId, notificationContent)

                val taskResult = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> callGerarImagemWorker(sceneId, findSceneCena(sceneId), imageStaticGenPromptFromInput!!, listaImagensParaApi)
                    TASK_TYPE_CHANGE_CLOTHES -> callTrocaRoupaWorker(findSceneGeneratedPath(sceneId)!!, chosenReferenceImagePathForClothesChange!!, findSceneCena(sceneId) ?: sceneId, applicationContext)
                    TASK_TYPE_GENERATE_VIDEO -> callGerarVideoWorker(sceneId, findSceneCena(sceneId), videoGenPromptFromInput!!, sourceImagePathForVideo!!)
                    else -> InternalTaskResult(errorMessage = "Tarefa desconhecida")
                }
                
                if (taskResult.isSuccess) {
                    success = true
                    lastError = null
                    finalResult = taskResult // Salva o resultado de sucesso
                    
                    updateSceneStateGeneral(sceneId, taskType, 0, true, taskResult.filePath, taskResult.thumbPath, null)
                    break
                } else { 
                    lastError = taskResult.errorMessage ?: appContext.getString(R.string.error_unknown_task_failure)
                    currentAttempt++
                    if (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                        updateSceneStateGeneral(sceneId, taskType = taskType, attempt = currentAttempt, success = false, generatedAssetPath = null, videoPreviewPath = null, errorMessage = lastError)
                        delay(RETRY_DELAY_MILLIS)
                    } else if (!coroutineContext.isActive) {
                        lastError = appContext.getString(R.string.error_task_cancelled_externally)
                        break
                    }
                }
            }
            
            ProjectPersistenceManager.saveProjectState(appContext)
            
            if (!success) {
                throw Exception(lastError ?: appContext.getString(R.string.error_max_attempts_reached, MAX_ATTEMPTS))
            }
            
            updateNotificationProgress(notificationId, appContext.getString(R.string.notification_task_completed_success, taskType, sceneId.take(8)), true)
            
            // Retorna os dados necessários para o ViewModel reagir
            return@coroutineScope Result.success(workDataOf(
                "sceneId" to sceneId,
                "newImagePath" to finalResult?.filePath,
                "newThumbPath" to finalResult?.thumbPath
            ))

        } catch (e: Exception) {
            val finalErrorMessage = when (e) {
                is CancellationException -> e.message ?: appContext.getString(R.string.error_task_cancelled_explicitly)
                else -> e.message ?: appContext.getString(R.string.error_unexpected_worker_error)
            }
            
            updateSceneStateGeneral(sceneId, taskType, 0, false, null, null, finalErrorMessage)
            updateNotificationProgress(notificationId, appContext.getString(R.string.notification_task_failed, taskType, sceneId.take(8), finalErrorMessage.take(50)), true, isError = true)
            return@coroutineScope Result.failure(workDataOf("error" to finalErrorMessage))
        }
    }
    
    
    private suspend fun findSceneCena(sceneId: String): String? = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.cena
    private suspend fun findSceneGeneratedPath(sceneId: String): String? = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.imagemGeradaPath

    private suspend fun updateSceneStateGeneral(
        sceneId: String, taskType: String, attempt: Int, success: Boolean?,
        generatedAssetPath: String?, videoPreviewPath: String?, errorMessage: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val currentList = projectDataStoreManager.sceneLinkDataList.first()
            val newList = currentList.map { item ->
                if (item.id == sceneId) {
                    when (taskType) {
                        TASK_TYPE_GENERATE_IMAGE, TASK_TYPE_CHANGE_CLOTHES -> item.copy(
                            isGenerating = (taskType == TASK_TYPE_GENERATE_IMAGE && success == null),
                            isChangingClothes = (taskType == TASK_TYPE_CHANGE_CLOTHES && success == null),
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true) generatedAssetPath else item.pathThumb,
                            videoPreviewPath = null, // Limpa para forçar regeneração
                            generationErrorMessage = if (success == false) errorMessage else null,
                            isGeneratingVideo = false
                        )
                        TASK_TYPE_GENERATE_VIDEO -> item.copy(
                            isGeneratingVideo = success == null,
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true) videoPreviewPath else item.pathThumb,
                            videoPreviewPath = null, // Limpa para forçar regeneração
                            promptVideo = inputData.getString(KEY_VIDEO_GEN_PROMPT) ?: item.promptVideo,
                            generationErrorMessage = if (success == false) errorMessage else null,
                            isGenerating = false,
                            isChangingClothes = false
                        )
                        else -> item
                    }
                } else item
            }
            if (newList != currentList) {
                projectDataStoreManager.setSceneLinkDataList(newList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ERRO ao atualizar estado para cena $sceneId", e)
        }
    }

    private suspend fun callGerarImagemWorker(sceneId: String, cena: String?, prompt: String, listaImagensReferencia: List<ImagemReferencia>): InternalTaskResult {
        val resultado = GeminiImageApi.gerarImagem(cena.orEmpty(), prompt, applicationContext, listaImagensReferencia)
        return if (resultado.isSuccess) {
            InternalTaskResult(filePath = resultado.getOrNull() ?: "")
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message ?: "Erro desconhecido")
        }
    }

    private suspend fun callGerarVideoWorker(sceneId: String, cena: String?, prompt: String, imgPatch: String): InternalTaskResult {
        val resultado = GeminiVideoApi.gerarVideo(cena ?: sceneId, prompt, applicationContext, imgPatch)
        return if (resultado.isSuccess) {
            InternalTaskResult(filePath = resultado.getOrNull()?.firstOrNull() ?: "")
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message ?: "Erro desconhecido")
        }
    }

        private suspend fun callTrocaRoupaWorker(
        imagemCenaPath: String,
        imagemReferenciaPath: String,
        cena: String,
        context: Context
    ): InternalTaskResult {
        return tryOnMutex.withLock {
            try {
                // Extrai o nome do arquivo original
                val nomeArquivo = File(imagemCenaPath).name
                val projectDirName = videoPreferences.videoProjectDir.first()
                val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
                val tryDir = File(baseProjectDir, "try_on")
                tryDir.mkdirs()
                
                // Define novo caminho da cópia
                val copiaImagemCena = File(tryDir, nomeArquivo)
                
                // Faz a cópia do arquivo original para o diretório do projeto
                File(imagemCenaPath).copyTo(copiaImagemCena, overwrite = true)
                val resultado = ProvadorVirtual.generate(
                    fotoPath = copiaImagemCena.absolutePath,
                    figurinoPath = imagemReferenciaPath,
                    context = context
                )
    
                // Retorna o resultado do processamento
                if (resultado.isSuccess) {
                    InternalTaskResult(filePath = resultado.getOrNull())
                } else {
                    InternalTaskResult(
                        errorMessage = resultado.exceptionOrNull()?.message
                            ?: appContext.getString(R.string.error_clothes_change_api_failed)
                    )
                }
                
    
            } catch (e: IOException) {
                Log.e("ArquivoCopia", "Erro ao copiar o arquivo: ${e.message}")
                InternalTaskResult(errorMessage = "Erro ao copiar imagem para projeto")
            }
        }
    }

}