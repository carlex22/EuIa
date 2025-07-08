// File: euia/ui/MusicSelectionContent.kt
package com.carlex.euia.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.viewmodel.MusicTrack
import com.carlex.euia.viewmodel.MusicViewModel

@Composable
fun MusicSelectionContent(
    modifier: Modifier = Modifier,
    musicViewModel: MusicViewModel = viewModel()
) {
    val suggestion by musicViewModel.musicSuggestion.collectAsState()
    val tracks by musicViewModel.musicTracks.collectAsState()
    val isLoading by musicViewModel.isLoading.collectAsState()
    val selectedPath by musicViewModel.selectedMusicPath.collectAsState()
    val context = LocalContext.current

    // Launcher para o seletor de arquivos de áudio locais
    val localFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        musicViewModel.saveLocalMusicPath(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Exibe a sugestão de estilo de música
        Text(
            text = suggestion,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Botão para selecionar um arquivo de música local
        OutlinedButton(
            onClick = { localFilePickerLauncher.launch("audio/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.music_select_local_file)) // Necessário adicionar string
        }

        // Exibe o caminho do arquivo selecionado
        if (selectedPath.isNotEmpty()) {
            Text(
                text = stringResource(R.string.music_current_selection, selectedPath.substringAfterLast('/')), // Necessário adicionar string
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        // Exibe a lista de músicas da API ou um indicador de carregamento
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracks, key = { it.id }) { track ->
                    MusicTrackItem(
                        track = track,
                        onPlayClick = { musicViewModel.playTrack(context, it.url) },
                        onSelectClick = { musicViewModel.selectTrackForDownload(context, it) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicTrackItem(
    track: MusicTrack,
    onPlayClick: (MusicTrack) -> Unit,
    onSelectClick: (MusicTrack) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium)
                Text("${track.artist} - ${track.duration}", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                IconButton(onClick = { onPlayClick(track) }) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.music_action_play)) // Necessário adicionar string
                }
                IconButton(onClick = { onSelectClick(track) }) {
                    Icon(Icons.Default.Check, stringResource(R.string.music_action_select)) // Necessário adicionar string
                }
            }
        }
    }
}