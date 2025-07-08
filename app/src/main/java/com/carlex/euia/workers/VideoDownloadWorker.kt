
// File: euia/workers/VideoDownloadWorker.kt
package com.carlex.euia.worker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

class VideoDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "VideoDownloadWorker"
    private val videoProjectDataStore = VideoProjectDataStoreManager(appContext)

    companion object {
        const val KEY_SCENE_ID = "key_scene_id_for_download"
        const val KEY_VIDEO_URL = "key_video_url_for_download"
        const val KEY_PROJECT_DIR_NAME = "key_project_dir_name"
        const val KEY_ERROR_MESSAGE = "key_download_error_message"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: return@withContext Result.failure()
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return@withContext Result.failure()
        val projectDirName = inputData.getString(KEY_PROJECT_DIR_NAME) ?: return@withContext Result.failure()

        Log.i(TAG, "Iniciando download para cena $sceneId a partir de: $videoUrl")

        try {
            // Baixar o vídeo
            val downloadedVideoFile = downloadFile(videoUrl, projectDirName, "downloaded_video_${sceneId}")
            if (downloadedVideoFile == null || !isActive) {
                throw Exception("Falha no download ou tarefa cancelada.")
            }

            // Gerar a thumbnail
            val generatedThumbPath = generateThumbnail(downloadedVideoFile.absolutePath, projectDirName, "thumb_from_${downloadedVideoFile.nameWithoutExtension}")
            if (generatedThumbPath == null || !isActive) {
                downloadedVideoFile.delete()
                throw Exception("Falha na geração da thumbnail ou tarefa cancelada.")
            }
            
            // Atualizar o DataStore
            updateSceneData(sceneId, downloadedVideoFile.absolutePath, generatedThumbPath)

            Log.i(TAG, "Sucesso! Cena $sceneId atualizada com vídeo: ${downloadedVideoFile.absolutePath} e thumb: $generatedThumbPath")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Erro no VideoDownloadWorker para cena $sceneId", e)
            updateSceneData(sceneId, null, null, e.message)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to e.message))
        }
    }

    private fun downloadFile(url: String, projectDirName: String, baseName: String): File? {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return null

        val videoDir = BitmapUtils.getAppSpecificDirectory(applicationContext, projectDirName, "downloaded_videos")
        val outputFile = File(videoDir, "$baseName.mp4")

        response.body?.byteStream()?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1 && coroutineContext.isActive) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                if(!coroutineContext.isActive) {
                    outputFile.delete()
                    return null
                }
            }
        }
        return outputFile
    }

    private suspend fun generateThumbnail(videoPath: String, projectDirName: String, thumbBaseName: String): String? {
        var retriever: MediaMetadataRetriever? = null
        var thumbnailBitmap: Bitmap? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            thumbnailBitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            
            if (thumbnailBitmap != null) {
                BitmapUtils.saveBitmapToFile(
                    applicationContext, thumbnailBitmap, projectDirName,
                    "ref_images", // Salva na mesma pasta das outras referências
                    thumbBaseName, Bitmap.CompressFormat.WEBP, 80
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gerar thumbnail para $videoPath", e)
            null
        } finally {
            retriever?.release()
            BitmapUtils.safeRecycle(thumbnailBitmap, "VideoDownloadWorker_Thumbnail")
        }
    }

    private suspend fun updateSceneData(sceneId: String, videoPath: String?, thumbPath: String?, error: String? = null) {
        val currentList = videoProjectDataStore.sceneLinkDataList.first()
        val newList = currentList.map {
            if (it.id == sceneId) {
                it.copy(
                    isGeneratingVideo = false,
                    generationErrorMessage = error,
                    imagemGeradaPath = videoPath ?: it.imagemGeradaPath, // Mantém o antigo se o novo for nulo
                    pathThumb = thumbPath ?: it.pathThumb
                )
            } else {
                it
            }
        }
        videoProjectDataStore.setSceneLinkDataList(newList)
    }
}