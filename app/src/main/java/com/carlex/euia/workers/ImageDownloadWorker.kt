// File: euia/workers/ImageDownloadWorker.kt
package com.carlex.euia.worker

import android.content.Context
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException // <<< CORREÇÃO 1: Importação adicionada

private const val TAG = "ImageDownloadWorker"

class ImageDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStore = VideoProjectDataStoreManager(applicationContext)

    companion object {
        const val KEY_SCENE_ID = "key_scene_id_for_image_download"
        const val KEY_IMAGE_URL = "key_image_url_for_download"
        const val KEY_PROJECT_DIR_NAME = "key_project_dir_name"
        const val KEY_ERROR_MESSAGE = "key_image_download_error"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sceneId = inputData.getString(KEY_SCENE_ID) ?: return@withContext Result.failure()
        val imageUrl = inputData.getString(KEY_IMAGE_URL) ?: return@withContext Result.failure()
        val projectDirName = inputData.getString(KEY_PROJECT_DIR_NAME) ?: return@withContext Result.failure()

        Log.i(TAG, "Iniciando download da IMAGEM para cena $sceneId a partir de: $imageUrl")

        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(imageUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                // <<< CORREÇÃO 2: Alterado de response.code() para response.code >>>
                throw Exception("Falha no download da imagem: Código ${response.code}")
            }

            val imageDir = BitmapUtils.getAppSpecificDirectory(applicationContext, projectDirName, "pixabay_images")
            val extension = imageUrl.substringAfterLast('.', "jpg")
            val outputFile = File(imageDir, "pixabay_${sceneId}_${System.currentTimeMillis()}.$extension")

            response.body?.byteStream()?.use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && coroutineContext.isActive) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    if(!coroutineContext.isActive) {
                        outputFile.delete()
                        throw CancellationException("Download da imagem cancelado.")
                    }
                }
            }

            updateSceneWithDownloadedImage(sceneId, outputFile.absolutePath)
            Log.i(TAG, "Sucesso! Cena $sceneId atualizada com a imagem: ${outputFile.absolutePath}")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Erro no ImageDownloadWorker para cena $sceneId", e)
            updateSceneWithError(sceneId, e.message)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to e.message))
        }
    }

    private suspend fun updateSceneWithDownloadedImage(sceneId: String, imagePath: String) {
        val currentList = videoProjectDataStore.sceneLinkDataList.first()
        val newList = currentList.map {
            if (it.id == sceneId) {
                it.copy(
                    isGeneratingVideo = false,
                    generationErrorMessage = null,
                    imagemGeradaPath = imagePath,
                    pathThumb = imagePath
                )
            } else {
                it
            }
        }
        videoProjectDataStore.setSceneLinkDataList(newList)
    }

    private suspend fun updateSceneWithError(sceneId: String, error: String?) {
        val currentList = videoProjectDataStore.sceneLinkDataList.first()
        val newList = currentList.map {
            if (it.id == sceneId) {
                it.copy(isGeneratingVideo = false, generationErrorMessage = error)
            } else {
                it
            }
        }
        videoProjectDataStore.setSceneLinkDataList(newList)
    }
}