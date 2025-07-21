// File: euia/viewmodel/SceneGenerationService.kt
package com.carlex.euia.viewmodel

import android.content.Context
import android.util.Log
import com.carlex.euia.api.GeminiTextAndVisionProRestApi
import com.carlex.euia.data.*
import com.carlex.euia.prompts.CreateScenes
import com.carlex.euia.prompts.CreateScenesChat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*

private const val TAG = "SceneGenerationService"

/**
 * Serviço responsável por orquestrar a geração da estrutura de cenas (roteiro visual).
 * Encapsula a lógica de coletar dados, construir o prompt, chamar a IA e processar a resposta.
 *
 * @param context O contexto da aplicação.
 * @param userInfoDataStoreManager Acesso aos dados de perfil do usuário.
 * @param audioDataStoreManager Acesso aos dados de áudio e narrativa.
 * @param videoDataStoreManager Acesso à lista de imagens de referência.
 */
class SceneGenerationService(
    private val context: Context,
    private val userInfoDataStoreManager: UserInfoDataStoreManager,
    private val audioDataStoreManager: AudioDataStoreManager,
    private val videoDataStoreManager: VideoDataStoreManager
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Executa o fluxo completo de geração de cenas.
     * @return Um [Result] contendo a lista de [SceneLinkData] em caso de sucesso, ou uma [Exception] em caso de falha.
     */
    suspend fun generateSceneStructure(): Result<List<SceneLinkData>> {
        return try {
            // 1. Coleta todos os dados necessários dos DataStores
            val isChat = audioDataStoreManager.isChatNarrative.first()
            val narrative = audioDataStoreManager.prompt.first()
            val legendaPath = audioDataStoreManager.legendaPath.first()
            val videoTitle = audioDataStoreManager.videoTitulo.first()
            val langTone = audioDataStoreManager.userLanguageToneAudio.first()
            val targetAudience = audioDataStoreManager.userTargetAudienceAudio.first()
            val intro = audioDataStoreManager.videoObjectiveIntroduction.first()
            val content = audioDataStoreManager.videoObjectiveVideo.first()
            val outcome = audioDataStoreManager.videoObjectiveOutcome.first()
            val userName = userInfoDataStoreManager.userNameCompany.first()
            val userSegment = userInfoDataStoreManager.userProfessionSegment.first()
            val userAddress = userInfoDataStoreManager.userAddress.first()

            // 2. Constrói o prompt apropriado (monólogo ou diálogo)
            val prompt = if (isChat) {
                CreateScenesChat(narrative, userName, userSegment, userAddress, langTone, targetAudience, videoTitle, intro, content, outcome).prompt
            } else {
                CreateScenes(narrative, userName, userSegment, userAddress, langTone, targetAudience, videoTitle, intro, content, outcome).prompt
            }

            val imageRefs = videoDataStoreManager.imagensReferenciaJson.first().let { jsonString ->
                if (jsonString.isNotBlank() && jsonString != "[]") Json.decodeFromString<List<ImagemReferencia>>(jsonString) else emptyList()
            }
            val imagePathsForGemini = imageRefs.map { it.path }

            // 3. Chama a API da IA
            val geminiResult = GeminiTextAndVisionProRestApi.perguntarAoGemini(
                pergunta = prompt,
                imagens = imagePathsForGemini,
                arquivoTexto = "$legendaPath.raw_transcript.json" // Anexa o arquivo de transcrição para contexto preciso
            )

            if (geminiResult.isFailure) {
                return Result.failure(geminiResult.exceptionOrNull() ?: Exception("A IA falhou em gerar as cenas."))
            }

            val rawResponse = geminiResult.getOrThrow()
            if (rawResponse.isBlank()) {
                return Result.failure(Exception("A IA retornou uma resposta vazia para as cenas."))
            }

            // 4. Limpa e processa a resposta JSON
            val cleanedResponse = cleanGeminiResponse(rawResponse)
            val sceneList = parseSceneData(cleanedResponse, imageRefs)

            if (sceneList.isEmpty()) {
                Log.w(TAG, "O processamento do JSON da IA não resultou em nenhuma cena. Resposta limpa: $cleanedResponse")
                return Result.failure(Exception("A IA não retornou uma estrutura de cenas válida."))
            }

            Log.i(TAG, "Estrutura de ${sceneList.size} cenas gerada com sucesso pela IA.")
            Result.success(sceneList)

        } catch (e: Exception) {
            Log.e(TAG, "Falha crítica durante a geração da estrutura de cenas.", e)
            Result.failure(e)
        }
    }

    /**
     * Limpa a resposta bruta da IA, removendo marcadores de código e extraindo apenas o JSON válido.
     */
    private fun cleanGeminiResponse(response: String): String {
        var cleaned = response.trim()
        if (cleaned.startsWith("```json")) cleaned = cleaned.removePrefix("```json").trimStart()
        else if (cleaned.startsWith("```")) cleaned = cleaned.removePrefix("```").trimStart()
        if (cleaned.endsWith("```")) cleaned = cleaned.removeSuffix("```").trimEnd()
        
        val startIndex = cleaned.indexOfFirst { it == '[' || it == '{' }
        val endIndex = cleaned.indexOfLast { it == ']' || it == '}' }
        
        return if (startIndex != -1 && endIndex != -1) {
            cleaned.substring(startIndex, endIndex + 1)
        } else {
            cleaned // Retorna a string limpa se não encontrar um JSON claro
        }
    }

    /**
     * Converte a string JSON limpa em uma lista de objetos [SceneLinkData].
     */
    private fun parseSceneData(jsonString: String, allRefs: List<ImagemReferencia>): List<SceneLinkData> {
        val sceneList = mutableListOf<SceneLinkData>()
        try {
            val jsonArray = JSONArray(jsonString)
            var lastTimeEnd: Double? = 0.0

            for (i in 0 until jsonArray.length()) {
                val cenaObj = jsonArray.getJSONObject(i)
                val originalImageIndex = cenaObj.optString("FOTO_REFERENCIA", null)?.toIntOrNull()
                var refPath = ""
                var refDesc = ""
                var videoPath: String? = null
                var thumbPath: String? = null
                var isVideo = false

                if (originalImageIndex != null && originalImageIndex > 0 && originalImageIndex <= allRefs.size) {
                    val ref = allRefs[originalImageIndex - 1]
                    refPath = ref.path
                    refDesc = ref.descricao
                    if (ref.pathVideo != null) {
                        videoPath = ref.pathVideo
                        thumbPath = ref.path
                        isVideo = true
                    }
                }

                sceneList.add(SceneLinkData(
                    id = UUID.randomUUID().toString(),
                    cena = cenaObj.optString("CENA", null),
                    tempoInicio = lastTimeEnd,
                    tempoFim = cenaObj.optDouble("TEMPO_FIM", 0.0).takeIf { it > 0 },
                    imagemReferenciaPath = refPath,
                    descricaoReferencia = refDesc,
                    promptGeracao = cenaObj.optString("PROMPT_PARA_IMAGEM", null),
                    exibirProduto = cenaObj.optBoolean("EXIBIR_PRODUTO", false),
                    imagemGeradaPath = videoPath,
                    pathThumb = thumbPath,
                    isGenerating = false,
                    aprovado = isVideo,
                    promptVideo = cenaObj.optString("TAG_SEARCH_WEB", null)
                ))
                lastTimeEnd = cenaObj.optDouble("TEMPO_FIM", 0.0).takeIf { it > 0 }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Falha ao processar o JSON da IA para as cenas: '$jsonString'", e)
            return emptyList() // Retorna lista vazia em caso de erro de parsing
        }
        return sceneList
    }
}