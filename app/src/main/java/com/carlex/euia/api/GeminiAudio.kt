// File: com/carlex/euia/api/GeminiAudio.kt
package com.carlex.euia.api

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.carlex.euia.api.Audio // To reuse gerarLegendaSRT
import com.carlex.euia.BuildConfig // Import BuildConfig
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.Locale

object GeminiAudio {
    private const val TAG = "GeminiAudio"
    // !! IMPORTANT !! Replace with your actual Gemini API key.
    // Storing API keys directly in code is not recommended for production apps.
    // Use secure methods like environment variables or secrets management.
    private const val API_KEY = BuildConfig.GEMINI_API_KEY // Using BuildConfig
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MODEL_ID = "gemini-2.5-flash-preview-tts" // Verifique se este é o modelo TTS mais atual e adequado
    // NOTA: O nome do modelo pode variar. Anteriormente era "gemini-2.5-flash-preview-tts".
    // É importante usar o nome de modelo correto fornecido pela documentação do Gemini para TTS.
    // O endpoint "streamGenerateContent" é usado para streaming, se a API TTS usar um endpoint diferente (ex: "generateContent" para não-streaming)
    // ou um modelo específico (ex: `models/tts-model-id:synthesizeSpeech`), isso precisaria ser ajustado.
    // Assumindo que `streamGenerateContent` ainda é o caminho para TTS com os modelos Gemini.
    private const val GENERATE_CONTENT_API = "streamGenerateContent" // Ou :generateAnswer ou :synthesizeSpeech dependendo da API exata

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // Increased read timeout for streaming
        .build()

    private data class GeminiVoiceInfo(
        val name: String,
        val gender: String, // "Masculino", "Feminino", "Neutro"
        val style: String   // Emoção/Estilo da voz
    )

    private val allGeminiVoices = listOf(
        GeminiVoiceInfo("Zephyr", "Masculino", "Informativo, neutro"),
        GeminiVoiceInfo("Puck", "Masculino", "Levemente teatral, expressivo"),
        GeminiVoiceInfo("Charon", "Masculino", "Sério, profundo"),
        GeminiVoiceInfo("Fenrir", "Masculino", "Calmo, grave"),
        GeminiVoiceInfo("Orus", "Masculino", "Claro, comercial"),
        GeminiVoiceInfo("Iapetus", "Masculino", "Calmo, narrativo"),
        GeminiVoiceInfo("Umbriel", "Masculino", "Suave, neutro"),
        GeminiVoiceInfo("Algieba", "Masculino", "Madura, instrutiva"),
        GeminiVoiceInfo("Algenib", "Masculino", "Jovial, empático"),
        GeminiVoiceInfo("Rasalgethi", "Masculino", "Madura, instrutiva"),
        GeminiVoiceInfo("Achernar", "Masculino", "Firme, clara"),
        GeminiVoiceInfo("Schedar", "Masculino", "Levemente dramática, épica"),
        GeminiVoiceInfo("Gacrux", "Masculino", "Narrador, calmo"),
        GeminiVoiceInfo("Zubenelgenubi", "Masculino", "Neutro, técnico"),
        GeminiVoiceInfo("Vindemiatrix", "Masculino", "Envolvente, suave"),
        GeminiVoiceInfo("Sadachbia", "Masculino", "Reflexiva, baixa tonalidade"),
        GeminiVoiceInfo("Sadaltager", "Masculino", "Jornalístico, claro"),
        GeminiVoiceInfo("Sulafat", "Masculino", "Robusto, firme"),
        GeminiVoiceInfo("Kore", "Feminino", "Alegre, expressiva"),
        GeminiVoiceInfo("Leda", "Feminino", "Jovem, entusiasta"),
        GeminiVoiceInfo("Aoede", "Feminino", "Suave, emotiva"),
        GeminiVoiceInfo("Callirrhoe", "Feminino", "Calma, natural"),
        GeminiVoiceInfo("Autonoe", "Feminino", "Delicada, gentil"),
        GeminiVoiceInfo("Enceladus", "Feminino", "Brilhante, calorosa"),
        GeminiVoiceInfo("Despina", "Feminino", "Serena, narrativa"),
        GeminiVoiceInfo("Erinome", "Feminino", "Comercial, clara"),
        GeminiVoiceInfo("Laomedeia", "Feminino", "Intimista, suave"),
        GeminiVoiceInfo("Alnilam", "Feminino", "Jovial, otimista"),
        GeminiVoiceInfo("Pulcherrima", "Feminino", "Poética, cadenciada"),
        GeminiVoiceInfo("Achird", "Feminino", "Leve, doce"),
        GeminiVoiceInfo("Bright", "Neutro", "Brilhante, motivacional"),
        GeminiVoiceInfo("Upbeat", "Neutro", "Alegre, positivo"),
        GeminiVoiceInfo("Informative", "Neutro", "Didático, explicativo"),
        GeminiVoiceInfo("Firm", "Neutro", "Autoritário, direto"),
        GeminiVoiceInfo("Excitable", "Neutro", "Empolgado, animado"),
        GeminiVoiceInfo("Campfire story", "Neutro", "Contador de histórias, informal"),
        GeminiVoiceInfo("Breezy", "Neutro", "Descontraído, casual")
    )

