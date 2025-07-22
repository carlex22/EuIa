// File: euia/ui/project/VideoPlayer.kt
package com.carlex.euia.ui.project

import android.net.Uri
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG_VIDEO_PLAYER = "ProjectVideoPlayer"

/**
 * Um Composable reutilizável para exibir um vídeo de um arquivo local.
 * Gerencia a obtenção da Uri a partir de um caminho de arquivo e o ciclo de vida do VideoView.
 *
 * @param videoPath O caminho absoluto para o arquivo de vídeo local.
 * @param isPlaying Controla se o vídeo deve estar tocando ou pausado.
 * @param onPlaybackStateChange Callback para notificar o chamador sobre mudanças no estado de reprodução.
 * @param invalidPathErrorText Mensagem a ser exibida se o caminho do vídeo for inválido.
 * @param loopVideo Define se o vídeo deve tocar em loop.
 * @param modifier O Modifier a ser aplicado ao componente do player.
 */
@Composable
fun ProjectVideoPlayer(
    videoPath: String,
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String,
    loopVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoUri by remember(videoPath) { mutableStateOf<Uri?>(null) }
    var isLoadingUri by remember(videoPath) { mutableStateOf(true) }

    LaunchedEffect(videoPath) {
        isLoadingUri = true
        videoUri = withContext(Dispatchers.IO) {
            try {
                if (videoPath.isNotBlank()) {
                    val file = File(videoPath)
                    if (file.exists() && file.isFile) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else {
                        Log.w(TAG_VIDEO_PLAYER, "Arquivo de vídeo não encontrado: $videoPath")
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG_VIDEO_PLAYER, "Erro ao obter URI para: $videoPath", e)
                null
            }
        }
        isLoadingUri = false
    }

    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)

    if (isLoadingUri) {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        }
    } else if (videoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(videoUri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = loopVideo
                        if (isPlaying) start()
                    }
                    setOnCompletionListener {
                        currentOnPlaybackStateChange(false)
                    }
                    setOnErrorListener { _, _, _ ->
                        currentOnPlaybackStateChange(false)
                        true
                    }
                }
            },
            update = { view ->
                if (isPlaying && !view.isPlaying) {
                    view.start()
                } else if (!isPlaying && view.isPlaying) {
                    view.pause()
                }
            },
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text(invalidPathErrorText, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}