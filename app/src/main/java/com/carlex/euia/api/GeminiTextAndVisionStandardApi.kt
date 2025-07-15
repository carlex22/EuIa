// File: com/carlex/euia/api/GeminiTextAndVisionStandardApi.kt
package com.carlex.euia.api

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.ServerException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import com.carlex.euia.MyApplication

/**
 * Objeto singleton para interagir com a API Gemini Standard (Flash/Pro),
 * incorporando um sistema de pool de chaves (separado por tipo) e uma lógica de múltiplas tentativas.
 * Esta API é configurada para usar chaves do tipo "text".
 */
object GeminiTextAndVisionStandardApi {
    private const val TAG = "GeminiApiStandard" // Tag diferente para diferenciar dos outros
    private const val modelName = "gemini-2.0-flash" // Ou gemini-pro-vision, gemini-1.0-pro-vision-latest
    private const val TIPO_DE_CHAVE = "text"

    // Instanciação interna e preguiçosa (lazy) das dependências.
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    /**
     * Pergunta ao Gemini usando um pool de chaves e com lógica de retry para o erro 429.
     *
     * @param pergunta O prompt de texto.
     * @param imagens A lista de caminhos de arquivo para as imagens de referência.
     * @param arquivoTexto O caminho do arquivo de texto opcional para incluir no prompt.
     * @return Um [Result] contendo a resposta do Gemini em caso de sucesso, ou uma exceção em caso de falha.
     */
    suspend fun perguntarAoGemini(
        pergunta: String,
        imagens: List<String>,
        arquivoTexto: String? = null
    ): Result<String> {
        val applicationContext = getApplicationFromContext()
        if (applicationContext == null) {
            val errorMsg = "Contexto da aplicação não disponível. Impossível verificar créditos."
            Log.e(TAG, errorMsg)
            return Result.failure(IllegalStateException(errorMsg))
        }
        
        val authViewModel = AuthViewModel(applicationContext)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                // ETAPA 1: VERIFICAR E DEDUZIR CRÉDITOS
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.TEXT_STANDARD)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.TEXT_STANDARD.cost}) deduzidos para API Standard. Prosseguindo.")

                // ETAPA 2: LÓGICA DE GERAÇÃO COM TENTATIVAS DINÂMICAS
                val keyCount = try {
                    firestore.collection("chaves_api_pool").get().await().size()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao obter contagem de chaves, usando fallback 10.", e)
                    10 // Fallback
                }
                val MAX_TENTATIVAS = if (keyCount > 0) keyCount else 10

                var tentativas = 0
                while (tentativas < MAX_TENTATIVAS) {
                    var chaveAtual: String? = null
                    try {
                        chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                        Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS ($TIPO_DE_CHAVE): Usando chave que termina em '${chaveAtual.takeLast(4)}'")

                        val generativeModel = GenerativeModel(
                            modelName = modelName,
                            apiKey = chaveAtual,
                            generationConfig = generationConfig {
                                temperature = 0.7f
                                topK = 32
                                topP = 0.8f
                            }
                        )
                        
                        val adjustedImagePaths = ajustarCaminhosDeImagem(imagens)
                        val bitmaps = processarImagens(adjustedImagePaths)
                        val textoArquivoLido = arquivoTexto?.let { lerArquivoTexto(it) }
                        
                        val content = content {
                            text(pergunta)
                            textoArquivoLido?.let { text(it) }
                            bitmaps.forEach { image(it) }
                        }

                        val response = generativeModel.generateContent(content)
                        val resposta = response.text ?: throw Exception("Resposta nula recebida do Gemini Standard")

                        Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} ($TIPO_DE_CHAVE) com a chave '${chaveAtual.takeLast(4)}'.")
                        gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                        
                        return@withContext Result.success(resposta)

                    } catch (e: ServerException) {
                        val isRateLimitError = if (e.message?.contains("429")!! || e.message?.contains("rate")!!) true else false
                        if (isRateLimitError && chaveAtual != null) {
                            Log.w(TAG, "Erro 429 (Rate Limit) ($TIPO_DE_CHAVE) na chave '${chaveAtual.takeLast(4)}'. Bloqueando-a...")
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            tentativas++
                            if (tentativas < MAX_TENTATIVAS) {
                                delay(1000)
                                continue
                            } else {
                                throw Exception("Máximo de tentativas ($MAX_TENTATIVAS) atingido para o tipo '$TIPO_DE_CHAVE'.")
                            }
                        } else {
                            Log.e(TAG, "Erro de servidor não-retentável ($TIPO_DE_CHAVE) na chave '${chaveAtual?.takeLast(4)}'", e)
                            if (chaveAtual != null) {
                               gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            }
                            throw e
                        }
                    } catch (e: NenhumaChaveApiDisponivelException) {
                        Log.e(TAG, "Não há chaves disponíveis para o tipo '$TIPO_DE_CHAVE'.", e)
                        throw e
                    }
                } // Fim do loop while

                throw Exception("Falha ao obter resposta do Gemini para o tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas.")
            
            } catch (e: Exception) {
                // ETAPA 3: REEMBOLSO EM CASO DE QUALQUER FALHA
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro na API Standard. Reembolsando ${TaskType.TEXT_STANDARD.cost} créditos.", e)
                    authViewModel.refundCredits(TaskType.TEXT_STANDARD)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private object AppContextHolder {
        var application: Application? = null
    }

    private fun getApplicationFromContext(): Application? {
        return AppContextHolder.application
    }

    fun setApplicationContext(app: Application) {
        AppContextHolder.application = app
    }

    private fun ajustarCaminhosDeImagem(imagens: List<String>): List<String> {
        return imagens.map { originalPath ->
            val originalFile = File(originalPath)
            if (originalFile.name.startsWith("thumb_")) {
                val cleanImageFile = File(originalFile.parentFile, originalFile.name.replaceFirst("thumb_", "img_"))
                if (cleanImageFile.exists()) cleanImageFile.absolutePath else originalPath
            } else {
                originalPath
            }
        }
    }

    private fun processarImagens(imagePaths: List<String>): List<Bitmap> {
        return imagePaths.mapNotNull { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar imagem para API: $path", e)
                null
            }
        }
    }

    private fun lerArquivoTexto(caminhoArquivo: String): String? {
        if (AppContextHolder.application == null) {
            Log.w(TAG, "O contexto da aplicação não foi definido. A dedução de créditos pode falhar.")
        }
        return try {
            File(caminhoArquivo).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler arquivo de texto para API: $caminhoArquivo", e)
            null
        }
    }
}