// File: euia/viewmodel/MusicViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class MusicTrack(
    val id: String,
    val title: String,
    val duration: String,
    val path: String
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MusicViewModel"
    private val audioDataStore = AudioDataStoreManager(application)
    private var mediaPlayer: MediaPlayer? = null
    private var playerJob: Job? = null

    private val sharedMusicDir = File(application.getExternalFilesDir(null), "SharedMusic")

    private val _musicTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val musicTracks: StateFlow<List<MusicTrack>> = _musicTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val selectedMusicPath: StateFlow<String> = audioDataStore.videoMusicPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
        
    private val _isPlayingId = MutableStateFlow<String?>(null)
    val isPlayingId: StateFlow<String?> = _isPlayingId.asStateFlow()

    init {
        // <<< INÍCIO DA CORREÇÃO >>>
        // A verificação de diretório e o carregamento dos arquivos agora
        // são disparados dentro de uma corrotina para não bloquear o construtor.
        viewModelScope.launch(Dispatchers.IO) {
            if (!sharedMusicDir.exists()) {
                sharedMusicDir.mkdirs()
            }
            loadLocalMusicFiles()
        }
        // <<< FIM DA CORREÇÃO >>>
    }

    fun loadLocalMusicFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // A verificação `exists()` foi removida daqui, pois já acontece no `init`
                val files = sharedMusicDir.listFiles { _, name -> name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") }
                val tracks = files?.mapNotNull { file ->
                    getMusicTrackFromFile(file)
                } ?: emptyList()
                _musicTracks.value = tracks.sortedBy { it.title }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar arquivos de música locais", e)
            } finally {
                withContext(Dispatchers.Main) {
                     _isLoading.value = false
                }
            }
        }
    }
    
    private fun getMusicTrackFromFile(file: File): MusicTrack? {
         return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            val durationFormatted = String.format("%02d:%02d", minutes, seconds)
            retriever.release()
            MusicTrack(id = file.absolutePath, title = file.nameWithoutExtension, duration = durationFormatted, path = file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler metadados do arquivo: ${file.name}", e)
            null
        }
    }

    fun playOrPauseTrack(track: MusicTrack) {
        playerJob?.cancel() 
        playerJob = viewModelScope.launch {
            if (_isPlayingId.value == track.id && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                _isPlayingId.value = null 
                Log.d(TAG, "Pausando a faixa: ${track.title}")
            } else {
                stopPlayback() 
                
                Log.d(TAG, "Iniciando a faixa: ${track.title}")
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(track.path)
                        setOnPreparedListener { mp ->
                            mp.start()
                            _isPlayingId.value = track.id 
                        }
                        setOnCompletionListener {
                            Log.d(TAG, "Faixa completou: ${track.title}")
                            stopPlayback() 
                        }
                        setOnErrorListener { mp, what, extra ->
                            Log.e(TAG, "Erro no MediaPlayer (what: $what, extra: $extra) para a faixa: ${track.path}")
                            stopPlayback()
                            true 
                        }
                        prepareAsync()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Erro de IO ao preparar a faixa: ${track.path}", e)
                    stopPlayback()
                }
            }
        }
    }
    
    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlayingId.value = null
        Log.d(TAG, "Playback parado e MediaPlayer liberado.")
    }

    fun selectMusicForProject(track: MusicTrack) {
        viewModelScope.launch {
            audioDataStore.setVideoMusicPath(track.path)
        }
    }

    fun deleteMusicFile(track: MusicTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isPlayingId.value == track.id) {
                withContext(Dispatchers.Main) {
                    stopPlayback()
                }
            }
            val file = File(track.path)
            if (file.exists() && file.delete()) {
                if (selectedMusicPath.value == track.path) {
                    audioDataStore.setVideoMusicPath("")
                }
                // Recarrega a lista após a exclusão
                loadLocalMusicFiles()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Falha ao deletar o arquivo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    fun saveLocalMusicPath(uri: Uri?) {
        if (uri == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val context = getApplication<Application>().applicationContext
            try {
                val fileName = BitmapUtils.getFileNameFromUri(context, uri) ?: "music_${System.currentTimeMillis()}"
                val outputFile = File(sharedMusicDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.i(TAG, "Música importada e salva em: ${outputFile.absolutePath}")

                val newTrack = getMusicTrackFromFile(outputFile)
                if (newTrack != null) {
                    // Seleciona a música recém-importada automaticamente
                    selectMusicForProject(newTrack)
                    Log.i(TAG, "Música recém-importada '${newTrack.title}' foi definida como a seleção atual.")
                }

                // Recarrega a lista para incluir o novo arquivo
                loadLocalMusicFiles()

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar o arquivo de música importado.", e)
                 withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao importar música.", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        playerJob?.cancel()
    }
}