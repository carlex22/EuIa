// File: ui/VideoGeneratorContent.kt
package com.carlex.euia.ui

import android.content.Context // Necessário para FileProvider, mas não usado diretamente aqui
import android.net.Uri
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.R
import com.carlex.euia.viewmodel.VideoGeneratorViewModel
import java.io.File

private const val TAG_CONTENT = "VideoGeneratorContent" // Tag para logging crítico nesta tela

/**
 * Composable que exibe o conteúdo da aba "Finalizar" no fluxo de criação de vídeo.
 *
 * Esta tela é responsável por:
 * - Mostrar logs detalhados durante o processo de geração do vídeo final.
 * - Apresentar um player de vídeo ([VideoView]) para o vídeo gerado, se disponível.
 * - Exibir uma mensagem placeholder caso nenhum vídeo tenha sido gerado ou o processo esteja em andamento.
 *
 * A ação de iniciar a geração do vídeo e o indicador de progresso global são gerenciados
 * pela BottomAppBar controlada por `AppNavigationHostComposable`.
 *
 * @param modifier [Modifier] para aplicar a este Composable.
 * @param innerPadding [PaddingValues] fornecido pelo Scaffold pai, para garantir que o conteúdo
 *                     não se sobreponha a elementos como a TopAppBar ou BottomAppBar.
 * @param videoGeneratorViewModel ViewModel que gerencia o estado e a lógica da geração do vídeo final,
 *                                incluindo o caminho do vídeo gerado, logs de geração e estado de processamento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGeneratorContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    videoGeneratorViewModel: VideoGeneratorViewModel = viewModel()
) {
    val generatedVideoPath by videoGeneratorViewModel.generatedVideoPath.collectAsState()
    val isGeneratingVideo by videoGeneratorViewModel.isGeneratingVideo.collectAsState()
    val generationLogs by videoGeneratorViewModel.generationLogs.collectAsState()

    val logListState = rememberLazyListState()
    var isVideoPlaying by remember { mutableStateOf(false) }

    // Efeito para rolar automaticamente para o final da lista de logs quando novos logs são adicionados.
    LaunchedEffect(generationLogs.size) {
        if (generationLogs.isNotEmpty()) {
            logListState.animateScrollToItem(generationLogs.lastIndex)
        }
    }

    // Efeito para resetar o estado de reprodução do vídeo se o caminho do vídeo mudar
    // (ex: um novo vídeo é gerado, substituindo um anterior).
    LaunchedEffect(generatedVideoPath) {
        isVideoPlaying = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding) // Aplica o padding do Scaffold pai
            .padding(horizontal = 16.dp), // Padding horizontal adicional para o conteúdo interno
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Condicionalmente exibe logs, o player de vídeo ou um placeholder.
        when {
            isGeneratingVideo -> {
                // Exibe logs enquanto o vídeo está sendo gerado.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(), // Ocupa o espaço vertical disponível
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.video_generator_title_generating),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .weight(1f) // Permite que a lista de logs expanda
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(generationLogs) { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            generatedVideoPath.isNotBlank() -> {
                // Exibe o player de vídeo se um caminho válido foi gerado.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(), // Ocupa o espaço vertical disponível
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center // Centraliza o player se houver espaço extra
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            // .weight(1f) // Pode ser removido se o aspectRatio for suficiente
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f) // Mantém proporção comum de vídeo vertical
                            .background(Color.Black) // Fundo para o player
                    ) {
                        VideoPlayerInternal(
                            videoPath = generatedVideoPath,
                            isPlaying = isVideoPlaying,
                            onPlaybackStateChange = { isPlaying -> isVideoPlaying = isPlaying },
                            invalidPathErrorText = stringResource(R.string.video_generator_error_invalid_path)
                        )
                        // Overlay com botão de play se o vídeo não estiver tocando.
                        if (!isVideoPlaying) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.4f)) // Fundo semi-transparente
                                    .clickable { isVideoPlaying = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.video_generator_action_play_video),
                                    modifier = Modifier.size(72.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Exibe parte do caminho do arquivo gerado.
                    Text(
                        text = stringResource(R.string.video_generator_label_file_path, generatedVideoPath.takeLast(50)),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            else -> {
                // Placeholder se nenhum vídeo foi gerado e o processo não está em andamento.
                Box(
                    modifier = Modifier.weight(1f).fillMaxSize(), // Ocupa todo o espaço e centraliza
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.video_generator_placeholder_no_video), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Composable interno que encapsula o [VideoView] do Android para reprodução de vídeo.
 *
 * @param videoPath O caminho local do arquivo de vídeo a ser reproduzido.
 * @param isPlaying Estado booleano que controla se o vídeo deve estar tocando.
 * @param onPlaybackStateChange Callback para notificar mudanças no estado de reprodução.
 * @param invalidPathErrorText Mensagem a ser exibida se o caminho do vídeo for inválido.
 */
