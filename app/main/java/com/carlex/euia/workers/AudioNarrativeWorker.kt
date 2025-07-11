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
import com.carlex.euia.api.GeminiTextAndVisionProApi
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
import java.io.IOException
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.BufferedReader
import java.io.InputStreamReader

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
const val KEY_GENERATED_PROMPT = "generatedPrompt"
const val KEY_ERROR_MESSAGE = "errorMessage"
const val KEY_RESULT_PATH = "resultPath"

private const val NOTIFICATION_ID_AUDIO = 2
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

        createNotificationChannel()
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.notification_channel_name_audio)
            val descriptionText = appContext.getString(R.string.notification_channel_description_audio)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_AUDIO, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
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
            Log.d(TAG, "Estado inicial salvo. IsChatNarrative: $isChatNarrativeInput")

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
            Log.d(TAG, "Diretório do projeto final: ${projectDir.absolutePath}")

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
                        videoObjectiveIntroduction, videoObjectiveVideo, videoObjectiveOutcome, videoTimeSeconds
                    )
                }

                val contextFilePath = audioDataStoreManager.narrativeContextFilePath.first()
                var contextFileContent: String? = null
                if (isChatNarrativeInput && contextFilePath.isNotBlank()) {
                    Log.d(TAG, "Tentando ler arquivo de contexto para chat: $contextFilePath")
                    try {
                        val uri = Uri.parse(contextFilePath)
                        appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                contextFileContent = reader.readText()
                            }
                        }
                        if (!contextFileContent.isNullOrBlank()) {
                            Log.i(TAG, "Conteúdo do arquivo de contexto lido com sucesso (length: ${contextFileContent?.length}).")
                        } else {
                            Log.w(TAG, "Arquivo de contexto ($contextFilePath) está vazio ou não pôde ser lido. Usando prompt principal como fallback.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao ler arquivo de contexto ($contextFilePath). Usando prompt principal. Erro: ${e.message}")
                    }
                }
                // Se contextFileContent for nulo ou vazio, E for chat, usa o prompt principal do DataStore.
                // Se não for chat, arquivoTextoParaGemini continua null.
                val arquivoTextoParaGemini = if (isChatNarrativeInput) {
                                                contextFileContent ?: audioDataStoreManager.prompt.first()
                                            } else {
                                                null
                                            }


                val refinedPromptResult = gerarPromptAudioWorker(promptBaseGemini, imagePathsForSingleNarrator, arquivoTextoParaGemini)

                if (refinedPromptResult is Result.Success) {
                    val geminiFullJsonResponse = refinedPromptResult.outputData.getString(KEY_GENERATED_PROMPT) ?: ""
                    Log.d(TAG, ">>> VALOR EXATO DE geminiFullJsonResponse ANTES DO PARSE:\n'$geminiFullJsonResponse'")

                    if (geminiFullJsonResponse.isNotEmpty()) {
                        try {
                            var jsonResponseToParse = geminiFullJsonResponse
                            if (jsonResponseToParse.startsWith("\uFEFF")) {
                                jsonResponseToParse = jsonResponseToParse.substring(1)
                            }
                            val trimmedResponse = jsonResponseToParse.trimStart()
                            val rootJsonElementOrgJson: org.json.JSONObject

                            if (trimmedResponse.startsWith("[")) {
                                val jsonArray = org.json.JSONArray(jsonResponseToParse)
                                rootJsonElementOrgJson = if (jsonArray.length() > 0) jsonArray.getJSONObject(0)
                                                         else throw JSONException("Array JSON (org.json) da IA está vazio.")
                            } else if (trimmedResponse.startsWith("{")) {
                                rootJsonElementOrgJson = org.json.JSONObject(jsonResponseToParse)
                            } else {
                                throw JSONException("Resposta da IA não é JSON Array nem Object. Conteúdo: ${jsonResponseToParse.take(100)}")
                            }

                            if (isChatNarrativeInput) {
                                finalPromptUsed = rootJsonElementOrgJson.optString("dialogScript", "")
                                if (finalPromptUsed.isBlank()) throw JSONException("Campo 'dialogScript' não encontrado ou vazio (org.json).")
                                Log.d(TAG, "Script de diálogo (org.json): '${finalPromptUsed.take(100)}...'")
                                if (rootJsonElementOrgJson.has("speakerVoiceSuggestions")) {
                                     val suggestions = rootJsonElementOrgJson.optJSONArray("speakerVoiceSuggestions")
                                     Log.d(TAG, "Sugestões de voz para chat (org.json): $suggestions")
                                } else {
                                     Log.w(TAG, "Campo 'speakerVoiceSuggestions' não encontrado no JSON do chat (org.json).")
                                }
                            } else {
                                finalPromptUsed = rootJsonElementOrgJson.optString("promptAudio", "")
                                if (finalPromptUsed.isBlank()) throw JSONException("Campo 'promptAudio' não encontrado ou vazio (org.json).")
                                Log.d(TAG, "Prompt narrador único (org.json): '${finalPromptUsed.take(100)}...'")

                                val vozesSubObject = rootJsonElementOrgJson.optJSONObject("vozes")
                                if (vozesSubObject != null) {
                                    audioDataStoreManager.setSexo(vozesSubObject.optString("sexo", "Female"))
                                    val idadeStr = vozesSubObject.optString("idade", "30")
                                    audioDataStoreManager.setIdade(idadeStr.split("-").firstOrNull()?.trim()?.toIntOrNull() ?: idadeStr.toIntOrNull() ?: 30)
                                    audioDataStoreManager.setEmocao(vozesSubObject.optString("emocao", "Neutro"))
                                    Log.d(TAG, "Características (single) de 'vozes' (org.json) salvas.")
                                } else {
                                    Log.w(TAG, "Sub-objeto 'vozes' não encontrado no JSON para narrador único (org.json). Usando defaults.")
                                    audioDataStoreManager.setSexo("Female")
                                    audioDataStoreManager.setIdade(30)
                                    audioDataStoreManager.setEmocao("Neutro")
                                }
                            }
                            audioDataStoreManager.setPrompt(finalPromptUsed)
                            updateNotificationProgress(appContext.getString(R.string.notification_content_audio_text_created))
                            updateWorkerProgress("Texto da narrativa criado!", true)

                        } catch (eOrgJson: JSONException) {
                            Log.w(TAG, "Falha ao parsear com org.json ('${eOrgJson.message}'). Tentando com kotlinx.serialization. JSON:\n$geminiFullJsonResponse")
                            try {
                                var jsonResponseToParseKt = geminiFullJsonResponse
                                if (jsonResponseToParseKt.startsWith("\uFEFF")) {
                                     jsonResponseToParseKt = jsonResponseToParseKt.substring(1)
                                }
                                val ktJsonElement = kotlinJsonParser.parseToJsonElement(jsonResponseToParseKt)
                                val rootJsonObjectKt: kotlinx.serialization.json.JsonObject

                                if (ktJsonElement is KtJsonArray) {
                                    rootJsonObjectKt = if (ktJsonElement.isNotEmpty()) ktJsonElement.first().jsonObject
                                                      else throw JSONException("Array JSON (kotlinx) da IA está vazio.")
                                } else if (ktJsonElement is KtJsonObject) {
                                    rootJsonObjectKt = ktJsonElement.jsonObject
                                } else {
                                    throw JSONException("Resposta da IA não é JsonArray nem JsonObject (kotlinx).")
                                }

                                if (isChatNarrativeInput) {
                                    finalPromptUsed = rootJsonObjectKt["dialogScript"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (finalPromptUsed.isBlank()) throw JSONException("Campo 'dialogScript' não encontrado ou vazio (kotlinx).")
                                    Log.d(TAG, "Script de diálogo (kotlinx): '${finalPromptUsed.take(100)}...'")
                                } else {
                                    finalPromptUsed = rootJsonObjectKt["promptAudio"]?.jsonPrimitive?.contentOrNull ?: ""
                                    if (finalPromptUsed.isBlank()) throw JSONException("Campo 'promptAudio' não encontrado ou vazio (kotlinx).")
                                    Log.d(TAG, "Prompt narrador único (kotlinx): '${finalPromptUsed.take(100)}...'")

                                    val vozesSubObjectKt = rootJsonObjectKt["vozes"]?.jsonObject
                                    if (vozesSubObjectKt != null) {
                                        audioDataStoreManager.setSexo(vozesSubObjectKt["sexo"]?.jsonPrimitive?.content ?: "Female")
                                        val idadeStr = vozesSubObjectKt["idade"]?.jsonPrimitive?.content ?: "30"
                                        audioDataStoreManager.setIdade(idadeStr.split("-").firstOrNull()?.trim()?.toIntOrNull() ?: idadeStr.toIntOrNull() ?: 30)
                                        audioDataStoreManager.setEmocao(vozesSubObjectKt["emocao"]?.jsonPrimitive?.content ?: "Neutro")
                                        Log.d(TAG, "Características (single) de 'vozes' (kotlinx) salvas.")
                                    } else {
                                         Log.w(TAG, "Sub-objeto 'vozes' não encontrado no JSON para narrador único (kotlinx). Usando defaults.")
                                        audioDataStoreManager.setSexo("Female")
                                        audioDataStoreManager.setIdade(30)
                                        audioDataStoreManager.setEmocao("Neutro")
                                    }
                                }
                                audioDataStoreManager.setPrompt(finalPromptUsed)
                                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_text_created))
                                updateWorkerProgress("Texto da narrativa criado!", true)

                            } catch (eKt: Exception) {
                                Log.e(TAG, "Erro CRÍTICO ao parsear JSON (ambos org.json e kotlinx.serialization falharam). JSON:\n$geminiFullJsonResponse\nErro kotlinx: ${eKt.message}", eKt)
                                finalErrorMessage = appContext.getString(R.string.error_processing_ai_json_response_critical, eKt.message?.take(50) ?: "Detalhe indisponível")
                                setGenerationErrorWorker(finalErrorMessage)
                                return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                            }
                        }
                    } else {
                        finalErrorMessage = appContext.getString(R.string.error_ai_empty_prompt_response)
                        setGenerationErrorWorker(finalErrorMessage)
                        return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                    }
                } else {
                    finalErrorMessage = (refinedPromptResult as Result.Failure).outputData.getString(KEY_ERROR_MESSAGE)
                        ?: appContext.getString(R.string.error_failed_to_generate_prompt)
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
                Log.d(TAG, "Gerando áudio de CHAT. S1: '$voiceSpeaker1Input', S2: '$voiceSpeaker2Input', S3: '$voiceSpeaker3Input'")
                if (voiceSpeaker1Input.isNullOrBlank()) { // Speaker 1 é sempre necessário para chat
                    finalErrorMessage = appContext.getString(R.string.error_chat_speaker1_voice_mandatory)
                    setGenerationErrorWorker(finalErrorMessage)
                    return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                }
                 if (voiceSpeaker2Input.isNullOrBlank()) { // Speaker 2 também é necessário para um diálogo mínimo
                    finalErrorMessage = appContext.getString(R.string.error_chat_speaker2_voice_mandatory)
                    setGenerationErrorWorker(finalErrorMessage)
                    return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                }
                val speakerMap = mutableMapOf("Personagem 1" to voiceSpeaker1Input, "Personagem 2" to voiceSpeaker2Input)
                if (!voiceSpeaker3Input.isNullOrBlank()) {
                    speakerMap["Personagem 3"] = voiceSpeaker3Input
                }
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generating_dialog))
                updateWorkerProgress("Gerando áudio do diálogo...", true)
                audioResult = GeminiMultiSpeakerAudio.generate(
                    dialogText = finalPromptUsed,
                    speakerVoiceMap = speakerMap,
                    context = applicationContext,
                    projectDir = projectDir
                )
            } else {
                val vozParaGerar = voiceOverrideInput ?: voiceSpeaker1Input ?: audioDataStoreManager.voz.first()
                if (vozParaGerar.isBlank()) {
                    finalErrorMessage = appContext.getString(R.string.error_no_voice_selected_for_single_narrator)
                    setGenerationErrorWorker(finalErrorMessage)
                    return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
                }
                Log.d(TAG, "Gerando áudio de NARRADOR ÚNICO. Voz: '$vozParaGerar'")
                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generating_audio, vozParaGerar))
                updateWorkerProgress("Gerando áudio (narrador único)...", true)
                audioResult = GeminiAudio.generate(
                    text = finalPromptUsed,
                    voiceName = vozParaGerar,
                    context = applicationContext,
                    projectDir = projectDir
                )
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
                    cena = File(generatedAudioPath!!).nameWithoutExtension,
                    filePath = generatedAudioPath!!,
                    TextoFala = finalPromptUsed,
                    context = applicationContext,
                    projectDir = projectDir
                )
                
                if (legendaResult.isSuccess) {
                    var generatedLegendaPath = legendaResult.getOrThrow()
                    setLegendaPathWorker(generatedLegendaPath!!)
                    updateNotificationProgress(applicationContext.getString(R.string.notification_content_audio_subs_generated_successfully))
                    updateWorkerProgress("Legenda original gerada.", true)

                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_task_cancelled_before_subtitle_correction))
                    
                    Log.d(TAG, "Iniciando correção da legenda...")
                    updateNotificationProgress(appContext.getString(R.string.notification_content_audio_correcting_subs))
                    updateWorkerProgress("Corrigindo legenda...", true)
                    val srtContentOriginal = File(generatedLegendaPath!!).readText()
                    val promptCorrecaoLegenda = """
                        Você é um revisor de legendas profissional.
                        A seguir está o conteúdo de um arquivo de legenda no formato SRT e o prompt da narrativa original.
                        Sua tarefa é corrigir APENAS os erros gramaticais e ortográficos no TEXTO de cada entrada da legenda.
                        NÃO altere os números de sequência.
                        NÃO altere os timestamps (códigos de tempo).
                        NÃO altere a formatação de quebra de linha dentro de uma entrada de legenda.
                        Mantenha a estrutura e o formato EXATAMENTE como no original.
                        Retorne APENAS o conteúdo do arquivo corrigido. sem incluir ``` exiba so a resposta final formato texto

                        Contexto da Narrativa Original:
                        $finalPromptUsed
                        
                        Conteúdo SRT Original para correção:
                        $srtContentOriginal
                    """.trimIndent()

                    val correcaoResult = GeminiTextAndVisionStandardApi.perguntarAoGemini(
                        pergunta = promptCorrecaoLegenda,
                        imagens = emptyList(),
                        arquivoTexto = null
                    )

                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException(appContext.getString(R.string.error_task_cancelled_during_subtitle_correction))

                    if (correcaoResult.isSuccess) {
                        val srtCorrigido = correcaoResult.getOrNull()
                        if (!srtCorrigido.isNullOrBlank()) {
                            val nomeBaseLegendaOriginal = File(generatedLegendaPath!!).nameWithoutExtension
                            val arquivoLegendaCorrigida = File(projectDir, "${nomeBaseLegendaOriginal}_corrigido.txt")
                            try {
                                arquivoLegendaCorrigida.writeText(srtCorrigido)
                                Log.i(TAG, "Legenda corrigida salva em: ${arquivoLegendaCorrigida.absolutePath}")
                                updateNotificationProgress(appContext.getString(R.string.notification_content_audio_subs_corrected))
                                updateWorkerProgress("Legenda corrigida.", true)
                                setLegendaPathWorker(arquivoLegendaCorrigida.absolutePath!!)
                            } catch (ioe: IOException) {
                                Log.e(TAG, "Falha ao salvar legenda corrigida: ${ioe.message}", ioe)
                            }
                        } else {
                            Log.w(TAG, "Gemini retornou legenda corrigida vazia.")
                        }
                    } else {
                        Log.w(TAG, "Falha ao corrigir legenda com Gemini: ${correcaoResult.exceptionOrNull()?.message}")
                    }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "[!] Trabalho de áudio cancelado: ${e.message}", e)
            finalErrorMessage = e.message ?: appContext.getString(R.string.error_audio_task_cancelled)
            updateNotificationProgress(appContext.getString(R.string.notification_content_audio_generation_cancelled), true)
            setGenerationErrorWorker(finalErrorMessage)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
        } catch (e: Exception) {
            Log.e(TAG, "[!] Erro inesperado no processo de geração de áudio (Worker)", e)
            finalErrorMessage = appContext.getString(R.string.error_unexpected_worker_error, (e.message ?: e.javaClass.simpleName))
            setGenerationErrorWorker(finalErrorMessage)
            updateNotificationProgress(appContext.getString(R.string.notification_content_audio_unexpected_error), true)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMessage))
        } finally {
            Log.d(TAG, "doWork() Bloco Finally. Sucesso Geral: $overallSuccess. Erro Final: $finalErrorMessage")
            audioDataStoreManager.setIsAudioProcessing(false)
            val finalProgressText = if (overallSuccess && finalErrorMessage == null) {
                appContext.getString(R.string.status_finished)
            } else {
                finalErrorMessage ?: appContext.getString(R.string.status_finished_with_error)
            }
            updateWorkerProgress(finalProgressText, false)
            Log.d(TAG, "Estado final salvo no DataStore (isAudioProcessing=false).")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun gerarPromptAudioWorker(
        promptDeEntradaParaGemini: String,
        imagensPatch: List<String>,
        conteudoAdicionalOuCaminhoArquivo: String?
    ): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker - gerarPromptAudioWorker: Iniciado.")
        if (promptDeEntradaParaGemini.isBlank()) {
            Log.e(TAG, "Worker - gerarPromptAudioWorker: Prompt de entrada para Gemini está vazio.")
            val errorMsg = appContext.getString(R.string.error_empty_prompt_for_ai)
            setGenerationErrorWorker(errorMsg)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }
        Log.d(TAG, "Worker - gerarPromptAudioWorker: Prompt para Gemini (início): '${promptDeEntradaParaGemini.take(100)}...'")
        Log.d(TAG, "Worker - gerarPromptAudioWorker: Imagens: ${imagensPatch.size}, Conteúdo Adicional/Caminho: ${!conteudoAdicionalOuCaminhoArquivo.isNullOrBlank()}")

        val respostaResult = try {
            GeminiTextAndVisionProApi.perguntarAoGemini(
                pergunta = promptDeEntradaParaGemini,
                imagens = imagensPatch,
                arquivoTexto = conteudoAdicionalOuCaminhoArquivo
            )
        } catch (e: Exception) {
            Log.e(TAG, "Worker - gerarPromptAudioWorker: Erro não esperado na chamada Gemini: ${e.message}", e)
            val errorMsg = appContext.getString(R.string.error_generating_prompt_with_error, (e.message ?: e.javaClass.simpleName))
            setGenerationErrorWorker(errorMsg)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }

        if (respostaResult.isSuccess) {
            val respostaBruta = respostaResult.getOrNull() ?: ""
            Log.d(TAG, "Worker - gerarPromptAudioWorker: Resposta bruta Gemini: ${respostaBruta.take(300)}")
            if (respostaBruta.isBlank()) {
                Log.e(TAG, "Worker - gerarPromptAudioWorker: Resposta do Gemini em branco.")
                val errorMsg = appContext.getString(R.string.error_ai_empty_response)
                setGenerationErrorWorker(errorMsg)
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }
            val respostaAjustada = ajustarRespostaGeminiAudioWorker(respostaBruta)
            Log.d(TAG, "Worker - gerarPromptAudioWorker: Resposta Gemini ajustada: '${respostaAjustada.take(300)}'")
            try {
                var jsonResponseToValidate = respostaAjustada
                if (jsonResponseToValidate.startsWith("\uFEFF")) {
                     jsonResponseToValidate = jsonResponseToValidate.substring(1)
                }
                val trimmedResponseForValidation = jsonResponseToValidate.trimStart()
                if (trimmedResponseForValidation.startsWith("[")) {
                    org.json.JSONArray(jsonResponseToValidate)
                } else if (trimmedResponseForValidation.startsWith("{")) {
                    org.json.JSONObject(jsonResponseToValidate)
                } else {
                    throw JSONException("Resposta ajustada não é JSON Array nem Object: ${jsonResponseToValidate.take(100)}")
                }
                val outputData = workDataOf(KEY_GENERATED_PROMPT to respostaAjustada)
                Log.d(TAG, "Worker - gerarPromptAudioWorker: Sucesso, retornando JSON ajustado.")
                return@withContext Result.success(outputData)
            } catch (e: JSONException) {
                Log.e(TAG, "Worker - gerarPromptAudioWorker: Falha ao validar/parsear JSON (ajustada: '${respostaAjustada.take(100)}'). Erro: ${e.message}", e)
                val errorMsg = appContext.getString(R.string.error_processing_ai_json_response)
                setGenerationErrorWorker(errorMsg)
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }
        } else {
            val errorMsgFromApi = respostaResult.exceptionOrNull()?.message ?: appContext.getString(R.string.error_unknown_gemini_api_failure)
            Log.e(TAG, "Worker - gerarPromptAudioWorker: Falha na API Gemini: $errorMsgFromApi", respostaResult.exceptionOrNull())
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
            }
        }
        return respostaLimpa
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

    // Strings XML que podem ser necessárias:
    // <string name="error_no_external_storage">Armazenamento externo não disponível.</string>
    // <string name="error_failed_to_create_project_dir">Falha ao criar diretório do projeto: %s</string>
    // <string name="error_empty_prompt_for_ai">O prompt de instrução para a IA não pode estar vazio.</string>
    // <string name="error_task_cancelled_before_subtitle_correction">Tarefa cancelada antes da correção da legenda.</string>
    // <string name="error_task_cancelled_during_subtitle_correction">Tarefa cancelada durante a correção da legenda.</string>
    // <string name="notification_content_audio_correcting_subs">Corrigindo texto da legenda...</string>
    // <string name="notification_content_audio_subs_corrected">Legenda corrigida.</string>
    // <string name="error_processing_ai_json_response_critical">Erro crítico ao processar JSON da IA: %s</string>
    // <string name="error_chat_speaker1_voice_mandatory">Voz do Personagem 1 é obrigatória para diálogo.</string>
    // <string name="error_chat_speaker2_voice_mandatory">Voz do Personagem 2 é obrigatória para diálogo.</string>
    // <string name="error_narrator_voice_not_selected_internal">Voz do narrador não selecionada (interno).</string>

}