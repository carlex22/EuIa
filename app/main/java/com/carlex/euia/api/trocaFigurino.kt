
// File: euia/api/ProvadorVirtual.kt
package com.carlex.euia.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import android.graphics.*
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import kotlinx.serialization.json.JsonElement // Certifique-se de importar JsonElement



object ProvadorVirtual {
    private const val TAG = "ProvadorVirtual"
    private const val BASE_URL = "https://kwai-kolors-kolors-virtual-try-on.hf.space"

    // Use AtomicInteger for thread-safe increment if generate() can be called concurrently
    // Initial value based on Python code
    private var triggerId = 25 // Simple Int might suffice if called sequentially from UI thread
    private val sessionHash = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    // Configure OkHttpClient (reuse instance for efficiency)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Set a long read timeout for the SSE connection, or rely on EventSource's handling
        .readTimeout(5, TimeUnit.MINUTES) // Example: 5 minutes for SSE polling
        .writeTimeout(30, TimeUnit.SECONDS)
        // Optional: Add cookie jar if session handling relies on cookies beyond session_hash
        // .cookieJar(JavaNetCookieJar(CookieManager()))
        .build()

    // Configure JSON parser
    private val json = Json {
        ignoreUnknownKeys = true // Be lenient with extra fields from server
        isLenient = true         // Allow minor JSON format deviations if needed
        encodeDefaults = true    // Ensure default values are included if needed
    }

    private fun log(message: String) {
        // Use Android's Logcat
        Log.d(TAG, message)
    }

