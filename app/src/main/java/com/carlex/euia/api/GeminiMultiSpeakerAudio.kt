// File: euia/api/GeminiMultiSpeakerAudio.kt
package com.carlex.euia.api

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object GeminiMultiSpeakerAudio {
    private const val TAG = "GeminiMultiSpeakerAudio"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MODEL_ID = "gemini-2.5-flash-preview-tts" // Usando um modelo que aceita texto e pode gerar áudio
    private const val GENERATE_CONTENT_API = "streamGenerateContent"
    private const val TIPO_DE_CHAVE = "audio"

    // Instanciação interna das dependências
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    private val client = OkHttpClient.Builder()
        .connectTimeout(360, TimeUnit.SECONDS)
        .readTimeout(380, TimeUnit.SECONDS)
        .writeTimeout(360, TimeUnit.SECONDS)
        .build()

    private val kotlinJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // --- DATA CLASSES COMPLETAS ---

    @Serializable
    private data class MultiSpeakerAudioRequest(
        val contents: List<MultiSpeakerContent>,
        @SerialName("generationConfig") val generationConfig: MultiSpeakerGenerationConfig
    )

    @Serializable
    private data class MultiSpeakerContent(
        val role: String = "user",
        val parts: List<MultiSpeakerTextPart>
    )

    @Serializable
    private data class MultiSpeakerTextPart(
        val text: String
    )

    @Serializable
    private data class MultiSpeakerGenerationConfig(
        val responseModalities: List<String> = listOf("audio"),
        val temperature: Float? = 2.0f,
        @SerialName("speech_config") val speechConfig: SpeechConfig
    )

    @Serializable
    private data class SpeechConfig(
        @SerialName("multi_speaker_voice_config") val multiSpeakerVoiceConfig: MultiSpeakerVoiceConfig
    )

    @Serializable
    private data class MultiSpeakerVoiceConfig(
        @SerialName("speaker_voice_configs") val speakerVoiceConfigs: List<SpeakerVoiceConfigEntry>
    )

    @Serializable
    private data class SpeakerVoiceConfigEntry(
        val speaker: String,
        @SerialName("voice_config") val voiceConfig: PrebuiltVoiceContainer
    )

    @Serializable
    private data class PrebuiltVoiceContainer(
        @SerialName("prebuilt_voice_config") val prebuiltVoiceConfig: PrebuiltVoice
    )

    @Serializable
    private data class PrebuiltVoice(
        @SerialName("voice_name") val voiceName: String
    )
    
    // --- FUNÇÃO PRINCIPAL ---

    suspend fun generate(
        dialogText: String,
        speakerVoiceMap: Map<String, String>,
        context: Context,
        projectDir: File
    ): Result<String> {
        val authViewModel = AuthViewModel(context.applicationContext as Application)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                // ETAPA 1: VERIFICAR E DEDUZIR CRÉDITOS
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.AUDIO_MULTI)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.AUDIO_MULTI.cost}) deduzidos. Prosseguindo com a geração do diálogo.")

                // ETAPA 2: LÓGICA DE GERAÇÃO
                if (speakerVoiceMap.isEmpty()) {
                    throw IllegalArgumentException("O mapa de vozes dos locutores não pode estar vazio.")
                }

                val keyCount = try {
                    firestore.collection("chaves_api_pool").get().await().size()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao obter contagem de chaves, usando fallback 10.", e)
                    10
                }
                val MAX_TENTATIVAS = if (keyCount > 0) keyCount else 10

                var tentativas = 0
                while (tentativas < MAX_TENTATIVAS) {
                    var chaveAtual: String? = null
                    try {
                        chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                        Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS ($TIPO_DE_CHAVE): Usando chave '${chaveAtual.takeLast(4)}'")

                        val requestPayload = buildRequestPayload(dialogText, speakerVoiceMap)
                        val requestJsonString = kotlinJsonParser.encodeToString(requestPayload)
                        val requestBody = requestJsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                        
                        val url = "$BASE_URL$MODEL_ID:$GENERATE_CONTENT_API?key=$chaveAtual"
                        val request = Request.Builder().url(url).post(requestBody).build()
                        
                        Log.i(TAG, "==== DEBUG GeminiMultiSpeakerAudio ====")
                        Log.i(TAG, "dialogText enviado:\n$dialogText")
                        Log.i(TAG, "speakerVoiceMap enviado: $speakerVoiceMap")
                        Log.i(TAG, "requestPayload serializado:\n$requestJsonString")


                        val response: Response = client.newCall(request).execute()

                        if (response.isSuccessful) {
                            Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} ($TIPO_DE_CHAVE) com a chave '${chaveAtual.takeLast(4)}'.")
                            gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                            
                            return@withContext processSuccessfulResponse(response, projectDir, context, dialogText)
                        } else if (response.code == 429) {
                            Log.w(TAG, "Erro 429 ($TIPO_DE_CHAVE) na chave '${chaveAtual.takeLast(4)}'. Bloqueando...")
                            response.body?.close()
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            tentativas++
                            if (tentativas < MAX_TENTATIVAS) {
                                delay(1000)
                                continue
                            } else {
                                throw Exception("Máx. de tentativas ($MAX_TENTATIVAS) atingido.")
                            }
                        } else {
                            val errorBody = response.body?.string() ?: "Erro desconhecido"
                            response.body?.close()
                            Log.e(TAG, "Erro de servidor não-retentável ($TIPO_DE_CHAVE), Código: ${response.code}, Corpo: $errorBody")
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            throw IOException("Erro da API (${response.code}): $errorBody")
                        }

                    } catch (e: NenhumaChaveApiDisponivelException) {
                        Log.e(TAG, "Não há chaves disponíveis para o tipo '$TIPO_DE_CHAVE'.", e)
                        throw e
                    }
                } // Fim do while

                throw Exception("Falha ao gerar áudio para o tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas.")
            
            } catch(e: Exception) {
                // ETAPA 3: REEMBOLSO EM CASO DE QUALQUER FALHA
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro durante a geração do áudio multi-speaker. Reembolsando ${TaskType.AUDIO_MULTI.cost} créditos.", e)
                    authViewModel.refundCredits(TaskType.AUDIO_MULTI)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    // --- FUNÇÕES AUXILIARES ---

    private fun buildRequestPayload(dialogText: String, speakerVoiceMap: Map<String, String>): MultiSpeakerAudioRequest {
        val speakerVoiceConfigEntries = speakerVoiceMap.map { (speakerTag, voiceName) ->
            SpeakerVoiceConfigEntry(
                speaker = speakerTag,
                voiceConfig = PrebuiltVoiceContainer(PrebuiltVoice(voiceName))
            )
        }
        return MultiSpeakerAudioRequest(
            contents = listOf(MultiSpeakerContent(parts = listOf(MultiSpeakerTextPart(text = dialogText)))),
            generationConfig = MultiSpeakerGenerationConfig(
                speechConfig = SpeechConfig(
                    multiSpeakerVoiceConfig = MultiSpeakerVoiceConfig(speakerVoiceConfigs = speakerVoiceConfigEntries)
                )
            )
        )
    }

    private suspend fun processSuccessfulResponse(response: Response, projectDir: File, context: Context, dialogText: String): Result<String> {
        val responseBodyString = response.body?.string()
        if (responseBodyString.isNullOrBlank()) {
            return Result.failure(IOException("Corpo da resposta de áudio (Multi-Speaker) está vazio."))
        }

        var audioDataBase64: String? = null
        var mimeType: String? = null
        try {
            val jsonArray = JSONArray(responseBodyString)
            if (jsonArray.length() > 0) {
                val firstCandidatePart = jsonArray.optJSONObject(0)
                    ?.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                audioDataBase64 = firstCandidatePart?.optJSONObject("inlineData")?.optString("data")
                mimeType = firstCandidatePart?.optJSONObject("inlineData")?.optString("mimeType")
            }
        } catch (e: JSONException) {
             try {
                val jsonObject = JSONObject(responseBodyString)
                 val firstCandidatePart = jsonObject
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                audioDataBase64 = firstCandidatePart?.optJSONObject("inlineData")?.optString("data")
                mimeType = firstCandidatePart?.optJSONObject("inlineData")?.optString("mimeType")
            } catch (e2: JSONException) {
                return Result.failure(JSONException("Falha ao parsear JSON (nem array, nem objeto): ${e2.message}"))
            }
        }

        if (audioDataBase64.isNullOrBlank()) {
            return Result.failure(IOException("Dados de áudio Base64 não encontrados na resposta."))
        }
        
        val finalWavPath = saveAndProcessAudio(audioDataBase64, mimeType, projectDir)
            .getOrElse { return Result.failure(it) }
        
        Audio.gerarLegendaSRT(
            cena = File(finalWavPath).nameWithoutExtension,
            filePath = finalWavPath,
            TextoFala = dialogText,
            context = context,
            projectDir = projectDir
        ).onFailure { Log.w(TAG, "Falha ao gerar legenda SRT para áudio multi-speaker: ${it.message}") }

        return Result.success(finalWavPath)
    }

    private suspend fun saveAndProcessAudio(audioBase64: String, mimeType: String?, projectDir: File): kotlin.Result<String> {
        val audioBytes = try { Base64.decode(audioBase64, Base64.DEFAULT) } catch (e: Exception) { return kotlin.Result.failure(e) }
        
        val effectiveMimeType = mimeType ?: "audio/L16;codec=pcm;rate=24000"

        if (effectiveMimeType.startsWith("audio/wav", ignoreCase = true)) {
            val file = File(projectDir, "gemini_multispeaker_tts_${System.currentTimeMillis()}.wav")
            FileOutputStream(file).use { it.write(audioBytes) }
            return kotlin.Result.success(file.absolutePath)
        } else if (effectiveMimeType.startsWith("audio/L16", ignoreCase = true)) {
            val rawFile = File(projectDir, "gemini_multispeaker_raw_${System.currentTimeMillis()}.pcm")
            FileOutputStream(rawFile).use { it.write(audioBytes) }

            val wavFile = File(projectDir, "gemini_multispeaker_tts_${System.currentTimeMillis()}.wav")
            val sampleRate = if (effectiveMimeType.contains("rate=")) effectiveMimeType.substringAfter("rate=").substringBefore(';').toIntOrNull() ?: 24000 else 24000
            val ffmpegCommand = "-y -f s16le -ar $sampleRate -ac 1 -i \"${rawFile.absolutePath}\" \"${wavFile.absolutePath}\""
            
            val session = FFmpegKit.execute(ffmpegCommand)
            rawFile.delete()
            
            return if (ReturnCode.isSuccess(session.returnCode)) {
                kotlin.Result.success(wavFile.absolutePath)
            } else {
                kotlin.Result.failure(Exception("FFmpeg falhou ao converter RAW para WAV. Código: ${session.returnCode}"))
            }
        }
        return kotlin.Result.failure(IOException("Formato de áudio não suportado: $effectiveMimeType"))
    }
    
    fun getAvailableVoices(gender: String? = null): List<Pair<String, String>> {
        val allVoices = listOf(
            Pair("Zephyr", "Masculino - Informativo, neutro"),
            Pair("Puck", "Masculino - Levemente teatral, expressivo"),
            Pair("Charon", "Masculino - Sério, profundo"),
            Pair("Fenrir", "Masculino - Calmo, grave"),
            Pair("Orus", "Masculino - Claro, comercial"),
            Pair("Iapetus", "Masculino - Calmo, narrativo"),
            Pair("Umbriel", "Masculino - Suave, neutro"),
            Pair("Algieba", "Masculino - Madura, instrutiva"),
            Pair("Algenib", "Masculino - Jovial, empático"),
            Pair("Rasalgethi", "Masculino - Madura, instrutiva"),
            Pair("Achernar", "Masculino - Firme, clara"),
            Pair("Schedar", "Masculino - Levemente dramática, épica"),
            Pair("Gacrux", "Masculino - Narrador, calmo"),
            Pair("Zubenelgenubi", "Masculino - Neutro, técnico"),
            Pair("Vindemiatrix", "Masculino - Envolvente, suave"),
            Pair("Sadachbia", "Masculino - Reflexiva, baixa tonalidade"),
            Pair("Sadaltager", "Masculino - Jornalístico, claro"),
            Pair("Sulafat", "Masculino - Robusto, firme"),
            Pair("Kore", "Feminino - Alegre, expressiva"),
            Pair("Leda", "Feminino - Jovem, entusiasta"),
            Pair("Aoede", "Feminino - Suave, emotiva"),
            Pair("Callirrhoe", "Feminino - Calma, natural"),
            Pair("Autonoe", "Feminino - Delicada, gentil"),
            Pair("Enceladus", "Feminino - Brilhante, calorosa"),
            Pair("Despina", "Feminino - Serena, narrativa"),
            Pair("Erinome", "Feminino - Comercial, clara"),
            Pair("Laomedeia", "Feminino - Intimista, suave"),
            Pair("Alnilam", "Feminino - Jovial, otimista"),
            Pair("Pulcherrima", "Feminino - Poética, cadenciada"),
            Pair("Achird", "Feminino - Leve, doce"),
            Pair("Bright", "Neutro - Brilhante, motivacional"),
            Pair("Upbeat", "Neutro - Alegre, positivo"),
            Pair("Informative", "Neutro - Didático, explicativo"),
            Pair("Firm", "Neutro - Autoritário, direto"),
            Pair("Excitable", "Neutro - Empolgado, animado"),
            Pair("Campfire story", "Neutro - Contador de histórias, informal"),
            Pair("Breezy", "Neutro - Descontraído, casual")
        )
        if (gender.isNullOrBlank()) return allVoices

        val normalizedGender = gender.trim().lowercase(Locale.getDefault())
        return allVoices.filter {
            val voiceGenderPart = it.second.split(" - ").firstOrNull()?.trim()?.lowercase(Locale.getDefault())
            when (normalizedGender) {
                "male", "masculino" -> voiceGenderPart == "masculino"
                "female", "feminino" -> voiceGenderPart == "feminino"
                "neutral", "neutro" -> voiceGenderPart == "neutro"
                else -> true
            }
        }.map { Pair(it.first, it.second.split(" - ").getOrElse(1) { "Estilo não especificado" }) }
    }
}