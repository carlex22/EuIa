package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.carlex.euia.utils.ProjectPersistenceManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionProApi
import com.carlex.euia.data.*
import com.carlex.euia.prompts.CenaInputInfo
import com.carlex.euia.prompts.CreateObjectsForSingleScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException // <<< CORREÇÃO: Importação adicionada

private const val TAG = "PostProductionWorker"
private const val NOTIFICATION_ID_POST = 532
private const val NOTIFICATION_CHANNEL_ID_POST = "PostProductionChannelEUIA"

class PostProductionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val audioDataStore = AudioDataStoreManager(applicationContext)
    private val videoPrefsDataStore = VideoPreferencesDataStoreManager(applicationContext)
    private val videoProjectDataStore = VideoProjectDataStoreManager(applicationContext)
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val TAG_POST_PRODUCTION = "post_production_work"
        const val KEY_ERROR_MESSAGE = "error_message_post_production"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification(applicationContext.getString(R.string.notification_content_post_production_starting))
        return ForegroundInfo(NOTIFICATION_ID_POST, notification)
    }

    override suspend fun doWork(): Result = coroutineScope {
        Log.i(TAG, "Iniciando tarefa em LOTE de Pós-Produção do 'Mestre Ilusionista'...")

        val projectDirName = videoPrefsDataStore.videoProjectDir.first()
        val logDir = getProjectSpecificDirectory(projectDirName, "post_production_logs")
        if (logDir == null) {
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Não foi possível criar o diretório de logs."))
        }

        try {
            val textoNarrativa = audioDataStore.prompt.first()
            val largura = videoPrefsDataStore.videoLargura.first() ?: 720
            val altura = videoPrefsDataStore.videoAltura.first() ?: 1280
            val cenasDeEntrada = videoProjectDataStore.sceneLinkDataList.first()

            if (cenasDeEntrada.isEmpty()) {
                return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Nenhuma cena encontrada para processar."))
            }
            if (textoNarrativa.isBlank()) {
                return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Narrativa não encontrada para gerar o roteiro."))
            }

            val listaDeResultados = mutableListOf<PostProductionScene>()
            val totalCenas = cenasDeEntrada.size

            for ((index, cena) in cenasDeEntrada.withIndex()) {
                if (!isActive) throw CancellationException("Worker cancelado durante o lote.")

                val progressPercent = ((index + 1) * 100) / totalCenas
                //updateNotification("Analisando Cena ${index + 1}/$totalCenas...", progressPercent)
                Log.d(TAG, "Processando cena ${cena.id} (${index + 1} de $totalCenas)")

                val (imgWidth, imgHeight) = obterDimensoesReaisDaImagem(cena.imagemGeradaPath) ?: (largura to altura)
                val duracao = ((cena.tempoFim ?: 0.0) - (cena.tempoInicio ?: 0.0)).coerceAtLeast(0.1)
                val textoEspecifico = cena.descricaoReferencia.ifBlank { "(cena sem narração específica)" }

                val inputInfo = CenaInputInfo(cena.id, textoEspecifico, duracao, imgWidth, imgHeight)
                val promptGenerator = CreateObjectsForSingleScene(inputInfo, textoNarrativa, largura, altura)

                // CORREÇÃO: Chamada correta para a API sem os parâmetros extras
                val resultadoIA = GeminiTextAndVisionProApi.perguntarAoGemini(
                    pergunta = promptGenerator.prompt,
                    imagens = listOfNotNull(cena.imagemGeradaPath)
                )

                if (resultadoIA.isSuccess) {
                    val respostaJson = resultadoIA.getOrNull()
                    if (!respostaJson.isNullOrBlank()) {
                        saveRawResponseToFile(logDir, cena.id, respostaJson)
                        try {
                            val postScene = jsonParser.decodeFromString<PostProductionScene>(respostaJson)
                            listaDeResultados.add(postScene)
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao decodificar JSON para a cena ${cena.id}. Resposta salva em logs.", e)
                        }
                    }
                } else {
                    Log.e(TAG, "Falha na API para cena ${cena.id}: ${resultadoIA.exceptionOrNull()?.message}")
                }
                delay(500)
            }
            
            

            if (listaDeResultados.isNotEmpty()) {
                // <<< CORREÇÃO: A chamada para setPostProductionScenes está correta e deve funcionar agora >>>
                //videoProjectDataStore.setPostProductionScenes(listaDeResultados)
                val successMsg = "Pós-produção concluída. ${listaDeResultados.size} de $totalCenas cenas processadas com sucesso."
                updateNotification(successMsg, makeDismissible = true)
                Log.i(TAG, successMsg)
                return@coroutineScope Result.success()
            } else {
                val errorMsg = "Nenhuma cena pôde ser processada com sucesso."
                updateNotification(errorMsg, makeDismissible = true)
                return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }

        } catch (e: Exception) {
            val finalErrorMsg = when (e) {
                is CancellationException -> "Análise de pós-produção cancelada."
                else -> e.message ?: "Erro desconhecido na pós-produção."
            }
            Log.e(TAG, "Erro fatal no PostProductionWorker: $finalErrorMsg", e)
            updateNotification(finalErrorMsg, makeDismissible = true)
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to finalErrorMsg))
        }
    }
    
    private fun saveRawResponseToFile(logDir: File, sceneId: String, response: String) {
        try {
            val file = File(logDir, "response_${sceneId}.txt")
            file.writeText(response)
            Log.d(TAG, "Resposta bruta para a cena $sceneId salva em: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Não foi possível salvar o arquivo de log para a cena $sceneId", e)
        }
    }

    private fun getProjectSpecificDirectory(projectDirName: String, subDir: String): File? {
        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val projectPath = File(baseDir, projectDirName)
        val finalDir = File(projectPath, subDir)
        if (!finalDir.exists() && !finalDir.mkdirs()) {
            Log.e(TAG, "Falha ao criar diretório: ${finalDir.absolutePath}")
            return null
        }
        return finalDir
    }
    
    private fun obterDimensoesReaisDaImagem(path: String?): Pair<Int, Int>? {
        if (path == null) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0 && options.outHeight > 0) { Pair(options.outWidth, options.outHeight) } else { null }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler dimensões da imagem: $path", e)
            null
        }
    }

    // --- FUNÇÕES DE NOTIFICAÇÃO ADICIONADAS PARA RESOLVER O ERRO ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.notification_channel_name_post)
            val descriptionText = applicationContext.getString(R.string.notification_channel_description_post)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_POST, name, importance).apply {
                description = descriptionText
            }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, progress: Int? = null): Notification {
        val title = applicationContext.getString(R.string.notification_title_post_production)
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_POST)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // Indicador indeterminado
        }
        return builder.build()
    }

    private fun updateNotification(contentText: String, makeDismissible: Boolean = false, progress: Int? = null) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // CORREÇÃO: A assinatura foi corrigida para aceitar makeDismissible e progress.
        // A lógica de `makeDismissible` agora é implícita: a notificação final é a única que não é "ongoing".
        val finalNotification = createNotification(contentText, progress)
        if(makeDismissible) {
            finalNotification.flags = finalNotification.flags and Notification.FLAG_ONGOING_EVENT.inv()
        }
        notificationManager.notify(NOTIFICATION_ID_POST, finalNotification)
    }
}