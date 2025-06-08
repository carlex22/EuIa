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
import com.carlex.euia.api.FirebaseImagenApi
import com.carlex.euia.api.GeminiVideoApi
import com.carlex.euia.api.ProvadorVirtual
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.BitmapUtils
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


// Define TAG for logging
private const val TAG = "VideoProcessingWorker"

// Constantes para a notificação
private const val NOTIFICATION_ID = 1
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


    private val tryOnMutex = Mutex()
    private val MAX_ATTEMPTS = 3
    private val RETRY_DELAY_MILLIS = 2000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sceneId = inputData.getString(KEY_SCENE_ID)?.take(8) ?: appContext.getString(R.string.scene_id_unknown)
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: appContext.getString(R.string.task_type_processing)
        val title = appContext.getString(R.string.video_processing_notification_title)
        var contentText = appContext.getString(R.string.video_processing_notification_generating_image, sceneId)

        when (taskType) {
            TASK_TYPE_CHANGE_CLOTHES -> contentText = appContext.getString(R.string.video_processing_notification_changing_clothes, sceneId)
            TASK_TYPE_GENERATE_VIDEO -> contentText = appContext.getString(R.string.video_processing_notification_generating_video_for_scene, sceneId)
        }


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
            Log.d(TAG, "Canal de notificação '$NOTIFICATION_CHANNEL_ID' criado/verificado.")
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
        Log.d(TAG, "Notificação atualizada: $contentText")
    }


    override suspend fun doWork(): Result = coroutineScope {
        Log.d(TAG, "doWork() Iniciado.")

        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_scene_id_missing)))
        val taskType = inputData.getString(KEY_TASK_TYPE)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_task_type_missing)))

        val imageStaticGenPromptFromInput = inputData.getString(KEY_IMAGE_GEN_PROMPT)
        val videoGenPromptFromInput = inputData.getString(KEY_VIDEO_GEN_PROMPT)

        val chosenReferenceImagePathForClothesChange = inputData.getString(KEY_CHOSEN_REFERENCE_IMAGE_PATH)
        val sourceImagePathForVideo = inputData.getString(KEY_SOURCE_IMAGE_PATH_FOR_VIDEO)

        val imagensReferenciaJsonFromInput = inputData.getString(KEY_IMAGENS_REFERENCIA_JSON_INPUT) ?: "[]"
        var listaImagensParaApi: List<ImagemReferencia>

        try {
            listaImagensParaApi = if (imagensReferenciaJsonFromInput.isNotBlank() && imagensReferenciaJsonFromInput != "[]") {
                json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), imagensReferenciaJsonFromInput)
            } else {
                emptyList()
            }
            Log.d(TAG, "Usando lista de ImagemReferencia do inputData com ${listaImagensParaApi.size} item(s).")

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao desserializar JSON de ImagemReferencia para cena $sceneId. Input JSON: $imagensReferenciaJsonFromInput", e)
            val errorMsg = appContext.getString(R.string.error_invalid_reference_images_input)
            updateSceneStateGeneral(sceneId, taskType, attempt = 0, success = false, errorMessage = errorMsg)
            updateNotificationProgress(appContext.getString(R.string.notification_error_image_refs_scene, sceneId.take(8)), true)
            return@coroutineScope Result.failure(workDataOf("error" to errorMsg))
        }

        Log.d(TAG, "Worker para Scene ID: $sceneId, Tipo de Tarefa: $taskType. Usando ${listaImagensParaApi.size} imagens de referência para a API (se aplicável à task).")


        val initialAttempt = 1
        updateSceneStateGeneral(
            sceneId = sceneId,
            taskType = taskType,
            attempt = initialAttempt,
            success = null,
            errorMessage = null
        )

        var currentAttempt = initialAttempt
        var success = false
        var lastError: String? = null

        try {
            while (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                Log.d(TAG, "Worker para $sceneId ($taskType): Executando Tentativa $currentAttempt de $MAX_ATTEMPTS.")
                val notificationContent = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.notification_generating_image_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.notification_changing_clothes_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.notification_generating_video_attempt, sceneId.take(8), currentAttempt, MAX_ATTEMPTS)
                    else -> "Processando..."
                }
                updateNotificationProgress(notificationContent)

                val taskResult: InternalTaskResult = when (taskType) {
                    TASK_TYPE_GENERATE_IMAGE -> {
                        if (imageStaticGenPromptFromInput.isNullOrBlank()) {
                            InternalTaskResult(errorMessage = appContext.getString(R.string.error_empty_prompt_for_image_gen_worker))
                        } else {
                            val currentSceneData = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }
                            callGerarImagemWorker(
                                sceneId = sceneId,
                                cena = currentSceneData?.cena,
                                prompt = imageStaticGenPromptFromInput,
                                listaImagensReferencia = listaImagensParaApi
                            )
                        }
                    }
                    TASK_TYPE_CHANGE_CLOTHES -> {
                        val currentSceneData = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }
                        if (currentSceneData?.imagemGeradaPath.isNullOrBlank() || chosenReferenceImagePathForClothesChange.isNullOrBlank()) {
                            InternalTaskResult(errorMessage = appContext.getString(R.string.error_missing_images_for_clothes_change))
                        } else {
                            callTrocaRoupaWorker(
                                imagemCenaPath = currentSceneData!!.imagemGeradaPath!!,
                                imagemReferenciaPath = chosenReferenceImagePathForClothesChange,
                                cena = currentSceneData.cena ?: sceneId,
                                context = applicationContext
                            ).let { clothesChangeResult ->
                                if (clothesChangeResult.isSuccess && clothesChangeResult.filePath == currentSceneData.imagemGeradaPath) {
                                    InternalTaskResult(errorMessage = appContext.getString(R.string.error_clothes_change_same_image))
                                } else {
                                    clothesChangeResult
                                }
                            }
                        }
                    }
                    TASK_TYPE_GENERATE_VIDEO -> {
                        if (videoGenPromptFromInput.isNullOrBlank()) {
                            InternalTaskResult(errorMessage = appContext.getString(R.string.error_empty_prompt_for_video_gen_worker))
                        } else if (sourceImagePathForVideo.isNullOrBlank()) {
                            InternalTaskResult(errorMessage = appContext.getString(R.string.error_empty_source_image_for_video_gen_worker))
                        }
                        else {
                            val currentSceneData = projectDataStoreManager.sceneLinkDataList.first().find { it.id == sceneId }
                            callGerarVideoWorker(
                                sceneId = sceneId,
                                cena = currentSceneData?.cena,
                                prompt = videoGenPromptFromInput, // Usa o prompt de vídeo
                                imgPatch = sourceImagePathForVideo
                            )
                        }
                    }
                    else -> InternalTaskResult(errorMessage = appContext.getString(R.string.error_unknown_task_type_worker, taskType))
                }

                if (taskResult.isSuccess) {
                    success = true
                    lastError = null
                    Log.d(TAG, "Worker para $sceneId ($taskType): Tarefa SUCESSO na tentativa $currentAttempt. Path: ${taskResult.filePath?.take(30)}..., Thumb: ${taskResult.thumbPath?.take(30)}")
                    updateSceneStateGeneral(sceneId, taskType, attempt = 0, success = true, generatedAssetPath = taskResult.filePath, generatedThumbPath = taskResult.thumbPath, errorMessage = null)
                    break
                } else {
                    lastError = taskResult.errorMessage ?: appContext.getString(R.string.error_unknown_task_failure)
                    Log.w(TAG, "Worker para $sceneId ($taskType): Tarefa FALHOU (erro: $lastError) na tentativa $currentAttempt.")
                    currentAttempt++
                    if (currentAttempt <= MAX_ATTEMPTS && coroutineContext.isActive) {
                        updateSceneStateGeneral(sceneId,
                            taskType = taskType,
                            attempt = currentAttempt,
                            success = false,
                            errorMessage = lastError)
                        delay(RETRY_DELAY_MILLIS)
                    } else if (!coroutineContext.isActive) {
                        lastError = appContext.getString(R.string.error_task_cancelled_externally)
                        break
                    }
                }
            }

            if (!success && coroutineContext.isActive) {
                updateSceneStateGeneral(sceneId, taskType, attempt = 0, success = false, errorMessage = lastError)
            }

            val finalNotificationContent = if (success) {
                appContext.getString(R.string.notification_task_completed_success, taskType, sceneId.take(8))
            } else {
                appContext.getString(R.string.notification_task_failed, taskType, sceneId.take(8), (lastError ?: appContext.getString(R.string.error_unknown_reason)))
            }
            updateNotificationProgress(finalNotificationContent, true)

            return@coroutineScope if (success) Result.success() else Result.failure(workDataOf("error" to (lastError ?: appContext.getString(R.string.error_max_attempts_reached, MAX_ATTEMPTS))))

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "Worker para $sceneId ($taskType): Job explicitamente cancelado durante doWork()", e)
            lastError = appContext.getString(R.string.error_task_cancelled_explicitly)
            updateSceneStateGeneral(sceneId, taskType, attempt = 0, success = false, errorMessage = appContext.getString(R.string.message_cancelled_by_user))
            updateNotificationProgress(appContext.getString(R.string.notification_task_cancelled, taskType, sceneId.take(8)), true)
            return@coroutineScope Result.failure(workDataOf("error" to lastError))
        } catch (e: Exception) {
            Log.e(TAG, "Worker para $sceneId ($taskType): Erro INESPERADO durante doWork()", e)
            lastError = appContext.getString(R.string.error_unexpected_worker_error_details, e.message ?: e.javaClass.simpleName)
            updateSceneStateGeneral(sceneId, taskType, attempt = 0, success = false, errorMessage = lastError)
            updateNotificationProgress(appContext.getString(R.string.notification_unexpected_error_scene, sceneId.take(8)), true)
            return@coroutineScope Result.failure(workDataOf("error" to (lastError)))
        } finally {
            Log.d(TAG, "Worker para $sceneId ($taskType): doWork() Finalizado.")
        }
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
                            promptVideo = inputData.getString(KEY_VIDEO_GEN_PROMPT) ?: item.promptVideo, // Salva o prompt de vídeo usado
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
        prompt: String, // Este agora é o videoGenPromptFromInput
        imgPatch: String
    ): InternalTaskResult {
        Log.d(TAG, "Worker para $sceneId (Vídeo): Chamado callGerarVideo para cena '${cena ?: sceneId}' baseada em: '${imgPatch.take(30)}', prompt: '${prompt.take(30)}...'")
        val resultado = GeminiVideoApi.gerarVideo(
            cena = cena ?: sceneId,
            prompt = prompt, // Passa o prompt correto para a API de vídeo
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