    /**
     * Retorna uma lista de pares (nome da voz, estilo da voz) disponíveis para a API Gemini TTS,
     * opcionalmente filtrada por gênero.
     *
     * @param idioma O idioma desejado (atualmente ignorado, pois a lista é estática e os nomes das vozes são em inglês).
     * @param gender O gênero desejado ("Masculino", "Feminino", "Neutro", ou vazio/qualquer outro para todas).
     * @return Result contendo a lista de pares (nome da voz, estilo da voz) em caso de sucesso.
     */
    suspend fun getAvailableVoices(gender: String, locale: String = "pt-BR"): Result<List<Pair<String, String>>> {
        return withContext(Dispatchers.IO) { // Simula IO para consistência com outras APIs de voz
            try {
                val normalizedGender = gender.trim().lowercase(Locale.getDefault())
                val filteredVoiceInfoList = when {
                    normalizedGender == "male" || normalizedGender == "masculino" ->
                        allGeminiVoices.filter { it.gender == "Masculino" }
                    normalizedGender == "female" || normalizedGender == "feminino" ->
                        allGeminiVoices.filter { it.gender == "Feminino" }
                    normalizedGender == "neutral" || normalizedGender == "neutro" ->
                        allGeminiVoices.filter { it.gender == "Neutro" }
                    normalizedGender.isBlank() || normalizedGender == "all" ->
                        allGeminiVoices
                    else -> {
                        Log.w(TAG, "Gênero não reconhecido para filtro de voz Gemini: '$gender'. Retornando todas as vozes.")
                        allGeminiVoices
                    }
                }

                val voiceNameAndStylePairs = filteredVoiceInfoList.map { voiceInfo ->
                    Pair(voiceInfo.name, voiceInfo.style)
                }

                Log.d(TAG, "Retornando ${voiceNameAndStylePairs.size} pares de voz/estilo Gemini para gênero '$gender' (normalizado: '$normalizedGender').")
                Result.success(voiceNameAndStylePairs)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao obter vozes Gemini (estáticas): ${e.message}", e)
                Result.failure(e)
            }
        }
    }


