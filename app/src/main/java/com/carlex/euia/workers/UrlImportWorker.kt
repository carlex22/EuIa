// File: euia/workers/UrlImportWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.carlex.euia.utils.ProjectPersistenceManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.api.ExtrairMl
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.api.GeminiTextAndVisionStandardApi
import com.carlex.euia.data.*
import com.carlex.euia.prompts.ExtractDetailedPageContentAsKeyValuesPrompt
import com.carlex.euia.prompts.ExtractInfoFromUrlPrompt
import com.carlex.euia.utils.NotificationUtils // Importa o utilitário
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import com.carlex.euia.utils.*
import kotlin.coroutines.coroutineContext

// Constantes para a notificação
private const val NOTIFICATION_ID_URL = 3544

class UrlImportWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG_URL_IMPORT_WORKER = "UrlImportWorker"

    // DataStore Managers
    private val audioDataStoreManager = AudioDataStoreManager(applicationContext)
    private val refImageDataStoreManager = RefImageDataStoreManager(applicationContext)
    private val userInfoDataStoreManager = UserInfoDataStoreManager(applicationContext)
    private val videoDataStoreManager = VideoDataStoreManager(applicationContext)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(applicationContext)
    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(applicationContext)

    private val workManager = WorkManager.getInstance(applicationContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val kotlinJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    companion object {
        const val KEY_URL_INPUT = "key_url_input"
        const val KEY_SUGGESTED_TITLE_FROM_PHASE1 = "key_suggested_title_from_phase1"
        const val KEY_MAIN_SUMMARY_FROM_PHASE1 = "key_main_summary_from_phase1"
        const val KEY_ACTION = "key_action"
        const val ACTION_PRE_CONTEXT_EXTRACTION = "action_pre_context_extraction"
        const val ACTION_PROCESS_CONTENT_DETAILS = "action_process_content_details"
        const val KEY_OUTPUT_ERROR_MESSAGE = "key_output_error_message"
        const val TAG_URL_IMPORT_WORK_PRE_CONTEXT = "url_import_work_pre_context"
        const val TAG_URL_IMPORT_WORK_CONTENT_DETAILS = "url_import_work_content_details"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val urlInputForNotification = inputData.getString(KEY_URL_INPUT)?.take(30) ?: appContext.getString(R.string.url_unknown)
        val action = inputData.getString(KEY_ACTION) ?: ACTION_PRE_CONTEXT_EXTRACTION
        var contentText = appContext.getString(R.string.notification_content_url_starting, urlInputForNotification)

        if (action == ACTION_PROCESS_CONTENT_DETAILS) {
            contentText = appContext.getString(R.string.notification_content_url_processing_details, urlInputForNotification)
        } else if (action == ACTION_PRE_CONTEXT_EXTRACTION) {
            contentText = appContext.getString(R.string.notification_content_url_extracting_precontext_cleaning, urlInputForNotification)
        }
        
        // A criação do canal foi removida daqui, pois agora é feita centralmente no MyApplication
        val notification = createNotification(contentText)
        return ForegroundInfo(NOTIFICATION_ID_URL, notification)
    }

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_URL_IMPORT) // <<< USA A CONSTANTE CENTRALIZADA
            .setContentTitle(appContext.getString(R.string.notification_title_url_import))
            .setTicker(appContext.getString(R.string.notification_title_url_import))
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
        notificationManager.notify(NOTIFICATION_ID_URL, notification)
        Log.d(TAG_URL_IMPORT_WORKER, "Notificação de Importação de URL atualizada: $contentText")
    }

    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: ACTION_PRE_CONTEXT_EXTRACTION
        Log.d(TAG_URL_IMPORT_WORKER, "Iniciando doWork com ação: $action")
        var finalResult: Result = Result.failure()
        
        OverlayManager.showOverlay(appContext, "📝", -1)

        try {
            val urlInputForNotification = inputData.getString(KEY_URL_INPUT)?.take(30) ?: appContext.getString(R.string.url_generic_placeholder)
            when (action) {
                ACTION_PRE_CONTEXT_EXTRACTION -> {
                    performFullCleanup()
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_analyzing, urlInputForNotification))
                    finalResult = handlePreContextExtractionAndImageEnqueuing()
                }
                ACTION_PROCESS_CONTENT_DETAILS -> {
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_extracting_details, urlInputForNotification))
                    val url = inputData.getString(KEY_URL_INPUT)
                    val title = inputData.getString(KEY_SUGGESTED_TITLE_FROM_PHASE1) ?: ""
                    val summary = inputData.getString(KEY_MAIN_SUMMARY_FROM_PHASE1) ?: ""

                    if (url.isNullOrBlank()) {
                        val errorMsg = appContext.getString(R.string.error_url_missing_for_details)
                        finalResult = Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
                    } else {
                        finalResult = extractAndMergeDetailedPageContent(url, title, summary)
                    }
                }
                else -> {
                    val errorMsg = appContext.getString(R.string.error_unknown_action, action)
                    finalResult = Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
                }
            }

            if (finalResult is Result.Success) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_import_completed_successfully, urlInputForNotification), true)
            } else if (finalResult is Result.Failure) {
                val errorMessage = finalResult.outputData.getString(KEY_OUTPUT_ERROR_MESSAGE) ?: appContext.getString(R.string.error_url_import_failed)
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_import_failed_details, urlInputForNotification, errorMessage.take(50)), true, isError = true)
            }
            
            ProjectPersistenceManager.saveProjectState(appContext)
            OverlayManager.hideOverlay(appContext) 
            
            return finalResult

        } catch (e: kotlinx.coroutines.CancellationException) {
        OverlayManager.hideOverlay(appContext) 
            Log.w(TAG_URL_IMPORT_WORKER, "Importação de URL cancelada: ${e.message}", e)
            val cancelMsg = e.message ?: appContext.getString(R.string.error_url_import_cancelled)
            updateNotificationProgress(cancelMsg, true, isError = true)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to cancelMsg))
        } catch (e: Exception) {
        OverlayManager.hideOverlay(appContext) 
            Log.e(TAG_URL_IMPORT_WORKER, "Erro catastrófico no UrlImportWorker: ${e.message}", e)
            val errorMsg = appContext.getString(R.string.error_critical_url_import, (e.message ?: e.javaClass.simpleName))
            updateNotificationProgress(errorMsg, true, isError = true)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
        }
    }
    
    // ... O restante das funções (performFullCleanup, handlePreContextExtractionAndImageEnqueuing, etc.) permanece o mesmo ...
    private suspend fun performFullCleanup() {
        updateNotificationProgress(appContext.getString(R.string.notification_content_url_cleaning_data, inputData.getString(KEY_URL_INPUT)?.take(30) ?: "..."))
        Log.i(TAG_URL_IMPORT_WORKER, "Ação PRE_CONTEXT_EXTRACTION: Iniciando limpeza PROFUNDA de dados e arquivos do projeto.")

        // 1. Apagar arquivos físicos associados a ImagemReferencia
        val currentImagensReferenciaJson = videoDataStoreManager.imagensReferenciaJson.first()
        if (currentImagensReferenciaJson.isNotBlank() && currentImagensReferenciaJson != "[]") {
            Log.d(TAG_URL_IMPORT_WORKER, "Limpando arquivos de ImagemReferencia (VideoDataStoreManager)...")
            try {
                val imagensReferenciaList: List<ImagemReferencia> = kotlinJson.decodeFromString(
                    ListSerializer(ImagemReferencia.serializer()), currentImagensReferenciaJson
                )
                for (imagemRef in imagensReferenciaList) {
                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
                    deleteFileWorker(imagemRef.path, "ImagemReferencia_Path")
                    imagemRef.pathVideo?.let { deleteFileWorker(it, "ImagemReferencia_PathVideo") }
                }
            } catch (e: Exception) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao decodificar/apagar arquivos de ImagemReferencia: ${e.message}", e)
            }
        }

        // 2. Apagar arquivos físicos associados a SceneLinkData
        val currentSceneLinkDataJson = videoProjectDataStoreManager.sceneLinkDataJsonString.first()
        if (currentSceneLinkDataJson.isNotBlank() && currentSceneLinkDataJson != "[]") {
            Log.d(TAG_URL_IMPORT_WORKER, "Limpando arquivos de SceneLinkData (VideoProjectDataStoreManager)...")
            try {
                val sceneLinkDataList: List<SceneLinkData> = kotlinJson.decodeFromString(
                    ListSerializer(SceneLinkData.serializer()), currentSceneLinkDataJson
                )
                for (sceneData in sceneLinkDataList) {
                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
                    deleteFileWorker(sceneData.imagemReferenciaPath, "SceneLink_RefPath")
                    sceneData.imagemGeradaPath?.let { deleteFileWorker(it, "SceneLink_GeneratedAssetPath") }
                    sceneData.pathThumb?.let { deleteFileWorker(it, "SceneLink_ThumbPath") }
                    sceneData.audioPathSnippet?.let { deleteFileWorker(it, "SceneLink_AudioSnippetPath") }
                }
            } catch (e: Exception) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao decodificar/apagar arquivos de SceneLinkData: ${e.message}", e)
            }
        }

        Log.i(TAG_URL_IMPORT_WORKER, "Limpando dados dos DataStoreManagers...")
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
        audioDataStoreManager.clearAllAudioPreferences()
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
        refImageDataStoreManager.clearAllRefImagePreferences()
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
        videoDataStoreManager.clearAllSettings()
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Limpeza cancelada")
        videoProjectDataStoreManager.clearProjectState()
        // videoGeneratorDataStoreManager.clearGeneratorState() // Descomente se usado

        Log.i(TAG_URL_IMPORT_WORKER, "Limpeza de dados e arquivos concluída.")
    }
     private suspend fun deleteFileWorker(filePath: String?, logContext: String) {
        if (filePath.isNullOrBlank()) return

        withContext(Dispatchers.IO) {
            if (!coroutineContext.isActive) return@withContext
            try {
                val file = File(filePath)
                if (file.exists()) {
                    if (file.delete()) {
                        Log.d(TAG_URL_IMPORT_WORKER, "Arquivo ($logContext) excluído: $filePath")
                    } else {
                        Log.w(TAG_URL_IMPORT_WORKER, "Falha ao excluir arquivo ($logContext): $filePath")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro de segurança ao tentar excluir arquivo ($logContext) $filePath: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao excluir arquivo ($logContext) $filePath: ${e.message}", e)
            }
        }
    }
     private suspend fun handlePreContextExtractionAndImageEnqueuing(): Result {
        val urlInput = inputData.getString(KEY_URL_INPUT)
        if (urlInput.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_url_not_provided)))
        }
        Log.i(TAG_URL_IMPORT_WORKER, "Fase 1 (Pré-Contexto & Imagens ML): Iniciando para URL: $urlInput")

        try {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_phase1_cancelled_early))

            val currentUserNameCompany = userInfoDataStoreManager.userNameCompany.first()
            val currentUserProfessionSegment = userInfoDataStoreManager.userProfessionSegment.first()
            val currentUserAddress = userInfoDataStoreManager.userAddress.first()
            val currentUserLanguageTone = audioDataStoreManager.userLanguageToneAudio.first()
            val currentUserTargetAudience = audioDataStoreManager.userTargetAudienceAudio.first()
            val isChatMode = audioDataStoreManager.isChatNarrative.first()
            
            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_contacting_ai_precontext, urlInput.take(30)))
            val preContextPromptObject = ExtractInfoFromUrlPrompt(
                contentUrl = urlInput, currentUserNameCompany, currentUserProfessionSegment,
                currentUserAddress, currentUserLanguageTone, currentUserTargetAudience, isChatMode
            )
            
            
            val youtubeUrlForApi: String? = if (urlInput.contains("youtube.com", ignoreCase = true) || urlInput.contains("youtu.be", ignoreCase = true)) {
                Log.i(TAG_URL_IMPORT_WORKER, "YouTube URL detectada. Será enviada para análise de vídeo.")
                urlInput
            } else {
                null
            }

            // Chama a API PRO, passando a URL do YouTube se aplicável
            val geminiResult: kotlin.Result<String> = GeminiTextAndVisionProRestApi.perguntarAoGemini(
                pergunta = preContextPromptObject.prompt,
                imagens = emptyList(), 
                arquivoTexto = null,
                youtubeUrl = youtubeUrlForApi // Passa a URL do YouTube aqui
            )
            
            
           // val geminiResult: kotlin.Result<String> = GeminiTextAndVisionProRestApi.perguntarAoGemini(preContextPromptObject.prompt, emptyList(), "")

            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_phase1_cancelled_after_gemini))

            if (geminiResult.isFailure) {
                val errorMsg = appContext.getString(R.string.error_failed_to_get_precontext_from_ai, (geminiResult.exceptionOrNull()?.message?.take(50) ?: ""))
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
            }
            val geminiResponseJson = geminiResult.getOrNull()
            if (geminiResponseJson.isNullOrBlank()) {
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_ai_empty_precontext_response)))
            }

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_processing_precontext_data, urlInput.take(30)))
            val cleanedJson = ajustarResposta1(geminiResponseJson)
            var suggestedTitle = ""
            var mainSummary = ""

            try {
                val jsonElement = kotlinJson.parseToJsonElement(cleanedJson)
                val jsonObject = jsonElement.jsonObject
                suggestedTitle = jsonObject["suggested_title"]?.jsonPrimitive?.contentOrNull ?: ""
                mainSummary = jsonObject["main_summary"]?.jsonPrimitive?.contentOrNull ?: ""
                val introObjective = jsonObject["video_objective_introduction"]?.jsonPrimitive?.contentOrNull ?: ""
                val contentObjective = jsonObject["video_objective_content"]?.jsonPrimitive?.contentOrNull ?: ""
                val outcomeObjective = jsonObject["video_objective_outcome"]?.jsonPrimitive?.contentOrNull ?: ""
                val suggestedLanguageTone = jsonObject["suggested_language_tone"]?.jsonPrimitive?.contentOrNull ?: ""
                val suggestedTargetAudience = jsonObject["suggested_target_audience"]?.jsonPrimitive?.contentOrNull ?: ""

                if (suggestedTitle.isNotBlank()) audioDataStoreManager.setVideoTitulo(suggestedTitle)
                audioDataStoreManager.setIsChatNarrative(isChatMode)
                audioDataStoreManager.setVideoObjectiveIntroduction(introObjective)
                audioDataStoreManager.setVideoObjectiveVideo(contentObjective)
                audioDataStoreManager.setVideoObjectiveOutcome(outcomeObjective)
                if (suggestedLanguageTone.isNotBlank() && !suggestedLanguageTone.contains("padrão", true)) audioDataStoreManager.setUserLanguageToneAudio(suggestedLanguageTone)
                if (suggestedTargetAudience.isNotBlank() && !suggestedTargetAudience.contains("Pessoas interessadas", true)) audioDataStoreManager.setUserTargetAudienceAudio(suggestedTargetAudience)
                Log.i(TAG_URL_IMPORT_WORKER, "Pré-contexto salvo. Título: $suggestedTitle")
            } catch (e: Exception) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao parsear JSON do pré-contexto: ${e.message}. JSON: $cleanedJson", e)
            }

            var imageProcessingWorkRequests = listOf<OneTimeWorkRequest>()
            if (urlInput.contains("mercadolivre", ignoreCase = true) && coroutineContext.isActive) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_extracting_ml_images, urlInput.take(30)))
                Log.i(TAG_URL_IMPORT_WORKER, "URL é do Mercado Livre. Extraindo imagens com ExtrairMl.")
                val extratorResult = importarDadosMl(urlInput, applicationContext)
                val caminhosImagensParaProcessar = extratorResult.getOrNull()?.get("imagensSalvas") as? List<String> ?: emptyList()

                if (caminhosImagensParaProcessar.isNotEmpty() && coroutineContext.isActive) {
                    Log.i(TAG_URL_IMPORT_WORKER, "ExtrairMl retornou ${caminhosImagensParaProcessar.size} caminhos.")
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_preparing_images, caminhosImagensParaProcessar.size))
                    val contentUrisParaWorker = mutableListOf<String>()
                    val authority = "${applicationContext.packageName}.fileprovider"
                    for (filePath in caminhosImagensParaProcessar) {
                        if (!coroutineContext.isActive) break
                        try {
                            val contentUri = FileProvider.getUriForFile(applicationContext, authority, File(filePath))
                            applicationContext.grantUriPermission(applicationContext.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            contentUrisParaWorker.add(contentUri.toString())
                        } catch (e: Exception) {
                            Log.e(TAG_URL_IMPORT_WORKER, "Erro ao obter content URI para $filePath: ${e.message}", e)
                        }
                    }
                    if (contentUrisParaWorker.isNotEmpty() && coroutineContext.isActive) {
                        val imageProcessingRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
                            .setInputData(workDataOf(ImageProcessingWorker.KEY_MEDIA_URIS to contentUrisParaWorker.toTypedArray()))
                            .addTag(ImageProcessingWorker.TAG_IMAGE_PROCESSING_WORK).build()
                        val refImageAnalysisRequest = OneTimeWorkRequestBuilder<RefImageAnalysisWorker>()
                            .addTag(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK).build()
                        imageProcessingWorkRequests = listOf(imageProcessingRequest, refImageAnalysisRequest)
                    }
                }
            }

            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_import_cancelled_before_phase2))

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_finalizing_next_step, urlInput.take(30)))
            val phase2Request = OneTimeWorkRequestBuilder<UrlImportWorker>()
                .setInputData(workDataOf(
                    KEY_ACTION to ACTION_PROCESS_CONTENT_DETAILS, KEY_URL_INPUT to urlInput,
                    KEY_SUGGESTED_TITLE_FROM_PHASE1 to suggestedTitle, KEY_MAIN_SUMMARY_FROM_PHASE1 to mainSummary
                )).addTag(TAG_URL_IMPORT_WORK_CONTENT_DETAILS).build()

            if (imageProcessingWorkRequests.isNotEmpty()) {
                workManager.beginWith(imageProcessingWorkRequests).then(phase2Request).enqueue()
            } else {
                workManager.beginWith(phase2Request).enqueue()
            }
            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG_URL_IMPORT_WORKER, "Fase 1 (Pré-Contexto) cancelada: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to (e.message ?: appContext.getString(R.string.error_phase1_cancelled))))
        } catch (e: Exception) {
            Log.e(TAG_URL_IMPORT_WORKER, "Erro durante a Fase 1 (Pré-Contexto): ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase1_generic, (e.message?.take(50) ?: ""))))
        }
    }
     private suspend fun extractAndMergeDetailedPageContent(originalUrl: String, suggestedTitle: String, initialSummary: String): Result {
        Log.i(TAG_URL_IMPORT_WORKER, "Fase 2 (Detalhes): Iniciando para URL: $originalUrl")
        try {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_phase2_cancelled_early))

            val currentDetailsJson = refImageDataStoreManager.refObjetoDetalhesJson.first()
            val allDetailsMap: MutableMap<String, String> = try {
                if (currentDetailsJson.isNotBlank() && currentDetailsJson != "{}") {
                    kotlinJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), currentDetailsJson).toMutableMap()
                } else { mutableMapOf() }
            } catch (e: Exception) { mutableMapOf() }

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_contacting_ai_details, originalUrl.take(30)))
            val detailedContentPromptObject = ExtractDetailedPageContentAsKeyValuesPrompt(originalUrl, suggestedTitle, initialSummary)
            
            
            val youtubeUrlForApi: String? = if (originalUrl.contains("youtube.com", ignoreCase = true) || originalUrl.contains("youtu.be", ignoreCase = true)) {
                Log.i(TAG_URL_IMPORT_WORKER, "YouTube URL detectada. Será enviada para análise de vídeo.")
                originalUrl
            } else {
                null
            }

            // Chama a API PRO, passando a URL do YouTube se aplicável
            val geminiDetailedResult: kotlin.Result<String> = GeminiTextAndVisionProRestApi.perguntarAoGemini(
                pergunta = detailedContentPromptObject.prompt,
                imagens = emptyList(), // Sem imagens nesta fase
                arquivoTexto = null,
                youtubeUrl = youtubeUrlForApi // Passa a URL do YouTube aqui
            )
            
            
            
           // val geminiDetailedResult: kotlin.Result<String> = GeminiTextAndVisionProApi.perguntarAoGemini(detailedContentPromptObject.prompt, emptyList(), "")

            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_phase2_cancelled_after_gemini))

            if (geminiDetailedResult.isFailure) {
                val errorMsg = appContext.getString(R.string.error_failed_to_get_details_from_ai, (geminiDetailedResult.exceptionOrNull()?.message?.take(50) ?: ""))
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
            }
            val detailedJsonResponse = geminiDetailedResult.getOrNull()
            if (!detailedJsonResponse.isNullOrBlank()) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_processing_page_details, originalUrl.take(30)))
                val cleanedDetailedJson = ajustarResposta1(detailedJsonResponse)
                try {
                    val jsonElement = kotlinJson.parseToJsonElement(cleanedDetailedJson)
                    jsonElement.jsonObject["detailed_key_values"]?.let { kvElement ->
                        if (kvElement is JsonArray) {
                            kvElement.forEach { item ->
                                val itemObj = item.jsonObject
                                val key = itemObj["chave"]?.jsonPrimitive?.contentOrNull
                                val value = itemObj["valor"]?.jsonPrimitive?.contentOrNull
                                if (!key.isNullOrBlank() && value != null) allDetailsMap[key] = value
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG_URL_IMPORT_WORKER, "Erro ao parsear JSON dos detalhes da página: ${e.message}. JSON: $cleanedDetailedJson", e)
                }
            }

            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_import_cancelled_before_saving_details))

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_saving_final_details, originalUrl.take(30)))
            val mergedDetailsJsonToSave = if (allDetailsMap.isNotEmpty()) {
                kotlinJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), allDetailsMap)
            } else "{}"
            refImageDataStoreManager.setRefObjetoDetalhesJson(mergedDetailsJsonToSave)
            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG_URL_IMPORT_WORKER, "Fase 2 (Detalhes) cancelada: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to (e.message ?: appContext.getString(R.string.error_phase2_cancelled))))
        } catch (e: Exception) {
            Log.e(TAG_URL_IMPORT_WORKER, "Erro inesperado em extractAndMergeDetailedContent: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_processing_details_generic, (e.message?.take(50) ?: ""))))
        }
    }
     private suspend fun importarDadosMl(url: String, context: Context): kotlin.Result<Map<String, Any>> {
        val extrator = ExtrairMl()
        return extrator.extrairDados(url, context)
    }
     private fun ajustarResposta1(resposta: String): String {
        var respostaLimpa = resposta.trim()
        if (respostaLimpa.startsWith("```json", ignoreCase = true)) respostaLimpa = respostaLimpa.removePrefix("```json").trimStart()
        else if (respostaLimpa.startsWith("```")) respostaLimpa = respostaLimpa.removePrefix("```").trimStart()
        if (respostaLimpa.endsWith("```")) respostaLimpa = respostaLimpa.removeSuffix("```").trimEnd()
        val primeiroColchete = respostaLimpa.indexOfFirst { it == '[' || it == '{' }
        val fimJson = respostaLimpa.indexOfLast { it == ']' || it == '}' }
        return if (primeiroColchete != -1 && fimJson != -1 && fimJson >= primeiroColchete) {
            respostaLimpa.substring(primeiroColchete, fimJson + 1)
        } else respostaLimpa
    }
}