    /**
     * Uploads an image file to the server.
     * Mimics the _upload_image function.
     */
    private suspend fun uploadImage(imagePath: String): FileData? = withContext(Dispatchers.IO) {
        val file = File(imagePath)
        if (!file.exists()) {
            log("❌ Arquivo não encontrado: $imagePath")
            return@withContext null
        }
        log("Iniciando upload de ${file.name}...")

        val uploadId = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        val uploadUrl = "$BASE_URL/upload?upload_id=$uploadId"

        val mimeType = "image/png" // Ou outra lógica de detecção se necessário

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responsePreview = response.peekBody(500).string() // Loga até 500 bytes
                log("Resposta do upload (${response.code}): $responsePreview...")

                if (!response.isSuccessful) {
                    throw IOException("Erro HTTP no upload: ${response.code} - ${response.message}")
                }

                val responseBody = response.body?.string() // Lê o corpo completo aqui
                if (responseBody.isNullOrEmpty()) {
                    throw IOException("Resposta do upload vazia")
                }

                val pathList: List<String> = try {
                     json.decodeFromString<List<String>>(responseBody)
                } catch (e: SerializationException) {
                     // Log mais detalhado em caso de falha na desserialização
                     log("❌ Falha ao desserializar a resposta do upload como List<String>. Resposta: $responseBody")
                     throw e // Relança a exceção para ser pega pelo catch externo
                }


                if (pathList.isEmpty()) {
                    throw IOException("Resposta do upload (lista de paths) está vazia")
                }
                
                val path: String = pathList[0]

                log("✅ Path extraído da resposta do upload: $path")

                return@withContext FileData(
                    path = path,
                    url = "$BASE_URL/file=$path",
                    orig_name = file.name,
                    size = file.length(),
                    mime_type = mimeType
                )
            }
        } catch (e: Exception) {
            log("❌ Falha no upload de ${file.name}: ${e.message}")
            // Log adicional caso seja um erro específico de JSON
             if (e is kotlinx.serialization.SerializationException) {
                  log("Erro durante parse do JSON. Verifique a estrutura da resposta do servidor.")
             }
            e.printStackTrace() // Imprime o stacktrace completo para debug
            return@withContext null
        }
    }

    /**
     * Generates the payload for the try-on request.
     * Mimics the generate_payload function.
     */
    private suspend fun generatePayload(humanImgPath: String, clothImgPath: String): RequestPayload? {
        log("Gerando payload...")
        triggerId++ // Increment trigger ID (consider thread safety if needed)

        val humanData = uploadImage(humanImgPath)
        val clothData = uploadImage(clothImgPath)

        if (humanData == null || clothData == null) {
            log("❌ Falha ao fazer upload de uma ou ambas as imagens.")
            return null
        }

        // Serialize FileData objects to JsonElement to include them in the list
        val humanJsonElement = json.encodeToJsonElement(humanData)
        val clothJsonElement = json.encodeToJsonElement(clothData)

        // Construct the data list matching Python's structure: [human_data, cloth_data, 0, True]
        val dataList = listOf(
            humanJsonElement,
            clothJsonElement,
            JsonPrimitive(0), // Number 0
            JsonPrimitive(true) // Boolean True
        )

        return RequestPayload(
            data = dataList,
            eventData = null, // As in Python
            fnIndex = 2,      // Hardcoded index from Python
            triggerId = triggerId,
            sessionHash = sessionHash
        )
    }

    /**
     * Submits the job request to the /queue/join endpoint.
     * Mimics the submit_request function.
     * Returns true on success, false on failure.
     */
    private suspend fun submitRequest(payload: RequestPayload): Boolean = withContext(Dispatchers.IO) {
        log("Enviando requisição para /queue/join...")
        val url = "$BASE_URL/queue/join"

        return@withContext try {
            val payloadJsonString = json.encodeToString(payload)
            log("Payload JSON: $payloadJsonString") // Log for debugging

            val requestBody = payloadJsonString.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json") // Explicitly set header
                .build()

            client.newCall(request).execute().use { response ->
                val responseBodyString = response.peekBody(200).string() // Peek for logging
                log("Resposta da submissão (${response.code}): $responseBodyString")

                if (!response.isSuccessful) {
                    log("Erro na submissão: ${response.code} - ${response.message}")
                    false
                } else {
                    // Optionally parse response if event_id or other info is needed later
                    // val submitResponse = json.decodeFromString<SubmitResponse>(response.body!!.string())
                    // log("✅ Submissão bem-sucedida! Event ID: ${submitResponse.eventId}")
                    log("✅ Submissão bem-sucedida!")
                    true
                }
            }
        } catch (e: SerializationException) {
            log("❌ Erro ao serializar payload: ${e.message}")
            e.printStackTrace()
            false
        } catch (e: Exception) {
            log("❌ Erro na conexão/submissão: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Polls the /queue/data endpoint using Server-Sent Events (SSE) for the result.
     * Mimics the poll_result function.
     * Returns a Result containing the image URL on success, or an Exception on failure.
     */
    private suspend fun pollResult(timeoutMillis: Long = 300_000): Result<String> = withContext(Dispatchers.IO) {
        log("Iniciando polling SSE...")
        val url = "$BASE_URL/queue/data?session_hash=$sessionHash"
        val request = Request.Builder().url(url).get()
            // Optional: Add headers if needed, e.g., Accept: text/event-stream
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        // Use CompletableDeferred to bridge callback-based SSE with suspend function
        // Agora o Deferred carrega um Result<String>
        val resultDeferred = CompletableDeferred<Result<String>>()

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                log("Conexão SSE aberta.")
            }

            // Dentro de EventSourceListener
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                log("Evento SSE recebido: $data")
                try {
                    val eventData = json.decodeFromString<SseEvent>(data)
                    log("Evento SSE processado: msg='${eventData.msg}'")

                    when (eventData.msg) {
                        "estimation" -> {
                            log("ETA: ${eventData.rankEta ?: "N/A"}s")
                        }
                        "process_starts" -> {
                            log("Processamento iniciado no servidor.")
                        }
                        "process_generating" -> {
                             log("Gerando resultado...")
                        }
                        
                        "process_completed" -> {
                            log("Processamento finalizado pelo servidor.")

                            // ---- INÍCIO DO CÓDIGO A SER VERIFICADO ----
                            if (eventData.success == true && eventData.output?.data?.isNotEmpty() == true) {

                                val firstElement = eventData.output.data[0] // Pega como JsonElement

                                if (firstElement is JsonObject) { // Verifica se é Objeto
                                    val urlElement = firstElement["url"] // Acessa o campo "url"

                                    if (urlElement is JsonPrimitive && urlElement.isString) { // Verifica se é string
                                        val resultUrl = urlElement.content
                                        val fullResultUrl = if (resultUrl.startsWith("http")) {
                                             resultUrl
                                        } else {
                                            "$BASE_URL/file=$resultUrl"
                                        }
                                        log("✅ Sucesso! URL do resultado extraída: $fullResultUrl")
                                        resultDeferred.complete(kotlin.Result.success(fullResultUrl))
                                        eventSource.cancel()
                                    } else {
                                        log("❌ Campo 'url' não encontrado ou não é string no primeiro elemento de 'output.data'. Elemento: $firstElement")
                                        resultDeferred.complete(kotlin.Result.failure(IOException("Formato de resposta inesperado: campo 'url' ausente ou inválido.")))
                                        eventSource.cancel()
                                    }
                                } else {
                                    log("❌ Primeiro elemento em 'output.data' não é um objeto JSON. Conteúdo: $firstElement")
                                    resultDeferred.complete(kotlin.Result.failure(IOException("Formato de resposta inesperado: 'output.data[0]' não é um objeto.")))
                                    eventSource.cancel()
                                }
                            } else {
                                log("❌ Processamento concluído sem sucesso ('success'!=true ou 'output.data' vazio/ausente). Output: ${eventData.output}")
                                resultDeferred.complete(kotlin.Result.failure(IOException("Servidor indicou falha no processamento ou não retornou dados de saída.")))
                                eventSource.cancel()
                            }
                            // ---- FIM DO CÓDIGO A SER VERIFICADO ----
                        }
                        "heartbeat" -> {
                            log("Evento Heartbeat recebido.")
                        }
                        else -> {
                             log("Tipo de mensagem SSE não reconhecido: ${eventData.msg}")
                        }
                    }
                } catch (e: Exception) {
                    log("⚠️ Erro GERAL ao processar evento SSE: ${e.message} - Data que causou erro: $data")
                    e.printStackTrace() // Loga o stacktrace para debug
                      if (!resultDeferred.isCompleted) {
                         resultDeferred.complete(kotlin.Result.failure(e)) // Completa com falha
                      }
                      eventSource.cancel() // Fecha conexão em caso de erro grave de parse
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = "Falha na conexão SSE: ${t?.message ?: "Erro desconhecido"}, Código: ${response?.code}"
                log("❌ $errorMsg")
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(kotlin.Result.failure(IOException(errorMsg, t)))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                log("Conexão SSE fechada.")
                if (!resultDeferred.isCompleted) {
                    log("Conexão fechada antes de receber resultado ou erro explícito.")
                    resultDeferred.complete(kotlin.Result.failure(IOException("Conexão SSE fechada inesperadamente.")))
                }
            }
        }

        // Create EventSource factory using the shared OkHttpClient
        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, eventSourceListener)

        try {
            // Wait for the deferred result with a timeout
            return@withContext withTimeout(timeoutMillis) {
                resultDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            log("❌ Timeout (${timeoutMillis}ms) excedido durante o polling.")
            eventSource.cancel()
            return@withContext kotlin.Result.failure(e)
        } catch (e: Exception) {
             log("❌ Erro inesperado durante o polling: ${e.message}")
             eventSource.cancel()
             return@withContext kotlin.Result.failure(e)
        }
        // No finally needed to cancel eventSource here, as timeout/completion handles it.
    }



    // --- Função de redimensionamento (mantida como no original) ---
    private fun redimensionarComFundoTransparente(bitmap: Bitmap): Bitmap {
        // Garantindo as dimensões finais originais
        val finalWidth = 800
        val finalHeight = 1600
        log("Redimensionando imagem para ${finalWidth}x${finalHeight} com fundo transparente.")
    
        // Criar uma nova imagem com o fundo transparente nas dimensões finais
        val newBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
    
        // Definir fundo transparente
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    
        // Calcular a nova dimensão proporcional da imagem original
        val widthRatio = finalWidth.toFloat() / bitmap.width.toFloat()
        val heightRatio = finalHeight.toFloat() / bitmap.height.toFloat()
    
        // Usar a menor razão para garantir que a imagem se ajuste à área disponível sem deformar
        val scaleRatio = minOf(widthRatio, heightRatio)
    
        // Calcular as novas dimensões da imagem
        val newWidth = (bitmap.width * scaleRatio).toInt()
        val newHeight = (bitmap.height * scaleRatio).toInt()
    
        // Calcular o ponto de origem para centralizar
        val xOffset = (finalWidth - newWidth) / 2
        val yOffset = (finalHeight - newHeight) / 2
    
        // Redimensionar a imagem original
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    
        // Desenhar a imagem redimensionada sobre o fundo transparente
        canvas.drawBitmap(resizedBitmap, xOffset.toFloat(), yOffset.toFloat(), null)
    
        // Opcional: liberar memória do bitmap intermediário (com cautela)
        if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) { // Adicionado !isRecycled para segurança
            // resizedBitmap.recycle() // Use with caution
        }
    
        log("Redimensionamento concluído.")
        return newBitmap // Retorna o bitmap final de 800x1600
    }
    
    // --- NOVA Função de salvamento PARA CAMINHO ESPECÍFICO ---
    /**
     * Salva um bitmap em um caminho de arquivo específico, sobrescrevendo se existir.
     * Redimensiona o bitmap para 800x1600 com fundo transparente antes de salvar.
     *
     * @param bitmap O Bitmap a ser processado e salvo.
     * @param targetPath O caminho absoluto completo onde o arquivo WEBP será salvo.
     * @param context Android Context (não usado diretamente aqui, mas pode ser útil para futuras extensões).
     * @return O caminho absoluto do arquivo salvo (o mesmo que targetPath) em caso de sucesso, ou null em caso de erro.
     */
    private fun salvarImagemNoCaminhoEspecifico(bitmap: Bitmap, targetPath: String, context: Context): String? {
        log("Iniciando salvamento da imagem WEBP no caminho específico: $targetPath")
        var resizedBitmap: Bitmap? = null
        try {
            // Etapa 1: Redimensionar com fundo transparente
            resizedBitmap = redimensionarComFundoTransparente(bitmap)
            log("Bitmap redimensionado para 800x1600.")

            val file = File(targetPath)

            // Garante que o diretório pai exista
            file.parentFile?.let {
                if (!it.exists()) {
                    if (it.mkdirs()) {
                        log("Diretório pai criado: ${it.absolutePath}")
                    } else {
                        log("❌ Falha ao criar diretório pai: ${it.absolutePath}")
                        resizedBitmap.recycle() // Limpa o bitmap redimensionado
                        return null
                    }
                }
            }

            FileOutputStream(file).use { out ->
                // Salva como WEBP com qualidade 60
                resizedBitmap.compress(Bitmap.CompressFormat.WEBP, 60, out)
                out.flush()
            }

            log("✅ Imagem WEBP salva com sucesso em: ${file.absolutePath}")
            return file.absolutePath

        } catch (e: Exception) {
            log("❌ Erro ao salvar imagem WEBP em $targetPath: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            // Libera a memória do bitmap redimensionado
            resizedBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                    log("Bitmap redimensionado reciclado.")
                }
            }
        }
    }

    

    // --- MODIFICADA a função downloadAndSaveImage ---
    /**
     * Downloads an image from the specified URL, processes it (resizes to 800x1600 with transparent bg),
     * and saves it to the specified targetSavePath, overwriting if it exists.
     *
     * @param imageUrl The URL of the image to download.
     * @param targetSavePath The absolute path where the processed image will be saved (overwritten).
     * @param context Android Context.
     * @return The absolute path of the saved image file (same as targetSavePath), or null if an error occurred.
     */
    private suspend fun downloadAndSaveImage(imageUrl: String, targetSavePath: String, context: Context): String? = withContext(Dispatchers.IO) {
        log("Iniciando download da imagem de: $imageUrl para salvar em: $targetSavePath")

        val request = Request.Builder()
            .url(imageUrl)
            .get()
            .build()

        var downloadedBitmap: Bitmap? = null
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("❌ Falha no download: Código ${response.code} - ${response.message}")
                    return@withContext null
                }

                val body = response.body
                if (body == null) {
                    log("❌ Corpo da resposta do download está vazio.")
                    return@withContext null
                }

                try {
                    body.byteStream().use { inputStream ->
                         downloadedBitmap = BitmapFactory.decodeStream(inputStream)
                    }
                } catch(e: Exception) {
                     log("❌ Erro ao decodificar a imagem baixada: ${e.message}")
                     e.printStackTrace()
                }

                if (downloadedBitmap == null) {
                    log("❌ Não foi possível decodificar a imagem baixada.")
                    return@withContext null
                }
                log("✅ Imagem decodificada com sucesso. Iniciando salvamento no caminho especificado...")

                // Agora, chame a função de salvamento que usa o caminho específico
                val savedPath = salvarImagemNoCaminhoEspecifico(downloadedBitmap!!, targetSavePath, context)

                return@withContext savedPath // Retorna o caminho do arquivo salvo (targetSavePath) ou null se salvar falhou
            }
        } catch (e: IOException) {
            log("❌ Erro de IO durante o download: ${e.message}")
            e.printStackTrace()
            return@withContext null
        } catch (e: Exception) {
            log("❌ Erro inesperado durante o download ou processamento: ${e.message}")
            e.printStackTrace()
            return@withContext null
        } finally {
             downloadedBitmap?.let {
                 if (!it.isRecycled) {
                     it.recycle()
                     log("Bitmap original baixado reciclado.")
                 }
             }
        }
    }

    /**
     * Main function to orchestrate the virtual try-on process.
     * Takes paths to the human photo and the clothing item image.
     * The generated image will overwrite the original fotoPath.
     * Returns a Result containing the absolute path of the saved (overwritten) image file on success,
     * or an Exception on failure.
     */
    suspend fun generate(fotoPath: String, figurinoPath: String, context: Context): String? {
        log("🚀 Iniciando processo completo de TryOn Virtual...")
        log("fotoPath (será sobrescrito): $fotoPath")
        log("figurinoPath: $figurinoPath")

        // 1. Generate Payload (includes uploading images)
        val payload = generatePayload(fotoPath, figurinoPath)
        if (payload == null) {
            log("❌ Falha na criação do payload (upload falhou?).")
            return null
        }
        log("✅ Payload gerado com sucesso!")

        // 2. Submit Request
        val submitSuccess = submitRequest(payload)
        if (!submitSuccess) {
            log("❌ Falha na submissão do pedido.")
            return null
        }
        log("✅ Pedido submetido com sucesso! Aguardando resultado via SSE...")

        // 3. Poll for Result using SSE
        return try { 
            val pollResultOutcome = pollResult()

            if (pollResultOutcome.isSuccess) {
                val resultadoUrl = pollResultOutcome.getOrThrow()
                log("🎉 URL do resultado obtida via SSE: $resultadoUrl")
                log("Iniciando download e salvamento para sobrescrever: $fotoPath")

                val caminhoSalvo = downloadAndSaveImage(
                    imageUrl = resultadoUrl,
                    targetSavePath = fotoPath, // Passa o fotoPath original como destino
                    context = context
                )
                if (caminhoSalvo != null) {
                    log("✅ Imagem final salva (sobrescrita) em: $caminhoSalvo")
                    caminhoSalvo
                } else {
                    log("❌ Falha ao baixar ou salvar a imagem da URL: $resultadoUrl em $fotoPath")
                    null
                }
            } else { 
                val exception = pollResultOutcome.exceptionOrNull()
                log("❌ Polling SSE falhou: ${exception?.message}")
                null
            }
        } catch (e: Exception) {
            log("🔥 Erro crítico durante o polling: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            log("🏁 Processo TryOn (geração de URL) finalizado.")
        }
    }
    


// Define data classes for JSON serialization/deserialization
@Serializable
data class FileData(
    val path: String,
    val url: String,
    val orig_name: String,
    val size: Long,
    val mime_type: String,
    val meta: MetaData = MetaData() // Use default value for meta
)

@Serializable
data class MetaData(val _type: String = "gradio.FileData")

// Response from the /upload endpoint (assuming it's a list containing one item)
@Serializable
data class UploadResponseItem(
    val path: String
    // Add other fields if the actual response contains more
)

// Payload for the /queue/join endpoint
@Serializable
data class RequestPayload(
    // Using JsonElement allows mixing types like the FileData JSON and primitives
    val data: List<JsonElement>,
    @SerialName("event_data") val eventData: String? = null,
    @SerialName("fn_index") val fnIndex: Int,
    @SerialName("trigger_id") val triggerId: Int,
    @SerialName("session_hash") val sessionHash: String
)

// Response from the /queue/join endpoint (only event_id was used in Python)
@Serializable
data class SubmitResponse(
    @SerialName("event_id") val eventId: String? = null
    // Add other fields if needed
)

// Structure for Server-Sent Events (SSE) from /queue/data
@Serializable
data class SseEvent(
    val msg: String,
    @SerialName("rank_eta") val rankEta: Float? = null,
    val success: Boolean? = null,
    val output: SseOutput? = null
    // Add other fields based on actual SSE messages if needed
)


@Serializable
data class SseOutput(
    // CONFIRME QUE ESTÁ USANDO List<JsonElement> AQUI
    val data: List<JsonElement>
)
    

}