    /**
     * Generates audio from text using the Gemini TTS API and creates an SRT subtitle file.
     *
     * @param text The text to convert to speech.
     * @param voiceName The name of the voice to use (e.g., "Kore").
     *        Note: The `style` or "emotion" is implied by the `voiceName` chosen from `allGeminiVoices`.
     *        This function does not currently pass a separate "style" parameter to the Gemini API.
     * @param context Android Context needed for file operations and SRT generation.
     * @param projectDir The directory where the audio and SRT files will be saved.
     * @return Result containing the absolute path of the saved audio file (WAV) on success,
     *         or an Exception on failure.
     */
    suspend fun generate(
        text: String,
        voiceName: String,
        context: Context,
        projectDir: File
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🔊 Iniciando geração de áudio com Gemini TTS para texto: ${text.take(50)}..., Voz: $voiceName")

            if (API_KEY.isBlank() || API_KEY == "YOUR_GEMINI_API_KEY") {
                val errorMsg = "API Key para Gemini TTS não configurada."
                Log.e(TAG, errorMsg)
                return@withContext Result.failure(IllegalArgumentException(errorMsg))
            }

            if (!projectDir.exists()) {
                if (!projectDir.mkdirs()) {
                    val errorMsg = "Falha ao criar o diretório do projeto: ${projectDir.absolutePath}"
                    Log.e(TAG, errorMsg)
                    return@withContext Result.failure(IOException(errorMsg))
                }
            }

            val url = "$BASE_URL$MODEL_ID:$GENERATE_CONTENT_API?key=$API_KEY"
            
            val finalRequestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", text)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("audio") 
                    })
                    put("speech_config", JSONObject().apply { 
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply { 
                                put("voice_name", voiceName) 
                            })
                        })
                    })
                })
            }.toString()


            val requestBody = finalRequestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            var rawAudioFile: File? = null
            var finalWavFile: File? = null

            try {
                val response: Response = client.newCall(request).execute()
                val responseCode = response.code
                val responseMessage = response.message

                response.use { res ->
                    if (!res.isSuccessful) {
                        val errorBody = res.body?.string() ?: "Sem corpo de erro"
                        val errorMsg = "Erro HTTP na geração de áudio Gemini TTS: $responseCode - $responseMessage. Detalhes: ${errorBody.take(500)}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val responseBodyString = res.body?.string()
                    if (responseBodyString.isNullOrBlank()) {
                        val errorMsg = "Corpo da resposta de áudio Gemini TTS está vazio."
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    Log.d(TAG, "Resposta JSON recebida (primeiros 300 chars): ${responseBodyString.take(300)}")
                    
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
                        // Se não for um array, tenta parsear como um objeto direto
                        Log.w(TAG, "Resposta Gemini TTS não é um array JSON. Tentando como objeto. Erro: ${e.message}")
                        try {
                            val jsonObject = JSONObject(responseBodyString)
                             val firstCandidatePart = jsonObject
                                .optJSONArray("candidates")?.optJSONObject(0)
                                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                            audioDataBase64 = firstCandidatePart?.optJSONObject("inlineData")?.optString("data")
                            mimeType = firstCandidatePart?.optJSONObject("inlineData")?.optString("mimeType")
                        } catch (e2: JSONException) {
                            val errorMsg = "Falha ao parsear JSON da resposta Gemini TTS (nem array, nem objeto): ${e2.message}. Resposta: ${responseBodyString.take(500)}"
                            Log.e(TAG, errorMsg, e2)
                            return@withContext Result.failure(JSONException(errorMsg))
                        }
                    }

                    if (audioDataBase64.isNullOrBlank()) {
                        val errorMsg = "Dados de áudio Base64 não encontrados na resposta JSON. Resposta: ${responseBodyString.take(500)}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }
                     if (mimeType.isNullOrBlank()) {
                        Log.w(TAG, "MIME type não encontrado na resposta, usando default: audio/L16;codec=pcm;rate=24000")
                        mimeType = "audio/L16;codec=pcm;rate=24000"
                    }
                    Log.d(TAG, "MIME Type extraído: $mimeType. Dados de áudio (início): ${audioDataBase64.take(30)}...")


                    val audioBytes = try {
                        Base64.decode(audioDataBase64, Base64.DEFAULT)
                    } catch (e: IllegalArgumentException) {
                        val errorMsg = "Falha ao decodificar dados Base64: ${e.message}"
                        Log.e(TAG, errorMsg, e)
                        return@withContext Result.failure(IllegalArgumentException(errorMsg))
                    }

                    if (mimeType.startsWith("audio/wav", ignoreCase = true)) {
                        val wavAudioFileName = "gemini_tts_audio_${System.currentTimeMillis()}.wav"
                        finalWavFile = File(projectDir, wavAudioFileName)
                        FileOutputStream(finalWavFile).use { outputStream ->
                            outputStream.write(audioBytes)
                        }
                        Log.d(TAG, "✅ Áudio WAV Gemini TTS salvo diretamente em: ${finalWavFile!!.absolutePath}")
                    } else if (mimeType.startsWith("audio/L16", ignoreCase = true) || mimeType.startsWith("audio/pcm", ignoreCase = true)) {
                        val rawAudioFileName = "gemini_tts_audio_raw_${System.currentTimeMillis()}.pcm"
                        rawAudioFile = File(projectDir, rawAudioFileName)
                        FileOutputStream(rawAudioFile).use { outputStream ->
                            outputStream.write(audioBytes)
                        }

                        if (rawAudioFile == null || !rawAudioFile!!.exists() || rawAudioFile!!.length() == 0L) {
                            val errorMsg = "Arquivo de áudio RAW salvo está vazio ou não existe: ${rawAudioFile?.absolutePath ?: "null"}"
                            Log.e(TAG, errorMsg)
                            return@withContext Result.failure(IOException(errorMsg))
                        }
                        Log.d(TAG, "✅ Áudio RAW Gemini TTS salvo em: ${rawAudioFile!!.absolutePath}")

                        val wavAudioFileName = "gemini_tts_audio_${System.currentTimeMillis()}.wav"
                        finalWavFile = File(projectDir, wavAudioFileName)
                        
                        val sampleRate = if (mimeType.contains("rate=")) mimeType.substringAfter("rate=").substringBefore(';').toIntOrNull() ?: 24000 else 24000
                        val channels = 1 
                        val format = "s16le" 

                        val ffmpegCommand = "-y -f $format -ar $sampleRate -ac $channels -i \"${rawAudioFile!!.absolutePath}\" \"${finalWavFile!!.absolutePath}\""
                        Log.d(TAG, "Executando comando FFmpeg para converter PCM para WAV: $ffmpegCommand")

                        val session = FFmpegKit.execute(ffmpegCommand)
                        val returnCode = session.returnCode

                        if (!ReturnCode.isSuccess(returnCode)) {
                            val errorMsg = "FFmpeg falhou ao converter RAW para WAV. Código: $returnCode. Logs:\n${session.allLogsAsString.take(1000)}"
                            Log.e(TAG, errorMsg)
                            finalWavFile?.delete()
                            return@withContext Result.failure(Exception(errorMsg))
                        }
                        Log.d(TAG, "✅ Conversão PCM para WAV bem-sucedida. Arquivo WAV: ${finalWavFile!!.absolutePath}")
                    } else {
                         val errorMsg = "Formato de áudio não suportado recebido da API: $mimeType"
                         Log.e(TAG, errorMsg)
                         return@withContext Result.failure(IOException(errorMsg))
                    }


                    if (finalWavFile == null || !finalWavFile!!.exists() || finalWavFile!!.length() == 0L) {
                        val errorMsg = "Arquivo WAV final não foi criado ou está vazio: ${finalWavFile?.absolutePath ?: "null"}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val srtResult = Audio.gerarLegendaSRT(
                        cena = finalWavFile!!.nameWithoutExtension,
                        filePath = finalWavFile!!.absolutePath,
                        TextoFala = text,
                        context = context,
                        projectDir = projectDir
                    )

                    if (srtResult.isSuccess) {
                        Log.d(TAG, "✅ Legenda SRT gerada com sucesso: ${srtResult.getOrNull()}")
                    } else {
                        Log.w(TAG, "⚠️ Falha ao gerar legenda SRT para o áudio Gemini TTS: ${srtResult.exceptionOrNull()?.message}. O áudio ainda foi gerado.")
                    }

                    return@withContext Result.success(finalWavFile!!.absolutePath)
                }
            } catch (e: IOException) {
                val errorMsg = "Erro de I/O durante a geração de áudio Gemini TTS: ${e.message}"
                Log.e(TAG, errorMsg, e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } catch (e: JSONException) {
                val errorMsg = "Erro de JSON durante a geração de áudio Gemini TTS: ${e.message}"
                Log.e(TAG, errorMsg, e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                val errorMsg = "Erro inesperado durante a geração de áudio Gemini TTS: ${e.message}"
                Log.e(TAG, errorMsg, e)
                rawAudioFile?.delete()
                finalWavFile?.delete()
                return@withContext Result.failure(e)
            } finally {
                rawAudioFile?.delete()
                Log.d(TAG, "Arquivo RAW temporário limpo (se existiu): ${rawAudioFile?.absolutePath}")
            }
        }
    }
}