// File: euia/workers/PixabayVideoSearchWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.api.PixabayApiClient
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.NotificationUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import android.app.NotificationManager // <<< CORREÇÃO AQUI
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.app.NotificationCompat
import com.carlex.euia.api.PixabayImage
import com.carlex.euia.api.PixabayVideo
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.utils.BitmapUtils
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.VideoEditorComTransicoes
import com.carlex.euia.utils.WorkerTags
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "PixabaySearchWorker"

// Classe interna para unificar os resultados para a IA
private data class UnifiedAsset(
    val id: String, // Usaremos a URL da thumbnail/preview como ID
    val type: String, // "Video" ou "Image"
    val tags: String,
    val downloadUrl: String // URL para o download do arquivo final
)

class PixabayVideoSearchWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStore = VideoProjectDataStoreManager(applicationContext)
    private val videoPreferencesDataStore = VideoPreferencesDataStoreManager(applicationContext)
    private val audioDataStore = AudioDataStoreManager(applicationContext) // Adicionado
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_SCENE_ID = "key_scene_id_for_pixabay_search"
        const val KEY_ERROR_MESSAGE = "key_pixabay_search_error"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(applicationContext.getString(R.string.notification_content_pixabay_starting))
        return ForegroundInfo(System.currentTimeMillis().toInt(), notification)
    }

    override suspend fun doWork(): Result = coroutineScope {
        val sceneId = inputData.getString(KEY_SCENE_ID)
            ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "ID da cena não fornecido."))

        try {
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_getting_prompt, sceneId.take(4)))
            val scene = videoProjectDataStore.sceneLinkDataList.first().find { it.id == sceneId }
            val searchQuery = scene?.promptVideo
            if (searchQuery.isNullOrBlank()) {
                throw IllegalStateException("Prompt de vídeo (TAG_SEARCH_WEB) está vazio para a cena $sceneId.")
            }

            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_searching, searchQuery))

            // <<< INÍCIO DA LÓGICA DE BUSCA PARALELA >>>
            val videosDeferred = async { PixabayApiClient.searchVideos(searchQuery) }
            val imagesDeferred = async { PixabayApiClient.searchImages(searchQuery) }

            val videoResult = videosDeferred.await()
            val imageResult = imagesDeferred.await()
            // <<< FIM DA LÓGICA DE BUSCA PARALELA >>>

            val unifiedAssetList = mutableListOf<UnifiedAsset>()
            videoResult.getOrNull()?.forEach { video ->
                unifiedAssetList.add(UnifiedAsset(
                    id = video.videoFiles.small.thumbnail,
                    type = "Video",
                    tags = video.tags,
                    downloadUrl = video.videoFiles.small.url
                ))
            }
            imageResult.getOrNull()?.forEach { image ->
                unifiedAssetList.add(UnifiedAsset(
                    id = image.previewURL,
                    type = "Image",
                    tags = image.tags,
                    downloadUrl = image.largeImageURL
                ))
            }

            if (unifiedAssetList.isEmpty()) {
                throw IllegalStateException("Nenhum vídeo ou imagem encontrado na Pixabay para '$searchQuery'.")
            }

            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_ai_selection))
            val optionsText = unifiedAssetList.joinToString("\n") {
                "ID: ${it.id}\nTipo: ${it.type}\nTags: ${it.tags}\n"
            }
            val geminiPrompt = """
                Analisando a intenção criativa de um prompt, escolha o melhor asset de estoque (vídeo ou imagem) para ilustrá-lo.
                Prompt Original: "$searchQuery"

                Opções Disponíveis (analise o Tipo e as Tags para relevância):
                $optionsText

                Sua resposta DEVE ser apenas o ID (a URL da thumbnail/preview) da melhor opção, e nada mais.
            """.trimIndent()

            val geminiResult = GeminiTextAndVisionProRestApi.perguntarAoGemini(geminiPrompt, emptyList(), null, null, "gemini-2.5-flash")
            if (geminiResult.isFailure) {
                throw geminiResult.exceptionOrNull() ?: IllegalStateException("A IA falhou em escolher um asset.")
            }
            val chosenAssetId = geminiResult.getOrThrow().trim()
            val chosenAsset = unifiedAssetList.find { it.id == chosenAssetId }
                ?: throw IllegalStateException("A IA retornou um ID inválido: $chosenAssetId")

            Log.i(TAG, "IA escolheu o asset: Tipo=${chosenAsset.type}, URL=${chosenAsset.downloadUrl}")

            // Lógica de download e processamento movida para cá
            val projectDirName = videoPreferencesDataStore.videoProjectDir.first()
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_queuing_download))

            val downloadedFile = downloadFile(chosenAsset.downloadUrl, projectDirName, "asset_${sceneId}", chosenAsset.type)
            if (downloadedFile == null || !isActive) {
                throw Exception("Falha no download do asset ou tarefa cancelada.")
            }

            var finalAssetPath = downloadedFile.absolutePath
            var finalThumbPath: String? = null
            if (chosenAsset.type == "Video") {
                finalThumbPath = generateThumbnail(downloadedFile.absolutePath, projectDirName, "thumb_from_${downloadedFile.nameWithoutExtension}")
            } else {
                finalThumbPath = finalAssetPath // Para imagens, a thumb é a própria imagem.
            }

            if (finalThumbPath == null || !isActive) {
                downloadedFile.delete()
                throw Exception("Falha na geração da thumbnail ou tarefa cancelada.")
            }

            // Geração da pré-visualização (NOVA LÓGICA)
            updateNotification(applicationContext.getString(R.string.notification_content_preview_generating, sceneId.take(4)))
            val previewPath = generatePreviewForScene(sceneId, finalAssetPath)
            if (previewPath == null) {
                Log.w(TAG, "A geração da pré-visualização falhou para a cena $sceneId, mas o download foi bem-sucedido. Continuando sem prévia.")
            }

            updateSceneWithDownloadedAsset(sceneId, finalAssetPath, finalThumbPath, previewPath)

            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_success), isFinished = true)
            return@coroutineScope Result.success()

        } catch (e: Exception) {
            val errorMessage = if (e is CancellationException) "Busca cancelada." else e.message ?: "Erro desconhecido."
            Log.e(TAG, "Falha no PixabayVideoSearchWorker para cena $sceneId: $errorMessage", e)
            updateNotification(applicationContext.getString(R.string.notification_content_pixabay_failed, errorMessage.take(40)), isFinished = true, isError = true)
            updateSceneWithError(sceneId, errorMessage)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        }
    }

    private suspend fun downloadFile(url: String, projectDirName: String, baseName: String, type: String): File? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return@withContext null

        val subDir = if (type == "Video") "pixabay_videos" else "pixabay_images"
        val extension = url.substringAfterLast('.', if (type == "Video") "mp4" else "jpg")
        val outputDir = BitmapUtils.getAppSpecificDirectory(applicationContext, projectDirName, subDir)
        val outputFile = File(outputDir, "$baseName.${extension}")

        response.body?.byteStream()?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1 && coroutineContext.isActive) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                if (!coroutineContext.isActive) {
                    outputFile.delete()
                    return@withContext null
                }
            }
        }
        return@withContext outputFile
    }

    private suspend fun generateThumbnail(videoPath: String, projectDirName: String, thumbBaseName: String): String? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        var thumbnailBitmap: Bitmap? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            thumbnailBitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (thumbnailBitmap != null) {
                BitmapUtils.saveBitmapToFile(
                    applicationContext, thumbnailBitmap, projectDirName,
                    "ref_images",
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
            BitmapUtils.safeRecycle(thumbnailBitmap, "${TAG}_Thumbnail")
        }
    }

    // --- FUNÇÕES DE PRÉ-VISUALIZAÇÃO ADICIONADAS ---
    private suspend fun generatePreviewForScene(sceneId: String, downloadedAssetPath: String): String? {
        Log.i(TAG, "Iniciando geração de prévia para a cena $sceneId usando o asset: $downloadedAssetPath")
        var audioSnippetPath: String? = null
        try {
            val scene = videoProjectDataStore.sceneLinkDataList.first().find { it.id == sceneId }
                ?: throw IllegalStateException("Cena $sceneId não encontrada no DataStore para gerar prévia.")

            val mainAudioPath = audioDataStore.audioPath.first()
            if (mainAudioPath.isBlank()) throw IllegalStateException("Áudio principal não encontrado.")

            audioSnippetPath = createAudioSnippetForPreview(mainAudioPath, scene)
                ?: throw IOException("Falha ao criar trecho de áudio para prévia.")

            val projectDirName = videoPreferencesDataStore.videoProjectDir.first()
            val baseProjectDir = ProjectPersistenceManager.getProjectDirectory(applicationContext, projectDirName)
            val previewsDir = File(baseProjectDir, "scene_previews")
            previewsDir.mkdirs()

            val sceneWithAsset = scene.copy(imagemGeradaPath = downloadedAssetPath)
            val hash = generateScenePreviewHash(sceneWithAsset, videoPreferencesDataStore)
            val previewFile = File(previewsDir, "scene_${scene.cena}_$hash.mp4")

            if (previewFile.exists()) {
                Log.d(TAG, "Prévia já existe para a cena $sceneId, pulando a geração.")
                return previewFile.absolutePath
            }

            val success = VideoEditorComTransicoes.gerarPreviaDeCenaUnica(
                context = applicationContext,
                scene = sceneWithAsset,
                audioSnippetPath = audioSnippetPath,
                outputPreviewPath = previewFile.absolutePath,
                videoPreferences = videoPreferencesDataStore,
                logCallback = { Log.v("$TAG-FFmpegPreview", it) }
            )

            return if (success) previewFile.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar prévia para a cena $sceneId", e)
            return null
        } finally {
            audioSnippetPath?.let { File(it).delete() }
        }
    }

    private suspend fun createAudioSnippetForPreview(mainAudioPath: String, scene: SceneLinkData): String? = withContext(Dispatchers.IO) {
        val tempDir = File(applicationContext.cacheDir, "audio_snippets_pixabay_worker")
        tempDir.mkdirs()
        val outputFile = File.createTempFile("snippet_pix_${scene.id}_", ".mp3", tempDir)

        val startTime = scene.tempoInicio ?: 0.0
        val endTime = scene.tempoFim ?: 0.0
        val duration = (endTime - startTime).coerceAtLeast(0.1)

        val command = "-y -i \"$mainAudioPath\" -ss $startTime -t $duration -c:a libmp3lame -q:a 4 \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputFile.absolutePath
        } else {
            Log.e(TAG, "Falha ao cortar áudio para prévia (pixabay worker): ${session.allLogsAsString}")
            null
        }
    }

    private fun generateScenePreviewHash(scene: SceneLinkData, prefs: VideoPreferencesDataStoreManager): String {
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
    // --- FIM DAS FUNÇÕES DE PRÉ-VISUALIZAÇÃO ---

    private suspend fun updateSceneWithDownloadedAsset(sceneId: String, assetPath: String, thumbPath: String, previewPath: String?) {
        val currentList = videoProjectDataStore.sceneLinkDataList.first()
        val newList = currentList.map {
            if (it.id == sceneId) {
                it.copy(
                    isGeneratingVideo = false,
                    generationErrorMessage = null,
                    imagemGeradaPath = assetPath,
                    pathThumb = thumbPath,
                    videoPreviewPath = previewPath ?: it.videoPreviewPath
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

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val builder = NotificationCompat.Builder(applicationContext, NotificationUtils.CHANNEL_ID_VIDEO_PROCESSING)
            .setContentTitle(applicationContext.getString(R.string.notification_title_pixabay_search))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!isFinished && !isError)
            .setAutoCancel(isFinished || isError)
        return builder.build()
    }

    private fun updateNotification(message: String, isFinished: Boolean = false, isError: Boolean = false) {
        notificationManager.notify(System.currentTimeMillis().toInt(), createNotification(message, isFinished, isError))
    }
}