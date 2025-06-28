// File: viewmodel/VideoGeneratorViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.VideoGeneratorDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.services.VideoProgressOverlayService // <<<<< IMPORT ADICIONADO
import com.carlex.euia.utils.VideoEditorComTransicoes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel responsável por gerenciar a lógica de geração do vídeo final.
 *
 * Interage com:
 * - [VideoProjectDataStoreManager] para obter a lista de cenas válidas.
 * - [AudioDataStoreManager] para obter os caminhos dos arquivos de áudio principal, música e legenda.
 * - [VideoGeneratorDataStoreManager] para persistir o caminho do vídeo final gerado.
 * - [VideoEditorComTransicoes] para realizar a edição e montagem do vídeo.
 * - [VideoProgressOverlayService] para exibir um ícone de progresso flutuante.
 *
 * Expõe estados como [isGeneratingVideo], [generationLogs], [generationProgress],
 * e o caminho do vídeo gerado ([generatedVideoPath]).
 *
 * @param application A instância da aplicação, necessária para [AndroidViewModel] e contexto.
 */
class VideoGeneratorViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VideoGeneratorViewModel"

    private val audioDataStoreManager = AudioDataStoreManager(application)
    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(application)
    private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(application)
    private val applicationContext: Context = application.applicationContext

    private val _isGeneratingVideo = MutableStateFlow(false)
    val isGeneratingVideo: StateFlow<Boolean> = _isGeneratingVideo.asStateFlow()

    private val _generationLogs = MutableStateFlow<List<String>>(emptyList())
    val generationLogs: StateFlow<List<String>> = _generationLogs.asStateFlow()

    private val _generationProgress = MutableStateFlow(0.0f)
    val generationProgress: StateFlow<Float> = _generationProgress.asStateFlow()

    private val _generatedVideoPath = MutableStateFlow("")
    val generatedVideoPath: StateFlow<String> = _generatedVideoPath.asStateFlow()

    init {
        Log.d(TAG, "VideoGeneratorViewModel inicializado.")
        loadLastGeneratedVideoPathFromDataStore()
    }

    private fun loadLastGeneratedVideoPathFromDataStore() {
        viewModelScope.launch {
            videoGeneratorDataStoreManager.finalVideoPath.firstOrNull()?.let { savedPath ->
                if (savedPath.isNotEmpty()) {
                    val file = File(savedPath)
                    if (file.exists() && file.isFile) {
                        _generatedVideoPath.value = savedPath
                        Log.i(TAG, "Caminho do vídeo gerado carregado do DataStore: $savedPath")
                    } else {
                        Log.w(TAG, "Arquivo de vídeo salvo ($savedPath) não encontrado. Limpando do DataStore.")
                        videoGeneratorDataStoreManager.clearFinalVideoPath()
                    }
                }
            }
        }
    }

    fun clearCurrentVideoState() {
        _generatedVideoPath.value = ""
        _generationLogs.value = emptyList()
        _generationProgress.value = 0f
        viewModelScope.launch {
            videoGeneratorDataStoreManager.clearFinalVideoPath()
        }
        Log.i(TAG, "Estado do vídeo gerado (incluindo persistência) foi limpo.")
    }

    fun generateVideo() {
        if (_isGeneratingVideo.value) {
            Log.d(TAG, "Geração de vídeo já em andamento. Requisição ignorada.")
            Toast.makeText(applicationContext, R.string.video_gen_vm_status_already_generating, Toast.LENGTH_SHORT).show()
            return
        }

        // <<<<< INÍCIO DA MODIFICAÇÃO: INICIAR SERVIÇO DE OVERLAY >>>>>
        val startIntent = Intent(applicationContext, VideoProgressOverlayService::class.java).apply {
            action = VideoProgressOverlayService.ACTION_START
            putExtra(VideoProgressOverlayService.EXTRA_PROGRESS, 0f)
        }
        applicationContext.startService(startIntent)
        Log.d(TAG, "Serviço de Overlay iniciado (ACTION_START).")
        // <<<<< FIM DA MODIFICAÇÃO >>>>>

        _isGeneratingVideo.value = true
        _generationLogs.value = listOf(applicationContext.getString(R.string.progress_starting_generation))
        _generationProgress.value = 0.0f
        Log.i(TAG, "Iniciando geração de vídeo.")

        viewModelScope.launch(Dispatchers.IO) {
            var currentTotalDurationForProgress = 0.0

            try {
                Log.d(TAG, "Coletando dados de entrada para a geração do vídeo...")

                val currentScenes = videoProjectDataStoreManager.sceneLinkDataList.first()
                val scenesToInclude = currentScenes.filter { scene ->
                    scene.imagemGeradaPath?.isNotBlank() == true &&
                    scene.tempoInicio != null &&
                    scene.tempoFim != null &&
                    scene.tempoFim > scene.tempoInicio
                }

                if (scenesToInclude.isEmpty()) {
                    Log.w(TAG, "Nenhuma cena válida encontrada para incluir no vídeo.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.video_gen_vm_error_no_valid_scenes, Toast.LENGTH_LONG).show()
                    }
                    // Retorno antecipado - o bloco finally será executado
                    return@launch
                }

                currentTotalDurationForProgress = scenesToInclude.sumOf { it.tempoFim!! - it.tempoInicio!! }
                Log.d(TAG, "Duração total calculada para progresso: ${currentTotalDurationForProgress}s com ${scenesToInclude.size} cenas.")

                val audioFilePath = audioDataStoreManager.audioPath.first()
                if (audioFilePath.isBlank()) {
                    Log.w(TAG, "Caminho do áudio principal está vazio.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.video_gen_vm_error_main_audio_missing, Toast.LENGTH_LONG).show()
                    }
                    // Retorno antecipado - o bloco finally será executado
                    return@launch
                }

                val musicFilePath = audioDataStoreManager.videoMusicPath.first()
                val legendFilePath = audioDataStoreManager.legendaPath.first()

                Log.d(TAG, "Dados de entrada coletados. Chamando VideoEditor...")
                Log.d(TAG, "  Cenas: ${scenesToInclude.size}, Áudio: $audioFilePath, Música: $musicFilePath, Legenda: $legendFilePath")

                val finalVideoPathResult = VideoEditorComTransicoes.gerarVideoComTransicoes(
                    context = applicationContext,
                    scenes = scenesToInclude,
                    audioPath = audioFilePath,
                    musicaPath = musicFilePath,
                    legendaPath = legendFilePath,
                    logCallback = { logMessage ->
                        _generationLogs.value = _generationLogs.value + logMessage

                        val timeMatch = Regex("time=(\\d{2}:\\d{2}:\\d{2}\\.\\d{2})").find(logMessage)
                        timeMatch?.let {
                            val timeString = it.groupValues[1]
                            val parts = timeString.split(":", ".")
                            if (parts.size == 4) {
                                try {
                                    val hours = parts[0].toDouble()
                                    val minutes = parts[1].toDouble()
                                    val seconds = parts[2].toDouble()
                                    val centiseconds = parts[3].toDouble()
                                    val timeInSeconds = hours * 3600 + minutes * 60 + seconds + centiseconds / 100
                                    
                                    val progress = if (currentTotalDurationForProgress > 0) {
                                        (timeInSeconds / currentTotalDurationForProgress).toFloat().coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    _generationProgress.value = progress

                                    // <<<<< INÍCIO DA MODIFICAÇÃO: ATUALIZAR SERVIÇO DE OVERLAY >>>>>
                                    val updateIntent = Intent(applicationContext, VideoProgressOverlayService::class.java).apply {
                                        action = VideoProgressOverlayService.ACTION_UPDATE_PROGRESS
                                        putExtra(VideoProgressOverlayService.EXTRA_PROGRESS, progress)
                                    }
                                    applicationContext.startService(updateIntent)
                                    // <<<<< FIM DA MODIFICAÇÃO >>>>>

                                } catch (e: NumberFormatException) {
                                    Log.w(TAG, "Falha ao parsear tempo do log FFmpeg: '$timeString'", e)
                                }
                            }
                        }
                    }
                )

                Log.i(TAG, "VideoEditorComTransicoes concluído. Caminho resultante: $finalVideoPathResult")

                if (finalVideoPathResult.isNotBlank()) {
                    _generatedVideoPath.value = finalVideoPathResult
                    videoGeneratorDataStoreManager.setFinalVideoPath(finalVideoPathResult)
                    _generationProgress.value = 1.0f
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.video_gen_vm_success, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.e(TAG, "VideoEditorComTransicoes retornou um caminho vazio.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, R.string.video_gen_vm_error_ffmpeg_empty_path, Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: VideoEditorComTransicoes.VideoGenerationException) {
                Log.e(TAG, "Exceção durante a geração do vídeo (FFmpeg): ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, applicationContext.getString(R.string.video_gen_vm_error_ffmpeg_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado durante a geração do vídeo: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.video_gen_vm_error_unexpected, Toast.LENGTH_LONG).show()
                }
            } finally {
                _isGeneratingVideo.value = false
                Log.i(TAG, "Processo de geração de vídeo finalizado (sucesso ou falha).")

                // <<<<< INÍCIO DA MODIFICAÇÃO: PARAR SERVIÇO DE OVERLAY >>>>>
                val stopIntent = Intent(applicationContext, VideoProgressOverlayService::class.java).apply {
                    action = VideoProgressOverlayService.ACTION_STOP
                }
                applicationContext.startService(stopIntent)
                Log.d(TAG, "Serviço de Overlay parado (ACTION_STOP).")
                // <<<<< FIM DA MODIFICAÇÃO >>>>>
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "VideoGeneratorViewModel onCleared().")
    }
}