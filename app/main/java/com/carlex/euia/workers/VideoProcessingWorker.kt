// File: euia/workers/VideoProcessingWorker.kt
package com.carlex.euia.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.GeminiImageApi
import com.carlex.euia.api.ProvadorVirtual
import com.carlex.euia.api.GeminiVideoApi
import com.carlex.euia.api.QueueApiClient
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min

private const val TAG = "VideoProcessingWorker"

private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_CHANNEL_ID = "VideoProcessingChannelEUIA"

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

    private val projectDataStoreManager = VideoProjectDataStoreManager(applicationContext)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(applicationContext)
    private val queueApiClient = QueueApiClient.instance
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val tryOnMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val MAX_API_ATTEMPTS = 3
    private val RETRY_DELAY_MILLIS = 5000L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sceneId = inputData.getString(KEY_SCENE_ID)?.take(8) ?: appContext.getString(R.string.scene_id_unknown)
        val title = appContext.getString(R.string.video_processing_notification_title)
        val contentText = appContext.getString(R.string.notification_content_enqueuing, sceneId)

        createNotificationChannel()

        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.video_processing_notification_channel_name)
            val descriptionText = appContext.getString(R.string.video_processing_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false) {
        val title = appContext.getString(R.string.video_processing_notification_title)
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationBuilder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!makeDismissible)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    override suspend fun doWork(): Result = coroutineScope {
        Log.d(TAG, "[INÍCIO] doWork() para o worker ID: ${this@VideoProcessingWorker.id}")
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: return@coroutineScope failWorker(appContext.getString(R.string.error_scene_id_missing))
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return@coroutineScope failWorker(appContext.getString(R.string.error_task_type_missing))
        
        val userId = firebaseAuth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            val errorMsg = appContext.getString(R.string.error_user_not_authenticated_for_queue)
            updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = errorMsg)
            return@coroutineScope failWorker(errorMsg)
        }

        val taskResult = try {
            when (taskType) {
                TASK_TYPE_GENERATE_IMAGE -> handleQueueableTask(sceneId, taskType, userId) {
                    val prompt = inputData.getString(KEY_IMAGE_GEN_PROMPT)!!
                    val imagensReferenciaJson = inputData.getString(KEY_IMAGENS_REFERENCIA_JSON_INPUT) ?: "[]"
                    val listaImagens = json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), imagensReferenciaJson)
                    val cena = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.cena
                    callGerarImagemApi(sceneId, cena, prompt, listaImagens)
                }
                TASK_TYPE_CHANGE_CLOTHES -> handleQueueableTask(sceneId, taskType, userId) {
                    val imagemReferenciaPath = inputData.getString(KEY_CHOSEN_REFERENCE_IMAGE_PATH)!!
                    val imagemCenaPath = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.imagemGeradaPath!!
                    val cena = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.cena
                    callTrocaRoupaApi(imagemCenaPath, imagemReferenciaPath, cena ?: sceneId, applicationContext)
                }
                TASK_TYPE_GENERATE_VIDEO -> handleQueueableTask(sceneId, taskType, userId) {
                    val videoPrompt = inputData.getString(KEY_VIDEO_GEN_PROMPT)!!
                    val sourceImagePath = inputData.getString(KEY_SOURCE_IMAGE_PATH_FOR_VIDEO)!!
                    val cena = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.cena
                    callGerarVideoApi(sceneId, cena, videoPrompt, sourceImagePath)
                }
                else -> InternalTaskResult(errorMessage = appContext.getString(R.string.error_unknown_task_type_worker, taskType))
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "Worker para $sceneId ($taskType): Coroutine cancelada. ${e.message}")
            updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = appContext.getString(R.string.message_cancelled_by_user))
            return@coroutineScope failWorker(appContext.getString(R.string.error_processing_cancelled))
        } catch (e: Exception) {
            Log.e(TAG, "Erro não capturado no doWork para $sceneId ($taskType)", e)
            val errorMsg = appContext.getString(R.string.error_unexpected_worker_error, e.message)
            updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = errorMsg)
            return@coroutineScope failWorker(errorMsg)
        }

        if (taskResult.isSuccess) {
            updateSceneStateGeneral(sceneId, taskType, 0, true, taskResult.filePath, taskResult.thumbPath)
            updateNotificationProgress(appContext.getString(R.string.notification_task_completed_success, taskType, sceneId.take(8)), true)
            return@coroutineScope Result.success()
        } else {
            val finalError = taskResult.errorMessage ?: appContext.getString(R.string.error_task_failed_after_retries)
            updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = finalError)
            updateNotificationProgress(appContext.getString(R.string.notification_task_failed, taskType, sceneId.take(8), finalError.take(50)), true)
            return@coroutineScope Result.failure(workDataOf("error" to finalError))
        }
    }
    
    private suspend fun handleQueueableTask(
        sceneId: String,
        taskType: String,
        userId: String,
        actionToExecute: suspend () -> InternalTaskResult
    ): InternalTaskResult {
        Log.d(TAG, "[$sceneId] -> [handleQueueableTask] Iniciado para tarefa: $taskType")

        val sceneData = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }
        val requestId = sceneData?.queueRequestId ?: UUID.randomUUID().toString()

        if (sceneData?.queueRequestId.isNullOrBlank()) {
            Log.i(TAG, "[$sceneId] -> [handleQueueableTask] Requisição nova. Gerando ReqID: $requestId")
            updateSceneStateGeneral(sceneId, taskType, 1, null, queueRequestId = requestId, queueStatusMessage = appContext.getString(R.string.status_enqueuing))
            
            Log.d(TAG, "[$sceneId] -> [API_FILA] Chamando /enfileirar...")
            try {
                val enqueueResponse = queueApiClient.enqueueRequest(userId, requestId)
                if (!enqueueResponse.isSuccessful) {
                    val errorBody = enqueueResponse.errorBody()?.string() ?: "N/A"
                    val errorMsg = appContext.getString(R.string.error_enqueue_failed_simple)
                    Log.e(TAG, "[$sceneId] -> [API_FILA] Falha em /enfileirar. Código: ${enqueueResponse.code()}, Corpo: $errorBody")
                    updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = errorMsg, queueRequestId = null)
                    return InternalTaskResult(errorMessage = errorMsg)
                }
                Log.i(TAG, "[$sceneId] -> [API_FILA] Sucesso em /enfileirar.")
            } catch (e: Exception) {
                val errorMsg = appContext.getString(R.string.error_enqueue_connection_failed, e.message)
                Log.e(TAG, "[$sceneId] -> [API_FILA] Erro de conexão em /enfileirar.", e)
                updateSceneStateGeneral(sceneId, taskType, 0, false, errorMessage = errorMsg, queueRequestId = null)
                return InternalTaskResult(errorMessage = errorMsg)
            }
        } else {
            Log.i(TAG, "[$sceneId] -> [handleQueueableTask] Requisição existente encontrada. Retomando monitoramento para ReqID: $requestId")
        }
        
        Log.d(TAG, "[$sceneId] -> [handleQueueableTask] Entrando no loop de espera pela liberação (polling)...")
        if (!waitForRelease(sceneId, taskType, userId, requestId)) {
            val errorMsg = if (!coroutineContext.isActive) appContext.getString(R.string.error_processing_cancelled) else appContext.getString(R.string.error_queue_timeout)
            Log.w(TAG, "[$sceneId] -> [handleQueueableTask] Saiu do polling sem liberação: $errorMsg")
            
            Log.d(TAG, "[$sceneId] -> [API_FILA] Chamando /confirmar_execucao (após timeout/cancelamento) para liberar o slot...")
            queueApiClient.confirmExecution(userId, requestId)
            return InternalTaskResult(errorMessage = errorMsg)
        }
        Log.i(TAG, "[$sceneId] -> [handleQueueableTask] SLOT LIBERADO! Prosseguindo para execução da tarefa principal.")
        
        updateNotificationProgress(appContext.getString(R.string.notification_task_released, taskType, sceneId.take(8)))
        updateSceneStateGeneral(sceneId, taskType, 1, null, queueStatusMessage = appContext.getString(R.string.status_generating))
        
        var finalTaskResult: InternalTaskResult? = null
        try {
            var currentAttempt = 1
            Log.d(TAG, "[$sceneId] -> [handleQueueableTask] Entrando no loop de retentativas da API (Max: $MAX_API_ATTEMPTS).")
            while(currentAttempt <= MAX_API_ATTEMPTS && coroutineContext.isActive) {
                Log.i(TAG, "[$sceneId] -> [AÇÃO_PRINCIPAL] Executando tentativa $currentAttempt para tarefa '$taskType'...")
                finalTaskResult = actionToExecute()
                if(finalTaskResult.isSuccess) {
                    Log.i(TAG, "[$sceneId] -> [AÇÃO_PRINCIPAL] Sucesso na tentativa $currentAttempt.")
                    break
                }

                val attemptError = finalTaskResult.errorMessage ?: "Erro desconhecido na tentativa $currentAttempt"
                Log.w(TAG, "[$sceneId] -> [AÇÃO_PRINCIPAL] Tentativa $currentAttempt falhou: $attemptError")
                updateSceneStateGeneral(sceneId, taskType, currentAttempt, null, errorMessage = attemptError, queueStatusMessage = "Tentativa $currentAttempt falhou")
                
                currentAttempt++
                if(currentAttempt <= MAX_API_ATTEMPTS && coroutineContext.isActive) {
                    Log.d(TAG, "[$sceneId] -> [AÇÃO_PRINCIPAL] Aguardando ${RETRY_DELAY_MILLIS}ms para a próxima tentativa.")
                    delay(RETRY_DELAY_MILLIS)
                } else if(finalTaskResult != null) {
                    Log.e(TAG, "[$sceneId] -> [AÇÃO_PRINCIPAL] Número máximo de tentativas atingido.")
                    break 
                }
            }
        } finally {
            Log.d(TAG, "[$sceneId] -> [API_FILA] Entrando no bloco finally. Chamando /confirmar_execucao para ReqID $requestId...")
            try {
                queueApiClient.confirmExecution(userId, requestId)
                Log.i(TAG, "[$sceneId] -> [API_FILA] Sucesso em /confirmar_execucao.")
            } catch (e: Exception) {
                Log.e(TAG, "[$sceneId] -> [API_FILA] FALHA CRÍTICA em /confirmar_execucao.", e)
            }
        }
        return finalTaskResult ?: InternalTaskResult(errorMessage = "A ação principal não retornou um resultado.")
    }

    private suspend fun waitForRelease(sceneId: String, taskType: String, userId: String, requestId: String): Boolean {
        val maxPollingAttempts = 120 // 10 minutos (120 * 5s)
        var pollingAttempts = 0
        Log.d(TAG, "[$sceneId] -> [waitForRelease] Iniciado para ReqID: $requestId. Max tentativas: $maxPollingAttempts")
        while (pollingAttempts < maxPollingAttempts && coroutineContext.isActive) {
            delay(5000)
            pollingAttempts++
            Log.d(TAG, "[$sceneId] -> [API_FILA] Chamando /status_requisicao (Tentativa de polling: $pollingAttempts/$maxPollingAttempts)...")
            try {
                val response = queueApiClient.checkRequestStatus(userId, requestId)
                if (response.isSuccessful && response.body() != null) {
                    val statusBody = response.body()!!
                    Log.d(TAG, "[$sceneId] -> [API_FILA] Resposta de /status_requisicao: ${statusBody.status}")
                    if (statusBody.status == "liberado") {
                        Log.i(TAG, "[$sceneId] -> [waitForRelease] Status 'liberado' recebido. Saindo do loop.")
                        return true
                    }
                    
                    val statusMessage = statusBody.mensagem ?: appContext.getString(R.string.status_in_queue_position, (statusBody.posicaoFilaGlobal ?: 0) + 1)
                    Log.d(TAG, "[$sceneId] -> [waitForRelease] Ainda pendente. Mensagem: '$statusMessage'")
                    updateNotificationProgress(appContext.getString(R.string.notification_in_queue, sceneId.take(8), statusMessage))
                    updateSceneStateGeneral(sceneId, taskType, 1, null, queueStatusMessage = statusMessage)
                } else {
                     Log.w(TAG, "[$sceneId] -> [API_FILA] Resposta de /status_requisicao não foi bem-sucedida. Código: ${response.code()}")
                }
            } catch (e: Exception) { Log.e(TAG, "[$sceneId] -> [API_FILA] Erro de conexão em /status_requisicao.", e) }
            pollingAttempts++
        }
        Log.w(TAG, "[$sceneId] -> [waitForRelease] Fim do loop de polling. Liberado: false.")
        return false
    }
    
    private suspend fun updateSceneStateGeneral(
        sceneId: String, taskType: String, attempt: Int, success: Boolean?,
        generatedAssetPath: String? = null, generatedThumbPath: String? = null,
        errorMessage: String? = null, queueRequestId: String? = null, queueStatusMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val currentList = projectDataStoreManager.sceneLinkDataList.first()
            val newList = currentList.map { item ->
                if (item.id == sceneId) {
                    val taskFinished = success != null
                    val finalQueueRequestId = if (taskFinished) null else (queueRequestId ?: item.queueRequestId)
                    val finalQueueStatusMessage = if (taskFinished) null else (queueStatusMessage ?: item.queueStatusMessage)

                    when (taskType) {
                        TASK_TYPE_GENERATE_IMAGE -> item.copy(
                            isGenerating = !taskFinished,
                            generationAttempt = if (taskFinished) 0 else attempt,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            generationErrorMessage = if (success == false) errorMessage else null,
                            queueRequestId = finalQueueRequestId,
                            queueStatusMessage = finalQueueStatusMessage,
                        )
                        TASK_TYPE_CHANGE_CLOTHES -> item.copy(
                            isChangingClothes = !taskFinished,
                            clothesChangeAttempt = if (taskFinished) 0 else attempt,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            generationErrorMessage = if (success == false) errorMessage else null,
                            queueRequestId = finalQueueRequestId,
                            queueStatusMessage = finalQueueStatusMessage
                        )
                        TASK_TYPE_GENERATE_VIDEO -> item.copy(
                            isGeneratingVideo = !taskFinished,
                            generationAttempt = if (taskFinished) 0 else attempt,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true) generatedThumbPath else item.pathThumb,
                            generationErrorMessage = if (success == false) errorMessage else null,
                            queueRequestId = finalQueueRequestId,
                            queueStatusMessage = finalQueueStatusMessage
                        )
                        else -> item
                    }
                } else item
            }
            if (newList != currentList) {
                projectDataStoreManager.setSceneLinkDataList(newList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro fatal em updateSceneStateGeneral para cena $sceneId", e)
        }
    }

    private suspend fun callGerarImagemApi(sceneId: String, cena: String?, prompt: String, listaImagensReferencia: List<ImagemReferencia>): InternalTaskResult {
        val promptNegativo = appContext.getString(R.string.prompt_negativo_geral_imagem)
        val resultado = GeminiImageApi.gerarImagem(
            cena = cena.orEmpty(),
            prompt = "$prompt $promptNegativo",
            context = applicationContext,
            imagensParaUpload = listaImagensReferencia
        )
        return if (resultado.isSuccess) {
            InternalTaskResult(filePath = resultado.getOrNull())
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message)
        }
    }

    private suspend fun callTrocaRoupaApi(imagemCenaPath: String, imagemReferenciaPath: String, cena: String, context: Context): InternalTaskResult {
        return tryOnMutex.withLock {
            val resultadoPath = ProvadorVirtual.generate(fotoPath = imagemCenaPath, figurinoPath = imagemReferenciaPath, context = context)
            if (resultadoPath.isNullOrEmpty() || resultadoPath == imagemCenaPath) {
                InternalTaskResult(errorMessage = appContext.getString(R.string.error_clothes_change_api_failed))
            } else {
                InternalTaskResult(filePath = resultadoPath)
            }
        }
    }

    private suspend fun callGerarVideoApi(sceneId: String, cena: String?, prompt: String, imgPatch: String): InternalTaskResult {
        val resultado = GeminiVideoApi.gerarVideo(
            cena = cena ?: sceneId,
            prompt = prompt,
            context = applicationContext,
            imagemReferenciaPath = imgPatch
        )
        return if (resultado.isSuccess) {
            val videoPath = resultado.getOrNull()?.firstOrNull()
            if (videoPath.isNullOrBlank()) {
                InternalTaskResult(errorMessage = "API retornou sucesso mas sem caminho de vídeo.")
            } else {
                val thumbPath = extractAndSaveThumbnail(videoPath)
                InternalTaskResult(filePath = videoPath, thumbPath = thumbPath)
            }
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message)
        }
    }

    private suspend fun extractAndSaveThumbnail(videoPath: String): String? {
        return withContext(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            var thumbnailBitmap: Bitmap? = null
            try {
                retriever = MediaMetadataRetriever().apply { setDataSource(videoPath) }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                val frameTimeUs = if (durationMs > 1000) 1_000_000L else durationMs * 1000 / 2
                thumbnailBitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                
                if (thumbnailBitmap != null) {
                    val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
                    val baseName = "thumb_${File(videoPath).nameWithoutExtension}"
                    val (width, height) = videoPreferencesDataStoreManager.videoLargura.first() to videoPreferencesDataStoreManager.videoAltura.first()
                    
                    var resizedBitmap = BitmapUtils.resizeWithTransparentBackground(thumbnailBitmap, width ?: 720, height ?: 1280)
                    if (resizedBitmap == null) {
                        Log.w(TAG, "Falha ao redimensionar thumbnail, usando original.")
                        resizedBitmap = thumbnailBitmap.copy(thumbnailBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    }

                    val canvas = Canvas(resizedBitmap)
                    ContextCompat.getDrawable(appContext, R.drawable.ic_play_overlay_video)?.apply {
                        val iconSize = min(resizedBitmap.width, resizedBitmap.height) / 4
                        val (left, top) = (resizedBitmap.width - iconSize) / 2 to (resizedBitmap.height - iconSize) / 2
                        setBounds(left, top, left + iconSize, top + iconSize)
                        draw(canvas)
                    }

                    BitmapUtils.saveBitmapToFile(appContext, resizedBitmap, projectDirName, "generated_thumbnails", baseName, Bitmap.CompressFormat.JPEG, 85)
                } else { null }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao extrair thumbnail de $videoPath", e)
                null
            } finally {
                retriever?.release()
                BitmapUtils.safeRecycle(thumbnailBitmap, "Worker_ThumbExtract")
            }
        }
    }

    private fun failWorker(errorMsg: String): Result {
        Log.e(TAG, "Worker falhando rapidamente: $errorMsg")
        updateNotificationProgress(errorMsg, true)
        return Result.failure(workDataOf("error" to errorMsg))
    }
}