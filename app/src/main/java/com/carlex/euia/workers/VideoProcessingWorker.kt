// File: euia/workers/VideoProcessingWorker.kt
package com.carlex.euia.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.google.ai.client.generativeai.type.ServerException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.carlex.euia.MainActivity // Importado para o PendingIntent da notificação
import com.carlex.euia.R
import com.carlex.euia.api.GeminiImageApiImg3
import com.carlex.euia.api.GeminiImageApi
import com.carlex.euia.api.FirebaseImagenApi
import com.carlex.euia.api.GeminiVideoApi
import com.carlex.euia.api.ProvadorVirtual
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
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
import retrofit2.HttpException


// Define TAG for logging
private const val TAG = "VideoProcessingWorker"

// <<< CORREÇÃO 1: Constantes de notificação específicas para ESTE worker >>>
private const val NOTIFICATION_ID = 1322 // ID ÚNICO PARA ESTE WORKER
private const val NOTIFICATION_CHANNEL_ID = "VideoProcessingChannelEUIA"

// Estrutura para resultado das tarefas internas do worker
data class InternalTaskResult(
    val filePath: String? = null, // Para imagem gerada ou vídeo gerado
    val thumbPath: String? = null, // Para thumbnail do vídeo gerado
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
        const val KEY_IMAGE_GEN_PROMPT = "imageGenPrompt" // Para gerar imagem estática
        const val KEY_VIDEO_GEN_PROMPT = "videoGenPrompt"   // Para gerar vídeo
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
    private val videoDataStoreManager = VideoDataStoreManager(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val tryOnMutex = Mutex()
    private val MAX_ATTEMPTS = 3
    private val RETRY_DELAY_MILLIS = 2000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // <<< CORREÇÃO 2: Implementação das funções de notificação DENTRO do worker >>>
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(R.string.video_processing_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.video_processing_notification_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
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
            builder.setProgress(0, 0, true).setOngoing(true) // Indeterminado
        }
        return builder.build()
    }
    
    private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false, isError: Boolean = false) {
        val notification = createNotification(contentText, isFinished = makeDismissible, isError = isError)
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notificação de VideoProcessing atualizada: $contentText")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // <<< CORREÇÃO 3: Chamar a criação do canal ANTES de criar a notificação >>>
        //createNotificationChannel()
        val notification = createNotification(appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }


    override suspend fun doWork(): Result = coroutineScope {
        Log.d(TAG, "doWork() Iniciado.")

        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_scene_id_missing)))
        val taskType = inputData.getString(KEY_TASK_TYPE)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_task_type_missing)))

        val initialNotificationContent = when (taskType) {
            TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.video_processing_notification_generating_image, sceneId.take(8))
            TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.video_processing_notification_changing_clothes, sceneId.take(8))
            TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.video_processing_notification_generating_video_for_scene, sceneId.take(8))
            else -> appContext.getString(R.string.video_processing_notification_starting)
        }
        // <<< CORREÇÃO 4: Chamar a função de atualização de notificação correta >>>
        updateNotificationProgress(initialNotificationContent)
        
        val authViewModel = AuthViewModel(applicationContext as Application)
        var creditsDeductedForThisTask = false
        var lastError: String? = null

        try {
            if (taskType == TASK_TYPE_GENERATE_IMAGE) {
                // ... lógica de dedução de crédito ...
            }

            val imageStaticGenPromptFromInput = inputData.getString(KEY_IMAGE_GEN_PROMPT)
            val videoGenPromptFromInput = inputData.getString(KEY_VIDEO_GEN_PROMPT)

            val chosenReferenceImagePathForClothesChange = inputData.getString(KEY_CHOSEN_REFERENCE_IMAGE_PATH)
            val sourceImagePathForVideo = inputData.getString(KEY_SOURCE_IMAGE_PATH_FOR_VIDEO)

            val imagensReferenciaJsonFromInput = inputData.getString(KEY_IMAGENS_REFERENCIA_JSON_INPUT) ?: "[]"
            val listaImagensParaApi: List<ImagemReferencia> = try {
                if (imagensReferenciaJsonFromInput.isNotBlank() && imagensReferenciaJsonFromInput != "[]") {
                    json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), imagensReferenciaJsonFromInput)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                throw IllegalStateException(appContext.getString(R.string.error_invalid_reference_images_input), e)
            }
            
            var currentAttempt = 1
            var success = false

            while (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                updateSceneStateGeneral(sceneId, taskType, attempt = currentAttempt, success = null, errorMessage = null)
                
                val notificationContent = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.notification_generating_image_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.notification_changing_clothes_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.notification_generating_video_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    else -> "Processando..."
                }
                updateNotificationProgress(notificationContent)

                val taskResult = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> callGerarImagemWorker(sceneId, findSceneCena(sceneId), imageStaticGenPromptFromInput!!, listaImagensParaApi)
                    TASK_TYPE_CHANGE_CLOTHES -> callTrocaRoupaWorker(findSceneGeneratedPath(sceneId)!!, chosenReferenceImagePathForClothesChange!!, findSceneCena(sceneId) ?: sceneId, applicationContext)
                    TASK_TYPE_GENERATE_VIDEO -> callGerarVideoWorker(sceneId, findSceneCena(sceneId), videoGenPromptFromInput!!, sourceImagePathForVideo!!)
                    else -> InternalTaskResult(errorMessage = "Tarefa desconhecida")
                }
                
                if (taskResult.isSuccess) {
                        success = true
                        lastError = null
                        creditsDeductedForThisTask = false
                        updateSceneStateGeneral(sceneId, taskType, 0, true, taskResult.filePath, taskResult.thumbPath, null)
                        break
                } else { 
                    if (taskResult.errorMessage?.contains("429") == true){
                        lastError = "Fila de trabalho no servidor cheia. Tente novamente mais tarde. (Erro 429)"
                        Log.w(TAG, "Erro 429 recebido. Interrompendo tentativas.")
                        break
                    }
                    lastError = taskResult.errorMessage ?: appContext.getString(R.string.error_unknown_task_failure)
                    Log.w(TAG, "Worker para $sceneId ($taskType): Tarefa FALHOU (erro: $lastError) na tentativa $currentAttempt.")
                    currentAttempt++
                    if (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                        updateSceneStateGeneral(sceneId, taskType = taskType, attempt = currentAttempt, success = false, errorMessage = lastError)
                        delay(RETRY_DELAY_MILLIS)
                    } else if (!coroutineContext.isActive) {
                        lastError = appContext.getString(R.string.error_task_cancelled_externally)
                        break
                    }
                }
            }

            if (!success) {
                throw Exception(lastError ?: appContext.getString(R.string.error_max_attempts_reached, MAX_ATTEMPTS))
            }
            
            updateNotificationProgress(appContext.getString(R.string.notification_task_completed_success, taskType, sceneId.take(8)), true)
            return@coroutineScope Result.success()

        } catch (e: Exception) {
            val finalErrorMessage = when (e) {
                is CancellationException -> e.message ?: appContext.getString(R.string.error_task_cancelled_explicitly)
                else -> e.message ?: appContext.getString(R.string.error_unexpected_worker_error)
            }
            
            updateSceneStateGeneral(sceneId, taskType, 0, false, null, null, finalErrorMessage)
            updateNotificationProgress(appContext.getString(R.string.notification_task_failed, taskType, sceneId.take(8), finalErrorMessage.take(50)), true, isError = true)
            return@coroutineScope Result.failure(workDataOf("error" to finalErrorMessage))
        } finally {
            Log.d(TAG, "Worker para $sceneId ($taskType): doWork() Finalizado.")
        }
    }

    // O resto da sua classe permanece igual
    // ... (callGerarImagemWorker, updateSceneStateGeneral, etc.)
    private suspend fun findSceneCena(sceneId: String): String? {
        return projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.cena
    }

    private suspend fun findSceneGeneratedPath(sceneId: String): String? {
        return projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }?.imagemGeradaPath
    }

    private suspend fun updateSceneStateGeneral(
        sceneId: String,
        taskType: String,
        attempt: Int,
        success: Boolean? = null,
        generatedAssetPath: String? = null,
        generatedThumbPath: String? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "updateSceneStateGeneral: Scene $sceneId, Task: $taskType, Attempt: $attempt, Success API: $success, AssetPath: ${generatedAssetPath?.take(30)}, Thumb: ${generatedThumbPath?.take(30)}, Error: $errorMessage")

            val currentList: List<SceneLinkData> = projectDataStoreManager.sceneLinkDataList.first()
            val newList = currentList.map { item ->
                if (item.id == sceneId) {
                    when (taskType) {
                        TASK_TYPE_GENERATE_IMAGE -> item.copy(
                            isGenerating = success == null,
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true && generatedAssetPath != null) generatedAssetPath else item.imagemGeradaPath,
                            generationErrorMessage = if (success == false) errorMessage else if (success == true) null else item.generationErrorMessage,
                            pathThumb = null,
                            isGeneratingVideo = false
                        )
                        TASK_TYPE_CHANGE_CLOTHES -> item.copy(
                            isChangingClothes = success == null,
                            clothesChangeAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true && generatedAssetPath != null) generatedAssetPath else item.imagemGeradaPath,
                            generationErrorMessage = if (success == false) errorMessage else if (success == true) null else item.generationErrorMessage,
                            pathThumb = null,
                            isGeneratingVideo = false
                        )
                        TASK_TYPE_GENERATE_VIDEO -> item.copy(
                            isGeneratingVideo = success == null,
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true && generatedAssetPath != null) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true && generatedThumbPath != null) generatedThumbPath else item.pathThumb,
                            promptVideo = inputData.getString(KEY_VIDEO_GEN_PROMPT) ?: item.promptVideo,
                            generationErrorMessage = if (success == false) errorMessage else if (success == true) null else item.generationErrorMessage,
                            isGenerating = false,
                            isChangingClothes = false
                        )
                        else -> item
                    }
                } else {
                    item
                }
            }

            if (newList != currentList) {
                val writeSuccess = projectDataStoreManager.setSceneLinkDataList(newList)
                if (writeSuccess) {
                    Log.d(TAG, "updateSceneStateGeneral: Estado para cena $sceneId (task: $taskType) ATUALIZADO no DataStore.")
                } else {
                    Log.e(TAG, "updateSceneStateGeneral: FALHA ao escrever estado para cena $sceneId (task: $taskType) no DataStore.")
                }
            } else {
                Log.d(TAG, "updateSceneStateGeneral: Nenhuma mudança detectada para cena $sceneId (task: $taskType), DataStore não atualizado.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSceneStateGeneral: ERRO ao atualizar estado para cena $sceneId (task: $taskType) no DataStore", e)
        }
    }

    private suspend fun callGerarImagemWorker(
        sceneId: String,
        cena: String?,
        prompt: String,
        listaImagensReferencia: List<ImagemReferencia>
    ): InternalTaskResult {
        val promptNegativo = appContext.getString(R.string.prompt_negativo_geral_imagem)
        Log.d(TAG, "Worker para $sceneId (Imagem): Chamado callGerarImagem para cena: '${cena.orEmpty().take(15)}', prompt: '${prompt.take(30)}...', com ${listaImagensReferencia.size} refs.")
       
        val resultado = GeminiImageApi.gerarImagem(
            cena = cena.orEmpty(),
            prompt = "$prompt $promptNegativo",
            context = applicationContext,
            imagensParaUpload = listaImagensReferencia
        )
        
        return if (resultado.isSuccess) {
            val caminhoImagemGerada = resultado.getOrNull() ?: ""
            if (caminhoImagemGerada.isNotBlank()) {
                Log.d(TAG, "Worker para $sceneId (Imagem): callGerarImagem SUCESSO. Caminho: ${caminhoImagemGerada.take(30)}...")
                InternalTaskResult(filePath = caminhoImagemGerada)
            } else {
                val errorMsg = appContext.getString(R.string.error_gemini_api_empty_image_path)
                Log.w(TAG, "Worker para $sceneId (Imagem): $errorMsg")
                InternalTaskResult(errorMessage = errorMsg)
            }
        } else {
            val errorMessage = resultado.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_api_failure_worker)
            Log.e(TAG, "Worker para $sceneId (Imagem): callGerarImagem FALHA: $errorMessage", resultado.exceptionOrNull())
            InternalTaskResult(errorMessage = errorMessage)
        }
    }


    private suspend fun callGerarVideoWorker(
        sceneId: String,
        cena: String?,
        prompt: String, 
        imgPatch: String
    ): InternalTaskResult {
        Log.d(TAG, "Worker para $sceneId (Vídeo): Chamado callGerarVideo para cena '${cena ?: sceneId}' baseada em: '${imgPatch.take(30)}', prompt: '${prompt.take(30)}...'")
        val resultado = GeminiVideoApi.gerarVideo(
            cena = cena ?: sceneId,
            prompt = prompt,
            context = applicationContext,
            imagemReferenciaPath = imgPatch
        )

        return if (resultado.isSuccess) {
            val caminhosVideoGerado: List<String> = resultado.getOrNull() ?: emptyList()
            val primeiroVideoPath = caminhosVideoGerado.firstOrNull() ?: ""

            if (primeiroVideoPath.isNotBlank()) {
                Log.d(TAG, "Worker para $sceneId (Vídeo): Geração SUCESSO. Vídeo principal: ${primeiroVideoPath.take(30)}...")
                var generatedThumbPath: String? = null
                var retriever: MediaMetadataRetriever? = null
                var thumbnailBitmap: Bitmap? = null
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(primeiroVideoPath)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val videoDurationSeconds = (durationStr?.toLongOrNull() ?: 0) / 1000L
                    val frameTime = if (videoDurationSeconds > 1L) 1_000_000L else videoDurationSeconds * 500_000L
                    thumbnailBitmap = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                    if (thumbnailBitmap != null) {
                        val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
                        val videoFile = File(primeiroVideoPath)
                        val baseNameThumb = "thumb_${videoFile.nameWithoutExtension}"
                        val larguraThumb = videoPreferencesDataStoreManager.videoLargura.first() ?: VideoPreferencesDataStoreManager.DEFAULT_VIDEO_WIDTH_FALLBACK
                        val alturaThumb = videoPreferencesDataStoreManager.videoAltura.first() ?: VideoPreferencesDataStoreManager.DEFAULT_VIDEO_HEIGHT_FALLBACK

                        var finalThumbBitmap: Bitmap? = BitmapUtils.resizeWithTransparentBackground(thumbnailBitmap, larguraThumb, alturaThumb)

                        if (finalThumbBitmap == null) {
                             Log.w(TAG, "Falha ao redimensionar thumbnail, usando original.")
                             finalThumbBitmap = thumbnailBitmap.copy(thumbnailBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                        }

                        if(finalThumbBitmap != null) {
                            val playIconDrawable = ContextCompat.getDrawable(appContext, R.drawable.ic_play_overlay_video)
                            if (playIconDrawable != null) {
                                val canvas = Canvas(finalThumbBitmap)
                                val iconSize = min(finalThumbBitmap.width, finalThumbBitmap.height) / 4
                                val iconLeft = (finalThumbBitmap.width - iconSize) / 2
                                val iconTop = (finalThumbBitmap.height - iconSize) / 2
                                playIconDrawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                                playIconDrawable.draw(canvas)
                            }

                            generatedThumbPath = BitmapUtils.saveBitmapToFile(
                                context = appContext,
                                bitmap = finalThumbBitmap,
                                projectDirName = projectDirName,
                                subDir = "generated_thumbnails",
                                baseName = baseNameThumb,
                                format = Bitmap.CompressFormat.JPEG,
                                quality = 85
                            )
                            BitmapUtils.safeRecycle(finalThumbBitmap, "VideoWorker_FinalThumb")
                        }


                        if (generatedThumbPath != null) {
                            Log.i(TAG, "Worker para $sceneId (Vídeo): Thumbnail gerado e salvo em: $generatedThumbPath")
                        } else {
                            Log.w(TAG, "Worker para $sceneId (Vídeo): Falha ao salvar thumbnail gerado.")
                        }
                    } else {
                        Log.w(TAG, "Worker para $sceneId (Vídeo): Falha ao extrair frame para thumbnail do vídeo: $primeiroVideoPath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Worker para $sceneId (Vídeo): Erro ao gerar/salvar thumbnail: ${e.message}", e)
                } finally {
                    retriever?.release()
                    BitmapUtils.safeRecycle(thumbnailBitmap, "VideoWorker_ExtractedVideoFrame")
                }
                InternalTaskResult(filePath = primeiroVideoPath, thumbPath = generatedThumbPath)
            } else {
                val errorMsg = appContext.getString(R.string.error_gemini_video_api_empty_paths)
                Log.w(TAG, "Worker para $sceneId (Vídeo): $errorMsg")
                InternalTaskResult(errorMessage = errorMsg)
            }
        } else {
            val errorMessage = resultado.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_video_api_failure)
            Log.e(TAG, "Worker para $sceneId (Vídeo): callGerarVideo FALHA: $errorMessage", resultado.exceptionOrNull())
            InternalTaskResult(errorMessage = errorMessage)
        }
    }


    private suspend fun callTrocaRoupaWorker(imagemCenaPath: String, imagemReferenciaPath: String, cena: String, context: Context): InternalTaskResult {
        Log.d(TAG, "Worker: Tentando adquirir Mutex para callTrocaRoupa para cena '${cena.take(15)}'...")
        return tryOnMutex.withLock {
            Log.d(TAG, "Worker: Mutex adquirido para callTrocaRoupa. Chamando ProvadorVirtual.generate...")
            val resultadoPath = ProvadorVirtual.generate(fotoPath = imagemCenaPath, figurinoPath = imagemReferenciaPath, context = context)
            Log.d(TAG, "Worker: ProvadorVirtual.generate concluído.")
            if (resultadoPath.isNullOrEmpty()) {
                Log.e(TAG, "Worker: callTrocaRoupa FALHA ao gerar troca de roupa.")
                InternalTaskResult(errorMessage = appContext.getString(R.string.error_clothes_change_api_failed))
            } else {
                Log.d(TAG, "Worker: callTrocaRoupa SUCESSO. Resultado: ${resultadoPath.take(30)}...")
                InternalTaskResult(filePath = resultadoPath)
            }
        }
    }
}