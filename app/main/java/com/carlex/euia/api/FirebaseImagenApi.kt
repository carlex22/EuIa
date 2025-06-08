// File: api/FirebaseImagenApi.kt (ou o nome que você deu)
package com.carlex.euia.api // Certifique-se que este é o seu pacote correto

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.google.firebase.Firebase
import com.google.firebase.vertexai.GenerativeModel
import com.google.firebase.vertexai.type.Content
import com.google.firebase.vertexai.type.GenerateContentResponse
import com.google.firebase.vertexai.type.GenerationConfig
import com.google.firebase.vertexai.type.HarmCategory
import com.google.firebase.vertexai.type.HarmBlockThreshold // Import para HarmBlockThreshold
import com.google.firebase.vertexai.type.ImagePart
import com.google.firebase.vertexai.type.TextPart
import com.google.firebase.vertexai.type.InvalidStateException
import com.google.firebase.vertexai.type.FinishReason
import com.google.firebase.vertexai.type.PromptFeedback
import com.google.firebase.vertexai.type.SafetySetting // Import para SafetySetting
import com.google.firebase.vertexai.type.content // Import para o builder de escopo content {}
// As funções image() e text() dentro do builder content {} são geralmente resolvidas
// pelo import de com.google.firebase.vertexai.type.content, não precisam de imports separados.
import com.google.firebase.vertexai.vertexAI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

// Renomeando o objeto para corresponder ao erro no Worker
object FirebaseImagenApi {

    private const val TAG = "FirebaseImagenApi"

    private const val DEFAULT_WIDTH = 720
    private const val DEFAULT_HEIGHT = 1280

    private val API_TEMPERATURE: Float? = 0.4f
    private val API_TOP_K: Int? = 32
    private val API_TOP_P: Float? = 0.95f
    private val API_CANDIDATE_COUNT: Int? = 1

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // !! IMPORTANTE: CONFIRME O NOME CORRETO DO MODELO IMAGEN NA DOCUMENTAÇÃO DO FIREBASE !!
    private const val IMAGEN_MODEL_NAME = "imagen-3.0-fast-generate-001" // Exemplo, pode ser "imagen-flash", "imagen-2", etc.

