package com.carlex.euia.worker // Pacote do Worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log // Importa Log

// Importar classes necessárias dos arquivos existentes e de dados
import com.carlex.euia.api.GeminiTextAndVisionStandardApi // Importa a API Gemini
import com.carlex.euia.data.RefImageDataStoreManager // Para salvar os resultados da análise
import com.carlex.euia.data.VideoDataStoreManager // Para obter dados necessários (como caminhos de imagens e prompt de extras)
import com.carlex.euia.data.AudioDataStoreManager // Importar AudioDataStoreManager para o título
import com.carlex.euia.prompts.DescriptionClottings // Para gerar o prompt
import com.carlex.euia.data.ImagemReferencia // Para a estrutura dos dados de imagem

// Importações de serialização
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString

// Importações JSON antigas (manter como estão no ViewModel original)
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Importações de Coroutines e Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

// Importações WorkManager para Input/Output Data
import androidx.work.Data
import androidx.work.workDataOf

// Importações para a data class JsonDetail (será usada apenas internamente no worker para parse)
import kotlinx.serialization.Serializable

// Data class para representar um par chave-valor editável do JSON
// Mover para um arquivo de dados comum se for usada em múltiplos locais fora do Worker/ViewModel
@Serializable
data class JsonDetail(
    val key: String,
    var value: String
)

// Define TAG para logging
private const val TAG_WORKER = "RefImageAnalysisWorker"

// Define uma TAG para este Worker para facilitar a consulta pelo ViewModel
const val TAG_REF_IMAGE_ANALYSIS_WORK = "ref_image_analysis_work"


class RefImageAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // Inicializar DataStore Managers (usar applicationContext do Worker)
    private val refImageDataStoreManager = RefImageDataStoreManager(appContext)
    private val videoDataStoreManager = VideoDataStoreManager(appContext) // Still needed for extras and image paths
    private val audioDataStoreManager = AudioDataStoreManager(appContext) // Added for video title
    private val json = Json { ignoreUnknownKeys = true } // Usar o mesmo Json configurado

    // Definir chaves para Input/Output Data (se necessário)
    companion object {
        // Pode adicionar chaves para input data se o prompt ou caminhos de imagem
        // viessem diretamente do ViewModel, mas neste caso o Worker vai buscá-los.
        // Podemos usar output data para mensagens de erro/status.
        const val KEY_ERROR_MESSAGE = "error_message"
        
        const val TAG_REF_IMAGE_ANALYSIS_WORK = "ref_image_analysis_work" // Nome da tag
      
        // Opcional: Chave para o número de itens analisados, etc.
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        Log.d(TAG_WORKER, "doWork started for RefImageAnalysisWorker.")

        try {
            // 1. Obter dados necessários (prompt e caminhos de imagem)
            // O Worker busca estes dados diretamente dos DataStores
            Log.d(TAG_WORKER, "Buscando dados dos DataStores...")
            // UPDATED: Read title from AudioDataStoreManager
            val tituloValue = audioDataStoreManager.videoTitulo.first()
            val extrasValue = ""//videoDataStoreManager.extras.first()
            Log.d(TAG_WORKER, "Obtido título: '$tituloValue' (de AudioDataStore) e extras: '$extrasValue' dos DataStores para criar prompt.")

            val currentPrompt = DescriptionClottings(tituloValue, extrasValue).prompt
            Log.d(TAG_WORKER, "Prompt para análise criado: '$currentPrompt'")

            // Obter os caminhos das imagens do VideoDataStoreManager
            // Precisamos primeiro obter a string JSON e então parsear para a lista de ImagemReferencia
            val videoImagensReferenciaJsonString = videoDataStoreManager.imagensReferenciaJson.first()
            Log.d(TAG_WORKER, "Obtida string JSON de imagens de referência (size: ${videoImagensReferenciaJsonString.length}). Parseando...")

            val imageRefs = try {
                 if (videoImagensReferenciaJsonString.isNotBlank() && videoImagensReferenciaJsonString != "[]") {
                     json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), videoImagensReferenciaJsonString)
                 } else {
                     emptyList()
                 }
             } catch (e: Exception) {
                 Log.e(TAG_WORKER, "Falha parse JSON imagensReferenciaJsonString do DataStore: ${e.message}", e)
                 // Se não conseguir parsear as referências de imagem salvas, é um erro fatal para a análise.
                 val outputData = workDataOf(KEY_ERROR_MESSAGE to "Erro ao carregar referências de imagem salvas: ${e.message}") // TODO: String resource
                 return Result.failure(outputData)
             }

            val imagePaths = imageRefs.map { it.path }
            Log.d(TAG_WORKER, "Lista de ImagemReferencia obtida para análise (count: ${imageRefs.size}).")


            if (imagePaths.isEmpty()) {
                Log.w(TAG_WORKER, "Nenhum caminho de imagem encontrado para análise. Pulando chamada ao Gemini.")
                 val outputData = workDataOf(KEY_ERROR_MESSAGE to "Não há imagens de referência para analisar.") // TODO: String resource
                return Result.failure(outputData) // Considerar falha se não há imagens para analisar
            }

            Log.d(TAG_WORKER, "Caminhos de imagem encontrados. Chamando gerarDetalhesVisuais...")

            // 2. Executar a lógica pesada (chamar Gemini e processar a resposta)
            val jsonArrayResult = try {
                // Chama a função movida do ViewModel
                gerarDetalhesVisuais(currentPrompt, imagePaths)
            } catch (e: CancellationException) {
                Log.d(TAG_WORKER, "Worker cancelado durante a chamada gerarDetalhesVisuais.")
                val outputData = workDataOf(KEY_ERROR_MESSAGE to "Análise cancelada.") // TODO: String resource
                return Result.failure(outputData) // Retorna failure em caso de cancelamento
            } catch (e: Exception) {
                 Log.e(TAG_WORKER, "Erro durante a chamada gerarDetalhesVisuais: ${e.message}", e)
                 val outputData = workDataOf(KEY_ERROR_MESSAGE to "Erro durante a análise visual: ${e.message}") // TODO: String resource
                 return Result.failure(outputData) // Retorna failure em caso de erro
            }
             Log.d(TAG_WORKER, "gerarDetalhesVisuais concluído. Resultado JSONArray length: ${jsonArrayResult.length()}")


            // 3. Processar o resultado e salvar no DataStore
            if (jsonArrayResult.length() > 0) {
                Log.d(TAG_WORKER, "Resultado da análise não vazio. Processando e achatando JSONArray para salvar...")
                try {
                    val flattenedMap = processGeminiJsonResponse(jsonArrayResult)
                    Log.d(TAG_WORKER, "JSONArray processado e achatado em Map com ${flattenedMap.size} itens.")

                    // Converta o mapa achatado de volta para string JSON para salvar
                    val jsonStringResult = json.encodeToString(flattenedMap)
                    Log.d(TAG_WORKER, "Mapa achatado convertido para string JSON para salvar: $jsonStringResult")

                    // Salva o resultado final no DataStore da nova View (RefImageDataStoreManager)
                    refImageDataStoreManager.setRefObjetoDetalhesJson(jsonStringResult)
                    Log.d(TAG_WORKER, "Detalhes de análise achatados salvos com sucesso como string JSON no DataStore.")

                    // Retorna sucesso
                    Log.d(TAG_WORKER, "doWork finished successfully.")
                    return Result.success()

                } catch (e: Exception) {
                    Log.e(TAG_WORKER, "Erro ao processar, achatar e salvar resultado da análise Gemini: ${e.message}", e)
                    val outputData = workDataOf(KEY_ERROR_MESSAGE to "Erro ao processar/salvar resultado da análise: ${e.message}") // TODO: String resource
                    return Result.failure(outputData) // Retorna failure em caso de erro ao salvar
                }

            } else {
                Log.w(TAG_WORKER, "Análise do Gemini retornou um JSONArray vazio após processamento. Nenhum detalhe para salvar.")
                val outputData = workDataOf(KEY_ERROR_MESSAGE to "Gemini analisou, mas não retornou detalhes visuais.") // TODO: String resource
                return Result.failure(outputData) // Retorna failure ou sucesso dependendo se JSONArray vazio é considerado erro
                // Neste caso, considerando falha por não obter detalhes
            }

        } catch (e: Exception) {
            Log.e(TAG_WORKER, "Erro inesperado durante a execução do Worker: ${e.message}", e)
             // Captura qualquer outra exceção não tratada
            val outputData = workDataOf(KEY_ERROR_MESSAGE to "Ocorreu um erro inesperado no Worker: ${e.message}") // TODO: String resource
            return Result.failure(outputData)
        }
    }

    // ============================================
    // --- Funções Auxiliares Movidas de RefImageViewModel ---
    // ============================================

     /**
      * Usa a API Gemini para obter detalhes visuais.
      * Movida de RefImageViewModel.
      * Retorna um JSONArray (pode ser vazio) ou lança exceção em caso de erro fatal.
      */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun gerarDetalhesVisuais(
        prompt: String, imagensPatch: List<String>
    ): JSONArray {
        Log.d(TAG_WORKER, "Iniciando gerarDetalhesVisuais com prompt: '$prompt', imagensPatch count: ${imagensPatch.size}")

        var jsonArray = org.json.JSONArray()
        Log.d(TAG_WORKER, "Gerando detalhes via Gemini...")
        Log.d(TAG_WORKER, "Chamando GeminiApiN.perguntarAoGemini...")

        // A chamada da API Gemini precisa ser feita em um contexto IO, que CoroutineWorker já fornece por padrão.
        // Se GeminiApiN já é suspend, a chamada direta é ok.
        val respostaResult = try {
            GeminiTextAndVisionStandardApi.perguntarAoGemini(prompt, imagensPatch, "") // Assume que esta é uma função suspend
        } catch (e: CancellationException) {
            Log.d(TAG_WORKER, "Chamada Gemini para prompt cancelada no Worker.")
            throw e // Relaça a CancellationException
        } catch (e: Exception) {
             Log.e(TAG_WORKER, "Erro não esperado na chamada Gemini (no Worker): ${e.message}", e)
             // Não lança exceção aqui, retorna um Result.failure no doWork,
             // ou trata o erro internamente e retorna JSONArray vazio.
             // Vamos retornar JSONArray vazio e deixar doWork decidir o Result.
             return org.json.JSONArray().also {
                  // Opcional: Logar ou reportar erro via OutputData, mas a falha geral no doWork já fará isso.
             }
        }

        if (respostaResult.isSuccess) {
            val resposta = respostaResult.getOrNull() ?: ""
            Log.d(TAG_WORKER, "Resposta bruta Gemini: $resposta")
            if (resposta.isBlank()) {
                Log.e(TAG_WORKER, "Resposta do Gemini em branco.")
                Log.d(TAG_WORKER, "Retornando JSONArray vazio (resposta em branco).")
                return org.json.JSONArray()
            }

            var respostaLimpa = ""

            try {
                // Lógica de ajuste de resposta (copiada do ViewModel)
                respostaLimpa = resposta.trim()
                if (respostaLimpa.startsWith("```json")) {
                     respostaLimpa = respostaLimpa.removePrefix("```json").trimStart()
                } else if (respostaLimpa.startsWith("```")) {
                     respostaLimpa = respostaLimpa.removePrefix("```").trimStart()
                }
                if (respostaLimpa.endsWith("```")) {
                     respostaLimpa = respostaLimpa.removeSuffix("```").trimEnd()
                }

                Log.d(TAG_WORKER, "Resposta Gemini APÓS remoção de markdown: '$respostaLimpa'")

                // Lógica de parsing (copiada do ViewModel)
                if (respostaLimpa.startsWith("[")) {
                     Log.d(TAG_WORKER, "Resposta limpa começa com '[', tentando parsear como JSONArray.")
                    val parsedArray = org.json.JSONArray(respostaLimpa)
                     Log.d(TAG_WORKER, "JSONArray parseado com sucesso. Length: ${parsedArray.length()}")
                    jsonArray = parsedArray

                } else if (respostaLimpa.startsWith("{")) {
                     Log.w(TAG_WORKER, "Resposta limpa começa com '{'. Esperava-se um JSONArray. Se o ViewModel precisa de um JSONArray, a resposta da API ou o parsing precisam ser ajustados.")
                     try {
                         val jsonObj = org.json.JSONObject(respostaLimpa)
                         jsonArray.put(jsonObj)
                         Log.d(TAG_WORKER, "Parsed JSONObject and wrapped in a JSONArray for returning.")
                     } catch (e: JSONException) {
                         Log.e(TAG_WORKER, "Falha ao parsear resposta limpa como JSONObject (no Worker): ${e.message}", e)
                     }
                }
                else {
                     Log.w(TAG_WORKER, "Resposta limpa não é um JSON Object ou Array. Não é possível parsear.")
                }

            } catch (e: JSONException) {
                 Log.e(TAG_WORKER, "Falha ao parsear JSON da resposta Gemini (no Worker): ${e.message}. Resposta limpa (primeiros 100): '${respostaLimpa.take(100)}'")
                 // Em caso de erro de parsing, retorna JSONArray vazio.
                 jsonArray = org.json.JSONArray()
            } catch (e: Exception) {
                 Log.e(TAG_WORKER, "Erro inesperado ao processar resposta Gemini (no Worker): ${e.message}. Resposta bruta (primeiros 100): '${resposta.take(100)}'", e)
                 // Em caso de erro inesperado no processamento, retorna JSONArray vazio.
                 jsonArray = org.json.JSONArray()
            }
        } else {
            val exception = respostaResult.exceptionOrNull()
            Log.e(TAG_WORKER, "Falha na API Gemini (no Worker): ${exception?.message}", exception)
            // Em caso de falha na API, retorna JSONArray vazio.
            jsonArray = org.json.JSONArray()
        }

        Log.d(TAG_WORKER, "Fim de gerarDetalhesVisuais (no Worker). Retornando JSONArray (Length: ${jsonArray.length()}).")
        return jsonArray // Retorna o JSONArray (pode estar vazio em caso de erros)
    }


    /**
     * Achata um JSONObject aninhado em um mapa plano.
     * Movida de RefImageViewModel.
     */
    private fun flattenObject(jsonObject: JSONObject, prefix: String, map: MutableMap<String, String>) {
       val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = jsonObject.get(key)

            when (value) {
                is JSONObject -> {
                    flattenObject(value, fullKey, map)
                }
                is JSONArray -> {
                    val arrayString = buildString {
                        for (i in 0 until value.length()) {
                            if (i > 0) append(", ")
                            append(value.opt(i)?.toString() ?: "null")
                        }
                    }
                    map[fullKey] = arrayString
                    Log.d(TAG_WORKER, "Achata chave: '$fullKey' com valor de array: '$arrayString'")
                }
                is Boolean, is Number, is String -> {
                    map[fullKey] = value.toString()
                    Log.d(TAG_WORKER, "Achata chave: '$fullKey' com valor primitivo: '$value'")
                }
                else -> {
                     Log.w(TAG_WORKER, "Tipo de valor inesperado para chave '$fullKey': ${value.javaClass.name}. Convertendo para string.")
                     map[fullKey] = value.toString()
                }
            }
        }
    }

    /**
     * Processa o JSONArray da resposta Gemini e achata-o em um mapa plano.
     * Movida de RefImageViewModel.
     */
    private fun processGeminiJsonResponse(jsonArray: JSONArray): Map<String, String> {
        val flattenedMap = mutableMapOf<String, String>()
        Log.d(TAG_WORKER, "Iniciando processGeminiJsonResponse (no Worker) com JSONArray length: ${jsonArray.length()}")
        for (i in 0 until jsonArray.length()) {
            try {
                val jsonObject = jsonArray.getJSONObject(i)
                flattenObject(jsonObject, "", flattenedMap)
            } catch (e: JSONException) {
                Log.e(TAG_WORKER, "Erro processando elemento $i do JSONArray da resposta Gemini (no Worker)", e)
                // Continua processando os outros elementos mesmo se um falhar.
            }
        }
        Log.d(TAG_WORKER, "Fim de processGeminiJsonResponse (no Worker). Map resultante size: ${flattenedMap.size}")
        return flattenedMap
    }

}