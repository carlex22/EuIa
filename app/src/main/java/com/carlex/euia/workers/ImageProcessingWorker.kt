// File: euia/workers/ImageProcessingWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import com.carlex.euia.utils.ProjectPersistenceManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.prompts.CreateaDescriptionImagem
import com.carlex.euia.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import com.carlex.euia.utils.*

private const val TAG_WORKER = "ImageProcWorker"
private const val NOTIFICATION_ID_IMAGE = 4
private const val NOTIFICATION_CHANNEL_ID_IMAGE = "ImageProcessingChannelEUIA"

private data class GeminiImageAnalysisResult(
val description: String,
val containsPeople: Boolean
)

class ImageProcessingWorker(
val appContext: Context,
workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {


private val dataStoreManager = VideoDataStoreManager(applicationContext)
private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(applicationContext)
private val kotlinJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

companion object {
    const val KEY_MEDIA_URIS = "media_uris"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val TAG_IMAGE_PROCESSING_WORK = "image_processing_work"
}

private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
    val intent = Intent(appContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent = PendingIntent.getActivity(
        appContext, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_IMAGE_PROCESSING)
        .setContentTitle(appContext.getString(R.string.notification_title_media_processing))
        .setTicker(appContext.getString(R.string.notification_title_media_processing))
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

private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false, isError: Boolean = false) {
    val notification = createNotification(contentText, isFinished = makeDismissible, isError = isError)
    notificationManager.notify(NOTIFICATION_ID_IMAGE, notification)
}

override suspend fun getForegroundInfo(): ForegroundInfo {
    val uriStrings = inputData.getStringArray(KEY_MEDIA_URIS)
    val mediaCount = uriStrings?.size ?: 0
    var contentText = appContext.getString(R.string.notification_content_media_starting_multiple, mediaCount)
    if (mediaCount == 0) contentText = appContext.getString(R.string.notification_content_media_none_to_process)
    else if (mediaCount == 1) contentText = appContext.getString(R.string.notification_content_media_starting_single)
    val notification = createNotification(contentText)
    return ForegroundInfo(NOTIFICATION_ID_IMAGE, notification)
}

@OptIn(ExperimentalSerializationApi::class)
override suspend fun doWork(): Result = coroutineScope {
    Log.d(TAG_WORKER, "doWork started.")
    OverlayManager.showOverlay(appContext, "📝", -1)

    val uriStrings = inputData.getStringArray(KEY_MEDIA_URIS)
    if (uriStrings.isNullOrEmpty()) {
        val errorMsg = appContext.getString(R.string.error_no_media_to_process)
        updateNotificationProgress(errorMsg, true, isError = true)
        return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
    }

    Log.d(TAG_WORKER, "Received ${uriStrings.size} URIs for processing.")
    val uris = uriStrings.map { Uri.parse(it) }
    val processedImages = mutableListOf<ImagemReferencia>()
    var failedCount = 0
    var successfulCount = 0

    try {
        uris.forEachIndexed { index, uri ->
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_processing_cancelled_by_user))
            val progressText = appContext.getString(R.string.notification_content_media_processing_progress, index + 1, uris.size)
            updateNotificationProgress(progressText)
            Log.d(TAG_WORKER, progressText)
            OverlayManager.showOverlay(appContext, "📝", successfulCount * 5)
            val currentProjectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            val mimeType = getMimeType(uri)
            val processedMedia: ImagemReferencia? = when {
                mimeType?.startsWith("image/") == true -> processAndCreateReferenceImage(uri, currentProjectDirName)
                mimeType?.startsWith("video/") == true -> processVideoAndCreateReferenceImage(uri, currentProjectDirName)
                else -> null
            }
            if (processedMedia != null) {
                processedImages.add(processedMedia)
                successfulCount++
            } else {
                failedCount++
            }
        }

        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_saving_media_cancelled))
        updateNotificationProgress(appContext.getString(R.string.notification_content_media_saving))
        val currentListJson = dataStoreManager.imagensReferenciaJson.first()
        val currentList: List<ImagemReferencia> = try {
            if (currentListJson.isNotBlank() && currentListJson != "[]") kotlinJson.decodeFromString(ListSerializer(ImagemReferencia.serializer()), currentListJson) else emptyList()
        } catch (e: Exception) { emptyList() }

        val listWithNewItems = currentList + processedImages
        val updatedListJson = try {
            kotlinJson.encodeToString(ListSerializer(ImagemReferencia.serializer()), listWithNewItems)
        } catch (e: Exception) {
            val errorMsg = appContext.getString(R.string.error_serializing_final_media_list)
            updateNotificationProgress(errorMsg, true, isError = true)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }
        dataStoreManager.setImagensReferenciaJson(updatedListJson)

        val finalMessage: String
        val result: Result
        if (failedCount == uris.size) {
            finalMessage = appContext.getString(R.string.error_all_media_failed_processing, uris.size)
            result = Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalMessage))
            updateNotificationProgress(finalMessage, true, isError = true)
        } else if (failedCount > 0) {
            finalMessage = appContext.getString(R.string.status_media_processed_partial_failure, successfulCount, uris.size, failedCount)
            result = Result.success(workDataOf("warning" to finalMessage))
            updateNotificationProgress(finalMessage, true)
        } else {
            finalMessage = appContext.getString(R.string.status_media_processed_successfully, successfulCount)
            result = Result.success()
            updateNotificationProgress(finalMessage, true)
        }
        return@coroutineScope result

    } catch (e: kotlinx.coroutines.CancellationException) {
        val cancelMsg = e.message ?: appContext.getString(R.string.error_processing_cancelled)
        updateNotificationProgress(cancelMsg, true, isError = true)
        return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to cancelMsg))
    } catch (e: Exception) {
        val errorMsg = appContext.getString(R.string.error_unexpected_media_processing, e.message)
        updateNotificationProgress(errorMsg, true, isError = true)
        return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
    } finally {
        OverlayManager.hideOverlay(appContext)
        dataStoreManager.setIsProcessingImages(false)
        ProjectPersistenceManager.saveProjectState(appContext)
        Log.d(TAG_WORKER, "doWork finished.")
    }
}

