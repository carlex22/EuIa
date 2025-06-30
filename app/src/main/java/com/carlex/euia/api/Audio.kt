// File: euia/api/Audio.kt
package com.carlex.euia.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.carlex.euia.managers.AppConfigManager
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import java.io.FileNotFoundException
// Removido: import com.carlex.euia.data.VideoPreferencesDataStoreManager // Esta classe é usada fora deste arquivo para obter o projectDir
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.IOException // Adicionado para exceções de rede

object Audio {
    private const val TAG = "AudioGenerator"
    private const val TEXT_PART_CHAR_LIMIT = 680
    private val apiKey = AppConfigManager.getString("groq_API_KEY") ?: ""

    // --- Data classes (permanecem as mesmas) ---
    private data class WordTimestamp(
        val word: String,
        val start: Double,
        val end: Double
    )

    private data class Segment(
        val id: Int? = null,
        val seek: Int? = null,
        val start: Double,
        val end: Double,
        val text: String,
        val tokens: List<Int>? = null,
        val temperature: Double? = null,
        val avg_logprob: Double? = null,
        val compression_ratio: Double? = null,
        val no_speech_prob: Double? = null,
        val words: List<WordTimestamp1>? = null
    )

    private data class TranscriptionResponse(
        val task: String?,
        val language: String?,
        val duration: Double?,
        val text: String,
        val words: List<WordTimestamp1>?,
        val segments: List<Segment>?,
        val x_groq: Map<String, Any>?
    )

    private data class Voice(
        val short_name: String,
        val gender: String
    )
    
    var pith: Float = 0.0f
    var voiceRate: Float = 0.0f

    // --- ApiService interface (permanece a mesma) ---
    private interface ApiService {
    
            
    
        @GET("tts")
        suspend fun generateAudio(
            @Query("t") text: String, @Query("r") r: Float = voiceRate, @Query("v") voice: String,
            @Query("p") p: Float = pith, @Query("s") s: String = "cheerful"
        ): retrofit2.Response<okhttp3.ResponseBody>

        @GET("voices")
        suspend fun getVoices(
            @Query("locale") locale: String = "en-US", @Query("gender") gender: String
        ): Response<List<Voice>>

        @Multipart
        @POST("openai/v1/audio/transcriptions")
        suspend fun transcribeAudio(
            @Part file: MultipartBody.Part, @Part model: MultipartBody.Part,
            @Part response_format: MultipartBody.Part, @Part temperature: MultipartBody.Part,
            @Part timestamp_granularities: List<MultipartBody.Part>,
            @Part language: MultipartBody.Part? = null, @Part prompt: MultipartBody.Part? = null
        ): Response<TranscriptionResponse>
    }

    private val ttsService by lazy { createTtsService() }
    private val groqService by lazy { createGroqService() }

    private fun createTtsService(): ApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://carlex22222-tts1.hf.space/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
    
    

    private fun createGroqService(): ApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey") // SUA CHAVE API GROQ
                    .build()
                    .let(chain::proceed)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
    
