// File: euia/viewmodel/MusicViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.AudioDataStoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
// <<< IMPORTS ADICIONADOS AQUI >>>
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

// Data class para representar uma faixa de música sugerida pela API (ou fictícia)
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: String,
    val url: String // URL para streaming ou download
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val audioDataStore = AudioDataStoreManager(application)

    // Estado para a sugestão de estilo de música
    private val _musicSuggestion = MutableStateFlow("Para um vídeo inspirador, sugerimos uma trilha sonora orquestral com um toque de piano.")
    val musicSuggestion: StateFlow<String> = _musicSuggestion.asStateFlow()

    // Estado para a lista de músicas buscadas (inicia com dados fictícios)
    private val _musicTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val musicTracks: StateFlow<List<MusicTrack>> = _musicTracks.asStateFlow()

    // Estado de carregamento
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Caminho da música selecionada (lido do DataStore)
    // <<< CORREÇÃO AQUI >>>
    val selectedMusicPath: StateFlow<String> = audioDataStore.videoMusicPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        fetchMusicSuggestions()
    }

    // Busca sugestões de música (atualmente simulado)
    fun fetchMusicSuggestions() {
        viewModelScope.launch {
            _isLoading.value = true
            // Simula uma chamada de API com um delay
            kotlinx.coroutines.delay(1500)
            _musicTracks.value = listOf(
                MusicTrack("1", "Amanhecer Inspirador", "Orquestra Aurora", "2:30", "URL_MUSICA_1"),
                MusicTrack("2", "Sonhos de Piano", "Clara Tecla", "3:15", "URL_MUSICA_2"),
                MusicTrack("3", "Batida Eletrônica Positiva", "DJ Bits", "1:55", "URL_MUSICA_3")
            )
            _isLoading.value = false
        }
    }

    // Ação para tocar uma faixa (atualmente simulado com Toast)
    fun playTrack(context: Context, url: String) {
        Toast.makeText(context, "Simulando play para: $url", Toast.LENGTH_SHORT).show()
        // A implementação real usaria um player de mídia
    }

    // Ação para selecionar uma faixa para download (atualmente simulado com Toast)
    fun selectTrackForDownload(context: Context, track: MusicTrack) {
        Toast.makeText(context, "Enfileirando download de: ${track.title}", Toast.LENGTH_SHORT).show()
        // Esta função irá, no futuro, chamar o MusicDownloadWorker
    }

    // Salva o caminho de um arquivo de música local selecionado pelo usuário
    fun saveLocalMusicPath(uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                // Por enquanto, salvamos a URI diretamente. No futuro, copiaremos o arquivo
                // para o armazenamento interno e salvaremos o caminho absoluto.
                audioDataStore.setVideoMusicPath(uri.toString())
            }
        }
    }
}