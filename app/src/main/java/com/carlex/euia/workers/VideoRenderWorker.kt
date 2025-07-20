// File: euia/workers/VideoRenderWorker.kt
package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.data.VideoGeneratorDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.*
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

private const val TAG = "VideoRenderWorker"

class VideoRenderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(appContext)
    private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(appContext)
    private val videoPreferencesDataStoreManager = VideoPreferencesDataStoreManager(appContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val workManager = WorkManager.getInstance(appContext)


    companion object {
        const val TAG_VIDEO_RENDER = "video_render_work"
        const val KEY_AUDIO_PATH = "key_audio_path"
        const val KEY_MUSIC_PATH = "key_music_path"
        const val KEY_LEGEND_PATH = "key_legenda_path"
        const val KEY_OUTPUT_VIDEO_PATH = "key_output_video_path"
        const val KEY_ERROR_MESSAGE = "key_error_message"
        const val KEY_PROGRESS = "key_progress"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(0, appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), notification)
    }

    override suspend fun doWork(): Result {
        if (videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo.first()) {
            val errorMsg = "Uma renderização já está em andamento ou falhou. Cancele a tarefa anterior ou reinicie o app."
            Log.e(TAG, errorMsg)
            updateNotification(100, "Erro: Renderização Duplicada", isError = true, isFinished = true)
            showToast(errorMsg)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }

        try {
            videoGeneratorDataStoreManager.setCurrentlyGenerating(true)
            OverlayManager.showOverlay(appContext, "🎥", -1)

            // 1. Validação e Preparação das Prévias
            updateNotification(5, "Verificando cenas...")
            val scenesValidAndReady = validateAndPrepareScenePreviews()
            if (!scenesValidAndReady) {
                // A falha já foi tratada dentro da função de validação (toast, etc)
                throw CancellationException("Validação de prévias falhou. Abortando renderização final.")
            }

            // Se chegou aqui, todas as prévias estão OK.
            showToast("Tudo ok.. iniciando render final")
            updateNotification(20, "Iniciando renderização final...")

            // 2. Continua com a lógica original de renderização
            val audioPath = inputData.getString(KEY_AUDIO_PATH)
            val musicPath = inputData.getString(KEY_MUSIC_PATH) ?: ""
            val legendPath = inputData.getString(KEY_LEGEND_PATH) ?: ""

            // Refaz a busca para garantir que estamos com os caminhos de prévia mais recentes
            val scenesToInclude = videoProjectDataStoreManager.sceneLinkDataList.first().filter { scene ->
                !scene.videoPreviewPath.isNullOrBlank() &&
                scene.tempoInicio != null &&
                scene.tempoFim != null &&
                scene.tempoFim > scene.tempoInicio
            }

            if (scenesToInclude.isEmpty() || audioPath.isNullOrBlank()) {
                throw IllegalStateException("Dados essenciais faltando após validação (Cenas: ${scenesToInclude.size}, Áudio: ${!audioPath.isNullOrBlank()})")
            }

            var pro = -1
            val finalVideoPath = VideoEditorComTransicoes.gerarVideoComTransicoes(
                context = appContext,
                scenes = scenesToInclude,
                audioPath = audioPath,
                musicaPath = musicPath,
                legendaPath = legendPath,
                logCallback = { logMessage ->
                     val loteMatch = Regex("Lote (\\d+) de (\\d+), Duracao: ([\\d.]+), Concluido=([\\d.]+)").find(logMessage)
                    loteMatch?.let {
                        val loteAtual = it.groupValues[1].toInt()
                        val totalLotes = it.groupValues[2].toInt()
                        val duracaoLote = it.groupValues[3].toDouble()
                        val concluidoNoLote = it.groupValues[4].toDouble()

                        val progressoBase = 20 // Começa a contar a partir de 20%
                        val progressoRestante = 100 - progressoBase
                        val progressoPorLote = (progressoRestante / totalLotes.toFloat())
                        val progressoAtualNoLote = (concluidoNoLote / duracaoLote) * progressoPorLote
                        val progressoAcumulado = progressoBase + (progressoPorLote * (loteAtual - 1)) + progressoAtualNoLote

                        val progressoPercent = (progressoAcumulado.toInt()).coerceIn(0, 100)
                        
                        if (progressoPercent > pro) {
                            Log.w(TAG, "Progresso render video: $progressoPercent%")
                            OverlayManager.showOverlay(appContext, "$progressoPercent %", progressoPercent)
                            updateNotification(progressoPercent, "$progressoPercent%")
                            setProgressAsync(workDataOf(KEY_PROGRESS to (progressoPercent / 100f)))
                            pro = progressoPercent
                        }
                    }
                }
            )

            if (finalVideoPath.isNotBlank()) {
                videoGeneratorDataStoreManager.setFinalVideoPath(finalVideoPath)
                Log.i(TAG, "Vídeo gerado e salvo nas preferências: $finalVideoPath")
                updateNotification(100, "Concluído!", isFinished = true)
                return Result.success(workDataOf(KEY_OUTPUT_VIDEO_PATH to finalVideoPath))
            } else {
                throw Exception("Editor de vídeo retornou um caminho vazio.")
            }
        } catch (e: Exception) {
            val errorMessage = if (e is CancellationException) {
                e.message ?: appContext.getString(R.string.video_render_cancelled)
            } else {
                e.message ?: appContext.getString(R.string.video_render_unknown_error)
            }
            Log.e(TAG, "Falha na renderização do vídeo no worker.", e)
            updateNotification(100, "Falhou: ${errorMessage.take(30)}", isError = true, isFinished = true)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        } finally {
            ProjectPersistenceManager.saveProjectState(appContext)
            Log.d(TAG, "Bloco finally do Worker: Garantindo que o lock de renderização seja desativado.")
            videoGeneratorDataStoreManager.setCurrentlyGenerating(false)
            OverlayManager.hideOverlay(appContext)
        }
    }

    private suspend fun validateAndPrepareScenePreviews(): Boolean {
        val scenes = videoProjectDataStoreManager.sceneLinkDataList.first()
        val scenesThatNeedPreview = mutableListOf<String>() // Lista de IDs de cenas a serem geradas

        for (scene in scenes) {
            if (scene.imagemGeradaPath.isNullOrBlank()) {
                val errorMsg = "Cena ${scene.cena ?: scene.id.take(4)} não tem um asset de imagem/vídeo gerado. Não é possível continuar."
                showToast(errorMsg)
                Log.e(TAG, errorMsg)
                return false
            }

            val previewPath = scene.videoPreviewPath
            if (previewPath.isNullOrBlank() || !checkVideoFileIntegrity(previewPath)) {
                scenesThatNeedPreview.add(scene.id)
            }
        }

        if (scenesThatNeedPreview.isEmpty()) {
            Log.i(TAG, "Validação concluída. Todas as ${scenes.size} prévias estão válidas.")
            return true
        }

        Log.w(TAG, "Validação falhou. ${scenesThatNeedPreview.size} cenas precisam de prévia: $scenesThatNeedPreview")
        updateNotification(10, "Preparando prévias necessárias...")
        showToast("Algumas prévias de cenas precisam ser geradas. Aguarde...")

        val workRequests = scenesThatNeedPreview.map { sceneId ->
            OneTimeWorkRequestBuilder<ScenePreviewWorker>()
                .setInputData(workDataOf(ScenePreviewWorker.KEY_SCENE_ID to sceneId))
                .addTag("${WorkerTags.SCENE_PREVIEW_WORK}_$sceneId")
                .addTag(WorkerTags.SCENE_PREVIEW_WORK) // Tag geral
                .build()
        }
        
        workManager.enqueue(workRequests).result.await()

        return monitorWorkRequests(workRequests.map { it.id }.toSet())
    }

    // <<< CORREÇÃO AQUI >>>
    private suspend fun monitorWorkRequests(workIds: Set<UUID>): Boolean {
        // Envolve a chamada de monitoramento em um loop com delay
        while (coroutineContext.isActive) {
            val workInfos = withContext(Dispatchers.IO) {
                // A chamada .get() na ListenableFuture é blocante, por isso o withContext
                workManager.getWorkInfosForUniqueWork("SCENE_PREVIEW_QUEUE").get()
            }.filter { it.id in workIds } // Filtra para apenas os trabalhos que nos interessam

            val finishedCount = workInfos.count { it.state.isFinished }

            // Atualiza o progresso com base nos trabalhos finalizados
            updateNotification(
                progress = 10 + (finishedCount.toFloat() * 10f / workIds.size).toInt(),
                message = "Gerando prévia ${finishedCount + 1} de ${workIds.size}..."
            )

            // Verifica se algum trabalho falhou ou foi cancelado
            val failedOrCancelledWork = workInfos.firstOrNull { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
            if (failedOrCancelledWork != null) {
                val errorMsg = failedOrCancelledWork.outputData.getString(ScenePreviewWorker.KEY_ERROR_MESSAGE)
                    ?: "Geração de prévia falhou ou foi cancelada."
                showToast(errorMsg)
                Log.e(TAG, "Um dos workers de prévia falhou: $errorMsg")
                return false // Retorna falha
            }
            
            // Verifica se todos os trabalhos terminaram
            if (finishedCount == workIds.size) {
                Log.i(TAG, "Todos os workers de pré-visualização foram concluídos com sucesso.")
                return true // Retorna sucesso
            }

            delay(2000) // Espera 2 segundos antes de verificar novamente
        }
        return false // Loop foi cancelado pela coroutine
    }

    private suspend fun checkVideoFileIntegrity(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() < 1024) { 
            Log.w(TAG, "Checagem de integridade falhou: arquivo não existe ou é muito pequeno. Path: $filePath")
            return@withContext false
        }
        val session = FFprobeKit.execute("-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$filePath\"")
        if (ReturnCode.isSuccess(session.returnCode) && session.output.trim().toDoubleOrNull() != null) {
            Log.d(TAG, "Checagem de integridade OK para: $filePath")
            return@withContext true
        } else {
            Log.w(TAG, "Checagem de integridade (ffprobe) falhou para: $filePath. Deletando arquivo corrompido.")
            file.delete()
            return@withContext false
        }
    }

    private fun createNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_VIDEO_RENDER)
            .setContentTitle(appContext.getString(R.string.video_render_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        if (isFinished || isError) {
            builder.setProgress(0, 0, false).setOngoing(false).setAutoCancel(true)
        } else {
            builder.setProgress(100, progress, false).setOngoing(true).setAutoCancel(false)
        }
        return builder.build()
    }

    private fun updateNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false) {
        notificationManager.notify(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), createNotification(progress, message, isError, isFinished))
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}