    // !! IMPORTANTE: AJUSTE OS SAFETY SETTINGS CONFORME SUA POLÍTICA E AS OPÇÕES DISPONÍVEIS !!
    // Use o autocompletar da IDE para ver as opções exatas para HarmBlockThreshold.
    // Ex: BLOCK_LOW_AND_ABOVE, BLOCK_MEDIUM_AND_ABOVE, BLOCK_ONLY_HIGH.
    // O SDK pode não ter 'BLOCK_NONE'. Se precisar do mínimo de bloqueio,
    // use o valor menos restritivo disponível.
    //private val safetySettings = listOf(
        /*SafetySetting(HarmCategory.HARASSMENT, HarmBlockThreshold.BLOCK_LOW_AND_ABOVE), // EXEMPLO! VERIFIQUE NA SUA IDE!
        SafetySetting(HarmCategory.HATE_SPEECH, HarmBlockThreshold.BLOCK_LOW_AND_ABOVE), // EXEMPLO! VERIFIQUE NA SUA IDE!
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, HarmBlockThreshold.BLOCK_LOW_AND_ABOVE), // EXEMPLO! VERIFIQUE NA SUA IDE!
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, HarmBlockThreshold.BLOCK_LOW_AND_ABOVE)  // EXEMPLO! VERIFIQUE NA SUA IDE!*/
    //)

    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia>
    ): Result<String> {
        Log.i(TAG, "--- INÍCIO: ${this::class.java.simpleName}.gerarImagem (Firebase SDK `firebase-vertexai`) ---")
        Log.d(TAG, "Parâmetros de entrada:")
        Log.d(TAG, "  Cena (prefixo arquivo): $cena")
        Log.d(TAG, "  Prompt original (primeiros 100 chars): ${prompt.take(100)}...")
        Log.d(TAG, "  imagensParaUpload (contagem inicial recebida): ${imagensParaUpload.size}")

        return withContext(Dispatchers.IO) {
            var geradaBitmap: Bitmap? = null
            var caminhoArquivoGerado: String? = null
            val bitmapsDeReferenciaCarregados = mutableListOf<Bitmap>()

            try {
                val vertexAI = Firebase.vertexAI

                val genConfig = GenerationConfig.Builder().apply {
                    API_TEMPERATURE?.let { temperature = it }
                    API_TOP_K?.let { topK = it }
                    API_TOP_P?.let { topP = it }
                    API_CANDIDATE_COUNT?.let { candidateCount = it }
                }.build()
                Log.d(TAG, "Usando GenerationConfig: $genConfig")

                val imageGenerationModel: GenerativeModel = vertexAI.generativeModel(
                    modelName = IMAGEN_MODEL_NAME,
                    //safetySettings = safetySettings,
                    generationConfig = genConfig
                )
                Log.d(TAG, "Modelo Imagen '${IMAGEN_MODEL_NAME}' inicializado.")

                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()
                val larguraPreferida = videoPreferencesManager.videoLargura.first()
                val alturaPreferida = videoPreferencesManager.videoAltura.first()
                Log.d(TAG, "Preferências carregadas: projectDirName='$projectDirName', larguraPreferida=${larguraPreferida ?: "Padrão ($DEFAULT_WIDTH)"}, alturaPreferida=${alturaPreferida ?: "Padrão ($DEFAULT_HEIGHT)"}")

                var imagensEfetivasParaApi = imagensParaUpload
                
                if (false)
                if (imagensParaUpload.isEmpty()) {
                    Log.d(TAG, "Lista de imagens de referência recebida está vazia. Tentando carregar lista global.")
                    val videoDataStoreManager = VideoDataStoreManager(context)
                    val globalImagesJson = videoDataStoreManager.imagensReferenciaJson.first()
                    if (globalImagesJson.isNotBlank() && globalImagesJson != "[]") {
                        try {
                            imagensEfetivasParaApi = kotlinJsonParser.decodeFromString(ListSerializer(ImagemReferencia.serializer()), globalImagesJson)
                            Log.d(TAG, "Carregada lista global com ${imagensEfetivasParaApi.size} imagens.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao desserializar lista global de imagens: ${e.message}", e)
                        }
                    } else {
                        Log.d(TAG, "Lista global de imagens do VideoDataStoreManager também está vazia ou é inválida.")
                    }
                } else {
                    Log.d(TAG, "Usando a lista de imagens de referência fornecida com ${imagensEfetivasParaApi.size} itens.")
                }

                imagensEfetivasParaApi.forEach { imagemRef ->
                    var pathToLoadForApi = imagemRef.path
                    val originalFileNameForApi = File(imagemRef.path).name
                    if (originalFileNameForApi.startsWith("thumb_")) {
                        val cleanImageNameForApi = originalFileNameForApi.replaceFirst("thumb_", "img_")
                        val parentDirForApi = File(imagemRef.path).parentFile
                        if (parentDirForApi != null) {
                            val cleanImageFileForApi = File(parentDirForApi, cleanImageNameForApi)
                            if (cleanImageFileForApi.exists()) {
                                pathToLoadForApi = cleanImageFileForApi.absolutePath
                            }
                        }
                    }
                    val imageFile = File(pathToLoadForApi)
                    if (imageFile.exists()) {
                        try {
                            val refBitmap = BitmapUtils.decodeSampledBitmapFromUri(
                                context, Uri.fromFile(imageFile),
                                larguraPreferida ?: DEFAULT_WIDTH, alturaPreferida ?: DEFAULT_HEIGHT
                            )
                            if (refBitmap != null) {
                                bitmapsDeReferenciaCarregados.add(refBitmap)
                                Log.d(TAG, "Bitmap de referência '${imageFile.name}' carregado.")
                            } else {
                                Log.w(TAG, "Falha ao decodificar imagem de referência: $pathToLoadForApi")
                            }
                        } catch (e: OutOfMemoryError) {
                            Log.e(TAG, "OutOfMemoryError ao carregar bitmap de referência: $pathToLoadForApi.", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao carregar bitmap de referência: $pathToLoadForApi", e)
                        }
                    } else {
                        Log.w(TAG, "Arquivo de imagem de referência não encontrado: $pathToLoadForApi")
                    }
                }

                var promptLimpo = prompt.replace("\"", "")
                val allImageContextText = StringBuilder()
                if (imagensEfetivasParaApi.isNotEmpty()) {
                    allImageContextText.appendLine("\n\n--- Contexto Adicional das Imagens de Referência ---")
                     imagensEfetivasParaApi.forEachIndexed { index, imagemRef ->
                        var pathToLoadDesc = imagemRef.path
                        val originalFileNameDesc = File(imagemRef.path).name
                        if (originalFileNameDesc.startsWith("thumb_")) {
                             val cleanImageNameDesc = originalFileNameDesc.replaceFirst("thumb_", "img_")
                            val parentDirDesc = File(imagemRef.path).parentFile
                            if (parentDirDesc != null) {
                                val cleanImageFileDesc = File(parentDirDesc, cleanImageNameDesc)
                                if (cleanImageFileDesc.exists()) pathToLoadDesc = cleanImageFileDesc.absolutePath
                            }
                        }
                        allImageContextText.appendLine("Imagem de Referência ${index + 1} (Arquivo base: ${File(pathToLoadDesc).name}):")
                        if (imagemRef.descricao.isNotBlank() && imagemRef.descricao != "(Pessoas: Não)" && imagemRef.descricao != "(Pessoas: Sim)") {
                            allImageContextText.appendLine("  Descrição: \"${imagemRef.descricao}\"")
                        } else {
                            allImageContextText.appendLine("  Descrição: null")
                        }
                        if (imagemRef.pathVideo != null) {
                            allImageContextText.appendLine("  Tipo Original: Vídeo (usando frame como referência visual)")
                            imagemRef.videoDurationSeconds?.let { duration ->
                                allImageContextText.appendLine("  Duração do Vídeo Original: $duration segundos")
                            }
                        } else {
                            allImageContextText.appendLine("  Tipo Original: Imagem Estática")
                        }
                        allImageContextText.appendLine("  Contém Pessoas (na referência original): ${if (imagemRef.containsPeople) "Sim" else "Não"}")
                        allImageContextText.appendLine()
                    }
                    allImageContextText.appendLine("--- Fim do Contexto Adicional ---")
                }

                val finalPromptComContextoDasImagens = if (allImageContextText.toString().lines().count { it.isNotBlank() } > 2) {
                    promptLimpo + allImageContextText.toString()
                } else {
                    promptLimpo
                }

                val refImageDataStoreManager = RefImageDataStoreManager(context)
                val refObjetoDetalhesJson = refImageDataStoreManager.refObjetoDetalhesJson.first()

                val promptFinalParaApi = buildString {
                    append(finalPromptComContextoDasImagens)
                    if (refObjetoDetalhesJson.isNotBlank() && refObjetoDetalhesJson != "{}") {
                        appendLine()
                        appendLine()
                        append("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES DE OBJETOS OU ROUPAS DA IMAGEN ---")
                        appendLine()
                        append(refObjetoDetalhesJson)
                        appendLine()
                        append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---")
                    }
                }
                Log.i(TAG, "Prompt FINAL para API (primeiros 300 chars): ${promptFinalParaApi.take(300)}...")

                val inputContent = content {
                    text(promptFinalParaApi)
                    bitmapsDeReferenciaCarregados.forEach { refBitmap ->
                        image(refBitmap)
                    }
                }
                Log.d(TAG, "Conteúdo da requisição montado com ${bitmapsDeReferenciaCarregados.size} imagens de referência.")

                if (bitmapsDeReferenciaCarregados.isEmpty() && imagensEfetivasParaApi.isNotEmpty()) {
                    Log.w(TAG, "Nenhuma imagem de referência pôde ser carregada como Bitmap.")
                }

                Log.i(TAG, "Enviando request à API Gemini (Imagen) via Firebase SDK...")
                val response: GenerateContentResponse = imageGenerationModel.generateContent(inputContent)
                Log.i(TAG, "Resposta da API (Firebase SDK) recebida.")

                val firstCandidate = response.candidates?.firstOrNull()
                if (firstCandidate == null) {
                    val blockReason = response.promptFeedback?.blockReason
                    val blockMessage = response.promptFeedback?.blockReasonMessage
                    Log.e(TAG, "Nenhum candidato na resposta. Bloqueio do prompt: $blockReason - $blockMessage")
                    return@withContext Result.failure(Exception("Nenhum candidato na resposta da API. Bloqueio: $blockReason"))
                }

                val imagePart = firstCandidate.content.parts.filterIsInstance<ImagePart>().firstOrNull()

                if (imagePart != null) {
                    geradaBitmap = imagePart.image

                    if (geradaBitmap == null) {
                         // Se imagePart.image for nulo, a imagem não foi retornada como Bitmap direto.
                         // Acesso a 'blob' ou métodos como 'getDataAsByteArray()' dependeriam da API exata da ImagePart
                         // da sua versão do SDK, que não temos confirmação aqui.
                         // Por enquanto, consideramos falha se imagePart.image for nulo.
                        Log.e(TAG, "ImagePart recebida, mas Bitmap não pôde ser extraído (imagePart.image é nulo).")
                        return@withContext Result.failure(Exception("Falha ao extrair Bitmap da ImagePart."))
                    }
                    Log.d(TAG, "Bitmap da imagem gerada obtido com sucesso.")

                    // Salvar o bitmap em arquivo
                    if (projectDirName.isNullOrBlank()) {
                        Log.e(TAG, "Nome do diretório do projeto é nulo ou vazio.")
                        return@withContext Result.failure(Exception("Nome do diretório do projeto não configurado."))
                    }
                    val projectDir = File(context.filesDir, projectDirName)
                    if (!projectDir.exists()) projectDir.mkdirs()
                    val imagesDir = File(projectDir, "images")
                    if (!imagesDir.exists()) imagesDir.mkdirs()

                    val outputFileName = "${cena}_${System.currentTimeMillis()}.jpg"
                    val outputFile = File(imagesDir, outputFileName)

                    FileOutputStream(outputFile).use { out ->
                        val successCompress = geradaBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        if (!successCompress) {
                            Log.e(TAG, "Falha ao comprimir/salvar a imagem gerada: ${outputFile.absolutePath}")
                            return@withContext Result.failure(Exception("Falha ao salvar imagem gerada."))
                        }
                    }
                    caminhoArquivoGerado = outputFile.absolutePath
                    Log.i(TAG, "Imagem gerada salva com sucesso em: $caminhoArquivoGerado")
                    Result.success(caminhoArquivoGerado)

                } else {
                    val errorTextFromParts = firstCandidate.content.parts
                        .filterIsInstance<TextPart>()
                        .joinToString("\n") { it.text }
                    val finishReason = firstCandidate.finishReason
                    val safetyRatingsMsg = firstCandidate.safetyRatings.joinToString { "${it.category}: ${it.probability}" }

                    var errorMessage = "Imagem não foi gerada pela API (Firebase SDK)."
                    if (errorTextFromParts.isNotBlank()) errorMessage += " Detalhe do texto: $errorTextFromParts"
                    errorMessage += " Motivo da finalização: $finishReason."
                    if (finishReason == FinishReason.SAFETY) {
                        errorMessage += " Possível bloqueio por política de segurança. Ratings: $safetyRatingsMsg"
                    }
                    if (response.promptFeedback?.blockReason != null) {
                         errorMessage += " Feedback do prompt: Bloqueado por ${response.promptFeedback?.blockReason}. Detalhes: ${response.promptFeedback?.blockReasonMessage}"
                    }
                    Log.e(TAG, errorMessage)
                    Result.failure(Exception(errorMessage))
                }

            } catch (e: InvalidStateException) {
                Log.e(TAG, "Erro de estado inválido com Firebase SDK (verifique API habilitada, nome do modelo, projeto vinculado): ${e.message}", e)
                Result.failure(Exception("Erro de configuração ou permissão com a API Gemini (SDK): ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante a geração de imagem com Firebase SDK: ${e.message}", e)
                Result.failure(Exception("Erro ao gerar imagem (SDK): ${e.message}", e))
            } finally {
                Log.d(TAG, "Reciclando bitmaps...")
                BitmapUtils.safeRecycle(geradaBitmap, "${this::class.java.simpleName} (bitmap gerado API)")
                bitmapsDeReferenciaCarregados.forEachIndexed { index, bmp ->
                    BitmapUtils.safeRecycle(bmp, "${this::class.java.simpleName} (ref bitmap $index)")
                }
                Log.d(TAG, "Bitmaps reciclados.")
                Log.i(TAG, "--- FIM: ${this::class.java.simpleName}.gerarImagem (Firebase SDK `firebase-vertexai`) ---")
            }
        }
    }
}