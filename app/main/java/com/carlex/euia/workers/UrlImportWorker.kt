package com.carlex.euia.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.ExtrairMl
import com.carlex.euia.api.GeminiTextAndVisionStandardApi
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.UserInfoDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager // Adicionado
import com.carlex.euia.data.VideoPreferencesDataStoreManager // Adicionado
// Presumindo que estes DataStoreManagers existem e têm os métodos clear
// import com.carlex.euia.data.VideoGeneratorDataStoreManager
// import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.prompts.ExtractInfoFromUrlPrompt
import com.carlex.euia.prompts.ExtractDetailedPageContentAsKeyValuesPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

// Constantes para a notificação
private const val NOTIFICATION_ID_URL = 3
private const val NOTIFICATION_CHANNEL_ID_URL = "UrlImportChannelEUIA"


class UrlImportWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG_URL_IMPORT_WORKER = "UrlImportWorker"

    private val audioDataStoreManager = AudioDataStoreManager(applicationContext)
    private val refImageDataStoreManager = RefImageDataStoreManager(applicationContext)
    private val userInfoDataStoreManager = UserInfoDataStoreManager(applicationContext)
    private val videoDataStoreManager = VideoDataStoreManager(applicationContext) // Instanciado
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(applicationContext) // Instanciado
    // Presumindo que estes DataStoreManagers existem e têm os métodos clear
    // private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(applicationContext)
    // private val videoProjectDataStoreManager = VideoProjectDataStoreManager(applicationContext)

    private val workManager = WorkManager.getInstance(applicationContext)

    private val kotlinJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    companion object {
        const val KEY_URL_INPUT = "key_url_input"
        // TAG_URL_IMPORT_WORK foi removido pois usamos tags mais específicas abaixo
        const val KEY_SUGGESTED_TITLE_FROM_PHASE1 = "key_suggested_title_from_phase1"
        const val KEY_MAIN_SUMMARY_FROM_PHASE1 = "key_main_summary_from_phase1"
        const val KEY_ACTION = "key_action"
        const val ACTION_PRE_CONTEXT_EXTRACTION = "action_pre_context_extraction" // Ação que fará a limpeza
        const val ACTION_PROCESS_CONTENT_DETAILS = "action_process_content_details"
        const val KEY_OUTPUT_ERROR_MESSAGE = "key_output_error_message"
        const val TAG_URL_IMPORT_WORK_PRE_CONTEXT = "url_import_work_pre_context"
        const val TAG_URL_IMPORT_WORK_CONTENT_DETAILS = "url_import_work_content_details"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val urlInputForNotification = inputData.getString(KEY_URL_INPUT)?.take(30) ?: appContext.getString(R.string.url_unknown)
        val action = inputData.getString(KEY_ACTION) ?: ACTION_PRE_CONTEXT_EXTRACTION
        val title = appContext.getString(R.string.notification_title_url_import)
        var contentText = appContext.getString(R.string.notification_content_url_starting, urlInputForNotification)

        if (action == ACTION_PROCESS_CONTENT_DETAILS) {
            contentText = appContext.getString(R.string.notification_content_url_processing_details, urlInputForNotification)
        } else if (action == ACTION_PRE_CONTEXT_EXTRACTION) {
            // Modificado para refletir a limpeza, se for o caso
            contentText = appContext.getString(R.string.notification_content_url_extracting_precontext_cleaning, urlInputForNotification)
        }

        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_URL)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(NOTIFICATION_ID_URL, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.notification_channel_name_url)
            val descriptionText = appContext.getString(R.string.notification_channel_description_url)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_URL, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false) {
        // ... (função existente)
        val title = appContext.getString(R.string.notification_title_url_import)
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationBuilder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_URL)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!makeDismissible)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notificationManager.notify(NOTIFICATION_ID_URL, notificationBuilder.build())
        Log.d(TAG_URL_IMPORT_WORKER, "Notificação de Importação de URL atualizada: $contentText")
    }


    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: ACTION_PRE_CONTEXT_EXTRACTION
        Log.d(TAG_URL_IMPORT_WORKER, "Iniciando doWork com ação: $action")
        var finalResult: Result = Result.failure() // Inicializa como falha

        try {
            val urlInputForNotification = inputData.getString(KEY_URL_INPUT)?.take(30) ?: appContext.getString(R.string.url_generic_placeholder)
            when (action) {
                ACTION_PRE_CONTEXT_EXTRACTION -> {
                    // **INÍCIO DA LÓGICA DE LIMPEZA**
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_cleaning_data, urlInputForNotification))
                    Log.i(TAG_URL_IMPORT_WORKER, "Ação PRE_CONTEXT_EXTRACTION: Iniciando limpeza de dados e arquivos do projeto.")

                    // 1. Obter o diretório do projeto atual
                    val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()

                    // 2. Excluir arquivos do diretório do projeto
                    if (projectDirName.isNotBlank()) {
                        Log.i(TAG_URL_IMPORT_WORKER, "Limpando diretório do projeto: '$projectDirName'")
                        deleteProjectDirectoryContents(appContext, projectDirName)
                    } else {
                        Log.w(TAG_URL_IMPORT_WORKER, "Nome do diretório do projeto está em branco. Nenhum arquivo de projeto específico para limpar (diretórios de fallback não serão limpos automaticamente aqui).")
                        // Decida se quer limpar os diretórios de fallback gerais como "audio_general_musics", etc.
                        // Por segurança, esta implementação NÃO limpará os diretórios de fallback gerais automaticamente.
                        // A limpeza de "musicas_default", "gemini_api_default", etc., deve ser mais direcionada se necessário.
                    }

                    // 3. Limpar DataStoreManagers
                    Log.i(TAG_URL_IMPORT_WORKER, "Limpando preferências dos DataStoreManagers...")
                    audioDataStoreManager.clearAllAudioPreferences()
                    refImageDataStoreManager.clearAllRefImagePreferences()
                    videoDataStoreManager.clearAllSettings()
                    // Por enquanto, NÃO vamos limpar o nome do projeto aqui, apenas seu conteúdo.

                    // Adicione chamadas para limpar outros DataStoreManagers se eles existirem
                    // Ex:
                    // videoGeneratorDataStoreManager.clearGeneratorState()
                    // videoProjectDataStoreManager.clearProjectState()
                    Log.i(TAG_URL_IMPORT_WORKER, "Limpeza de preferências concluída.")
                    // **FIM DA LÓGICA DE LIMPEZA**

                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_analyzing, urlInputForNotification))
                    finalResult = handlePreContextExtractionAndImageEnqueuing()
                }
                ACTION_PROCESS_CONTENT_DETAILS -> {
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_extracting_details, urlInputForNotification))
                    val url = inputData.getString(KEY_URL_INPUT)
                    val title = inputData.getString(KEY_SUGGESTED_TITLE_FROM_PHASE1) ?: ""
                    val summary = inputData.getString(KEY_MAIN_SUMMARY_FROM_PHASE1) ?: ""

                    if (url.isNullOrBlank()) {
                        Log.e(TAG_URL_IMPORT_WORKER, "URL não fornecida para $ACTION_PROCESS_CONTENT_DETAILS")
                        val errorMsg = appContext.getString(R.string.error_url_missing_for_details)
                        updateNotificationProgress(errorMsg, true)
                        finalResult = Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
                    } else {
                        finalResult = extractAndMergeDetailedPageContent(url, title, summary)
                    }
                }
                else -> {
                    Log.e(TAG_URL_IMPORT_WORKER, "Ação desconhecida: $action")
                    val errorMsg = appContext.getString(R.string.error_unknown_action, action)
                    updateNotificationProgress(errorMsg, true)
                    finalResult = Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
                }
            }

            // Atualiza notificação final com base no resultado
            if (finalResult is Result.Success) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_import_completed_successfully, urlInputForNotification), true)
            } else if (finalResult is Result.Failure) {
                val errorMessage = finalResult.outputData.getString(KEY_OUTPUT_ERROR_MESSAGE) ?: appContext.getString(R.string.error_url_import_failed)
                // Se a falha foi na limpeza, o errorMessage já pode indicar isso.
                // Se a falha foi depois, usa a mensagem de erro específica.
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_import_failed_details, urlInputForNotification, errorMessage.take(50)), true)
            }
            return finalResult

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG_URL_IMPORT_WORKER, "Importação de URL cancelada: ${e.message}", e)
            val cancelMsg = e.message ?: appContext.getString(R.string.error_url_import_cancelled)
            updateNotificationProgress(cancelMsg, true)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to cancelMsg))
        }
        catch (e: Exception) {
            Log.e(TAG_URL_IMPORT_WORKER, "Erro catastrófico no UrlImportWorker: ${e.message}", e)
            val errorMsg = appContext.getString(R.string.error_critical_url_import, (e.message ?: e.javaClass.simpleName))
            updateNotificationProgress(errorMsg, true)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
        }
    }

    private suspend fun deleteProjectDirectoryContents(context: Context, projectDirName: String) {
        if (projectDirName.isBlank()) {
            Log.w(TAG_URL_IMPORT_WORKER, "deleteProjectDirectoryContents: Nome do projeto vazio, nenhuma exclusão será feita.")
            return
        }

        val dirsToDelete = mutableListOf<File>()

        // Diretório externo específico do app para o projeto
        context.getExternalFilesDir(null)?.let { externalBaseDir ->
            val externalProjectDir = File(externalBaseDir, projectDirName)
            if (externalProjectDir.exists() && externalProjectDir.isDirectory) {
                dirsToDelete.add(externalProjectDir)
            }
        }

        // Diretório interno específico do app para o projeto
        val internalProjectDir = File(context.filesDir, projectDirName)
        if (internalProjectDir.exists() && internalProjectDir.isDirectory) {
            dirsToDelete.add(internalProjectDir)
        }

        var deletedSomething = false
        dirsToDelete.forEach { dir ->
            Log.i(TAG_URL_IMPORT_WORKER, "Tentando excluir conteúdo de: ${dir.absolutePath}")
            // Exclui o conteúdo do diretório, não o próprio diretório do projeto,
            // para que a pasta do projeto permaneça, mas vazia.
            // Se quiser excluir a pasta do projeto também, use dir.deleteRecursively()
            // mas isso pode ser problemático se o DataStore ainda aponta para ela.
            // Limpar o conteúdo é mais seguro inicialmente.
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (file.deleteRecursively()) {
                        Log.d(TAG_URL_IMPORT_WORKER, "Subdiretório excluído: ${file.absolutePath}")
                        deletedSomething = true
                    } else {
                        Log.w(TAG_URL_IMPORT_WORKER, "Falha ao excluir subdiretório: ${file.absolutePath}")
                    }
                } else {
                    if (file.delete()) {
                        Log.d(TAG_URL_IMPORT_WORKER, "Arquivo excluído: ${file.absolutePath}")
                        deletedSomething = true
                    } else {
                        Log.w(TAG_URL_IMPORT_WORKER, "Falha ao excluir arquivo: ${file.absolutePath}")
                    }
                }
            }
        }
        if (deletedSomething) {
            Log.i(TAG_URL_IMPORT_WORKER, "Conteúdo do diretório do projeto '$projectDirName' limpo.")
        } else {
            Log.i(TAG_URL_IMPORT_WORKER, "Nenhum conteúdo encontrado ou excluído no diretório do projeto '$projectDirName'.")
        }
    }


    private suspend fun handlePreContextExtractionAndImageEnqueuing(): Result {
        // ... (resto da função como estava, mas agora APÓS a limpeza)
        val urlInput = inputData.getString(KEY_URL_INPUT)
        if (urlInput.isNullOrBlank()) {
            Log.e(TAG_URL_IMPORT_WORKER, "URL de entrada está vazia para Fase 1.")
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_url_not_provided)))
        }
        Log.i(TAG_URL_IMPORT_WORKER, "Fase 1 (Pré-Contexto & Imagens ML): Iniciando para URL: $urlInput")

        try {
            if (!coroutineContext.isActive) {
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 1 cancelada no início.")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase1_cancelled)))
            }

            val currentUserNameCompany = userInfoDataStoreManager.userNameCompany.first()
            val currentUserProfessionSegment = userInfoDataStoreManager.userProfessionSegment.first()
            val currentUserAddress = userInfoDataStoreManager.userAddress.first()
            // Estes agora podem estar vazios devido à limpeza, o prompt deve lidar com isso
            val currentUserLanguageTone = audioDataStoreManager.userLanguageToneAudio.first()
            val currentUserTargetAudience = audioDataStoreManager.userTargetAudienceAudio.first()


            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_contacting_ai_precontext, urlInput.take(30)))
            val preContextPromptObject = ExtractInfoFromUrlPrompt(
                contentUrl = urlInput,
                currentUserNameCompany = currentUserNameCompany, // Estes vêm do UserInfo, que pode ou não ter sido limpo
                currentUserProfessionSegment = currentUserProfessionSegment,
                currentUserAddress = currentUserAddress,
                currentUserLanguageTone = currentUserLanguageTone, // Este virá vazio se audioDataStore foi limpo
                currentUserTargetAudience = currentUserTargetAudience // Este virá vazio se audioDataStore foi limpo
            )
            val geminiResult: kotlin.Result<String> = GeminiTextAndVisionStandardApi.perguntarAoGemini(preContextPromptObject.prompt, emptyList(), "")

            if (!coroutineContext.isActive) {
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 1 cancelada após chamada Gemini (pré-contexto).")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase1_cancelled)))
            }

            if (geminiResult.isFailure) {
                val errorMsg = appContext.getString(R.string.error_failed_to_get_precontext_from_ai, (geminiResult.exceptionOrNull()?.message?.take(50) ?: ""))
                Log.e(TAG_URL_IMPORT_WORKER, errorMsg)
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
            }
            val geminiResponseJson = geminiResult.getOrNull()
            if (geminiResponseJson.isNullOrBlank()) {
                Log.e(TAG_URL_IMPORT_WORKER, "Resposta da Gemini para pré-contexto está vazia.")
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
                val contentObjective = jsonObject["video_objective_content"]?.jsonPrimitive?.contentOrNull ?: "" // Nome da chave para "video_objective_video"
                val outcomeObjective = jsonObject["video_objective_outcome"]?.jsonPrimitive?.contentOrNull ?: ""
                val suggestedLanguageTone = jsonObject["suggested_language_tone"]?.jsonPrimitive?.contentOrNull ?: ""
                val suggestedTargetAudience = jsonObject["suggested_target_audience"]?.jsonPrimitive?.contentOrNull ?: ""

                if (suggestedTitle.isNotBlank()) audioDataStoreManager.setVideoTitulo(suggestedTitle)
                audioDataStoreManager.setVideoObjectiveIntroduction(introObjective)
                audioDataStoreManager.setVideoObjectiveVideo(contentObjective) // Usa a chave correta
                audioDataStoreManager.setVideoObjectiveOutcome(outcomeObjective)
                // Não define tom/audiência se a sugestão for genérica, para manter o que o usuário possa ter definido antes da limpeza (se UserInfo não foi limpo)
                if (suggestedLanguageTone.isNotBlank() && !suggestedLanguageTone.contains("padrão", true) && !suggestedLanguageTone.contains("não disponível", true) && !suggestedLanguageTone.contains("Neutro", true) ) {
                    audioDataStoreManager.setUserLanguageToneAudio(suggestedLanguageTone)
                }
                if (suggestedTargetAudience.isNotBlank() && !suggestedTargetAudience.contains("Pessoas interessadas", true) && !suggestedTargetAudience.contains("não disponível", true) && !suggestedTargetAudience.contains("Público geral", true)) {
                    audioDataStoreManager.setUserTargetAudienceAudio(suggestedTargetAudience)
                }
                Log.i(TAG_URL_IMPORT_WORKER, "Pré-contexto salvo. Título: $suggestedTitle")
            } catch (e: Exception) {
                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao parsear JSON do pré-contexto: ${e.message}. JSON: $cleanedJson", e)
                // Continua mesmo se o parsing falhar, para tentar a fase 2 com o que tiver.
            }

            var imageProcessingWorkRequests = listOf<OneTimeWorkRequest>()
            if (urlInput.contains("mercadolivre", ignoreCase = true) && coroutineContext.isActive) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_extracting_ml_images, urlInput.take(30)))
                Log.i(TAG_URL_IMPORT_WORKER, "URL é do Mercado Livre. Tentando extrair imagens com ExtrairMl.")
                val extratorResult = importarDadosMl(urlInput, applicationContext)

                val caminhosImagensParaProcessar = extratorResult.getOrNull()?.get("imagensSalvas") as? List<String> ?: emptyList()

                if (caminhosImagensParaProcessar.isNotEmpty() && coroutineContext.isActive) {
                    Log.i(TAG_URL_IMPORT_WORKER, "ExtrairMl retornou ${caminhosImagensParaProcessar.size} caminhos de arquivo para o ML.")
                    updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_preparing_images, caminhosImagensParaProcessar.size))

                    val contentUrisParaWorker = mutableListOf<String>()
                    val authority = "${applicationContext.packageName}.fileprovider"

                    for (filePath in caminhosImagensParaProcessar) {
                        if (!coroutineContext.isActive) break
                        val file = File(filePath)
                        if (file.exists()) {
                            try {
                                val contentUri: Uri = FileProvider.getUriForFile(applicationContext, authority, file)
                                applicationContext.grantUriPermission(applicationContext.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                contentUrisParaWorker.add(contentUri.toString())
                            } catch (e: Exception) {
                                Log.e(TAG_URL_IMPORT_WORKER, "Erro ao obter/conceder content URI para $filePath: ${e.message}", e)
                            }
                        }
                    }

                    if (contentUrisParaWorker.isNotEmpty() && coroutineContext.isActive) {
                        val imageProcessingInputData = Data.Builder()
                            .putStringArray(ImageProcessingWorker.KEY_MEDIA_URIS, contentUrisParaWorker.toTypedArray()) // CORREÇÃO AQUI
                            .build()
                        val imageProcessingRequest = OneTimeWorkRequestBuilder<ImageProcessingWorker>()
                            .setInputData(imageProcessingInputData)
                            .addTag(ImageProcessingWorker.TAG_IMAGE_PROCESSING_WORK)
                            .build()
                        val refImageAnalysisRequest = OneTimeWorkRequestBuilder<RefImageAnalysisWorker>()
                            .addTag(RefImageAnalysisWorker.TAG_REF_IMAGE_ANALYSIS_WORK)
                            .build()
                        imageProcessingWorkRequests = listOf(imageProcessingRequest, refImageAnalysisRequest)
                    }
                }
            }

            if (!coroutineContext.isActive) {
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 1 cancelada antes de enfileirar próxima etapa.")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_import_cancelled)))
            }

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase1_finalizing_next_step, urlInput.take(30)))
            val phase2InputData = workDataOf(
                KEY_ACTION to ACTION_PROCESS_CONTENT_DETAILS,
                KEY_URL_INPUT to urlInput,
                KEY_SUGGESTED_TITLE_FROM_PHASE1 to suggestedTitle,
                KEY_MAIN_SUMMARY_FROM_PHASE1 to mainSummary
            )
            val phase2Request = OneTimeWorkRequestBuilder<UrlImportWorker>()
                .setInputData(phase2InputData)
                .addTag(TAG_URL_IMPORT_WORK_CONTENT_DETAILS) // Tag específica para a fase 2
                .build()

            if (imageProcessingWorkRequests.isNotEmpty()) {
                workManager.beginWith(imageProcessingWorkRequests)
                    .then(phase2Request)
                    .enqueue()
                Log.i(TAG_URL_IMPORT_WORKER, "Cadeia de workers de imagem (ML) seguida pela Fase 2 (detalhes) enfileirada.")
            } else {
                workManager.beginWith(phase2Request).enqueue()
                Log.i(TAG_URL_IMPORT_WORKER, "Fase 2 (detalhes do conteúdo) enfileirada diretamente.")
            }
            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG_URL_IMPORT_WORKER, "Fase 1 (Pré-Contexto) cancelada: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to (e.message ?: appContext.getString(R.string.error_phase1_cancelled))))
        }
        catch (e: Exception) {
            Log.e(TAG_URL_IMPORT_WORKER, "Erro durante a Fase 1 (Pré-Contexto): ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase1_generic, (e.message?.take(50) ?: ""))))
        }
    }

    private suspend fun extractAndMergeDetailedPageContent(originalUrl: String, suggestedTitle: String, initialSummary: String): Result {
        // ... (função existente, sem alterações de limpeza aqui) ...
        Log.i(TAG_URL_IMPORT_WORKER, "Fase 2 (Detalhes): Iniciando para URL: $originalUrl")

        try {
            if (!coroutineContext.isActive) { 
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 2 cancelada no início.")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase2_cancelled)))
            }

            val currentDetailsJson = refImageDataStoreManager.refObjetoDetalhesJson.first()
            val allDetailsMap: MutableMap<String, String> = try {
                if (currentDetailsJson.isNotBlank() && currentDetailsJson != "{}") {
                    kotlinJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), currentDetailsJson).toMutableMap()
                } else {
                    mutableMapOf()
                }
            } catch (e: Exception) {
                Log.w(TAG_URL_IMPORT_WORKER, "Erro ao decodificar refObjetoDetalhesJson existente: ${e.message}. Iniciando com mapa vazio.")
                mutableMapOf()
            }
            Log.d(TAG_URL_IMPORT_WORKER, "Detalhes carregados do DataStore (podem incluir análise de imagem ML): ${allDetailsMap.size} itens.")
            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_contacting_ai_details, originalUrl.take(30)))

            val detailedContentPromptObject = ExtractDetailedPageContentAsKeyValuesPrompt(originalUrl, suggestedTitle, initialSummary)
            val geminiDetailedResult: kotlin.Result<String> = GeminiTextAndVisionStandardApi.perguntarAoGemini(detailedContentPromptObject.prompt, emptyList(), "")

            if (!coroutineContext.isActive) { 
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 2 cancelada após chamada Gemini (detalhes).")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_phase2_cancelled)))
            }

            if (geminiDetailedResult.isFailure) {
                val errorMsg = appContext.getString(R.string.error_failed_to_get_details_from_ai, (geminiDetailedResult.exceptionOrNull()?.message?.take(50) ?: ""))
                Log.e(TAG_URL_IMPORT_WORKER, errorMsg)
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to errorMsg))
            }

            val detailedJsonResponse = geminiDetailedResult.getOrNull()
            if (!detailedJsonResponse.isNullOrBlank()) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_processing_page_details, originalUrl.take(30)))
                Log.d(TAG_URL_IMPORT_WORKER, "Resposta Gemini (detalhes página bruta): ${detailedJsonResponse.take(200)}")
                val cleanedDetailedJson = ajustarResposta1(detailedJsonResponse)
                Log.d(TAG_URL_IMPORT_WORKER, "Resposta Gemini (detalhes página limpa): ${cleanedDetailedJson.take(200)}")

                try {
                    val jsonElement = kotlinJson.parseToJsonElement(cleanedDetailedJson)
                    val jsonObject = jsonElement.jsonObject

                    jsonObject["detailed_key_values"]?.let { kvElement ->
                        if (kvElement is JsonArray) {
                            kvElement.forEach { item ->
                                val itemObj = item.jsonObject
                                val key = itemObj["chave"]?.jsonPrimitive?.contentOrNull
                                val value = itemObj["valor"]?.jsonPrimitive?.contentOrNull
                                if (!key.isNullOrBlank() && value != null) {
                                    allDetailsMap[key] = value
                                    Log.d(TAG_URL_IMPORT_WORKER, "Mesclando detalhe da página: Chave='${key.take(20)}', Valor='${value.take(30)}...'")
                                }
                            }
                        }
                    }
                    Log.i(TAG_URL_IMPORT_WORKER, "Pares chave-valor detalhados da página mesclados. Total: ${allDetailsMap.size}.")

                } catch (e: Exception) {
                    Log.e(TAG_URL_IMPORT_WORKER, "Erro ao parsear JSON dos detalhes da página da Gemini: ${e.message}. JSON: $cleanedDetailedJson", e)
                }
            } else {
                Log.w(TAG_URL_IMPORT_WORKER, "Resposta da Gemini para detalhes da página está vazia.")
            }

            if (!coroutineContext.isActive) { 
                Log.w(TAG_URL_IMPORT_WORKER, "Fase 2 cancelada antes de salvar detalhes.")
                return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_import_cancelled)))
            }

            updateNotificationProgress(appContext.getString(R.string.notification_content_url_phase2_saving_final_details, originalUrl.take(30)))
            if (allDetailsMap.isNotEmpty()) {
                val mergedDetailsJsonToSave = kotlinJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), allDetailsMap)
                refImageDataStoreManager.setRefObjetoDetalhesJson(mergedDetailsJsonToSave)
                Log.i(TAG_URL_IMPORT_WORKER, "Mapa de detalhes final salvo. JSON: ${mergedDetailsJsonToSave.take(200)}...")
            } else {
                refImageDataStoreManager.setRefObjetoDetalhesJson("{}") // Salva um JSON vazio se o mapa estiver vazio
                Log.i(TAG_URL_IMPORT_WORKER, "Mapa de detalhes final está vazio. Salvo '{}'.")
            }
            return Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG_URL_IMPORT_WORKER, "Fase 2 (Detalhes) cancelada: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to (e.message ?: appContext.getString(R.string.error_phase2_cancelled))))
        }
        catch (e: Exception) {
            Log.e(TAG_URL_IMPORT_WORKER, "Erro inesperado em extractAndMergeDetailedContent: ${e.message}", e)
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR_MESSAGE to appContext.getString(R.string.error_processing_details_generic, (e.message?.take(50) ?: ""))))
        }
    }


    private suspend fun importarDadosMl(
        url: String,
        context: Context,
    ): kotlin.Result<Map<String, Any>> {
        // ... (função existente)
        val extrator = ExtrairMl()
        Log.d(TAG_URL_IMPORT_WORKER, "Chamando extrator.extrairDados (para imagens ML) para URL: $url")
        return extrator.extrairDados(url, context)
    }

    private fun ajustarResposta1(resposta: String): String {
        // ... (função existente)
        Log.d(TAG_URL_IMPORT_WORKER, "ajustarResposta1: Resposta original (primeiros 100): '${resposta.take(100)}...'")
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
                Log.d(TAG_URL_IMPORT_WORKER, "ajustarResposta1: JSON substring extracted (balanço). Result (primeiros 100): '${respostaLimpa.take(100)}...'")
            } else {
                Log.w(TAG_URL_IMPORT_WORKER, "ajustarResposta1: Não foi possível encontrar o delimitador de fechamento correspondente.")
            }
        } else {
            Log.w(TAG_URL_IMPORT_WORKER, "ajustarResposta1: Não foi possível encontrar o início do JSON ([ ou {) após limpeza de ```.")
        }
        Log.d(TAG_URL_IMPORT_WORKER, "ajustarResposta1: Resposta após ajuste (primeiros 100): '${respostaLimpa.take(100)}...'")
        return respostaLimpa
    }
}