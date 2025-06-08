// File: euia/api/GeminiMultiSpeakerAudio.kt
package com.carlex.euia.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    private const val API_KEY = BuildConfig.GEMINI_API_KEY
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MODEL_ID = "gemini-2.5-flash-preview-tts"
    private const val GENERATE_CONTENT_API = "streamGenerateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val kotlinJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

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
        val temperature: Float? = 1.0f,
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

    @Serializable
    private data class GeminiApiResponseItem(
        @SerialName("candidates") val candidates: List<GeminiCandidate>? = null
    )

    @Serializable
    private data class GeminiCandidate(
        @SerialName("content") val content: GeminiContent? = null
    )

    @Serializable
    private data class GeminiContent(
        @SerialName("parts") val parts: List<GeminiPart>? = null,
        @SerialName("role") val role: String? = null
    )

    @Serializable
    private data class GeminiPart(
        @SerialName("inlineData") val inlineData: GeminiInlineData? = null,
        @SerialName("text") val text: String? = null
    )

    @Serializable
    private data class GeminiInlineData(
        @SerialName("mimeType") val mimeType: String,
        @SerialName("data") val data: String
    )

    @Serializable
    private data class GeminiErrorResponse(
        val error: GeminiErrorDetail
    )

    @Serializable
    private data class GeminiErrorDetail(
        val code: Int,
        val message: String,
        val status: String
    )

    suspend fun generate(
        dialogText: String,
        speakerVoiceMap: Map<String, String>,
        context: Context,
        projectDir: File
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🎙️ Initiating multi-speaker audio generation. Dialog: \"${dialogText.take(50)}...\", Speakers: ${speakerVoiceMap.size}")

            if (API_KEY.isBlank() || API_KEY == "YOUR_GEMINI_API_KEY") {
                val errorMsg = "API Key for Gemini TTS not configured."
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IllegalArgumentException(errorMsg))
            }

            if (!projectDir.exists() && !projectDir.mkdirs()) {
                val errorMsg = "Failed to create project directory: ${projectDir.absolutePath}"
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IOException(errorMsg))
            }

            val speakerVoiceConfigEntries = speakerVoiceMap.map { (speakerTag, voiceName) ->
                SpeakerVoiceConfigEntry(
                    speaker = speakerTag,
                    voiceConfig = PrebuiltVoiceContainer(PrebuiltVoice(voiceName))
                )
            }

            if (speakerVoiceConfigEntries.isEmpty()) {
                val errorMsg = "Speaker-voice map is empty. At least one speaker and voice must be defined."
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IllegalArgumentException(errorMsg))
            }

            val requestPayload = MultiSpeakerAudioRequest(
                contents = listOf(
                    MultiSpeakerContent(
                        parts = listOf(MultiSpeakerTextPart(text = dialogText))
                    )
                ),
                generationConfig = MultiSpeakerGenerationConfig(
                    speechConfig = SpeechConfig(
                        multiSpeakerVoiceConfig = MultiSpeakerVoiceConfig(
                            speakerVoiceConfigs = speakerVoiceConfigEntries
                        )
                    )
                )
            )

            val requestJsonString = try {
                kotlinJsonParser.encodeToString(MultiSpeakerAudioRequest.serializer(), requestPayload)
            } catch (e: Exception) {
                Log.e(TAG, "Error serializing multi-speaker request to JSON", e)
                return@withContext Result.failure(e)
            }

            Log.d(TAG, "Multi-speaker Request JSON: $requestJsonString")

            val requestBody = requestJsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val url = "$BASE_URL$MODEL_ID:$GENERATE_CONTENT_API?key=$API_KEY"
            val request = Request.Builder().url(url).post(requestBody).build()

            var rawAudioFile: File? = null
            var finalWavFile: File? = null

            try {
                val response: Response = client.newCall(request).execute()

                response.use { res ->
                    if (!res.isSuccessful) {
                        val errorBodyString = res.body?.string() ?: "No error body"
                        Log.e(TAG, "HTTP error ${res.code} from Gemini (Multi-Speaker): $errorBodyString")
                        try {
                            val errorResponse = kotlinJsonParser.decodeFromString(GeminiErrorResponse.serializer(), errorBodyString)
                            return@withContext Result.failure(IOException("Gemini API Error ${res.code} (Multi-Speaker): ${errorResponse.error.message} (Status: ${errorResponse.error.status})"))
                        } catch (e: Exception) {
                            return@withContext Result.failure(IOException("Gemini API Error ${res.code} (Multi-Speaker): ${errorBodyString.take(500)} (Failed to parse error details)"))
                        }
                    }

                    val responseBodyString = res.body?.string()
                    if (responseBodyString.isNullOrBlank()) {
                        val errorMsg = "Gemini TTS (Multi-Speaker) response body is empty."
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    Log.d(TAG, "Raw JSON response (Multi-Speaker, first 300 chars): ${responseBodyString.take(300)}")

                    var audioDataBase64: String? = null
                    var mimeType: String? = null
                    try {
                        // A API Gemini geralmente retorna um array de candidates, mesmo que seja um só
                        val jsonArray = JSONArray(responseBodyString)
                        if (jsonArray.length() > 0) {
                            val firstCandidatePart = jsonArray.optJSONObject(0)
                                ?.optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                            audioDataBase64 = firstCandidatePart?.optJSONObject("inlineData")?.optString("data")
                            mimeType = firstCandidatePart?.optJSONObject("inlineData")?.optString("mimeType")
                        }
                    } catch (e: JSONException) {
                         Log.w(TAG, "Resposta Gemini Multi-Speaker TTS não é um array JSON. Tentando como objeto. Erro: ${e.message}")
                        try {
                            val jsonObject = JSONObject(responseBodyString)
                             val firstCandidatePart = jsonObject
                                .optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                            audioDataBase64 = firstCandidatePart?.optJSONObject("inlineData")?.optString("data")
                            mimeType = firstCandidatePart?.optJSONObject("inlineData")?.optString("mimeType")
                        } catch (e2: JSONException) {
                            val errorMsg = "Falha ao parsear JSON da resposta Gemini Multi-Speaker TTS (nem array, nem objeto): ${e2.message}. Resposta: ${responseBodyString.take(500)}"
                            Log.e(TAG, errorMsg, e2)
                            return@withContext Result.failure(JSONException(errorMsg))
                        }
                    }


                    if (audioDataBase64.isNullOrBlank()) {
                        val errorMsg = "Audio Base64 data not found in Multi-Speaker response. Response: ${responseBodyString.take(500)}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }
                    if (mimeType.isNullOrBlank()) {
                        Log.w(TAG, "MIME type not found in Multi-Speaker response, assuming audio/L16;codec=pcm;rate=24000")
                        mimeType = "audio/L16;codec=pcm;rate=24000"
                    }
                    Log.d(TAG, "Extracted MIME Type (Multi-Speaker): $mimeType, Audio Data (first 20 chars): ${audioDataBase64.take(20)}...")

                    val audioBytes = try {
                        Base64.decode(audioDataBase64, Base64.DEFAULT)
                    } catch (e: IllegalArgumentException) {
                        val errorMsg = "Failed to decode Base64 audio data (Multi-Speaker): ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        return@withContext Result.failure(IllegalArgumentException(errorMsg))
                    }

                    if (mimeType.startsWith("audio/wav", ignoreCase = true)) {
                        val wavAudioFileName = "gemini_multispeaker_tts_${System.currentTimeMillis()}.wav"
                        val localFinalWavFile = File(projectDir, wavAudioFileName) 
                        FileOutputStream(localFinalWavFile).use { it.write(audioBytes) }
                        Log.d(TAG, "✅ Multi-speaker WAV audio saved directly: ${localFinalWavFile.absolutePath}")
                        finalWavFile = localFinalWavFile 
                    } else if (mimeType.startsWith("audio/L16", ignoreCase = true) || mimeType.startsWith("audio/pcm", ignoreCase = true)) {
                        val rawAudioFileName = "gemini_multispeaker_raw_${System.currentTimeMillis()}.pcm"
                        val localRawAudioFile = File(projectDir, rawAudioFileName) 
                        FileOutputStream(localRawAudioFile).use { it.write(audioBytes) }
                        rawAudioFile = localRawAudioFile 

                        if (!localRawAudioFile.exists() || localRawAudioFile.length() == 0L) {
                            val errorMsg = "Saved RAW audio file (Multi-Speaker) is empty or non-existent: ${localRawAudioFile.absolutePath}"
                            Log.e(TAG, errorMsg)
                            return@withContext Result.failure(IOException(errorMsg))
                        }
                        Log.d(TAG, "✅ Multi-speaker RAW audio saved: ${localRawAudioFile.absolutePath}")

                        val wavAudioFileName = "gemini_multispeaker_tts_${System.currentTimeMillis()}.wav"
                        val localFinalWavFile = File(projectDir, wavAudioFileName) 
                        finalWavFile = localFinalWavFile 

                        val sampleRate = if (mimeType.contains("rate=")) mimeType.substringAfter("rate=").substringBefore(';').toIntOrNull() ?: 24000 else 24000
                        val channels = 1
                        val format = "s16le"

                        val ffmpegCommand = "-y -f $format -ar $sampleRate -ac $channels -i \"${localRawAudioFile.absolutePath}\" \"${localFinalWavFile.absolutePath}\""
                        Log.d(TAG, "Executing FFmpeg for PCM to WAV (Multi-Speaker): $ffmpegCommand")
                        val session = FFmpegKit.execute(ffmpegCommand)

                        if (!ReturnCode.isSuccess(session.returnCode)) {
                            val errorMsg = "FFmpeg failed to convert RAW to WAV (Multi-Speaker). Code: ${session.returnCode}. Logs:\n${session.allLogsAsString.take(1000)}"
                            Log.e(TAG, errorMsg)
                            localFinalWavFile.delete() 
                            return@withContext Result.failure(Exception(errorMsg))
                        }
                        Log.d(TAG, "✅ PCM to WAV conversion successful (Multi-Speaker). WAV: ${localFinalWavFile.absolutePath}")
                    } else {
                        val errorMsg = "Unsupported audio format received from API (Multi-Speaker): $mimeType"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val localFinalWavPath = finalWavFile?.absolutePath
                    if (localFinalWavPath == null || finalWavFile?.exists() == false || finalWavFile?.length() == 0L) {
                        val errorMsg = "Final WAV file (Multi-Speaker) not created or empty: ${finalWavFile?.absolutePath ?: "null"}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }
                    // Geração de legenda SRT para áudio multi-speaker pode ser complexa se precisar alinhar com os speakers.
                    // Por ora, vamos usar o Audio.gerarLegendaSRT que gera uma legenda única para o texto completo.
                    val srtResult = Audio.gerarLegendaSRT(
                        cena = finalWavFile!!.nameWithoutExtension,
                        filePath = localFinalWavPath,
                        TextoFala = dialogText, // Usa o dialogText completo
                        context = context,
                        projectDir = projectDir
                    )
                    if (srtResult.isSuccess) {
                        Log.d(TAG, "✅ Legenda SRT (para áudio multi-speaker) gerada: ${srtResult.getOrNull()}")
                    } else {
                        Log.w(TAG, "⚠️ Falha ao gerar legenda SRT para áudio multi-speaker: ${srtResult.exceptionOrNull()?.message}")
                    }

                    return@withContext Result.success(localFinalWavPath)
                }
            } catch (e: IOException) {
                Log.e(TAG, "I/O error during multi-speaker audio generation: ${e.message}", e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } catch (e: JSONException) {
                Log.e(TAG, "JSON error during multi-speaker audio generation: ${e.message}", e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during multi-speaker audio generation: ${e.message}", e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } finally {
                rawAudioFile?.delete()
                Log.d(TAG, "Temporary RAW audio file (Multi-Speaker) cleaned (if existed): ${rawAudioFile?.absolutePath}")
            }
        }
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