private suspend fun processAndCreateReferenceImage(
    uri: Uri,
    projectDirName: String
): ImagemReferencia? = withContext(Dispatchers.IO) {
    try {
        val savedPath = saveImageToStorage(applicationContext, uri, projectDirName)
        if (!coroutineContext.isActive || savedPath == null) {
            return@withContext null
        }
        return@withContext ImagemReferencia(
            path = savedPath,
            descricao = "",
            pathVideo = null,
            videoDurationSeconds = null,
            containsPeople = true
        )
    } catch (e: Exception) {
        Log.e(TAG_WORKER, "Erro ao processar e criar referência para imagem: $uri", e)
        return@withContext null
    }
}

private suspend fun processVideoAndCreateReferenceImage(
    uri: Uri,
    projectDirName: String
): ImagemReferencia? = withContext(Dispatchers.IO) {
    Log.d(TAG_WORKER, "--- Iniciando processamento de VÍDEO para URI: $uri ---")
    var retriever: MediaMetadataRetriever? = null
    var thumbnailBitmap: Bitmap? = null
    var savedVideoPath: String? = null
    var savedThumbPath: String? = null

    try {
        retriever = MediaMetadataRetriever()
        retriever.setDataSource(appContext, uri)

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val videoDurationSeconds = (durationStr?.toLongOrNull() ?: 0) / 1000L

        val frameTime = if (videoDurationSeconds > 1L) 1_000_000L else 0L
        thumbnailBitmap = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

        if (thumbnailBitmap == null) {
            Log.e(TAG_WORKER, "Falha ao extrair thumbnail do vídeo: $uri")
            return@withContext null
        }

        
        val originalFileNameBase = BitmapUtils.getFileNameFromUri(appContext, uri) ?: "video"
        val baseNameForFiles = originalFileNameBase.substringBeforeLast(".")

        savedVideoPath = saveVideoFileToStorage(appContext, uri, projectDirName, "${baseNameForFiles}")
        

        savedThumbPath = BitmapUtils.saveBitmapToFile(
            context = appContext,
            bitmap = thumbnailBitmap,
            projectDirName = projectDirName,
            subDir = "thumbs",
            baseName = "${baseNameForFiles}",
            format = Bitmap.CompressFormat.WEBP,
            quality = 85
        )
        if (savedThumbPath == null || !isActive) {
            throw Exception("Falha ao salvar a thumbnail do vídeo ou tarefa cancelada.")
        }

        return@withContext ImagemReferencia(
            path = savedVideoPath!!,
            descricao = "",
            pathThumb = savedThumbPath,
            pathVideo = savedVideoPath,
            videoDurationSeconds = videoDurationSeconds,
            containsPeople = false // A análise de pessoas foi removida por simplicidade
        )

    } catch (e: Exception) {
        Log.e(TAG_WORKER, "Erro geral ao processar vídeo da URI: $uri", e)
        // Limpa arquivos parciais em caso de erro
        savedVideoPath?.let { File(it).delete() }
        savedThumbPath?.let { File(it).delete() }
        return@withContext null
    } finally {
        retriever?.release()
        thumbnailBitmap?.recycle()
    }
}

