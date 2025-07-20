// File: euia/workers/VideoProcessingWorker.kt
package com.carlex.euia.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.carlex.euia.viewmodel.VideoProjectViewModel
import android.graphics.Bitmap
import android.graphics.Canvas
import com.carlex.euia.utils.ProjectPersistenceManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import com.google.ai.client.generativeai.type.ServerException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.api.GeminiImageApiImg3
import com.carlex.euia.api.GeminiImageApi
import com.carlex.euia.api.FirebaseImagenApi
import com.carlex.euia.api.GeminiVideoApi
import com.carlex.euia.api.ProvadorVirtual
import com.carlex.euia.data.AudioDataStoreManager
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
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import com.carlex.euia.utils.*
import retrofit2.HttpException
import java.security.MessageDigest


// Define TAG for logging
private const val TAG = "VideoProcessingWorker"

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
    private val audioDataStoreManager = AudioDataStoreManager(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val workManager = WorkManager.getInstance(applicationContext)

    private val tryOnMutex = Mutex()
    private val MAX_ATTEMPTS = 3
    private val RETRY_DELAY_MILLIS = 2000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    // <<< MUDANÇA: Função para gerar um ID de notificação único e estável por cena >>>
    private fun getNotificationId(sceneId: String): Int {
        // Gera um ID numérico a partir do ID da cena para evitar colisões
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

        // <<< MUDANÇA: Usando a constante centralizada de NotificationUtils >>>
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
        Log.d(TAG, "Notificação (ID: $notificationId) de VideoProcessing atualizada: $contentText")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: "unknown_scene"
        // <<< MUDANÇA: Usando o ID dinâmico da notificação >>>
        val notificationId = getNotificationId(sceneId)
        val notification = createNotification(appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(notificationId, notification)
    }


    override suspend fun doWork(): Result = coroutineScope {
        OverlayManager.showOverlay(appContext, "🎬", -1)

        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_scene_id_missing)))
        val taskType = inputData.getString(KEY_TASK_TYPE)
            ?: return@coroutineScope Result.failure(workDataOf("error" to appContext.getString(R.string.error_task_type_missing)))
        
        // <<< MUDANÇA: Usando o ID dinâmico da notificação >>>
        val notificationId = getNotificationId(sceneId)

        val initialNotificationContent = when (taskType) {
            TASK_TYPE_GENERATE_IMAGE -> appContext.getString(R.string.video_processing_notification_generating_image, sceneId.take(8))
            TASK_TYPE_CHANGE_CLOTHES -> appContext.getString(R.string.video_processing_notification_changing_clothes, sceneId.take(8))
            TASK_TYPE_GENERATE_VIDEO -> appContext.getString(R.string.video_processing_notification_generating_video_for_scene, sceneId.take(8))
            else -> appContext.getString(R.string.video_processing_notification_starting)
        }
        updateNotificationProgress(notificationId, initialNotificationContent)
        
        val authViewModel = AuthViewModel(applicationContext as Application)
        var lastError: String? = null

        try {
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
                    
                    updateSceneStateGeneral(sceneId, taskType, 0, true, taskResult.filePath, taskResult.thumbPath, null)
                    enqueueScenePreviewWorker(sceneId)

                    break
                } else { 
                    if (taskResult.errorMessage?.contains("429") == true){
                        lastError = "Fila de trabalho no servidor cheia. Tente novamente mais tarde. (Erro 429)"
                        break
                    }
                    lastError = taskResult.errorMessage ?: appContext.getString(R.string.error_unknown_task_failure)
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
            
            ProjectPersistenceManager.saveProjectState(appContext)
            
            if (!success) {
                throw Exception(lastError ?: appContext.getString(R.string.error_max_attempts_reached, MAX_ATTEMPTS))
            }
            
            updateNotificationProgress(notificationId, appContext.getString(R.string.notification_task_completed_success, taskType, sceneId.take(8)), true)
            return@coroutineScope Result.success()

        } catch (e: Exception) {
            val finalErrorMessage = when (e) {
                is CancellationException -> e.message ?: appContext.getString(R.string.error_task_cancelled_explicitly)
                else -> e.message ?: appContext.getString(R.string.error_unexpected_worker_error)
            }
            
            updateSceneStateGeneral(sceneId, taskType, 0, false, null, null, finalErrorMessage)
            updateNotificationProgress(notificationId, appContext.getString(R.string.notification_task_failed, taskType, sceneId.take(8), finalErrorMessage.take(50)), true, isError = true)
            return@coroutineScope Result.failure(workDataOf("error" to finalErrorMessage))
        } finally {
             OverlayManager.hideOverlay(appContext)
        }
    }
    
    private fun enqueueScenePreviewWorker(sceneId: String) {
        Log.d(TAG, "Enfileirando ScenePreviewWorker para a cena $sceneId")
        val workRequest = OneTimeWorkRequestBuilder<ScenePreviewWorker>()
            .setInputData(workDataOf(ScenePreviewWorker.KEY_SCENE_ID to sceneId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("${WorkerTags.SCENE_PREVIEW_WORK}_$sceneId")
            .addTag(WorkerTags.SCENE_PREVIEW_WORK)
            .build()

        workManager.enqueueUniqueWork(
            "SCENE_PREVIEW_QUEUE",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }
    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File? {
        val baseAppDir = context.getExternalFilesDir(null) ?: context.filesDir
        val projectPath = File(baseAppDir, projectDirName.takeIf { it.isNotBlank() } ?: "DefaultProject")
        val finalDir = File(projectPath, subDir)
        if (!finalDir.exists() && !finalDir.mkdirs()) {
            return null
        }
        return finalDir
    }
    
    public fun generateScenePreviewHash(scene: SceneLinkData, prefs: VideoPreferencesDataStoreManager): String {
        val stringToHash = buildString {
            append(scene.cena)
            append(scene.imagemGeradaPath)
            append(scene.tempoInicio)
            append(scene.tempoFim)
            append(runBlocking { prefs.enableZoomPan.first() })
            append(runBlocking { prefs.videoHdMotion.first() })
            append(runBlocking { prefs.videoLargura.first() })
            append(runBlocking { prefs.videoAltura.first() })
            append(runBlocking { prefs.videoFps.first() })
        }
             
        val bytes = stringToHash.toByteArray()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }


    private suspend fun createAudioSnippetForPreview(mainAudioPath: String, scene: SceneLinkData): String? = withContext(Dispatchers.IO) {
        val tempDir = File(appContext.cacheDir, "audio_snippets_worker")
        tempDir.mkdirs()
        val outputFile = File.createTempFile("snippet_${scene.id}_", ".mp3", tempDir)

        val startTime = scene.tempoInicio ?: 0.0
        val endTime = scene.tempoFim ?: 0.0
        val duration = (endTime - startTime).coerceAtLeast(0.1)

        val command = "-y -i \"$mainAudioPath\" -ss $startTime -t $duration -c:a libmp3lame -q:a 4 \"${outputFile.absolutePath}\""
        
        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputFile.absolutePath
        } else {
            Log.e(TAG, "Falha ao cortar áudio para prévia (worker): ${session.allLogsAsString}")
            null
        }
    }

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
        videoPreviewPath: String? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val currentList: List<SceneLinkData> = projectDataStoreManager.sceneLinkDataList.first()
            val newList = currentList.map { item ->
                if (item.id == sceneId) {
                    when (taskType) {
                        TASK_TYPE_GENERATE_IMAGE, TASK_TYPE_CHANGE_CLOTHES -> item.copy(
                            isGenerating = (taskType == TASK_TYPE_GENERATE_IMAGE && success == null),
                            isChangingClothes = (taskType == TASK_TYPE_CHANGE_CLOTHES && success == null),
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true) generatedAssetPath else item.pathThumb,
                            videoPreviewPath = if (success == true) videoPreviewPath else item.videoPreviewPath,
                            generationErrorMessage = if (success == false) errorMessage else null,
                            isGeneratingVideo = false
                        )
                        TASK_TYPE_GENERATE_VIDEO -> item.copy(
                            isGeneratingVideo = success == null,
                            generationAttempt = if (success == null) attempt else 0,
                            imagemGeradaPath = if (success == true) generatedAssetPath else item.imagemGeradaPath,
                            pathThumb = if (success == true) videoPreviewPath else item.pathThumb,
                            videoPreviewPath = if (success == true && videoPreviewPath != null) videoPreviewPath else item.videoPreviewPath,
                            promptVideo = inputData.getString(KEY_VIDEO_GEN_PROMPT) ?: item.promptVideo,
                            generationErrorMessage = if (success == false) errorMessage else null,
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
                projectDataStoreManager.setSceneLinkDataList(newList)
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
        val resultado = GeminiImageApi.gerarImagem(
            cena = cena.orEmpty(),
            prompt = "$prompt",
            context = applicationContext,
            imagensParaUpload = listaImagensReferencia
        )
        
        return if (resultado.isSuccess) {
            val caminhoImagemGerada = resultado.getOrNull() ?: ""
            if (caminhoImagemGerada.isNotBlank()) {
                InternalTaskResult(filePath = caminhoImagemGerada)
            } else {
                InternalTaskResult(errorMessage = appContext.getString(R.string.error_gemini_api_empty_image_path))
            }
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_api_failure_worker))
        }
    }


    private suspend fun callGerarVideoWorker(
        sceneId: String,
        cena: String?,
        prompt: String, 
        imgPatch: String
    ): InternalTaskResult {
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
                InternalTaskResult(filePath = primeiroVideoPath, thumbPath = null)
            } else {
                InternalTaskResult(errorMessage = appContext.getString(R.string.error_gemini_video_api_empty_paths))
            }
        } else {
            InternalTaskResult(errorMessage = resultado.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_video_api_failure))
        }
    }


    private suspend fun callTrocaRoupaWorker(imagemCenaPath: String, imagemReferenciaPath: String, cena: String, context: Context): InternalTaskResult {
        return tryOnMutex.withLock {
            val resultadoPath = ProvadorVirtual.generate(fotoPath = imagemCenaPath, figurinoPath = imagemReferenciaPath, context = context)
            if (resultadoPath.isNullOrEmpty()) {
                InternalTaskResult(errorMessage = appContext.getString(R.string.error_clothes_change_api_failed))
            } else {
                InternalTaskResult(filePath = resultadoPath)
            }
        }
    }
}



