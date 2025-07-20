// File: euia/workers/ImageProcessingWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationChannel
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
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

// Define TAG for logging
private const val TAG_WORKER = "ImageProcWorker"

// <<< CORREÇÃO 1: Definir constantes de notificação específicas para ESTE worker >>>
private const val NOTIFICATION_ID_IMAGE = 4
private const val NOTIFICATION_CHANNEL_ID_IMAGE = "ImageProcessingChannelEUIA"

private const val DEFAULT_IMAGE_WIDTH = 720
private const val DEFAULT_IMAGE_HEIGHT = 1280


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
        // Mantém a tag original para consistência com o que já existe no seu código
        const val TAG_IMAGE_PROCESSING_WORK = "image_processing_work"
    }

    // <<< CORREÇÃO 2: Funções de notificação autossuficientes dentro do worker >>>

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.notification_channel_name_image)
            val descriptionText = appContext.getString(R.string.notification_channel_description_image)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_IMAGE, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG_WORKER, "Canal de notificação '$NOTIFICATION_CHANNEL_ID_IMAGE' criado/verificado.")
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

        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID_IMAGE)
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
            builder.setProgress(0, 0, true).setOngoing(true) // Indeterminado
        }
        return builder.build()
    }

    private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false, isError: Boolean = false) {
        val notification = createNotification(contentText, isFinished = makeDismissible, isError = isError)
        notificationManager.notify(NOTIFICATION_ID_IMAGE, notification)
        Log.d(TAG_WORKER, "Notificação de Processamento de Mídia atualizada: $contentText")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val uriStrings = inputData.getStringArray(KEY_MEDIA_URIS)
        val mediaCount = uriStrings?.size ?: 0
        var contentText = appContext.getString(R.string.notification_content_media_starting_multiple, mediaCount)
        if (mediaCount == 0) contentText = appContext.getString(R.string.notification_content_media_none_to_process)
        else if (mediaCount == 1) contentText = appContext.getString(R.string.notification_content_media_starting_single)

        // <<< CORREÇÃO 3: Chamar a criação do canal ANTES de criar a notificação >>>
        createNotificationChannel()

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
        val larguraPreferida = videoPreferencesDataStoreManager.videoLargura.first()
        val alturaPreferida = videoPreferencesDataStoreManager.videoAltura.first()
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
                
                OverlayManager.showOverlay(appContext, "📝", successfulCount*5)

                val currentProjectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
                val mimeType = getMimeType(uri)

                val processedMedia: ImagemReferencia? = when {
                    mimeType?.startsWith("image/") == true -> processAndCreateReferenceImage(uri, currentProjectDirName, larguraPreferida, alturaPreferida)
                    mimeType?.startsWith("video/") == true -> processVideoAndCreateReferenceImage(uri, currentProjectDirName, larguraPreferida, alturaPreferida)
                    else -> {
                        Log.w(TAG_WORKER, "Unsupported media type for URI: $uri (MIME: $mimeType). Skipping.")
                        null
                    }
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
            dataStoreManager.setIsProcessingImages(false) // Garante que a flag seja resetada
            
            ProjectPersistenceManager.saveProjectState(appContext)
            
            Log.d(TAG_WORKER, "doWork finished.")
        }
    }

    // --- O restante das funções auxiliares (processAndCreateReferenceImage, etc.) permanece o mesmo ---
    // ... (copie o resto do seu arquivo ImageProcessingWorker.kt a partir daqui)
    private suspend fun processAndCreateReferenceImage(
        uri: Uri,
        projectDirName: String,
        larguraPreferida: Int?,
        alturaPreferida: Int?
    ): ImagemReferencia? = withContext(Dispatchers.IO) {
        Log.d(TAG_WORKER, "--- Starting processAndCreateReferenceImage for URI: $uri in project: $projectDirName ---")
        try {
            val savedPath = try {
                Log.d(TAG_WORKER, "Calling saveImageToStorage for URI: $uri with projectDir: $projectDirName, LxA: ${larguraPreferida}x${alturaPreferida}")
                saveImageToStorage(applicationContext, uri, projectDirName, larguraPreferida, alturaPreferida)
            } catch (e: Exception) {
                Log.e(TAG_WORKER, "Exception during saveImageToStorage for URI: $uri", e)
                null
            }

            if (!coroutineContext.isActive || savedPath == null) {
                if (savedPath == null) Log.w(TAG_WORKER, "saveImageToStorage returned null for URI: $uri. Skipping description.")
                else Log.w(TAG_WORKER, "Processamento de imagem cancelado após salvar para URI: $uri.")
                return@withContext null
            }

            Log.d(TAG_WORKER, "Image saved successfully for URI: $uri. Path: ${savedPath.take(100)}...")
            val promptDescricao = CreateaDescriptionImagem().prompt
            Log.d(TAG_WORKER, "Calling analisarImagemComGemini for path: ${savedPath.take(100)}...")
           /* val analysisResult = try {
                analisarImagemComGemini(promptDescricao, savedPath)
            } catch (e: Exception) {
                Log.e(TAG_WORKER, "Error calling analisarImagemComGemini for path: ${savedPath.take(100)}...", e)
                GeminiImageAnalysisResult("", false)
            }*/

            if (!coroutineContext.isActive) {
                Log.w(TAG_WORKER, "Processamento de imagem cancelado após analisar para URI: $uri.")
                return@withContext null
            }

            //Log.d(TAG_WORKER, "analisarImagemComGemini returned for path: ${savedPath.take(100)}. Desc: ${analysisResult.description.take(50)}, People: ${analysisResult.containsPeople}")

            val imagem = ImagemReferencia(
                path = savedPath,
                descricao = "",
                pathVideo = null,
                videoDurationSeconds = null,
                containsPeople = true
            )
            Log.d(TAG_WORKER, "--- Finished processAndCreateReferenceImage for URI: $uri with Success ---")
            return@withContext imagem

        } catch (e: Exception) {
            Log.e(TAG_WORKER, "--- General error during processAndCreateReferenceImage for URI: $uri ---", e)
            return@withContext null
        }
    }

    private suspend fun processVideoAndCreateReferenceImage(
        uri: Uri,
        projectDirName: String,
        larguraPreferida: Int?,
        alturaPreferida: Int?
    ): ImagemReferencia? = withContext(Dispatchers.IO) {
        Log.d(TAG_WORKER, "--- Starting processVideoAndCreateReferenceImage for URI: $uri in project: $projectDirName ---")
        var retriever: MediaMetadataRetriever? = null
        var videoDurationSeconds: Long = 0
        var thumbnailBitmap: Bitmap? = null // Frame extraído original
        var savedCleanThumbnailPath: String? = null
        var savedIconThumbnailPath: String? = null
        var savedVideoPath: String? = null

        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(appContext, uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDurationSeconds = (durationStr?.toLongOrNull() ?: 0) / 1000L
            Log.d(TAG_WORKER, "Video duration: $videoDurationSeconds seconds")

            val frameTime = if (videoDurationSeconds > 1L) 1_000_000L else videoDurationSeconds * 500_000L
            thumbnailBitmap = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (thumbnailBitmap == null) {
                Log.e(TAG_WORKER, "Failed to extract thumbnail from video: $uri")
                return@withContext null
            }
            Log.d(TAG_WORKER, "Thumbnail extracted. Size: ${thumbnailBitmap.width}x${thumbnailBitmap.height}")


            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())


            // --- LÓGICA DE SALVAR AS DUAS VERSÕES DA THUMBNAIL ---
            val originalFileNameBase = getFileNameFromUri(appContext, uri)!!
            
            val origName = "${originalFileNameBase?.substringBeforeLast(".")}_${timestamp}"
            
            val directory = getAppSpecificMediaDir(appContext, projectDirName, "ref_images") 
            if (!directory.exists()) {
                directory.mkdirs()
            }

            
            // 1. Salvar a imagem limpa (img_...)
            savedCleanThumbnailPath = saveSpecificVideoThumbnailToStorage(
                context = appContext,
                bitmapToSave = thumbnailBitmap, // Bitmap original extraído
                projectDirName = projectDirName,
                baseNameForFile = "thumb_${origName}", // Prefixo img_
                larguraPreferida = larguraPreferida,
                alturaPreferida = alturaPreferida,
                drawPlayIcon = false // Não desenha o ícone
            )

            if (!coroutineContext.isActive || savedCleanThumbnailPath == null) {
                Log.w(TAG_WORKER, "Video processing cancelled or clean thumbnail save failed for URI: $uri.")
                return@withContext null
            }
            Log.d(TAG_WORKER, "Clean video thumbnail (img_...) saved: $savedCleanThumbnailPath")


            savedVideoPath = saveVideoFileToStorage(appContext, uri, projectDirName, origName!!)

            if (!coroutineContext.isActive || savedVideoPath == null) {
                Log.w(TAG_WORKER, "Video processing cancelled or video file save failed for URI: $uri.")
                File(savedCleanThumbnailPath!!).delete()
                File(savedIconThumbnailPath).delete()
                return@withContext null
            }

            Log.d(TAG_WORKER, "Icon video thumbnail (thumb_...) saved: $savedIconThumbnailPath")
            Log.d(TAG_WORKER, "Original video file saved: $savedVideoPath")

            val promptDescricao = CreateaDescriptionImagem().prompt
           /* val analysisResult = try {
                analisarImagemComGemini(promptDescricao, savedCleanThumbnailPath) // Analisa a imagem limpa
            } catch (e: Exception) {
                Log.e(TAG_WORKER, "Error calling analisarImagemComGemini for video thumbnail: ${e.message}", e)
                GeminiImageAnalysisResult("", false)
            }*/

            if (!coroutineContext.isActive) {
                Log.w(TAG_WORKER, "Video processing cancelled after describing thumbnail for URI: $uri.")
                return@withContext null
            }
            
           // val finalDescription = "Vídeo (Duração: ${videoDurationSeconds}s). Análise do frame: ${analysisResult.description}".trim()

           // Log.d(TAG_WORKER, "analisarImagemComGemini returned for video thumbnail. Desc: ${analysisResult.description.take(50)}, People: ${analysisResult.containsPeople}")
            val imagem = ImagemReferencia(
                path = savedVideoPath, // O 'path' principal é a thumb com ícone
                descricao = "",
                pathThumb = savedCleanThumbnailPath,
                videoDurationSeconds = videoDurationSeconds,
                containsPeople = true
            )
            Log.d(TAG_WORKER, "--- Finished processVideoAndCreateReferenceImage for URI: $uri with Success ---")
            return@withContext imagem

        } catch (e: Exception) {
            Log.e(TAG_WORKER, "--- General error during processVideoAndCreateReferenceImage for URI: $uri ---", e)
            return@withContext null
        } finally {
            retriever?.release()
            BitmapUtils.safeRecycle(thumbnailBitmap, "processVideoAndCreateReferenceImage_originalThumbnail")
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

    private suspend fun analisarImagemComGemini(
        prompt: String,
        imagePath: String
    ): GeminiImageAnalysisResult = withContext(Dispatchers.IO) {
        Log.d(TAG_WORKER, "analisarImagemComGemini called for image path: ${imagePath.take(100)}...")
        if (imagePath.isBlank()) {
            Log.e(TAG_WORKER, "analisarImagemComGemini: Image path is blank.")
            return@withContext GeminiImageAnalysisResult("", false)
        }
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            Log.e(TAG_WORKER, "analisarImagemComGemini: Image file not found at: ${imagePath.take(100)}...")
            return@withContext GeminiImageAnalysisResult("", false)
        }

        if (!coroutineContext.isActive) return@withContext GeminiImageAnalysisResult("", false)

        val imagemUnica: List<String> = listOf(imagePath)
        Log.d(TAG_WORKER, "analisarImagemComGemini: Prompt for Gemini: ${prompt.take(50)}...")
        val respostaResult: kotlin.Result<String> = try {
            GeminiTextAndVisionProRestApi.perguntarAoGemini(prompt, imagemUnica, null, null, "gemini-2.5-flash")
        } catch (e: Exception) {
            Log.e(TAG_WORKER, "analisarImagemComGemini: Exception during Gemini API call for ${imagePath.take(100)}...: ${e.message}", e)
            return@withContext GeminiImageAnalysisResult("", false)
        }

        if (!coroutineContext.isActive) return@withContext GeminiImageAnalysisResult("", false)

        Log.d(TAG_WORKER, "analisarImagemComGemini: Gemini API call finished for ${imagePath.take(100)}.... Result isSuccess: ${respostaResult.isSuccess}")

        if (respostaResult.isSuccess) {
            val respostaBruta = respostaResult.getOrNull() ?: ""
            Log.d(TAG_WORKER, "analisarImagemComGemini: Raw Gemini response (first 100 chars): '${respostaBruta.take(100)}...'")
            if (respostaBruta.isBlank()) {
                Log.w(TAG_WORKER, "analisarImagemComGemini: Gemini response is blank for ${imagePath.take(100)}.")
                return@withContext GeminiImageAnalysisResult("", false)
            }

            var description = ""
            var containsPeople = false
            var respostaAjustada = ""
            try {
                respostaAjustada = ajustarResposta1(respostaBruta)
                if (respostaAjustada.isBlank()) {
                    Log.w(TAG_WORKER, "analisarImagemComGemini: Adjusted response is blank for ${imagePath.take(100)}...")
                    return@withContext GeminiImageAnalysisResult("", false)
                }
                Log.d(TAG_WORKER, "analisarImagemComGemini: Adjusted response (first 100 chars): '${respostaAjustada.take(100)}...'")

                val jsonObject = JSONObject(respostaAjustada)
                description = jsonObject.optString("DescriptionImagem", "").trim()
                containsPeople = jsonObject.optBoolean("ContemPessoas", false)

                Log.d(TAG_WORKER, "analisarImagemComGemini: Extracted description: '$description', ContainsPeople: $containsPeople for ${imagePath.take(100)}...")

            } catch (e: JSONException) {
                Log.e(TAG_WORKER, "analisarImagemComGemini: Error parsing JSON from Gemini response (adjusted: '${respostaAjustada.take(100)}...'). Raw response (first 100 chars): '${respostaBruta.take(100)}...'", e)
            } catch (e: Exception) {
                Log.e(TAG_WORKER, "analisarImagemComGemini: Unexpected error processing description for image ${imagePath.take(100)}...", e)
            }
            return@withContext GeminiImageAnalysisResult(description, containsPeople)
        } else {
            Log.e(TAG_WORKER, "analisarImagemComGemini: Gemini API call returned failure for ${imagePath.take(100)}...: ${respostaResult.exceptionOrNull()?.message}")
            return@withContext GeminiImageAnalysisResult("", false)
        }
    }


    private fun saveImageToStorage(
        context: Context,
        uri: Uri,
        projectDirName: String,
        larguraPreferida: Int?,
        alturaPreferida: Int?
    ): String? {
        Log.d(TAG_WORKER, "saveImageToStorage: Attempting to save image from URI: $uri into project: $projectDirName with LxA: ${larguraPreferida}x${alturaPreferida}")
        val contentResolver: ContentResolver = context.contentResolver
        var inputStream: InputStream? = null
        var originalBitmap: Bitmap? = null
        var editedBitmap: Bitmap? = null

        try {
            inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG_WORKER, "saveImageToStorage: Failed to open input stream for image URI: $uri")
                return null
            }
            originalBitmap = BitmapFactory.decodeStream(inputStream)

            if (originalBitmap == null) {
                Log.e(TAG_WORKER, "saveImageToStorage: Failed to decode bitmap from input stream for URI: $uri")
                return null
            }
            Log.d(TAG_WORKER, "saveImageToStorage: Bitmap decoded. Size: ${originalBitmap.width}x${originalBitmap.height}")

            val larguraFinal = larguraPreferida ?: DEFAULT_IMAGE_WIDTH
            val alturaFinal = alturaPreferida ?: DEFAULT_IMAGE_HEIGHT
            Log.d(TAG_WORKER, "saveImageToStorage: Target dimensions for resize: ${larguraFinal}x${alturaFinal}")

            editedBitmap = originalBitmap//redimensionarComFundoTransparente(originalBitmap, larguraFinal, alturaFinal)

            // Não reciclar originalBitmap aqui se ele for o mesmo que editedBitmap (caso não haja redimensionamento)
            if (originalBitmap != editedBitmap && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
                Log.d(TAG_WORKER, "saveImageToStorage: Original bitmap recycled after resize.")
            }
            originalBitmap = null


            if (editedBitmap == null) {
                Log.e(TAG_WORKER, "saveImageToStorage: Edited bitmap is null after redimensioning for URI: $uri")
                return null
            }
            Log.d(TAG_WORKER, "saveImageToStorage: Edited bitmap created. Size: ${editedBitmap.width}x${editedBitmap.height}")

            val finalBitmapToSave: Bitmap = editedBitmap

            val baseDir: File?
            val finalSaveDirName: String

            if (projectDirName.isNotBlank()) {
                val externalAppFilesDir = context.getExternalFilesDir(null)
                if (externalAppFilesDir != null) {
                    baseDir = File(externalAppFilesDir, projectDirName)
                    finalSaveDirName = "ref_images"
                    Log.i(TAG_WORKER, "saveImageToStorage: Usando diretório externo: ${baseDir.absolutePath}/$finalSaveDirName")
                } else {
                    Log.w(TAG_WORKER, "saveImageToStorage: Armazenamento externo não disponível. Usando fallback interno: 'ref_images_default'.")
                    baseDir = context.filesDir
                    finalSaveDirName = "ref_images_default"
                }
            } else {
                Log.w(TAG_WORKER, "saveImageToStorage: Nome do projeto não definido. Usando fallback interno: 'ref_images_default'.")
                baseDir = context.filesDir
                finalSaveDirName = "ref_images_default"
            }

            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())

            // --- LÓGICA DE SALVAR AS DUAS VERSÕES DA THUMBNAIL ---
            val originalFileNameBase = getFileNameFromUri(appContext, uri)!!
            
            val origName = "${originalFileNameBase?.substringBeforeLast(".")}_${timestamp}"
            
            val directory = getAppSpecificMediaDir(appContext, projectDirName, "ref_images") 
            if (!directory.exists()) {
                directory.mkdirs()
            }


            val file = File(directory, "$origName.webp")

            try {
                FileOutputStream(file).use { outputStream ->
                    val success = finalBitmapToSave.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 85, outputStream)
                    if (!success) {
                         Log.w(TAG_WORKER, "saveImageToStorage: Compressão falhou para: ${file.absolutePath}")
                    }
                }
                Log.d(TAG_WORKER, "saveImageToStorage: Imagem salva em: ${file.absolutePath}")
                return file.absolutePath
            } catch (e: IOException) {
                 Log.e(TAG_WORKER, "saveImageToStorage: IOException ao salvar: ${file.absolutePath}", e)
                 return null
            } finally {
                if (editedBitmap != null && !editedBitmap.isRecycled) { // Verifica se editedBitmap é diferente do original
                    editedBitmap.recycle()
                    Log.d(TAG_WORKER, "saveImageToStorage: Final bitmap (edited) recycled.")
                }
            }

        } catch (e: IOException) {
            Log.e(TAG_WORKER, "saveImageToStorage: IOException (outer) processando $uri", e)
            return null
        } catch (e: SecurityException) {
            Log.e(TAG_WORKER, "saveImageToStorage: SecurityException com $uri", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG_WORKER, "saveImageToStorage: Erro inesperado com $uri", e)
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: IOException) {}
            // Garante que originalBitmap seja reciclado se não foi atribuído a editedBitmap e ainda existe
            if (originalBitmap != null && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
                Log.d(TAG_WORKER, "saveImageToStorage: Original bitmap (fallback) recycled in finally.")
            }
        }
    }


    /**
     * Salva um bitmap (que se espera ser um frame de vídeo) no armazenamento.
     * Redimensiona o bitmap, opcionalmente desenha um ícone de play, e salva.
     * @param context Contexto da aplicação.
     * @param bitmapToSave O bitmap original a ser processado e salvo.
     * @param projectDirName Nome do diretório do projeto.
     * @param baseNameForFile Nome base para o arquivo (sem {}stamp, uuid ou extensão).
     * @param larguraPreferida Largura preferida para redimensionamento.
     * @param alturaPreferida Altura preferida para redimensionamento.
     * @param drawPlayIcon Boolean indicando se o ícone de play deve ser desenhado.
     * @return Caminho do arquivo salvo ou null em caso de falha.
     */
    private fun saveSpecificVideoThumbnailToStorage(
        context: Context,
        bitmapToSave: Bitmap, // Este é o frame original extraído
        projectDirName: String,
        baseNameForFile: String, // Ex: "img_nomeoriginal_timestamp" ou "thumb_nomeoriginal_timestamp"
        larguraPreferida: Int?,
        alturaPreferida: Int?,
        drawPlayIcon: Boolean
    ): String? {
        Log.d(TAG_WORKER, "saveSpecificVideoThumbnailToStorage: BaseName='$baseNameForFile', DrawIcon=$drawPlayIcon")

        val larguraFinal = larguraPreferida ?: DEFAULT_IMAGE_WIDTH
        val alturaFinal = alturaPreferida ?: DEFAULT_IMAGE_HEIGHT
        var processedBitmap: Bitmap? = null // Bitmap que será realmente salvo
        var tempResizedBitmap: Bitmap? = null // Para o redimensionamento inicial

        try {
            // 1. Redimensionar com fundo transparente (preserva o conteúdo original proporcionalmente)
            tempResizedBitmap = bitmapToSave//redimensionarComFundoTransparente(bitmapToSave, larguraFinal, alturaFinal)

            if (tempResizedBitmap == null) {
                Log.e(TAG_WORKER, "Falha ao redimensionar o thumbnail base para '$baseNameForFile'.")
                return null
            }

            // 2. Se for para desenhar o ícone, cria uma cópia mutável do bitmap redimensionado
            if (drawPlayIcon) {
                processedBitmap = tempResizedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                BitmapUtils.safeRecycle(tempResizedBitmap, "saveSpecificVideoThumbnailToStorage_tempResized_after_copy_for_icon") // Recicla o redimensionado original
                val canvas = Canvas(processedBitmap)
                val playIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_play_overlay_video)
                if (playIconDrawable != null) {
                    val iconSize = min(processedBitmap.width, processedBitmap.height) / 4
                    val iconLeft = (processedBitmap.width - iconSize) / 2
                    val iconTop = (processedBitmap.height - iconSize) / 2
                    playIconDrawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                    playIconDrawable.draw(canvas)
                    Log.d(TAG_WORKER, "Ícone de Play desenhado em '$baseNameForFile'.")
                } else {
                    Log.w(TAG_WORKER, "Drawable do ícone de Play não encontrado para '$baseNameForFile'.")
                }
            } else {
                // Se não for desenhar o ícone, o bitmap redimensionado é o que será salvo
                processedBitmap = tempResizedBitmap
            }

            if (processedBitmap == null) {
                Log.e(TAG_WORKER, "Bitmap processado final é nulo para '$baseNameForFile'.")
                return null
            }

            // 3. Salvar o processedBitmap
            val saveDir = getAppSpecificMediaDir(context, projectDirName, "ref_images") // Salva na mesma pasta
            val finalFileName = "$baseNameForFile.webp" // Sempre salva como webp para consistência
            val file = File(saveDir, finalFileName)

            FileOutputStream(file).use { outputStream ->
                val success = processedBitmap.compress(Bitmap.CompressFormat.WEBP, 85, outputStream)
                if (!success) {
                    Log.w(TAG_WORKER, "Compressão WEBP falhou para: ${file.absolutePath}")
                    return null // Falha ao comprimir
                }
            }
            Log.d(TAG_WORKER, "Thumbnail específico salvo: ${file.absolutePath}")
            return file.absolutePath

        } catch (e: Exception) {
            Log.e(TAG_WORKER, "Erro ao salvar thumbnail específico '$baseNameForFile': ${e.message}", e)
            return null
        } finally {
            // Recicla o processedBitmap, que é ou o tempResizedBitmap ou sua cópia.
            // tempResizedBitmap já foi reciclado se uma cópia foi feita para o ícone.
            BitmapUtils.safeRecycle(processedBitmap, "saveSpecificVideoThumbnailToStorage_processedBitmap_final")
            // Não recicla bitmapToSave aqui, pois ele é o frame original e pode ser usado para a outra versão da thumb.
        }
    }


    private fun saveVideoFileToStorage(
        context: Context,
        videoUri: Uri,
        projectDirName: String,
        baseNameForVideo: String // Nome base sem timestamp/extensão, ex: "thumb_nomeoriginal_timestamp"
    ): String? {
        Log.d(TAG_WORKER, "saveVideoFileToStorage: Attempting to save video from URI: $videoUri, baseName: $baseNameForVideo")

        val contentResolver: ContentResolver = context.contentResolver
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = contentResolver.openInputStream(videoUri)
            if (inputStream == null) {
                Log.e(TAG_WORKER, "saveVideoFileToStorage: Failed to open input stream for video URI: $videoUri")
                return null
            }
            
            
            

            val directory = getAppSpecificMediaDir(context, projectDirName, "ref_images") // Salva na mesma pasta de referência
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val originalExtension = "mp4"
            // O nome do arquivo de vídeo usará o baseName (que já inclui o prefixo thumb_ e o timestamp)
            val videoFileName = "$baseNameForVideo.$originalExtension"
            val videoFile = File(directory, videoFileName)

            outputStream = FileOutputStream(videoFile)
            inputStream.copyTo(outputStream)

            Log.d(TAG_WORKER, "saveVideoFileToStorage: Video salvo em: ${videoFile.absolutePath}")
            return videoFile.absolutePath

        } catch (e: IOException) {
            Log.e(TAG_WORKER, "saveVideoFileToStorage: IOException ao salvar vídeo: ${e.message}", e)
            return null
        } catch (e: SecurityException) {
            Log.e(TAG_WORKER, "saveVideoFileToStorage: SecurityException ao salvar vídeo: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG_WORKER, "saveVideoFileToStorage: Erro inesperado ao salvar vídeo: ${e.message}", e)
            return null
        } finally {
            try { inputStream?.close() } catch (_: IOException) {}
            try { outputStream?.close() } catch (_: IOException) {}
        }
    }

    private fun getAppSpecificMediaDir(context: Context, projectDirName: String, subDir: String): File {
        val baseDir: File?
        val finalSaveDirName: String

        if (projectDirName.isNotBlank()) {
            val externalAppFilesDir = context.getExternalFilesDir(null)
            if (externalAppFilesDir != null) {
                baseDir = File(externalAppFilesDir, projectDirName)
                finalSaveDirName = subDir
            } else {
                baseDir = context.filesDir
                finalSaveDirName = "${subDir}_default"
            }
        } else {
            baseDir = context.filesDir
            finalSaveDirName = "${subDir}_default_project"
        }
        return File(baseDir, finalSaveDirName)
    }


    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (columnIndex != -1) {
                                fileName = cursor.getString(columnIndex)
                            } else {
                                Log.w(TAG_WORKER, "getFileNameFromUri: Column '${OpenableColumns.DISPLAY_NAME}' not found in cursor for URI: $uri")
                            }
                        } else {
                            Log.w(TAG_WORKER, "getFileNameFromUri: Cursor returned no rows for URI: $uri")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG_WORKER, "getFileNameFromUri: Error getting file name from URI: $uri", e)
                fileName = null
            }
        }
        if (fileName == null) {
            fileName = uri.path
            val cut = fileName?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                fileName = fileName?.substring(cut + 1)
            }
        }
        Log.d(TAG_WORKER, "getFileNameFromUri: File name extracted: '$fileName' for URI: $uri")
        return fileName
    }

    private fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
        } else {
            MimeTypeMap.getFileExtensionFromUrl(uri.path)
        }
    }


    private fun ajustarResposta1(resposta: String): String {
        Log.d(TAG_WORKER, "ajustarResposta1: Resposta original (primeiros 100): '${resposta.take(100)}...'")
        var respostaLimpa = resposta.trim()

        if (respostaLimpa.startsWith("```json", ignoreCase = true)) {
            respostaLimpa = respostaLimpa.removePrefix("```json").trimStart()
        } else if (respostaLimpa.startsWith("```")) {
            respostaLimpa = respostaLimpa.removePrefix("```").trimStart()
        }
        if (respostaLimpa.endsWith("```")) {
            respostaLimpa = respostaLimpa.removeSuffix("```").trimEnd()
        }

        val primeiroColchete = respostaLimpa.indexOf('[')
        val primeiroChave = respostaLimpa.indexOf('{')
        var inicioJson = -1
        var tipoJson: Char? = null

        if (primeiroColchete != -1 && (primeiroChave == -1 || primeiroColchete < primeiroChave)) {
            inicioJson = primeiroColchete
            tipoJson = '['
        } else if (primeiroChave != -1) {
            inicioJson = primeiroChave
            tipoJson = '{'
        }

        if (inicioJson != -1 && tipoJson != null) {
            var fimJson = -1
            var balance = 0
            val charAbertura = tipoJson
            val charFechamento = if (tipoJson == '[') ']' else '}'
            var dentroDeString = false
            for (i in inicioJson until respostaLimpa.length) {
                val charAtual = respostaLimpa[i]
                if (charAtual == '"') {
                    if (i == 0 || respostaLimpa[i - 1] != '\\') {
                        dentroDeString = !dentroDeString
                    }
                }
                if (!dentroDeString) {
                    if (charAtual == charAbertura) {
                        balance++
                    } else if (charAtual == charFechamento) {
                        balance--
                        if (balance == 0) {
                            fimJson = i
                            break
                        }
                    }
                }
            }
            if (fimJson != -1) {
                respostaLimpa = respostaLimpa.substring(inicioJson, fimJson + 1)
                Log.d(TAG_WORKER, "ajustarResposta1: JSON substring extracted (balanço). Result (primeiros 100): '${respostaLimpa.take(100)}...'")
            } else {
                Log.w(TAG_WORKER, "ajustarResposta1: Não foi possível encontrar o delimitador de fechamento correspondente.")
            }
        } else {
            Log.w(TAG_WORKER, "ajustarResposta1: Não foi possível encontrar o início do JSON ([ ou {) após limpeza de ```.")
        }
        Log.d(TAG_WORKER, "ajustarResposta1: Resposta após ajuste (primeiros 100): '${respostaLimpa.take(100)}...'")
        return respostaLimpa
    }

    private fun redimensionarComFundoTransparente(bitmap: Bitmap, larguraFinalDesejada: Int, alturaFinalDesejada: Int): Bitmap? {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        val finalWidth = larguraFinalDesejada.coerceAtLeast(1)
        val finalHeight = alturaFinalDesejada.coerceAtLeast(1)

        if (originalWidth <= 0 || originalHeight <= 0) {
            Log.w(TAG_WORKER, "Bitmap original com dimensões inválidas: ${originalWidth}x${originalHeight}. Não é possível redimensionar.")
            return null
        }
        Log.d(TAG_WORKER, "Redimensionando bitmap de ${originalWidth}x${originalHeight} para ${finalWidth}x${finalHeight}")

        var novoBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            novoBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(novoBitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val scale: Float
            val xOffset: Float
            val yOffset: Float

            if (originalWidth.toFloat() / originalHeight > finalWidth.toFloat() / finalHeight) {
                scale = finalWidth.toFloat() / originalWidth
                xOffset = 0f
                yOffset = (finalHeight - originalHeight * scale) / 2f
            } else {
                scale = finalHeight.toFloat() / originalHeight
                xOffset = (finalWidth - originalWidth * scale) / 2f
                yOffset = 0f
            }

            val newScaledWidth = (originalWidth * scale).toInt().coerceAtLeast(1)
            val newScaledHeight = (originalHeight * scale).toInt().coerceAtLeast(1)

            scaledBitmap = Bitmap.createScaledBitmap(bitmap, newScaledWidth, newScaledHeight, true)
            canvas.drawBitmap(scaledBitmap, xOffset, yOffset, null)

            return novoBitmap

        } catch (e: Exception) {
            Log.e(TAG_WORKER, "Erro ao redimensionar bitmap: ${e.message}", e)
            novoBitmap?.recycle()
            return null
        } finally {
            // Apenas recicla o scaledBitmap se ele foi criado e é diferente do bitmap original
            if (scaledBitmap != null && scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }
}