private fun getMimeType(uri: Uri): String? {
    return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        appContext.contentResolver.getType(uri)
    } else {
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase(Locale.getDefault()))
    }
}

private fun saveImageToStorage(
    context: Context,
    uri: Uri,
    projectDirName: String
): String? {
    Log.d(TAG_WORKER, "Salvando e convertendo para WEBP a partir da URI: $uri")
    var inputStream: InputStream? = null
    var originalBitmap: Bitmap? = null

    try {
        inputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o stream da URI")
        originalBitmap = BitmapFactory.decodeStream(inputStream) ?: throw IOException("Falha ao decodificar o bitmap")
        
        Log.d(TAG_WORKER, "Bitmap decodificado. Tamanho original: ${originalBitmap.width}x${originalBitmap.height}")

        val saveDir = BitmapUtils.getAppSpecificDirectory(context, projectDirName, "ref_images") 
            ?: throw IOException("Não foi possível criar o diretório de salvamento")

       val originalFileNameBase = BitmapUtils.getFileNameFromUri(context, uri) ?: "image"
       
       
        var finalFileName = "${originalFileNameBase.substringBeforeLast(".")}.webp"
        
        
        
        val file = File(saveDir, finalFileName)

        FileOutputStream(file).use { outputStream ->
            val success = originalBitmap.compress(Bitmap.CompressFormat.WEBP, 85, outputStream)
            if (!success) {
                file.delete()
                throw IOException("Falha na compressão do bitmap para WEBP")
            }
        }
        Log.i(TAG_WORKER, "Imagem salva e convertida para WEBP: ${file.absolutePath}")
        return file.absolutePath

    } catch (e: Exception) {
        Log.e(TAG_WORKER, "Erro ao salvar imagem da URI $uri", e)
        return null
    } finally {
        try { inputStream?.close() } catch (_: IOException) {}
        originalBitmap?.recycle()
    }
}

private fun saveVideoFileToStorage(context: Context, videoUri: Uri, projectDirName: String, baseName: String): String? {
    val extension = getFileExtensionFromUri(context, videoUri) ?: "mp4"
    val videoDir = BitmapUtils.getAppSpecificDirectory(context, projectDirName, "ref_videos") 
        ?: return null
    val outputFile = File(videoDir, "$baseName.$extension")

    try {
        context.contentResolver.openInputStream(videoUri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: return null
        return outputFile.absolutePath
    } catch (e: Exception) {
        Log.e(TAG_WORKER, "Erro ao salvar arquivo de vídeo", e)
        outputFile.delete()
        return null
    }
}


private fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: uri.lastPathSegment?.substringAfterLast('.', "").takeIf { it?.isNotEmpty() == true }
    } catch (e: Exception) { null }
}


}