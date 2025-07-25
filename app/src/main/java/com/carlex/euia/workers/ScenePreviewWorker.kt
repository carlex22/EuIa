// File: euia/workers/ScenePreviewWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlin.math.min
import com.carlex.euia.R
import com.carlex.euia.data.*
import com.carlex.euia.utils.NotificationUtils
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.VideoEditorComTransicoes
import com.carlex.euia.utils.WorkerTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "ScenePreviewWorker"

class ScenePreviewWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val projectDataStore = VideoProjectDataStoreManager(applicationContext)
    private val audioDataStore = AudioDataStoreManager(applicationContext)
    private val videoPreferencesDataStore = VideoPreferencesDataStoreManager(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_SCENE_ID = "key_scene_id_for_preview"
        const val KEY_ERROR_MESSAGE = "key_preview_error_message"
        // <<< CORREÇÃO AQUI: Renomeando a constante para o nome correto >>>
        const val KEY_OVERRIDE_IMAGE_PATH = "key_override_image_path"
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(applicationContext.getString(R.string.notification_content_preview_starting))
        return ForegroundInfo(System.currentTimeMillis().toInt(), notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "ID da cena não fornecido."))

        var audioSnippetPath: String? = null

        try {
            // A lógica de atualização da fila foi removida. O ViewModel cuida disso.
            Log.i(TAG, "Iniciando geração de prévia para a cena $sceneId.")
            
            val sceneOri = projectDataStore.sceneLinkDataList.first().find { it.id == sceneId }
                ?: throw IllegalStateException("Cena $sceneId não encontrada no DataStore.")
                
            val scene = sceneOri.copy(
                tempoFim = if (videoPreferencesDataStore.enableSceneTransitions.first()) {
                    sceneOri.tempoFim!! + 0.5
                } else {
                    sceneOri.tempoFim!!
                }
            )      
                
                
            val generatedAssetPath = scene.imagemGeradaPath
            if (generatedAssetPath.isNullOrBlank()) {
                throw IllegalStateException("A cena $sceneId não possui um asset gerado para criar uma prévia.")
            }
            
            val projectDirName = videoPreferencesDataStore.videoProjectDir.first()
            val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
            val previewsDir = File(baseProjectDir, "scene_previews")
            previewsDir.mkdirs()

            val enableZoomPan = videoPreferencesDataStore.enableZoomPan.first()
            val videoHdMotion = videoPreferencesDataStore.videoHdMotion.first()
            val videoWidth = videoPreferencesDataStore.videoLargura.first() ?: 720
            val videoHeight = videoPreferencesDataStore.videoAltura.first() ?: 1280
            val videoFps = videoPreferencesDataStore.videoFps.first()

            val currentHash = generateScenePreviewHash(scene, enableZoomPan, videoHdMotion, videoWidth, videoHeight, videoFps)
            val sceneIdentifier = scene.cena ?: sceneId.take(4)
            val expectedPreviewFile = File(previewsDir, "scene_${sceneIdentifier}_$currentHash.mp4")

            
            var isCacheValid = false
            if (expectedPreviewFile.exists() && expectedPreviewFile.isFile && expectedPreviewFile.length() > 100) {
                val ffprobeSession = FFprobeKit.execute("-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"${expectedPreviewFile.absolutePath}\"")
                if (ReturnCode.isSuccess(ffprobeSession.returnCode) && ffprobeSession.output.trim().toDoubleOrNull() != null) {
                    isCacheValid = true
                } else {
                    Log.w(TAG, "Cache de prévia para a cena $sceneId encontrado, mas parece corrompido. Deletando.")
                    expectedPreviewFile.delete()
                }
            }

            if (isCacheValid) {
                Log.i(TAG, "Usando prévia válida do cache para a cena $sceneId.")
                updateSceneData(sceneId, expectedPreviewFile.absolutePath, null)
                return@withContext Result.success()
            }

            val mainAudioPath = audioDataStore.audioPath.first()
            if (mainAudioPath.isBlank()) {
                throw IllegalStateException("Áudio principal do projeto não encontrado.")
            }

            updateNotification(applicationContext.getString(R.string.notification_content_preview_preparing_audio, sceneId.take(4)))
            audioSnippetPath = createAudioSnippetForPreview(mainAudioPath, scene)
                ?: throw IOException("Falha ao criar o trecho de áudio para a prévia.")
                
                Log.i(TAG, "scene ${scene.toString()}")
        
  

            updateNotification(applicationContext.getString(R.string.notification_content_preview_rendering, sceneId.take(4)))
            val success = VideoEditorComTransicoes.gerarPreviaDeCenaUnica(
                context = applicationContext,
                scene = scene,
                audioSnippetPath = audioSnippetPath,
                outputPreviewPath = expectedPreviewFile.absolutePath,
                videoPreferences = videoPreferencesDataStore,
                logCallback = { Log.v("$TAG-FFmpeg", it) }
            )

            if (success) {
                cleanupOldPreviews(previewsDir, sceneIdentifier, expectedPreviewFile.name)
                updateSceneData(sceneId, expectedPreviewFile.absolutePath, null)
                updateNotification(applicationContext.getString(R.string.notification_content_preview_success, sceneId.take(4)), isFinished = true)
                return@withContext Result.success()
            } else {
                throw IOException("O Editor de Vídeo falhou ao gerar a prévia.")
            }
        } catch (e: Exception) {
            val errorMessage = "Falha na prévia da cena $sceneId: ${e.message}"
            Log.e(TAG, errorMessage, e)
            updateNotification(errorMessage.take(40), isFinished = true, isError = true)
            updateSceneData(sceneId, null, e.message)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        } finally {
            //audioSnippetPath?.let { File(it).delete() }
        }
    }
    
    private fun generateScenePreviewHash(
        scene: SceneLinkData,
        enableZoomPan: Boolean,
        videoHdMotion: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int
    ): String {
        val arquivo = File(scene.imagemGeradaPath)  
        val tamanhoBytes: Long = if (arquivo.exists()) arquivo.length() else 0L


        val stringToHash = buildString {
            append(scene.cena)
            append("$tamanhoBytes")
            append(scene.tempoInicio)
            append(scene.tempoFim)
            append(enableZoomPan)
            append(videoHdMotion)
            append(videoWidth)
            append(videoHeight)
            append(videoFps)
        }
             
        val bytes = stringToHash.toByteArray()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
    
    private fun cleanupOldPreviews(previewsDir: File, sceneIdentifier: String, currentFileNameToKeep: String) {
        if (!previewsDir.exists()) return
        
        previewsDir.listFiles { _, name ->
            name.startsWith("scene_${sceneIdentifier}_") && name != currentFileNameToKeep
        }?.forEach { oldFile ->
            if (oldFile.delete()) {
                Log.i(TAG, "Prévia de cache antiga removida: ${oldFile.name}")
            } else {
                Log.w(TAG, "Falha ao remover prévia de cache antiga: ${oldFile.name}")
            }
        }
    }
    
    
    private suspend fun getClipDuration(filePath: String): Double? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Obtendo duração para: $filePath")
        val session = FFprobeKit.execute("-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$filePath\"")
        if (ReturnCode.isSuccess(session.returnCode)) {
            val durationString = session.output.trim()
            val duration = durationString.toDoubleOrNull()
            if (duration == null) {
                Log.e(TAG, "Falha ao converter a duração '$durationString' para Double.")
            }
            return@withContext duration
        } else {
            Log.e(TAG, "ffprobe falhou ao obter a duração para $filePath. Logs: ${session.allLogsAsString}")
            return@withContext null
        }
    }


    private suspend fun createAudioSnippetForPreview(mainAudioPath: String, scene: SceneLinkData): String? {
        
       
        
        val projectDirName = videoPreferencesDataStore.videoProjectDir.first()
        val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
        val previewsDir = File(baseProjectDir, "audio")
        previewsDir.mkdirs()
        
        
        
        
        val outputFile = File(previewsDir.absolutePath, "scene_${scene.id}_.mp3")

        val startTime = scene.tempoInicio ?: 0.0
        val endTime = scene.tempoFim ?: 0.0
        val duration = ((endTime  - startTime).coerceAtLeast(0.1))

        val command = "-y -i \"$mainAudioPath\" -ss $startTime -t $duration -c:a libmp3lame -q:a 4 \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            outputFile.absolutePath
        } else {
            Log.e(TAG, "Falha ao cortar áudio para prévia: ${session.allLogsAsString}")
            null
        }
    }
    
    private suspend fun updateSceneData(sceneId: String, previewPath: String?, error: String?) {
        val currentList = projectDataStore.sceneLinkDataList.first()
        val newList = currentList.map {
            if (it.id == sceneId) {
                it.copy(
                    videoPreviewPath = previewPath, 
                    generationErrorMessage = error, 
                    previewQueuePosition = -1 // Limpa a posição ao finalizar
                )
            } else {
                it
            }
        }
        projectDataStore.setSceneLinkDataList(newList)
    }
    
    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        return NotificationCompat.Builder(applicationContext, NotificationUtils.CHANNEL_ID_VIDEO_PROCESSING)
            .setContentTitle(applicationContext.getString(R.string.notification_title_preview_generation))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!isFinished && !isError)
            .setAutoCancel(isFinished || isError)
            .build()
    }

    private fun updateNotification(message: String, isFinished: Boolean = false, isError: Boolean = false) {
        notificationManager.notify(System.currentTimeMillis().toInt(), createNotification(message, isFinished, isError))
    }
}