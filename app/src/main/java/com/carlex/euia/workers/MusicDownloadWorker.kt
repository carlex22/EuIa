// File: euia/workers/MusicDownloadWorker.kt
package com.carlex.euia.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class MusicDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "MusicDownloadWorker"

    companion object {
        // Chave para passar a URL da música para o worker
        const val KEY_MUSIC_URL = "key_music_url"
        
        // Chave para retornar o caminho do arquivo salvo em caso de sucesso
        const val KEY_OUTPUT_FILE_PATH = "key_output_file_path"
        
        // Chave para retornar uma mensagem de erro em caso de falha
        const val KEY_ERROR_MESSAGE_DOWNLOAD = "key_error_message_download"
    }

    override suspend fun doWork(): Result {
        val musicUrl = inputData.getString(KEY_MUSIC_URL)

        if (musicUrl.isNullOrBlank()) {
            Log.e(TAG, "URL da música não fornecida ao worker.")
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE_DOWNLOAD to "URL da música é inválida."))
        }

        Log.d(TAG, "Iniciando download para a URL: $musicUrl")
        // Notificação de progresso seria iniciada aqui
        
        try {
            // TODO: Implementar a lógica de download real aqui.
            // 1. Obter o diretório do projeto a partir do DataStore.
            // 2. Usar uma biblioteca como OkHttp ou Ktor para baixar o arquivo da 'musicUrl'.
            // 3. Salvar o stream de bytes em um arquivo no diretório do projeto.
            // 4. Se for bem-sucedido, retornar o caminho absoluto do arquivo salvo.
            
            // Simulação de sucesso após um delay
            kotlinx.coroutines.delay(3000) // Simula 3 segundos de download
            val simulatedPath = "/path/to/downloaded/musica_baixada.mp3"
            Log.i(TAG, "Simulação de download concluída. Caminho: $simulatedPath")

            val outputData = workDataOf(KEY_OUTPUT_FILE_PATH to simulatedPath)
            return Result.success(outputData)

        } catch (e: Exception) {
            val errorMessage = "Falha no download da música: ${e.message}"
            Log.e(TAG, errorMessage, e)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE_DOWNLOAD to errorMessage))
        }
    }
}
