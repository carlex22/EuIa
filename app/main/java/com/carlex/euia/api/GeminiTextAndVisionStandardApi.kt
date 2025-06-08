// File: com/carlex/euia/api/GeminiTextAndVisionStandardApi.kt
package com.carlex.euia.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.*
import com.google.ai.client.generativeai.type.generationConfig // Corrigido para minúsculo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import com.carlex.euia.BuildConfig

object GeminiTextAndVisionStandardApi {
    private const val TAG = "GeminiApiStandard" // Tag diferente para diferenciar dos outros
    
    private const val apiKey = BuildConfig.GEMINI_API_KEY  
    private const val modelName = "gemini-2.0-flash" // Mantido como no seu original, pode ser gemini-pro-vision ou gemini-1.0-pro-vision-latest

    suspend fun perguntarAoGemini(pergunta: String, imagens: List<String>, arquivoTexto: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Iniciando chamada ao Gemini Standard com ${imagens.size} imagens")
                Log.d(TAG, "Prompt (início): ${pergunta.take(100)}")
          
                // <<< --- AJUSTE AQUI --- >>>
                val adjustedImagePaths = imagens.map { originalPath ->
                    val originalFile = File(originalPath)
                    val originalFileName = originalFile.name
                    if (originalFileName.startsWith("thumb_")) {
                        val cleanImageName = originalFileName.replaceFirst("thumb_", "img_")
                        val parentDir = originalFile.parentFile
                        if (parentDir != null) {
                            val cleanImageFile = File(parentDir, cleanImageName)
                            if (cleanImageFile.exists()) {
                                Log.d(TAG, "Gemini Standard: Usando imagem limpa '$cleanImageName' em vez de thumbnail '$originalFileName'.")
                                cleanImageFile.absolutePath
                            } else {
                                Log.w(TAG, "Gemini Standard: Imagem limpa '$cleanImageName' não encontrada, usando thumbnail '$originalFileName' como fallback.")
                                originalPath // Fallback para a thumbnail se a limpa não existir
                            }
                        } else {
                            originalPath // Fallback se o diretório pai não puder ser determinado
                        }
                    } else {
                        originalPath // Mantém o caminho original se não começar com "thumb_"
                    }
                }
                Log.d(TAG, "Caminhos de imagem ajustados para API: $adjustedImagePaths")
                // <<< --- FIM DO AJUSTE --- >>>
                
                val bitmaps = processarImagens(adjustedImagePaths) // Usa os caminhos ajustados
                if (bitmaps.isEmpty() && adjustedImagePaths.isNotEmpty()) { // Verifica com adjustedImagePaths
                    // Não lança exceção aqui, permite chamada à API apenas com texto se for o caso
                    Log.w(TAG, "Nenhuma imagem válida foi carregada para a API após ajuste de caminhos.")
                }
                val textoArquivo = arquivoTexto?.let { lerArquivoTexto(it) }
                
                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig { // Usando a importação correta
                        temperature = 0.7f // Ajustado para valores típicos para Standard/Pro Vision
                        topK = 32
                        topP = 0.8f
                        // maxOutputTokens = 2048 // Pode ser necessário para respostas mais longas
                    }
                )
                
                val content = content {
                    text(pergunta)
                    textoArquivo?.let { text(it) }
                    bitmaps.forEach { bitmap ->
                        image(bitmap)
                    }
                }
                
                val response = generativeModel.generateContent(content)
                
                val resposta = response.text ?: run {
                    Log.e(TAG, "Resposta da API Gemini Standard está nula. Detalhes da resposta: ${response.candidates?.joinToString { it.toString() }}")
                    throw Exception("Resposta nula recebida do Gemini Standard")
                }
                Log.d(TAG, "Resposta recebida com sucesso do Gemini Standard (tamanho: ${resposta.length})")
                Result.success(resposta)
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro na API Gemini Standard", e)
                Result.failure(Exception("Falha na comunicação com o Gemini Standard: ${e.message}"))
            }
        }
    }
    
    private fun processarImagens(imagePaths: List<String>): List<Bitmap> {
        return imagePaths.mapNotNull { path ->
            try {
                val imageFile = File(path)
                if (!imageFile.exists()) {
                    Log.w(TAG, "Arquivo não encontrado: $path")
                    return@mapNotNull null
                }
                
                // Opções de decodificação podem ser ajustadas se necessário
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888 // ARGB_8888 é geralmente melhor para qualidade
                    // inSampleSize pode ser calculado se as imagens forem muito grandes
                }
                
                BitmapFactory.decodeFile(path, options)?.also {
                    Log.d(TAG, "Imagem carregada para API: $path (${it.width}x${it.height})")
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError ao processar imagem $path para API", e)
                null
            }
            catch (e: Exception) {
                Log.e(TAG, "Erro ao processar imagem $path para API", e)
                null
            }
        }
    }
    
    private fun lerArquivoTexto(caminhoArquivo: String): String? {
        return try {
            val arquivo = File(caminhoArquivo)
            if (!arquivo.exists()) {
                Log.w(TAG, "Arquivo de texto não encontrado: $caminhoArquivo")
                return null
            }
            
            arquivo.readText().also {
                Log.d(TAG, "Texto lido do arquivo (${it.length} caracteres)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler arquivo de texto $caminhoArquivo", e)
            null
        }
    }
}