    private fun splitTextIntoParts(text: String, maxLengthHint: Int): List<String> {
        val parts = mutableListOf<String>()
        var remainingText = text.trim()
        val sentenceEnders = charArrayOf('.', '!', '?')

        if (remainingText.isEmpty()) return parts

        while (remainingText.isNotEmpty()) {
            if (remainingText.length <= maxLengthHint) {
                parts.add(remainingText)
                break
            }

            var breakPoint = -1
            val searchStart = (maxLengthHint * 0.7).toInt()
            var searchIndex = maxLengthHint

            val buffer = 60
            val effectiveSearchEnd = minOf(remainingText.length, maxLengthHint + buffer)

            for (i in effectiveSearchEnd -1 downTo searchStart) {
                if (i < remainingText.length && remainingText[i] in sentenceEnders) {
                    breakPoint = i + 1
                    break
                }
            }

            if (breakPoint == -1) {
                var spaceSearchIndex = maxLengthHint -1
                while (spaceSearchIndex >= searchStart && spaceSearchIndex < remainingText.length) {
                    if (remainingText[spaceSearchIndex].isWhitespace()) {
                        breakPoint = spaceSearchIndex + 1
                        break
                    }
                    spaceSearchIndex--
                }
            }

            if (breakPoint == -1 || breakPoint <= searchStart) {
                breakPoint = maxLengthHint
                if (breakPoint < remainingText.length && !remainingText[breakPoint].isWhitespace()) {
                    var tempBp = breakPoint -1
                    while(tempBp > 0 && !remainingText[tempBp].isWhitespace()) {
                        tempBp--
                    }
                    if (tempBp > 0) breakPoint = tempBp + 1
                }
            }

            if (breakPoint >= remainingText.length) {
                 breakPoint = remainingText.length
            }

            val part = remainingText.substring(0, breakPoint).trim()
            if (part.isNotEmpty()) {
                parts.add(part)
            }
            remainingText = remainingText.substring(breakPoint).trim()
        }
        Log.d(TAG, "Texto dividido em ${parts.size} partes.")
        parts.forEachIndexed { index, p -> Log.d(TAG, "Parte ${index+1} (${p.length} chars): ${p.take(100)}...")}
        return parts.filter { it.isNotBlank() }
    }

