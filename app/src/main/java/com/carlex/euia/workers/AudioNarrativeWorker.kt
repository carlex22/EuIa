// File: euia/workers/AudioNarrativeWorker.kt
package com.carlex.euia.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.carlex.euia.R
import com.carlex.euia.api.Audio
import com.carlex.euia.api.GeminiAudio
import com.carlex.euia.api.GeminiMultiSpeakerAudio
import com.carlex.euia.api.GeminiTextAndVisionStandardApi
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.UserInfoDataStoreManager
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.prompts.CreateAudioNarrative
import com.carlex.euia.prompts.CreateAudioNarrativeChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KtJsonArray
import kotlinx.serialization.json.JsonObject as KtJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray // org.json
import org.json.JSONException
import org.json.JSONObject // org.json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.BufferedReader
import java.util.UUID

// --- CONSTANTES NO NÍVEL DO ARQUIVO ---
const val KEY_PROMPT_TO_USE = "promptToUse"
const val KEY_IS_NEW_NARRATIVE = "isNewNarrative"
const val KEY_VOICE_OVERRIDE = "voiceToUseOverride"
const val KEY_REF_OBJETO_DETALHES_JSON = "refObjetoDetalhesJson"
const val KEY_IS_CHAT_NARRATIVE = "isChatNarrative"
const val KEY_VOICE_SPEAKER_1 = "voiceSpeaker1"
const val KEY_VOICE_SPEAKER_2 = "voiceSpeaker2"
const val KEY_VOICE_SPEAKER_3 = "voiceSpeaker3"

const val KEY_PROGRESS_TEXT = "generationProgressText"
const val KEY_IS_PROCESSING = "isAudioProcessing"
const val KEY_GENERATED_PROMPT_FILE_PATH = "generatedPromptFilePath" // CORREÇÃO: Nova chave
const val KEY_ERROR_MESSAGE = "errorMessage"
const val KEY_RESULT_PATH = "resultPath"

private const val NOTIFICATION_ID_AUDIO = 299987
private const val NOTIFICATION_CHANNEL_ID_AUDIO = "AudioNarrativeChannelEUIA"

class AudioNarrativeWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "AudioNarrativeWorker"

    private val audioDataStoreManager = AudioDataStoreManager(applicationContext)
    private val videoDataStoreManager = VideoDataStoreManager(applicationContext)
    private val userInfoDataStoreManager = UserInfoDataStoreManager(applicationContext)
    private val refImageDataStoreManager = RefImageDataStoreManager(applicationContext)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(applicationContext)

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = appContext.getString(R.string.notification_title_audio_processing)
        val isChat = inputData.getBoolean(KEY_IS_CHAT_NARRATIVE, false)
        val contentText = if (isChat) appContext.getString(R.string.notification_content_audio_dialog_starting)
                          else appContext.getString(R.string.notification_content_audio_starting)
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_AUDIO)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID_AUDIO, notification)
    }

    private fun updateNotificationProgress(contentText: String, makeDismissible: Boolean = false) {
        val title = appContext.getString(R.string.notification_title_audio_processing)
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationBuilder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_AUDIO)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(!makeDismissible)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notificationManager.notify(NOTIFICATION_ID_AUDIO, notificationBuilder.build())
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result = coroutineScope {
        Log.d(TAG, "doWork() Iniciado.")

        val promptToUseInput = inputData.getString(KEY_PROMPT_TO_USE) ?: ""
        val isNewNarrative = inputData.getBoolean(KEY_IS_NEW_NARRATIVE, true)
        val isChatNarrativeInput = inputData.getBoolean(KEY_IS_CHAT_NARRATIVE, false)
        val voiceSpeaker1Input = inputData.getString(KEY_VOICE_SPEAKER_1)
        val voiceSpeaker2Input = inputData.getString(KEY_VOICE_SPEAKER_2)
        val voiceSpeaker3Input = inputData.getString(KEY_VOICE_SPEAKER_3)
        val voiceOverrideInput = inputData.getString(KEY_VOICE_OVERRIDE)

        var generatedAudioPath: String?
        var finalPromptUsed = promptToUseInput
        var overallSuccess = true
        var finalErrorMessage: String? = null

        try {
            audioDataStoreManager.setIsAudioProcessing(true)
            audioDataStoreManager.setIsChatNarrative(isChatNarrativeInput)
            updateNotificationProgress(appContext.getString(R.string.notification_content_audio_starting))
            updateWorkerProgress("Iniciando geração...", true)
            audioDataStoreManager.setGenerationError(null)

            val currentProjectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            var projectDirNameSanitized = currentProjectDirName.takeIf { it.isNotBlank() }
                ?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            if (projectDirNameSanitized.isNullOrBlank()) {
                projectDirNameSanitized = "DefaultAudioProject_${System.currentTimeMillis()}"
            }
            val baseDir = appContext.getExternalFilesDir(null)
                ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to appContext.getString(R.string.error_no_external_storage)))
            val projectDir = File(baseDir, projectDirNameSanitized)
            if (!projectDir.exists() && !projectDir.mkdirs()) {
                 return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to appContext.getString(R.string.error_failed_to_create_project_dir, projectDir.absolutePath)))
            }

            val tituloAtual = audioDataStoreManager.videoTitulo.first()
            val extrasAtuais = refImageDataStoreManager.refObjetoDetalhesJson.first()
            val imagensJsonAtual = videoDataStoreManager.imagensReferenciaJson.first()
            val userName = userInfoDataStoreManager.userNameCompany.first()
            val userProf = userInfoDataStoreManager.userProfessionSegment.first()
            val userAddr = userInfoDataStoreManager.userAddress.first()
            val userLangTone = audioDataStoreManager.userLanguageToneAudio.first()
            val userTarget = audioDataStoreManager.userTargetAudienceAudio.first()
            val videoObjectiveIntroduction = audioDataStoreManager.videoObjectiveIntroduction.first()
            val videoObjectiveVideo = audioDataStoreManager.videoObjectiveVideo.first()
            val videoObjectiveOutcome = audioDataStoreManager.videoObjectiveOutcome.first()
            val videoTimeSeconds = audioDataStoreManager.videoTimeSeconds.first()
            val imagePathsForSingleNarrator = try {
                if (imagensJsonAtual.isNotBlank() && imagensJsonAtual != "[]") kotlinJsonParser.decodeFromString(ListSerializer(ImagemReferencia.serializer()), imagensJsonAtual).map { it.path } else emptyList()
            } catch (e: Exception) { emptyList() }

            if (isNewNarrative) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_task_cancelled_before_prompt))
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_creating_text))
                updateWorkerProgress("Criando texto para a narrativa...", true)

                val promptBaseGemini = if (isChatNarrativeInput) {
                    CreateAudioNarrativeChat(
                        userName, userProf, userAddr, userLangTone, userTarget,
                        tituloAtual, videoObjectiveIntroduction, videoObjectiveVideo, videoObjectiveOutcome, videoTimeSeconds,
                        voiceSpeaker1Input ?: "", voiceSpeaker2Input ?: "", voiceSpeaker3Input
                    ).prompt
                } else {
                    CreateAudioNarrative().getFormattedPrompt(
                        userName, userProf, userAddr, userTarget, userLangTone,
                        tituloAtual, extrasAtuais, imagePathsForSingleNarrator.joinToString("; "),
                        videoObjectiveIntroduction, videoObjectiveVideo, videoObjectiveOutcome, videoTimeSeconds, "${audioDataStoreManager.voiceSpeaker1.first()}", "${audioDataStoreManager.sexo.first()}"
                    )
                }

                val contextFilePath = audioDataStoreManager.narrativeContextFilePath.first()
                var contextFileContent: String? = null
                if (isChatNarrativeInput && contextFilePath.isNotBlank()) {
                    try {
                        val uri = Uri.parse(contextFilePath)
                        appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                contextFileContent = reader.readText()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao ler arquivo de contexto ($contextFilePath). Erro: ${e.message}")
                    }
                }
                val arquivoTextoParaGemini = if (isChatNarrativeInput) {
                                                contextFileContent ?: audioDataStoreManager.prompt.first()
                                            } else {
                                                null
                                            }

                val refinedPromptResult = gerarPromptAudioWorker(promptBaseGemini, imagePathsForSingleNarrator, arquivoTextoParaGemini)

                if (refinedPromptResult is Result.Success) {
                    val geminiResponseFilePath = refinedPromptResult.outputData.getString(KEY_GENERATED_PROMPT_FILE_PATH) ?: ""
                    if (geminiResponseFilePath.isNotEmpty()) {
                        val geminiFullJsonResponse = withContext(Dispatchers.IO) {
                            File(geminiResponseFilePath).readText()
                        }
                        
                        try {
                            val respostaAjustada = ajustarRespostaGeminiAudioWorker(geminiFullJsonResponse)
                            if (respostaAjustada.isBlank()) throw JSONException("Ajuste da resposta resultou em string vazia.")
                            val rootJsonElementOrgJson = if (respostaAjustada.startsWith("[")) JSONArray(respostaAjustada).optJSONObject(0) ?: throw JSONException("Array JSON da IA está vazio.") else JSONObject(respostaAjustada)

                            if (isChatNarrativeInput) {
                                finalPromptUsed = rootJsonElementOrgJson.optString("dialogScript", "")
                                if (finalPromptUsed.isBlank()) throw JSONException("Campo 'dialogScript' não encontrado ou vazio.")
                            } else {
                                finalPromptUsed = rootJsonElementOrgJson.optString("promptAudio", "")
                                if (finalPromptUsed.isBlank()) throw JSONException("Campo 'promptAudio' não encontrado ou vazio.")
                                val vozesSubObject = rootJsonElementOrgJson.optJSONObject("vozes")
                                vozesSubObject?.let {
                                    audioDataStoreManager.setSexo(it.optString("sexo", "Female"))
                                    val idadeStr = it.optString("idade", "30")
                                    audioDataStoreManager.setIdade(idadeStr.split("-").firstOrNull()?.trim()?.toIntOrNull() ?: idadeStr.toIntOrNull() ?: 30)
                                    audioDataStoreManager.setEmocao(it.optString("emocao", "Neutro"))
                                }
                            }
                            audioDataStoreManager.setPrompt(finalPromptUsed)
                            updateNotificationProgress(appContext.getString(R.string.notification_content_audio_text_created))
                            updateWorkerProgress("Texto da narrativa criado!", true)
                        } catch (e: JSONException) {
                            finalErrorMessage = appContext.getString(R.string.error_processing_ai_json_response_critical, e.message?.take(50) ?: "Detalhe indisponível")
                            setGenerationErrorWorker(finalErrorMessage)
                            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                        } finally {
                            withContext(Dispatchers.IO) {
                                File(geminiResponseFilePath).delete()
                            }
                        }
                    } else {
                        finalErrorMessage = appContext.getString(R.string.error_ai_empty_prompt_response)
                        setGenerationErrorWorker(finalErrorMessage)
                        return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                    }
                } else {
                    finalErrorMessage = (refinedPromptResult as Result.Failure).outputData.getString(KEY_ERROR_MESSAGE) ?: appContext.getString(R.string.error_failed_to_generate_prompt)
                    return@coroutineScope refinedPromptResult
                }
            } else {
                finalPromptUsed = promptToUseInput
                 if (finalPromptUsed.isBlank()) {
                    finalErrorMessage = appContext.getString(R.string.error_empty_audio_prompt_provided)
                    setGenerationErrorWorker(finalErrorMessage)
                    return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                }
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_using_existing_text))
                updateWorkerProgress("Usando texto existente...", true)
            }

            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_task_cancelled_before_audio_generation))

            val audioResult: kotlin.Result<String>
            if (isChatNarrativeInput) {
                if (voiceSpeaker1Input.isNullOrBlank() || voiceSpeaker2Input.isNullOrBlank()) {
                    throw IllegalStateException("appContext.getString(R.string.error_chat_speaker1_or_2_voice_mandatory)")
                }
                val speakerMap = mutableMapOf("Personagem 1" to voiceSpeaker1Input, "Personagem 2" to voiceSpeaker2Input)
                if (!voiceSpeaker3Input.isNullOrBlank()) {
                    speakerMap["Personagem 3"] = voiceSpeaker3Input
                }
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generating_dialog))
                updateWorkerProgress("Gerando áudio do diálogo...", true)
                audioResult = GeminiMultiSpeakerAudio.generate(dialogText = finalPromptUsed, speakerVoiceMap = speakerMap, context = applicationContext, projectDir = projectDir)
     8       } else {
                val vozParaGerar = voiceOverrideInput ?: voiceSpeaker1Input ?: audioDataStoreManager.voz.first()
                if (vozParaGerar.isBlank()) {
                    throw IllegalStateException(appContext.getString(R.string.error_no_voice_selected_for_single_narrator))
                }
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generating_audio, vozParaGerar))
                updateWorkerProgress("Gerando áudio (narrador único)...", true)
                audioResult = GeminiAudio.generate(text = finalPromptUsed, voiceName = vozParaGerar, context = applicationContext, projectDir = projectDir)
            }

            if (audioResult.isSuccess) {
                generatedAudioPath = audioResult.getOrThrow()
                setAudioPathWorker(generatedAudioPath)
                saveRelatedAudioDataWorker(extrasAtuais, imagensJsonAtual, userName, userProf, userAddr, userLangTone, userTarget)
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generated_successfully))
                updateWorkerProgress("Áudio gerado!", true)

                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_task_cancelled_before_subtitle_generation))
                
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generating_subs))
                updateWorkerProgress("Gerando legenda...", true)
                val legendaResult = Audio.gerarLegendaSRT(
                    cena = File(generatedAudioPath!!).nameWithoutExtension, filePath = generatedAudioPath!!,
                    TextoFala = finalPromptUsed, context = applicationContext, projectDir = projectDir
                )
                
                if (legendaResult.isSuccess) {
                    val generatedLegendaPath = legendaResult.getOrThrow()
                    setLegendaPathWorker(generatedLegendaPath)
                    updateNotificationProgress(applicationContext.getString(R.string.notification_content_audio_subs_generated_successfully))
                    updateWorkerProgress("Legenda gerada.", true)
                } else {
                    finalErrorMessage = legendaResult.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_subtitle_generation_failure)
                    setGenerationErrorWorker(finalErrorMessage)
                    updateNotificationProgress(appContext.getString(R.string.notification_content_audio_subs_generation_failed, finalErrorMessage.take(30)), true)
                    overallSuccess = false
                }
            } else {
                finalErrorMessage = audioResult.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_audio_api_failure)
                setGenerationErrorWorker(finalErrorMessage)
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generation_failed, finalErrorMessage.take(30)), true)
                overallSuccess = false
            }

            if (overallSuccess) {
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_process_completed_successfully), true)
                return@coroutineScope Result.success(workDataOf(KEY_RESULT_PATH to projectDir.absolutePath))
            } else {
                val finalErrorMsgForNotification = finalErrorMessage ?: appContext.getString(R.string.error_general_unspecified_failure)
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_process_failed, finalErrorMsgForNotification.take(50)), true)
                return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMsgForNotification))
            }

        } catch (e: Exception) {
            val isCancellation = e is kotlinx.coroutines.CancellationException
            finalErrorMessage = if (isCancellation) (e.message ?: appContext.getString(R.string.error_audio_task_cancelled))
                                else appContext.getString(R.string.error_unexpected_worker_error, (e.message ?: e.javaClass.simpleName))
            setGenerationErrorWorker(finalErrorMessage)
            updateNotificationProgress(finalErrorMessage, true)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
        } finally {
            audioDataStoreManager.setIsAudioProcessing(false)
            val finalProgressText = if (overallSuccess && finalErrorMessage == null) appContext.getString(R.string.status_finished) else finalErrorMessage ?: appContext.getString(R.string.status_finished_with_error)
            updateWorkerProgress(finalProgressText, false)
        }
    }

    private suspend fun gerarPromptAudioWorker(
        promptDeEntradaParaGemini: String,
        imagensPatch: List<String>,
        conteudoAdicionalOuCaminhoArquivo: String?
    ): Result = withContext(Dispatchers.IO) {
        if (promptDeEntradaParaGemini.isBlank()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to appContext.getString(R.string.error_empty_prompt_for_ai)))
        }

        val respostaResult = try {
            GeminiTextAndVisionProRestApi.perguntarAoGemini(
                pergunta = promptDeEntradaParaGemini,
                imagens = imagensPatch,
                arquivoTexto = conteudoAdicionalOuCaminhoArquivo
            )
        } catch (e: Exception) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to appContext.getString(R.string.error_generating_prompt_with_error, (e.message ?: e.javaClass.simpleName))))
        }
        
        if (respostaResult.isSuccess) {
            val respostaBruta = respostaResult.getOrNull() ?: ""
            if (respostaBruta.isBlank()) {
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to appContext.getString(R.string.error_ai_empty_response)))
            }
            try {
                val tempFile = File.createTempFile("gemini_prompt_${UUID.randomUUID()}", ".json", appContext.cacheDir)
                tempFile.writeText(respostaBruta)
                return@withContext Result.success(workDataOf(KEY_GENERATED_PROMPT_FILE_PATH to tempFile.absolutePath))
            } catch (e: IOException) {
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Erro ao salvar prompt temporário: ${e.message}"))
            }

        } else {
            val errorMsgFromApi = respostaResult.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_api_failure)
            val finalErrorMsg = appContext.getString(R.string.error_gemini_api_failure_with_message, errorMsgFromApi)
            setGenerationErrorWorker(finalErrorMsg)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMsg))
        }
    }

    private fun ajustarRespostaGeminiAudioWorker(resposta: String): String {
        var respostaLimpa = resposta.trim()
        if (respostaLimpa.startsWith("```json", ignoreCase = true)) {
            respostaLimpa = respostaLimpa.removePrefix("```json").trimStart()
        } else if (respostaLimpa.startsWith("```")) {
            respostaLimpa = respostaLimpa.removePrefix("```").trimStart()
        }
        if (respostaLimpa.endsWith("```")) {
            respostaLimpa = respostaLimpa.removeSuffix("```").trimEnd()
        }
        val primeiroColchete = respostaLimpa.indexOfFirst { it == '[' || it == '{' }
        val fimJson = respostaLimpa.indexOfLast { it == ']' || it == '}' }

        if (primeiroColchete != -1 && fimJson != -1 && fimJson >= primeiroColchete) {
            val jsonSubstring = respostaLimpa.substring(primeiroColchete, fimJson + 1)
            try {
                when {
                     jsonSubstring.trimStart().startsWith('[') -> JSONArray(jsonSubstring)
                     jsonSubstring.trimStart().startsWith('{') -> JSONObject(jsonSubstring)
                    else -> Log.w(TAG, "Substring não é claramente um JSON Array ou Object: $jsonSubstring")
                }
                return jsonSubstring
            } catch (e: JSONException){
                 Log.w(TAG, "Substring extraída não é JSON válido: '$jsonSubstring'. Erro: ${e.message}. Retornando resposta limpa de markdown.")
                 return respostaLimpa
            }
        } else {
             Log.w(TAG, "Falha ao isolar JSON, retornando resposta limpa de markdown: $respostaLimpa")
            return respostaLimpa
        }
    }

    private suspend fun updateWorkerProgress(text: String, isProcessing: Boolean) {
        audioDataStoreManager.setGenerationProgressText(text)
        audioDataStoreManager.setIsAudioProcessing(isProcessing)
        val progressData = workDataOf(KEY_PROGRESS_TEXT to text, KEY_IS_PROCESSING to isProcessing)
        try { setProgress(progressData) }
        catch (e: IllegalStateException) { Log.e(TAG, "Erro setProgress: ${e.message}", e) }
        catch (e: Exception) { Log.e(TAG, "Erro setProgress: ${e.message}", e) }
    }

    private suspend fun setAudioPathWorker(novoPath: String) {
        audioDataStoreManager.setAudioPath(novoPath)
    }

    private suspend fun setLegendaPathWorker(novoPath: String) {
        audioDataStoreManager.setLegendaPath(novoPath)
    }

    private suspend fun setGenerationErrorWorker(errorMsg: String?) {
        audioDataStoreManager.setGenerationError(errorMsg)
    }

    private suspend fun saveRelatedAudioDataWorker(
        videoExtras: String,
        videoImagensJson: String,
        userNameCompany: String,
        userProfessionSegment: String,
        userAddress: String,
        userLanguageTone: String,
        userTargetAudience: String
    ) {
        audioDataStoreManager.setVideoExtrasAudio(videoExtras)
        audioDataStoreManager.setVideoImagensReferenciaJsonAudio(videoImagensJson)
        audioDataStoreManager.setUserNameCompanyAudio(userNameCompany)
        audioDataStoreManager.setUserProfessionSegmentAudio(userProfessionSegment)
        audioDataStoreManager.setUserAddressAudio(userAddress)
        audioDataStoreManager.setUserLanguageToneAudio(userLanguageTone)
        audioDataStoreManager.setUserTargetAudienceAudio(userTargetAudience)
    }
}