@Composable
private fun VideoPlayerInternal(
    videoPath: String,
    isPlaying: Boolean,
    onPlaybackStateChange: (Boolean) -> Unit,
    invalidPathErrorText: String
) {
    val context = LocalContext.current
    // Calcula a URI do vídeo, usando FileProvider para acesso seguro.
    // A URI é recalculada apenas se `videoPath` mudar.
    val videoUri = remember(videoPath) {
        if (videoPath.isNotBlank()) {
            val file = File(videoPath)
            if (file.exists() && file.isFile) {
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    Log.e(TAG_CONTENT, "VideoPlayer: Erro ao obter URI com FileProvider para: $videoPath. Verifique a configuração do FileProvider e o caminho do arquivo.", e)
                    null
                }
            } else {
                Log.w(TAG_CONTENT, "VideoPlayer: Arquivo de vídeo não encontrado em $videoPath")
                null
            }
        } else null
    }

    val currentOnPlaybackStateChange by rememberUpdatedState(onPlaybackStateChange)
    var videoViewInstance by remember { mutableStateOf<VideoView?>(null) }

    // Efeito para limpar recursos do VideoView quando o Composable é disposto ou a URI muda.
    DisposableEffect(videoUri) {
        onDispose {
            videoViewInstance?.apply {
                stopPlayback()
                setMediaController(null) // Remove o MediaController para evitar leaks
                //setOnCompletionListener(null) // Adicional, se necessário
                //setOnErrorListener(null)
                //setOnPreparedListener(null)
            }
            videoViewInstance = null
            Log.d(TAG_CONTENT, "VideoPlayer: Instância do VideoView limpa.")
        }
    }

    if (videoUri != null) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoViewInstance = this
                    val mediaController = MediaController(ctx)
                    setMediaController(mediaController)
                    // Ancorar os controles de mídia à própria VideoView pode ser mais robusto.
                    mediaController.setAnchorView(this)

                    setVideoURI(videoUri)
                    setOnPreparedListener { mediaPlayer ->
                        Log.d(TAG_CONTENT, "VideoPlayer: Vídeo preparado. Duração: ${mediaPlayer.duration}ms. URI: $videoUri")
                        // Não inicia a reprodução automaticamente; isso é controlado pelo estado `isPlaying`.
                        currentOnPlaybackStateChange(this.isPlaying) // Atualiza o estado inicial de reprodução
                    }
                    setOnCompletionListener {
                        Log.d(TAG_CONTENT, "VideoPlayer: Reprodução completa. URI: $videoUri")
                        currentOnPlaybackStateChange(false)
                        seekTo(0) // Volta para o início para permitir nova reprodução
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG_CONTENT, "VideoPlayer: Erro durante a reprodução. What: $what, Extra: $extra. URI: $videoUri")
                        currentOnPlaybackStateChange(false)
                        true // Indica que o erro foi tratado
                    }
                }
            },
            update = { view ->
                // Controla a reprodução/pausa com base no estado `isPlaying`.
                if (isPlaying && !view.isPlaying) {
                    view.requestFocus() // Solicita foco para que os controles de mídia funcionem corretamente.
                    view.start()
                } else if (!isPlaying && view.isPlaying) {
                    view.pause()
                }
                // Se a videoUri mudar, o AndroidView é recomposto (devido à chave no DisposableEffect)
                // e o factory será chamado novamente, configurando a nova URI.
            },
            modifier = Modifier.fillMaxSize() // O VideoView preenche o Box pai.
        )
    } else {
        // Exibe uma mensagem de erro se a URI do vídeo for nula.
        Box(
            modifier = Modifier.fillMaxSize().background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                invalidPathErrorText,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}