    /**
     * Concatena uma lista de arquivos de áudio usando FFmpegKit.
     * @param audioFilePaths Lista de caminhos absolutos para os arquivos de áudio de entrada.
     * @param outputFilePath Caminho absoluto para o arquivo de áudio de saída.
     * @param context Contexto da aplicação (usado para cacheDir).
     * @return Result contendo o caminho do arquivo de saída em caso de sucesso, ou uma exceção em caso de falha.
     */
    private fun concatenateAudioFilesWithFFmpeg(
    audioFilePaths: List<String>,
    outputFilePath: String,
    context: Context // Necessário para context.cacheDir
): Result<String> {
    if (audioFilePaths.isEmpty()) {
        return Result.failure(IllegalArgumentException("Nenhum arquivo de áudio fornecido para concatenação."))
    }
    if (audioFilePaths.size == 1) {
        Log.d(TAG, "Apenas um arquivo de áudio fornecido, não é necessária concatenação. Copiando para o destino.")
        try {
            File(audioFilePaths.first()).copyTo(File(outputFilePath), overwrite = true)
            return Result.success(outputFilePath)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao copiar arquivo único: ${e.message}", e)
            return Result.failure(e)
        }
    }

    val filesToConcat = mutableListOf<String>()
    val tempFiles = mutableListOf<File>() // Lista para armazenar arquivos temporários a serem limpos

    try {
        // Processar arquivos de áudio, cortando o último se necessário
        audioFilePaths.forEachIndexed { index, path ->
            if (index == audioFilePaths.lastIndex) { // Exemplo: Cortar o último arquivo
                Log.d(TAG, "Cortando 1 segundo do final do arquivo: $path")

                val inputFile = File(path)
                val trimmedFileName = "trimmed_${inputFile.nameWithoutExtension}_${System.currentTimeMillis()}.${inputFile.extension}"
                val trimmedOutputFile = File(context.cacheDir, trimmedFileName)
                tempFiles.add(trimmedOutputFile) // Adicionar à lista para limpeza posterior

                // Comando FFmpeg para cortar 1 segundo do final
                // Usa -ss do final com seek_timestamp (requires seeking support in demuxer)
                // Uma alternativa mais robusta pode ser determinar a duração total e usar -to
                // Vamos tentar o -ss negativo primeiro, que é mais simples se suportado pelo formato.
                // Se não funcionar, precisaremos de uma etapa anterior para obter a duração.
                val trimCommand = "-y -i \"${path}\" -ss -1 -c copy \"${trimmedOutputFile.absolutePath}\""

                Log.d(TAG, "Executando comando FFmpeg para cortar: $trimCommand")
                val trimSession = FFmpegKit.execute(trimCommand)

                if (ReturnCode.isSuccess(trimSession.returnCode)) {
                    Log.d(TAG, "Corte FFmpeg bem-sucedido. Arquivo cortado: ${trimmedOutputFile.absolutePath}")
                    if (trimmedOutputFile.exists() && trimmedOutputFile.length() > 0) {
                        filesToConcat.add(trimmedOutputFile.absolutePath)
                    } else {
                        Log.e(TAG, "FFmpeg retornou sucesso no corte, mas o arquivo de saída cortado não foi encontrado ou está vazio: ${trimmedOutputFile.absolutePath}")
                        Log.e(TAG, "Logs FFmpeg (corte):\n${trimSession.allLogsAsString}")
                        throw Exception("FFmpeg sucesso no corte, mas arquivo de saída cortado inválido.")
                    }
                } else {
                    Log.e(TAG, "FFmpeg falhou ao cortar arquivo: ${path} com código: ${trimSession.returnCode}")
                    Log.e(TAG, "Logs FFmpeg (corte):\n${trimSession.allLogsAsString}")
                    throw Exception("Falha ao cortar arquivo de áudio com FFmpeg. Código: ${trimSession.returnCode}.")
                }

            } else {
                // Adicionar arquivos que não precisam ser cortados diretamente
                filesToConcat.add(path)
            }
        }

        // Continuar com a concatenação usando a lista potentially modificada (filesToConcat)
        val listFileName = "ffmpeg_concat_list_${System.currentTimeMillis()}.txt"
        val listFile = File(context.cacheDir, listFileName) // Usa cacheDir para arquivo de lista temporário
        tempFiles.add(listFile) // Adicionar à lista para limpeza posterior


        FileOutputStream(listFile).use { fos ->
            filesToConcat.forEach { path ->
                val escapedPath = path.replace("'", "'\\''")
                val fileEntry = "file '$escapedPath'\n"
                fos.write(fileEntry.toByteArray(StandardCharsets.UTF_8))
            }
        }
        Log.d(TAG, "Arquivo de lista para FFmpeg criado em: ${listFile.absolutePath}")

        // O comando de concatenação permanece o mesmo, mas agora usa a lista que pode incluir o arquivo cortado
        val ffmpegCommand = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"$outputFilePath\""
        Log.d(TAG, "Executando comando FFmpeg: $ffmpegCommand")

        val session = FFmpegKit.execute(ffmpegCommand)

        if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "FFmpeg concatenação bem-sucedida. Saída: $outputFilePath")
            if (File(outputFilePath).exists() && File(outputFilePath).length() > 0) {
                return Result.success(outputFilePath)
            } else {
                Log.e(TAG, "FFmpeg retornou sucesso na concatenação, mas o arquivo de saída não foi encontrado ou está vazio: $outputFilePath")
                Log.e(TAG, "Logs FFmpeg (concatenação):\n${session.allLogsAsString}")
                return Result.failure(Exception("FFmpeg sucesso, mas arquivo de saída inválido."))
            }
        } else {
            Log.e(TAG, "FFmpeg concatenação falhou com código: ${session.returnCode}")
            Log.e(TAG, "Logs FFmpeg (concatenação):\n${session.allLogsAsString}")
            File(outputFilePath).delete() // Limpa arquivo de saída parcial
            return Result.failure(Exception("Falha na concatenação com FFmpeg. Código: ${session.returnCode}. Verifique os logs para detalhes."))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Erro durante a preparação ou execução do FFmpeg: ${e.message}", e)
        File(outputFilePath).delete() // Limpa arquivo de saída parcial
        return Result.failure(e)
    } finally {
        // Limpar todos os arquivos temporários criados
        tempFiles.forEach { file ->
            if (file.exists()) {
                if (file.delete()) {
                    Log.d(TAG, "Arquivo temporário deletado: ${file.name}")
                } else {
                    Log.w(TAG, "Falha ao deletar arquivo temporário: ${file.name}")
                }
            }
        }
    }
}



    suspend fun generate(
        voz: String,
        cena: String,
        text: String,
        context: Context, // Ainda necessário para concatenateAudioFilesWithFFmpeg (cacheDir)
        projectDir: File,
        pithd: Float,
        voiceRated: Float // Diretório do projeto para salvar os arquivos
    ): Result<String> {
    
      pith = pithd
      voiceRate = voiceRated
    
      
    
    
        Log.d(TAG, "🔊 Iniciando geração de áudio MULTI-PART com FFmpeg para a cena: $cena. Salvando em: ${projectDir.absolutePath}. Texto (início): ${text.take(50)}...")
        return withContext(Dispatchers.IO) {
            try {
                // Garante que o diretório do projeto exista
                if (!projectDir.exists()) {
                    if (!projectDir.mkdirs()) {
                        Log.e(TAG, "Falha ao criar o diretório do projeto: ${projectDir.absolutePath}")
                        return@withContext Result.failure(IOException("Falha ao criar o diretório do projeto: ${projectDir.absolutePath}"))
                    }
                }

                val cleanedOriginalText = cleanText(text)
                val textParts = splitTextIntoParts(cleanedOriginalText, TEXT_PART_CHAR_LIMIT)

                if (textParts.isEmpty()) {
                    Log.w(TAG, "Nenhuma parte de texto gerada para áudio.")
                    return@withContext Result.failure(IllegalArgumentException("Texto de entrada resultou em zero partes para processar."))
                }

                val audioPartFiles = mutableListOf<File>()
                var success = true
                var lastApiError: String? = null

                for ((index, partText) in textParts.withIndex()) {
                    Log.d(TAG, "Gerando áudio para PARTE ${index + 1}/${textParts.size} (Voz: $voz)")
                    val partFileName = "audio_part_${System.currentTimeMillis()}_$index.mp3"
                    // Salva o arquivo de parte no diretório do projeto
                    val partFile = File(projectDir, partFileName)

                    try {
                        val response = ttsService.generateAudio(text = partText, voice = voz)
                        if (!response.isSuccessful) {
                            val errorBody = response.errorBody()?.string() ?: "Sem corpo de erro"
                            lastApiError = "Erro HTTP ${response.code()} ao baixar áudio para PARTE ${index + 1}. Resposta: ${errorBody.take(200)}"
                            Log.e(TAG, "❌ $lastApiError")
                            success = false
                            break
                        }
                        val audioBytes = response.body()?.bytes()
                        if (audioBytes == null || audioBytes.isEmpty()) {
                            lastApiError = "Corpo da resposta do áudio para PARTE ${index + 1} está vazio."
                            Log.e(TAG, "❌ $lastApiError")
                            success = false
                            break
                        }
                        FileOutputStream(partFile).use { it.write(audioBytes) }
                        audioPartFiles.add(partFile)
                        Log.d(TAG, "✅ Áudio para PARTE ${index + 1} salvo em: ${partFile.absolutePath}")
                    } catch (e: IOException) { // Captura especificamente erros de rede/IO
                        lastApiError = "Erro de Rede/IO ao gerar áudio para PARTE ${index + 1}: ${e.message}"
                        Log.e(TAG, "❌ $lastApiError", e)
                        success = false
                        break
                    } catch (e: Exception) {
                        lastApiError = "Erro inesperado ao gerar áudio para PARTE ${index + 1}: ${e.message}"
                        Log.e(TAG, "❌ $lastApiError", e)
                        success = false
                        break
                    }
                }

                if (!success || audioPartFiles.isEmpty()) {
                    Log.e(TAG, "Falha na geração de uma ou mais partes de áudio. Limpando arquivos parciais. Último erro: $lastApiError")
                    audioPartFiles.forEach { it.delete() }
                    return@withContext Result.failure(Exception(lastApiError ?: "Falha ao gerar todas as partes de áudio."))
                }

                val finalOutputFileName = "audio_concatenated_${System.currentTimeMillis()}.mp3"
                // Salva o arquivo final no diretório do projeto
                val finalOutputFile = File(projectDir, finalOutputFileName)

                val audioPartPaths = audioPartFiles.map { it.absolutePath }

                val concatResult = concatenateAudioFilesWithFFmpeg(
                    audioPartPaths,
                    finalOutputFile.absolutePath,
                    context // Passa o contexto para uso do cacheDir
                )

                audioPartFiles.forEach {
                    if (it.exists()) {
                        if (!it.delete()) {
                            Log.w(TAG, "Falha ao deletar arquivo de áudio parcial: ${it.absolutePath}")
                        }
                    }
                }
                Log.d(TAG, "Arquivos de áudio parciais (${audioPartFiles.size}) foram processados para limpeza.")

                if (concatResult.isFailure) {
                    Log.e(TAG, "❌ Falha ao concatenar arquivos de áudio com FFmpeg: ${concatResult.exceptionOrNull()?.message}")
                    return@withContext Result.failure(concatResult.exceptionOrNull() ?: Exception("Falha desconhecida na concatenação de áudio com FFmpeg"))
                }

                val concatenatedAudioPath = concatResult.getOrThrow()
                Log.d(TAG, "✅ Áudios concatenados com sucesso com FFmpeg em: $concatenatedAudioPath")

                val srtFileNameBase = File(concatenatedAudioPath).nameWithoutExtension
                val srtResult = gerarLegendaSRT(
                    cena = srtFileNameBase,
                    filePath = concatenatedAudioPath,
                    TextoFala = cleanedOriginalText,
                    context = context, // Passa o contexto para uso futuro se necessário (ex: cache)
                    projectDir = projectDir // Passa o diretório do projeto para salvar o SRT
                )

                if (srtResult.isSuccess) {
                    Log.d(TAG, "✅ Legenda SRT gerada com sucesso: ${srtResult.getOrNull()}")
                } else {
                    Log.w(TAG, "⚠️ Falha ao gerar legenda SRT para o áudio concatenado: ${srtResult.exceptionOrNull()?.message}. O áudio ainda foi gerado.")
                }

                Result.success(concatenatedAudioPath)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro inesperado na função generate multi-part: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getAvailableVoices(idioma: String, gender: String): Result<List<String>> {
        Log.d(TAG, "🗣️ Buscando vozes para idioma: $idioma. Filtro de Gênero desejado: $gender")
        return withContext(Dispatchers.IO) {
            try {
                val response = ttsService.getVoices(gender = gender) // 'idioma' não é usado pela API atual, apenas 'gender'
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Sem corpo de erro"
                    val errorMessage = "Erro na requisição de vozes: HTTP ${response.code()} - ${errorBody.take(200)}"
                    Log.e(TAG, "❌ $errorMessage")
                    return@withContext Result.failure(IOException(errorMessage))
                }
                val voices = response.body() ?: emptyList()
                val filteredVoices = filterVoices(voices, gender)
                Log.d(TAG, "✅ Filtro concluído. Vozes encontradas para '$gender' (${filteredVoices.size}): $filteredVoices")
                Result.success(filteredVoices)
            } catch (e: IOException) { // Captura erros de rede/IO
                Log.e(TAG, "❌ Erro de Rede/IO ao buscar vozes: ${e.message}", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro inesperado ao buscar/filtrar vozes: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun gerarLegendaSRT(
        cena: String,
        filePath: String,
        TextoFala: String,
        context: Context, // Para uso futuro ou consistência, pode ser removido se não usado.
        projectDir: File   // Diretório do projeto para salvar o SRT e o JSON bruto
    ): Result<String> {
        val audioFileForSrt = File(filePath) // Este arquivo já está no projectDir
        val srtBaseName = audioFileForSrt.nameWithoutExtension
        val srtFileName = "$srtBaseName.srt"

        return withContext(Dispatchers.IO) {
            try {
                if (!projectDir.exists()) {
                     if (!projectDir.mkdirs()) {
                        val errorMsg = "Falha ao criar o diretório do projeto para SRT: ${projectDir.absolutePath}"
                        Log.e(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }
                }

                if (!audioFileForSrt.exists()) {
                    val errorMsg = "Arquivo de áudio não encontrado para SRT: $filePath"
                    Log.e(TAG, "❌ $errorMsg")
                    return@withContext Result.failure(FileNotFoundException(errorMsg))
                }

                val filePart = MultipartBody.Part.createFormData("file", audioFileForSrt.name, audioFileForSrt.asRequestBody("audio/mpeg".toMediaTypeOrNull()))
                val modelName = "whisper-large-v3-turbo"
                val modelPart = MultipartBody.Part.createFormData("model", modelName)
                val responseFormatPart = MultipartBody.Part.createFormData("response_format", "verbose_json")
                val temperaturePart = MultipartBody.Part.createFormData("temperature", "1.0")

                val timestampGranularitiesParts = listOf(
                    MultipartBody.Part.createFormData("timestamp_granularities[]", "word")
                )
                val wordGranularityRequested = true
                var finalApiPromptText = ""
                Log.d(TAG, "Prompt final para API (transcrição): '$finalApiPromptText'")
                val promptPart = if (finalApiPromptText.isNotEmpty()) MultipartBody.Part.createFormData("prompt", finalApiPromptText) else null
                val languagePart: MultipartBody.Part? = null

                Log.d(TAG, "📝 Enviando arquivo para transcrição SRT: ${audioFileForSrt.name}, Modelo: $modelName, Prompt: '${if (finalApiPromptText.isEmpty()) "(vazio)" else "..."}'")

                val response = groqService.transcribeAudio(
                    file = filePart, model = modelPart, response_format = responseFormatPart,
                    temperature = temperaturePart, timestamp_granularities = timestampGranularitiesParts,
                    language = languagePart, prompt = promptPart
                )

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Sem corpo de erro"
                    val errorMessage = "Erro HTTP ${response.code()} na transcrição SRT - ${errorBody.take(200)}"
                    Log.e(TAG, "❌ $errorMessage")
                    return@withContext Result.failure(IOException(errorMessage))
                }
                val transcriptionResponse = response.body()
                if (transcriptionResponse == null) {
                    val errorMsg = "Resposta da transcrição SRT é nula"
                    Log.e(TAG, "❌ $errorMsg")
                    return@withContext Result.failure(NullPointerException(errorMsg))
                }
                try {
                    val rawTranscriptFileName = "$srtBaseName.raw_transcript.json"
                    // Salva o JSON bruto no diretório do projeto
                    saveRawTranscriptAsJson(projectDir, transcriptionResponse, rawTranscriptFileName)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erro ao salvar resposta bruta da transcrição SRT: ${e.message}", e)
                }

                val wordsFromResponse = transcriptionResponse.words
                if (wordGranularityRequested && (wordsFromResponse == null || wordsFromResponse.isEmpty())) {
                    val errorMsg = "API não retornou 'words' para SRT como solicitado."
                    Log.e(TAG, "❌ FALHA DE DADOS (SRT): $errorMsg")
                    if (transcriptionResponse.text.isBlank()){
                         return@withContext Result.failure(Exception(errorMsg + " Nenhum texto global também."))
                    }
                     Log.w(TAG, "$errorMsg. Tentando usar texto global se não houver palavras.")
                }

                if (wordsFromResponse == null && transcriptionResponse.text.isBlank()) {
                     val errorMsg = "A transcrição (SRT) não retornou nenhum dado utilizável."
                     Log.w(TAG, "⚠️ $errorMsg")
                     return@withContext Result.failure(IllegalArgumentException(errorMsg))
                }

                val srtContent = generateSrtContent1(wordsFromResponse ?: emptyList(), 6, 1)
                // Salva o arquivo SRT no diretório do projeto
                val srtFile = saveSrtFile(projectDir, srtContent, srtFileName)
                Log.d(TAG, "✅ Arquivo SRT salvo em: ${srtFile.absolutePath}")
                Result.success(srtFile.absolutePath)

            } catch (e: IOException) { // Captura erros de rede/IO
                Log.e(TAG, "❌ Erro de Rede/IO ao gerar legenda SRT: ${e.message}", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erro inesperado ao gerar legenda SRT: ${e.message}")
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    private fun cleanText(text: String): String {
        var cleaned = text.replace(Regex("[^\\p{L}\\p{N}\\s.,!?-]"), "")
        cleaned = cleaned.replace("chihade", "cihade", ignoreCase = true)
        cleaned = cleaned.replace("alia", "allia", ignoreCase = true)
        return cleaned.trim()
    }

    private fun filterVoices(voices: List<Voice>, gender: String): List<String> {
        return if (gender.equals("Neutral", ignoreCase = true) || gender.isBlank()) {
            voices.map { it.short_name }
        } else {
            voices.filter { it.gender.equals(gender, ignoreCase = true) }.map { it.short_name }
        }
    }

    private fun generateSrtContent(allWords: List<WordTimestamp1>, maxCharsPerLine: Int, preferredWordsPerLine : Int): String {
        val srtBuilder = StringBuilder()
        var entryIndex = 1

        if (allWords.isEmpty()) {
            Log.w(TAG, "Nenhuma palavra com timestamp recebida para gerar SRT.")
            return ""
        }
        var currentWordBufferForLine = mutableListOf<WordTimestamp1>()

        for (i in allWords.indices) {
            val currentWordInfo = allWords[i]
            currentWordBufferForLine.add(currentWordInfo)
            val currentLineText = currentWordBufferForLine.joinToString(" ") { it.word }
            var forceLineBreak = false
            if (currentWordInfo.word.endsWith("?") || currentWordInfo.word.endsWith("!") || currentWordInfo.word.endsWith(".")) {
                forceLineBreak = true
            }
            else if (i + 1 < allWords.size) {
                val nextWordInfo = allWords[i + 1]
                if ((currentLineText + " " + nextWordInfo.word).length > maxCharsPerLine) {
                    forceLineBreak = true
                }
            }
            else if (currentWordBufferForLine.size >= preferredWordsPerLine) {
                forceLineBreak = true
            }
            if (i == allWords.size - 1) {
                forceLineBreak = true
            }
            if (forceLineBreak) {
                if (currentWordBufferForLine.isNotEmpty()) {
                    val lineTextOutput = currentWordBufferForLine.joinToString(" ") { it.word }
                    val firstWordTime = currentWordBufferForLine.first().start
                    val lastWordTime = currentWordBufferForLine.last().end

                    srtBuilder.append("$entryIndex\n")
                    srtBuilder.append("${formatSrtTime(firstWordTime)} --> ${formatSrtTime(lastWordTime)}\n")
                    srtBuilder.append("$lineTextOutput\n\n")
                    entryIndex++
                    currentWordBufferForLine.clear()
                }
            }
        }
        return srtBuilder.toString()
    }
    
    



// Definições de exemplo para o código compilar.
// É crucial que seu WordTimestamp real use Double para start/end.
data class WordTimestamp1(val word: String, val start: Double, val end: Double)

// --- FUNÇÃO formatSrtTime CORRIGIDA ---
// Agora ela aceita um Double (segundos) e o converte internamente.
private fun formatSrtTime1(seconds: Double): String {
    // Converte segundos (Double) para milissegundos (Long) para facilitar os cálculos
    val totalMillis = (seconds * 1000).toLong()
    
    val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
    val secs = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60
    val milliseconds = totalMillis % 1000
    
    return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, milliseconds)
}


// --- FUNÇÃO generateSrtContent CORRIGIDA ---
private fun generateSrtContent1(
    allWords: List<WordTimestamp1>,
    maxCharsPerLine: Int,
    preferredWordsPerLine: Int
): String {
    val srtBuilder = StringBuilder()
    var entryIndex = 1

    if (allWords.isEmpty()) {
        Log.w(TAG, "Nenhuma palavra com timestamp recebida para gerar SRT.")
        return ""
    }

    var currentLineBuffer = mutableListOf<WordTimestamp1>()
    
    // --- MUDANÇA 1: Alterado de Long para Double ---
    // A variável agora é Double para corresponder aos tipos de start/end.
    var previousEndTime: Double = 0.0

    for (i in allWords.indices) {
        val currentWord = allWords[i]
        currentLineBuffer.add(currentWord)

        val isEndOfSentence = currentWord.word.endsWith(".") ||
                              currentWord.word.endsWith("!") ||
                              currentWord.word.endsWith("?")
        val isLastWordOfAll = (i == allWords.size - 1)
        var nextWordWouldExceedLimit = false
        if (!isLastWordOfAll && !isEndOfSentence) {
            val nextWord = allWords[i + 1]
            val prospectiveLine = (currentLineBuffer.joinToString(" ") { it.word } + " " + nextWord.word)
            if (prospectiveLine.length > maxCharsPerLine) {
                nextWordWouldExceedLimit = true
            }
        }
        val significantWordCount = currentLineBuffer.count { it.word.length >= 4 }
        val preferredWordCountReached = significantWordCount >= preferredWordsPerLine

        if (isLastWordOfAll || isEndOfSentence || nextWordWouldExceedLimit || preferredWordCountReached) {
            
            if (currentLineBuffer.isNotEmpty()) {
                val lineText = currentLineBuffer.joinToString(" ") { it.word }
                
                // Agora todas estas variáveis são Double, resolvendo os erros
                val srtStartTime = previousEndTime
                val srtEndTime = currentLineBuffer.last().end

                srtBuilder.append("$entryIndex\n")
                srtBuilder.append("${formatSrtTime1(srtStartTime)} --> ${formatSrtTime1(srtEndTime)}\n")
                srtBuilder.append("$lineText\n\n")

                // A atribuição agora funciona, pois ambos são Double.
                previousEndTime = srtEndTime

                entryIndex++
                currentLineBuffer.clear()
            }
        }
    }

    return srtBuilder.toString()
}

    private fun saveSrtFile(projectDir: File, content: String, fileName: String): File {
        // Garante que o diretório do projeto exista (defensivo, pode já ter sido criado antes)
        if (!projectDir.exists()) projectDir.mkdirs()
        val file = File(projectDir, fileName)
        FileOutputStream(file).use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
        return file
    }

    private fun saveRawTranscriptAsJson(projectDir: File, transcriptResponse: TranscriptionResponse, fileName: String): File {
        // Garante que o diretório do projeto exista
        if (!projectDir.exists()) projectDir.mkdirs()
        val gson = GsonBuilder().setPrettyPrinting().create()
        val jsonContent = gson.toJson(transcriptResponse)
        val file = File(projectDir, fileName)
        FileOutputStream(file).use { it.write(jsonContent.toByteArray(StandardCharsets.UTF_8)) }
        Log.d(TAG, "Conteúdo JSON bruto da transcrição salvo em ${file.absolutePath}")
        return file
    }

    private fun formatSrtTime(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) {
            Log.w(TAG, "Tempo inválido para formatação SRT: $seconds. Usando 00:00:00,000")
            return "00:00:00,000"
        }
        val totalMillis = (seconds * 1000).toLong()
        val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % TimeUnit.HOURS.toMinutes(1)
        val secs = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % TimeUnit.MINUTES.toSeconds(1)
        val millis = totalMillis % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis)
    }

    fun String.encodeURLParameter(): String {
        return try {
            URLEncoder.encode(this, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao codificar URL: $this", e)
            this
        }
    }
}