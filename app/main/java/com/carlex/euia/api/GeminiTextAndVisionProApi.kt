// File: com/carlex/euia/api/GeminiTextAndVisionProApi.kt
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

object GeminiTextAndVisionProApi {
    private const val TAG = "GeminiApiPro" // Tag diferente
    
    private const val apiKey = BuildConfig.GEMINI_API_KEY
    private const val modelName = "gemini-2.5-flash-preview-04-17" // Ou o modelo Pro Vision correto ex: "gemini-1.5-pro-preview-0409" ou "gemini-1.0-pro-vision-latest"

    suspend fun perguntarAoGemini(pergunta: String, imagens: List<String>, arquivoTexto: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Iniciando chamada ao Gemini Pro com ${imagens.size} imagens")
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
                                Log.d(TAG, "Gemini Pro: Usando imagem limpa '$cleanImageName' em vez de thumbnail '$originalFileName'.")
                                cleanImageFile.absolutePath
                            } else {
                                Log.w(TAG, "Gemini Pro: Imagem limpa '$cleanImageName' não encontrada, usando thumbnail '$originalFileName' como fallback.")
                                originalPath
                            }
                        } else {
                            originalPath
                        }
                    } else {
                        originalPath
                    }
                }
                Log.d(TAG, "Caminhos de imagem ajustados para API: $adjustedImagePaths")
                // <<< --- FIM DO AJUSTE --- >>>
                
                val bitmaps = processarImagens(adjustedImagePaths)
                if (bitmaps.isEmpty() && adjustedImagePaths.isNotEmpty()) {
                    Log.w(TAG, "Nenhuma imagem válida foi carregada para a API Pro após ajuste de caminhos.")
                }
                val textoArquivo = arquivoTexto?.let { lerArquivoTexto(it) }
                
                val generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = apiKey,
                        generationConfig = generationConfig { temperature = 0.3f; topK = 40; topP = 0.95f }
                    )
                
                /*
                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey,
                    generationConfig = generationConfig { // Usando a importação correta
                        temperature = 0.7f // Ajustado para valores típicos para Standard/Pro Vision
                        topK = 32
                        topP = 0.8f     // Pode ajustar conforme necessidade
                        // maxOutputTokens = 8192 // Modelos Pro podem ter limites maiores
                    }
                )*/
                
                val content = content {
                    text(pergunta)
                    textoArquivo?.let { text(it) }
                    bitmaps.forEach { bitmap ->
                        image(bitmap)
                    }
                }
                
                val response = generativeModel.generateContent(content)
                
                val resposta = response.text ?: run {
                    Log.e(TAG, "Resposta da API Gemini Pro está nula. Detalhes da resposta: ${response.candidates?.joinToString { it.toString() }}")
                    throw Exception("Resposta nula recebida do Gemini Pro")
                }
                Log.d(TAG, "Resposta recebida com sucesso do Gemini Pro (tamanho: ${resposta.length})")
                Result.success(resposta)
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro na API Gemini Pro", e)
                Result.failure(Exception("Falha na comunicação com o Gemini Pro: ${e.message}"))
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
                
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
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