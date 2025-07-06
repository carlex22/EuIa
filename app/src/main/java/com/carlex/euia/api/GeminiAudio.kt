// File: com/carlex/euia/api/GeminiAudio.kt
package com.carlex.euia.api

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.api.Audio // Para reusar gerarLegendaSRT
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

object GeminiAudio {
    private const val TAG = "GeminiAudio"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MODEL_ID = //"gemini-2.5-pro-preview-tts"
                                    "gemini-2.5-flash-preview-tts"
    private const val GENERATE_CONTENT_API = "streamGenerateContent"
    
    // --- CONSTANTES PARA O GERENCIADOR DE CHAVES ---
    private const val TIPO_DE_CHAVE = "audio"

    // --- INSTANCIAÇÃO INTERNA DAS DEPENDÊNCIAS ---
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }
    
    // --- CLIENTE HTTP (sem alterações) ---
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // --- DATA CLASSES E LISTA DE VOZES (sem alterações) ---
    private data class GeminiVoiceInfo(
        val name: String,
        val gender: String, // "Masculino", "Feminino", "Neutro"
        val style: String   // Emoção/Estilo da voz
    )

    private val allGeminiVoices = listOf(
        // ... (a lista de vozes permanece a mesma)
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

    // --- getAvailableVoices (sem alterações) ---
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


    // --- FUNÇÃO generate() ATUALIZADA ---
    suspend fun generate(
        text: String,
        voiceName: String,
        context: Context,
        projectDir: File
    ): Result<String> {
        val authViewModel = AuthViewModel(context.applicationContext as Application)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                // ETAPA 1: VERIFICAR E DEDUZIR CRÉDITOS
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.AUDIO_SINGLE)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.AUDIO_SINGLE.cost}) deduzidos. Prosseguindo com a geração do áudio.")

                // ETAPA 2: LÓGICA DE GERAÇÃO COM TENTATIVAS
                Log.d(TAG, "🔊 Iniciando geração de áudio com Gemini TTS para texto: ${text.take(50)}..., Voz: $voiceName")

                if (!projectDir.exists() && !projectDir.mkdirs()) {
                    val errorMsg = "Falha ao criar o diretório do projeto: ${projectDir.absolutePath}"
                    Log.e(TAG, errorMsg)
                    throw IOException(errorMsg)
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

                        val url = "$BASE_URL$MODEL_ID:$GENERATE_CONTENT_API?key=$chaveAtual"
                        val requestBody = buildRequestBody(text, voiceName)
                        val request = Request.Builder().url(url).post(requestBody).build()
                        
                        val response = client.newCall(request).execute()

                        if (response.isSuccessful) {
                            Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} com a chave '${chaveAtual.takeLast(4)}'.")
                            gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                            
                            return@withContext processSuccessfulResponse(response, projectDir, context, text)
                        
                        } else if (response.code == 429) {
                            response.body?.close()
                            Log.w(TAG, "Erro 429 (Rate Limit) ($TIPO_DE_CHAVE) na chave '${chaveAtual.takeLast(4)}'. Bloqueando...")
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            tentativas++
                            if (tentativas < MAX_TENTATIVAS) {
                                delay(1000)
                                continue 
                            } else {
                                throw Exception("Máximo de tentativas ($MAX_TENTATIVAS) atingido.")
                            }
                        } else {
                            val errorBody = response.body?.string() ?: "Erro desconhecido"
                            Log.e(TAG, "Erro de API não recuperável ($TIPO_DE_CHAVE), Código: ${response.code}, Corpo: $errorBody")
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            throw IOException("Erro da API (${response.code}): $errorBody")
                        }

                    } catch (e: NenhumaChaveApiDisponivelException) {
                        Log.e(TAG, "Não há chaves de API disponíveis para o tipo '$TIPO_DE_CHAVE'.", e)
                        throw e
                    }
                } // Fim do while

                throw Exception("Falha ao gerar áudio do tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas.")
            
            } catch (e: Exception) {
                // ETAPA 3: REEMBOLSO EM CASO DE QUALQUER FALHA
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro durante a geração do áudio. Reembolsando ${TaskType.AUDIO_SINGLE.cost} créditos.", e)
                    authViewModel.refundCredits(TaskType.AUDIO_SINGLE)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private fun buildRequestBody(text: String, voiceName: String): okhttp3.RequestBody {
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
                put("temperature", 2)
                put("speech_config", JSONObject().apply {
                    put("voice_config", JSONObject().apply {
                        put("prebuilt_voice_config", JSONObject().apply {
                            put("voice_name", voiceName)
                        })
                    })
                })
            })
        }.toString()
        return finalRequestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    }

    private suspend fun processSuccessfulResponse(
        response: Response,
        projectDir: File,
        context: Context,
        originalText: String
    ): Result<String> {
        var rawAudioFile: File? = null
        var finalWavFile: File? = null

        try {
            val responseBodyString = response.body?.string()
            if (responseBodyString.isNullOrBlank()) {
                return Result.failure(IOException("Corpo da resposta de áudio Gemini TTS está vazio."))
            }

            Log.d(TAG, "Resposta JSON recebida (primeiros 300 chars): ${responseBodyString.take(300)}")

            var audioDataBase64: String? = null
            var mimeType: String? = null
            try {
                // ... (lógica de parsing JSON inalterada)
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
                     return Result.failure(JSONException("Falha ao parsear JSON da resposta Gemini TTS (nem array, nem objeto): ${e2.message}"))
                }
            }

            if (audioDataBase64.isNullOrBlank()) {
                return Result.failure(IOException("Dados de áudio Base64 não encontrados na resposta JSON."))
            }
            mimeType = mimeType.takeIf { !it.isNullOrBlank() } ?: "audio/L16;codec=pcm;rate=24000"
            Log.d(TAG, "MIME Type: $mimeType. Dados (início): ${audioDataBase64.take(30)}...")

            val audioBytes = Base64.decode(audioDataBase64, Base64.DEFAULT)

            if (mimeType.startsWith("audio/wav", ignoreCase = true)) {
                val wavAudioFileName = "gemini_tts_audio_${System.currentTimeMillis()}.wav"
                finalWavFile = File(projectDir, wavAudioFileName)
                FileOutputStream(finalWavFile).use { it.write(audioBytes) }
                Log.d(TAG, "✅ Áudio WAV Gemini TTS salvo diretamente em: ${finalWavFile!!.absolutePath}")
            } else if (mimeType.startsWith("audio/L16", ignoreCase = true) || mimeType.startsWith("audio/pcm", ignoreCase = true)) {
                // ... (lógica de conversão PCM para WAV com FFmpeg inalterada)
                rawAudioFile = File(projectDir, "gemini_tts_audio_raw_${System.currentTimeMillis()}.pcm")
                FileOutputStream(rawAudioFile).use { it.write(audioBytes) }

                finalWavFile = File(projectDir, "gemini_tts_audio_${System.currentTimeMillis()}.wav")
                val sampleRate = if (mimeType.contains("rate=")) mimeType.substringAfter("rate=").substringBefore(';').toIntOrNull() ?: 24000 else 24000
                
                val ffmpegCommand = "-y -f s16le -ar $sampleRate -ac 1 -i \"${rawAudioFile.absolutePath}\" \"${finalWavFile.absolutePath}\""
                val session = FFmpegKit.execute(ffmpegCommand)

                if (!ReturnCode.isSuccess(session.returnCode)) {
                    return Result.failure(Exception("FFmpeg falhou ao converter RAW para WAV. Código: ${session.returnCode}"))
                }
            } else {
                return Result.failure(IOException("Formato de áudio não suportado: $mimeType"))
            }

            if (finalWavFile == null || !finalWavFile!!.exists() || finalWavFile!!.length() == 0L) {
                return Result.failure(IOException("Arquivo WAV final não foi criado ou está vazio."))
            }

            // Geração de legenda (movida para dentro desta função de sucesso)
            /*val srtResult = Audio.gerarLegendaSRT(
                cena = finalWavFile!!.nameWithoutExtension,
                filePath = finalWavFile!!.absolutePath,
                TextoFala = originalText,
                context = context,
                projectDir = projectDir
            )
            srtResult.onFailure { Log.w(TAG, "⚠️ Falha ao gerar legenda SRT para o áudio Gemini TTS: ${it.message}") }*/

            return Result.success(finalWavFile!!.absolutePath)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            rawAudioFile?.delete()
        }
    }
}