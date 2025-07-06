// File: euia/utils/VideoEditorComTransicoes.kt
package com.carlex.euia.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.arthenica.ffmpegkit.*
import android.graphics.BitmapFactory
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive // Importa isActive
import kotlinx.coroutines.CancellationException // Importa CancellationException

/**
 * Editor de vídeo focado em:
 * - Criar slideshow a partir de imagens ou usar clipes de vídeo (agora usando SceneLinkData).
 * - Adicionar transições (controlado por preferência).
 * - Adicionar efeito Ken Burns (zoompan) (controlado por preferência).
 * - Mixar áudio principal (voz) e música de fundo.
 * - Adicionar legendas (controlado por preferência).
 * - Usar dimensões de vídeo das preferências.
 * - Salvar vídeo final no diretório de preferências do projeto.
 */
object VideoEditorComTransicoes {

    private const val TAG = "VideoEditorComTransicoes"
    private const val DEFAULT_VIDEO_WIDTH = 720
    private const val DEFAULT_VIDEO_HEIGHT = 1280
    private const val DEFAULT_TRANSITION_DURATION = 0.5 // Duração padrão da transição em segundos
    private const val BATCH_SIZE = 5 // Número de cenas por lote

    

    // Função auxiliar para criar imagem preta
    private fun createTemporaryBlackImage(context: Context, width: Int, height: Int, projectDirName: String): String? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val tempDir = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
        
        tempDir?.mkdirs()

        val file = File(tempDir, "black_padding_end_${System.currentTimeMillis()}.png")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            Log.d(TAG, "Imagem preta temporária criada em: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao criar imagem preta temporária: ${e.message}", e)
            bitmap.recycle()
            return null
        }
    }


    suspend fun gerarVideoComTransicoes(
        context: Context, // `context` is passed from ViewModel or Worker
        scenes: List<SceneLinkData>,
        audioPath: String,
        musicaPath: String,
        legendaPath: String, // Este é o caminho do arquivo SRT gerado
        logCallback: (String) -> Unit
    ): String = coroutineScope { // Uses coroutineScope to inherit context and be cancellable
        Log.d(TAG, "🎬 Iniciando gerarVideo com ${scenes.size} cenas SceneLinkData")
        require(scenes.isNotEmpty()) { "A lista de cenas não pode estar vazia" }

        val videoPreferencesManager = VideoPreferencesDataStoreManager(context) // Local instance for scope
        val (projectDirName, larguraVideoPref, alturaVideoPref, enableSubtitlesPref, enableSceneTransitionsPref, enableZoomPanPref, videoFpsPref, videoHdMotionPref) = withContext(Dispatchers.IO) {
            val dirName = videoPreferencesManager.videoProjectDir.first()
            val largura = videoPreferencesManager.videoLargura.first()
            val altura = videoPreferencesManager.videoAltura.first()
            val subtitles = videoPreferencesManager.enableSubtitles.first()
            val transitions = videoPreferencesManager.enableSceneTransitions.first()
            val zoomPan = videoPreferencesManager.enableZoomPan.first()
            val fps = videoPreferencesManager.videoFps.first()
            val hdMotion = videoPreferencesManager.videoHdMotion.first()
            Log.d(TAG, "Preferências lidas: Dir=$dirName, LxA=${largura ?: "N/D"}x${altura ?: "N/D"}, Legendas=$subtitles, Transições=$transitions, ZoomPan=$zoomPan, FPS=$fps, HDMotion=$hdMotion")
            Octuple(dirName, largura, altura, subtitles, transitions, zoomPan, fps, hdMotion)
        }

        val larguraFinalVideo = larguraVideoPref ?: DEFAULT_VIDEO_WIDTH
        val alturaFinalVideo = alturaVideoPref ?: DEFAULT_VIDEO_HEIGHT
        val tempoDeTransicaoEfetivo = if (enableSceneTransitionsPref && scenes.size > 1) DEFAULT_TRANSITION_DURATION else 0.0

        val sceneMediaInputs = mutableListOf<Pair<SceneLinkData, String>>()
        for (scene in scenes) {
            if (!isActive) throw CancellationException("Processo cancelado durante a preparação das cenas.")
            if (scene.imagemGeradaPath != null && scene.tempoInicio != null && scene.tempoFim != null && scene.tempoFim > scene.tempoInicio) {
                val mediaFile = File(scene.imagemGeradaPath)
                if (!mediaFile.exists()) {
                    Log.w(TAG, "Arquivo de mídia base (thumbnail/imagem) não encontrado: ${scene.imagemGeradaPath} para cena ${scene.cena}. Pulando cena.")
                    continue
                }
                if (mediaFile.name.startsWith("Vid_")) {
                    val videoFile = File(mediaFile.parentFile, mediaFile.nameWithoutExtension + ".mp4")
                    if (videoFile.exists()) {
                        sceneMediaInputs.add(Pair(scene, videoFile.absolutePath))
                        Log.d(TAG, "Cena ${scene.cena} usará VÍDEO: ${videoFile.absolutePath}")
                    } else {
                        Log.w(TAG, "Arquivo de vídeo ${videoFile.absolutePath} não encontrado para thumbnail ${scene.imagemGeradaPath}. Pulando cena ${scene.cena}.")
                    }
                } else {
                    sceneMediaInputs.add(Pair(scene, scene.imagemGeradaPath!!))
                    Log.d(TAG, "Cena ${scene.cena} usará IMAGEM: ${scene.imagemGeradaPath}")
                }
            } else {
                Log.w(TAG, "Cena ${scene.cena} inválida (sem mídia ou timing). Pulando.")
            }
        }
        
        require(sceneMediaInputs.isNotEmpty()) { "Nenhuma cena válida com mídia (imagem ou vídeo) e timing encontrada para gerar o vídeo." }

        // --- INÍCIO DA LÓGICA PARA ADICIONAR CENA PRETA DE PREENCHIMENTO FINAL ---
        var finalMediaPaths = sceneMediaInputs.map { it.second }.toMutableList()
        var finalValidScenes = sceneMediaInputs.map { it.first }.toMutableList()
        var duracaoCenas = finalValidScenes.map { it.tempoFim!! - it.tempoInicio!! }.toMutableList()
        var blackImagePathTemporary: String? = null // For cleanup later

        val lastMediaPath = finalMediaPaths.lastOrNull()
        val isLastSceneVideo = lastMediaPath?.let { File(it).name.endsWith(".mp4", ignoreCase = true) } ?: false

        if (isLastSceneVideo) {
            logCallback("Última cena é um vídeo. Adicionando cena preta de ${DEFAULT_TRANSITION_DURATION}s no final para transição.")
            Log.i(TAG, "Última cena real é um vídeo. Tentando adicionar cena preta de preenchimento.")

            blackImagePathTemporary = createTemporaryBlackImage(
                context,
                larguraFinalVideo,
                alturaFinalVideo,
                projectDirName
            )

            if (blackImagePathTemporary != null) {
                val lastActualSceneEndTime = finalValidScenes.lastOrNull()?.tempoFim ?: 0.0 // Default to 0 if list is empty, though check above should prevent this
                val blackPaddingScene = SceneLinkData(
                    id = UUID.randomUUID().toString(),
                    cena = "BLACK_PADDING_END",
                    tempoInicio = lastActualSceneEndTime, // Informativo
                    tempoFim = lastActualSceneEndTime + DEFAULT_TRANSITION_DURATION, // Informativo
                    imagemReferenciaPath = blackImagePathTemporary,
                    descricaoReferencia = "Preenchimento preto final",
                    promptGeracao = null,
                    imagemGeradaPath = blackImagePathTemporary, // Caminho para a imagem preta
                    similaridade = null,
                    aprovado = true,
                    exibirProduto = false,
                    isGenerating = false,
                    isChangingClothes = false,
                    generationAttempt = 0,
                    clothesChangeAttempt = 0
                )
                finalValidScenes.add(blackPaddingScene)
                finalMediaPaths.add(blackImagePathTemporary)
                duracaoCenas.add(DEFAULT_TRANSITION_DURATION) // Adiciona a duração da cena preta
                Log.i(TAG, "Cena preta adicionada. Novo total de mídias: ${finalMediaPaths.size}")
            } else {
                logCallback("Falha ao criar imagem preta temporária. Vídeo será gerado sem preenchimento extra.")
                Log.w(TAG, "Não foi possível criar a imagem preta temporária.")
            }
        }
        // --- FIM DA LÓGICA PARA ADICIONAR CENA PRETA ---

        // Converter SRT para ASS para a narrativa completa (uma vez só)
        var fullLegendaAssPath: String = "" // Caminho para o arquivo ASS temporário completo
        var tempFullAssFile: File? = null // Variável para rastrear o arquivo ASS temporário para limpeza

        if (enableSubtitlesPref && legendaPath.isNotBlank()) {
            val srtFile = File(legendaPath)
            if (srtFile.exists()) {
                try {
                    val srtContent = srtFile.readText()

                    val assConverter = SrtToAssConverter() // Usa valores padrão do construtor
                    val assContent = assConverter.convertSrtToAss(
                        srtContent = srtContent,
                        videoWidth = larguraFinalVideo,
                        videoHeight = alturaFinalVideo
                    )

                    val tempAssDir = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
                    if (tempAssDir == null) {
                        logCallback("❌ Erro interno: não foi possível criar diretório para legendas ASS. Gerando sem legendas.")
                        Log.e(TAG, "Falha ao criar diretório temporário para ASS. Gerando vídeo sem legendas.")
                    } else {
                        tempAssDir.mkdirs() // Garante que o diretório exista
                        val uniqueAssFileName = "full_legenda_${UUID.randomUUID()}.ass"
                        tempFullAssFile = File(tempAssDir, uniqueAssFileName)
                        tempFullAssFile.writeText(assContent)
                        fullLegendaAssPath = tempFullAssFile.absolutePath
                        Log.i(TAG, "Legenda SRT completa convertida para ASS e salva temporariamente em: $fullLegendaAssPath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao converter SRT para ASS ou salvar arquivo temporário: ${e.message}", e)
                    logCallback("❌ Erro ao preparar legendas. Gerando vídeo sem legendas. Detalhes: ${e.message}")
                }
            } else {
                Log.w(TAG, "Arquivo de legenda SRT não encontrado em: $legendaPath. Não serão usadas legendas.")
                logCallback("⚠️ Arquivo de legenda SRT não encontrado. Gerando vídeo sem legendas.")
            }
        } else {
            Log.d(TAG, "Legendas desabilitadas ou caminho da legenda vazio. Não serão usadas legendas.")
        }

        val fonteArialPath = try {
            copiarFonteParaCache(context, "Arial.ttf")
        } catch (e: Exception) {
            Log.e(TAG, "Falha crítica ao preparar fonte: ${e.message}")
            tempFullAssFile?.delete()
            blackImagePathTemporary?.let { File(it).delete() } 
            throw RuntimeException("Não foi possível preparar a fonte necessária para legendas.", e)
        }

        val subVideoPaths = mutableListOf<String>()
        val totalBatches = (finalValidScenes.size + BATCH_SIZE - 1) / BATCH_SIZE
        var currentGlobalTimeOffset = 0.0
        
        var batchNumber = 0

        // --- Processar em lotes ---
        for (i in finalValidScenes.indices step BATCH_SIZE) {
            if (!isActive) throw CancellationException("Processo cancelado durante o processamento de lotes.")
            batchNumber = (i / BATCH_SIZE) + 1
            logCallback("Processando lote $batchNumber de $totalBatches...")
            Log.i(TAG, "Processando lote $batchNumber de $totalBatches (cenas $i a ${min(i + BATCH_SIZE - 1, finalValidScenes.lastIndex)})")

            val currentBatchScenes = finalValidScenes.subList(i, min(i + BATCH_SIZE, finalValidScenes.size))
            val currentBatchMediaPaths = finalMediaPaths.subList(i, min(i + BATCH_SIZE, finalMediaPaths.size))
            val currentBatchDurations = duracaoCenas.subList(i, min(i + BATCH_SIZE, duracaoCenas.size))

            val batchOutputFilePath = createOutputFilePath(context, "batch_video_$batchNumber", projectDirName)

            // Calculate overall duration of this specific batch
            val batchTotalDuration = currentBatchDurations.sum() + (currentBatchScenes.size - 1) * tempoDeTransicaoEfetivo

            val tempBatchAssFile: File? = if (fullLegendaAssPath.isNotBlank()) {
                val batchAssFilePath = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
                if (batchAssFilePath == null) {
                    Log.e(TAG, "Falha ao criar diretório para legendas ASS de lote.")
                    null 
                } else {
                    val batchAssContent = SrtToAssConverter().convertSrtToAss(
                        srtContent = extractSrtForTimeRange(legendaPath, currentGlobalTimeOffset, currentGlobalTimeOffset + batchTotalDuration),
                        videoWidth = larguraFinalVideo,
                        videoHeight = alturaFinalVideo
                    )
                    val tempAssFile = File(batchAssFilePath, "batch_legenda_${batchNumber}_${UUID.randomUUID()}.ass")
                    tempAssFile.writeText(batchAssContent)
                    tempAssFile
                }
            } else null

            // Build and execute command for this batch
            val batchCommand = buildFFmpegCommandForBatch(
                context = context, 
                mediaPaths = currentBatchMediaPaths,
                duracaoCenas = currentBatchDurations,
                batchIndex = batchNumber,
                larguraVideo = larguraFinalVideo,
                alturaVideo = alturaFinalVideo,
                enableSubtitles = enableSubtitlesPref,
                legendaPath = tempBatchAssFile?.absolutePath, 
                fonteArialPath = fonteArialPath,
                enableSceneTransitions = enableSceneTransitionsPref,
                enableZoomPan = enableZoomPanPref,
                videoFps = videoFpsPref,
                videoHdMotion = videoHdMotionPref,
                outputPath = batchOutputFilePath,
                globalOffsetSeconds = currentGlobalTimeOffset 
            )

            try {
                _executeFFmpegCommand(batchCommand, batchOutputFilePath, "Lote $batchNumber de ${totalBatches+1}, Duracao: ${batchTotalDuration*1000}", logCallback)
                subVideoPaths.add(batchOutputFilePath) // <<-- Linha 339 na sua contagem (antes da correção). É uma adição normal.
            } finally {
                tempBatchAssFile?.delete()
                Log.d(TAG, "Arquivo ASS temporário do lote $batchNumber deletado: ${tempBatchAssFile?.absolutePath}")
            }
            currentGlobalTimeOffset += batchTotalDuration

            if (!isActive) throw CancellationException("Processo cancelado após o lote $batchNumber.")
        }

        // --- Concatenar os mini-vídeos ---
        logCallback("Concatenando ${subVideoPaths.size} mini-vídeos...")
        Log.i(TAG, "Concatenando ${subVideoPaths.size} mini-vídeos no final.")
        val finalOutputPath = createOutputFilePath(context, "video_final_concatenado", projectDirName)
        _concatenateSubVideos(context, videoPreferencesManager, subVideoPaths, finalOutputPath, logCallback)

        // --- Mixar áudio e adicionar legendas ao vídeo final ---
        logCallback("Mixando áudio e adicionando legendas ao vídeo final...")
        Log.i(TAG, "Mixando áudio principal e música, e adicionando legendas ao vídeo concatenado.")
        val finalVideoWithAudioAndSubsPath = createOutputFilePath(context, "video_final_com_audio_legendas", projectDirName)

        val ffmpegAudioSubsCommand = buildAudioAndSubtitleMixingCommand(
            videoPath = finalOutputPath,
            audioPath = audioPath,
            musicaPath = musicaPath,
            legendaPath = fullLegendaAssPath, 
            fonteArialPath = fonteArialPath,
            usarLegendas = enableSubtitlesPref,
            outputVideoPath = finalVideoWithAudioAndSubsPath,
            videoFps = videoFpsPref,
            totalDuration = currentGlobalTimeOffset 
        )
        _executeFFmpegCommand(ffmpegAudioSubsCommand, finalVideoWithAudioAndSubsPath, "Lote ${batchNumber+1} de ${totalBatches+1}, Duracao: $duracaoCenas", logCallback)

        // Limpeza dos arquivos temporários
        subVideoPaths.forEach { File(it).delete() }
        File(finalOutputPath).delete() 
        tempFullAssFile?.delete()
        blackImagePathTemporary?.let { File(it).delete() } 
        
        //Log.i(TAG, "Processo de geração de vídeo concluído com sucesso. Saída final: $finalVideoWithAudioAndSubsPath")
        //return 
        finalVideoWithAudioAndSubsPath
    }

    private suspend fun _executeFFmpegCommand(command: String, outputPath: String, taskName: String, logCallback: (String) -> Unit) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Executando FFmpeg para '$taskName':\n$command")

        return suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(command, { completedSession ->
                val returnCode = completedSession.returnCode
                val logs = completedSession.allLogsAsString
                val timeElapsed = (System.currentTimeMillis() - startTime) / 1000.0

                if (ReturnCode.isSuccess(returnCode)) {
                    val outFile = File(outputPath)
                    if (outFile.exists() && outFile.length() > 100) {
                        Log.i(TAG, "FFmpeg '$taskName' concluído com SUCESSO em ${"%.2f".format(Locale.US, timeElapsed)}s. Saída: $outputPath")
                        cont.resume(Unit)
                    } else {
                        val reason = if (!outFile.exists()) "não foi encontrado" else "está vazio ou muito pequeno (${outFile.length()} bytes)"
                        val errMsg = "FFmpeg '$taskName' reportou sucesso, mas o arquivo de saída '$outputPath' $reason. Logs:\n$logs"
                        Log.e(TAG, errMsg)
                        logCallback("❌ $taskName falhou: Arquivo inválido.")
                        outFile.delete() 
                        cont.resumeWithException(VideoGenerationException(errMsg))
                    }
                } else {
                    val errMsg = "FFmpeg '$taskName' FALHOU com código de retorno: $returnCode (Tempo: ${"%.2f".format(Locale.US, timeElapsed)}s). Logs:\n$logs"
                    Log.e(TAG, errMsg)
                    logCallback("❌ $taskName falhou com erro.")
                    File(outputPath).delete() 
                    cont.resumeWithException(VideoGenerationException(errMsg))
                }
            }, { log ->
                Log.v(TAG, "FFmpegLog ($taskName): ${log.message}")
                //logCallback(log.message)
            }, { stat ->
                val statMessage = "$taskName, Concluido=${stat.time}"
                Log.d(TAG, statMessage)
                logCallback(statMessage)
            })

            cont.invokeOnCancellation {
                Log.w(TAG, "🚫 Operação FFmpeg '$taskName' cancelada!")
                logCallback("🚫 Operação '$taskName' cancelada!")
                FFmpegKit.cancel(session.sessionId)
                File(outputPath).delete() 
                cont.resumeWithException(CancellationException("Operação FFmpeg '$taskName' cancelada."))
            }
        }
    }

    private suspend fun buildFFmpegCommandForBatch(
        context: Context, 
        mediaPaths: List<String>,
        duracaoCenas: List<Double>,
        batchIndex: Int,
        larguraVideo: Int,
        alturaVideo: Int,
        enableSubtitles: Boolean,
        legendaPath: String?, 
        fonteArialPath: String,
        enableSceneTransitions: Boolean,
        enableZoomPan: Boolean,
        videoFps: Int, 
        videoHdMotion: Boolean,
        outputPath: String,
        globalOffsetSeconds: Double 
    ): String {
        val cmd = StringBuilder("-y -hide_banner ")
        val filterComplex = StringBuilder()
        val tempoDeTransicaoEfetivo = if (enableSceneTransitions && mediaPaths.size > 1) DEFAULT_TRANSITION_DURATION else 0.0

        var inputIndex = 0
        mediaPaths.forEachIndexed { index, path ->
            val isVideoInput = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val duracaoDestaCena = duracaoCenas[index]
            val isLast = (index == mediaPaths.lastIndex)
            val inputDurationParaComando =
                if (isVideoInput) duracaoDestaCena
                else duracaoDestaCena + if (!isLast) tempoDeTransicaoEfetivo else 0.0
            if (isVideoInput) {
                cmd.append(String.format(Locale.US, "-stream_loop -1 -t %.4f -i \"%s\" -an ", duracaoDestaCena, path))
            } else {
                cmd.append(String.format(Locale.US, "-loop 1 -r %d -t %.4f -i \"%s\" ", videoFps, inputDurationParaComando, path))
            }
            inputIndex++
        }

        val processedMediaPads = mutableListOf<String>()
        mediaPaths.forEachIndexed { i, path ->
            val outputPad = "[processed_m$i]"
            processedMediaPads.add(outputPad)

            val isVideo = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val durBase = duracaoCenas[i]
            val isLast = (i == mediaPaths.lastIndex)
            val trimDuration = durBase + (if (!isLast && enableSceneTransitions) tempoDeTransicaoEfetivo else 0.0)

            val frames = (trimDuration * videoFps).toInt().coerceAtLeast(1)
            val durationExata = frames.toDouble() / videoFps
            val w = larguraVideo
            val h = alturaVideo
            val input = "[${i}:v]" 

            filterComplex.append("  ${input}split=2[main${i}][bg${i}];\n")
            filterComplex.append("  [bg${i}]scale='max(iw,${w}*2)':'max(ih,${h}*2)',crop=${w}:${h},boxblur=40:2,setsar=1[bg_final${i}];\n")

            var squareDix = w
            var squareDiy = h
            var ss = "${w}x${h}"

            if (w > h) {
                squareDix = (squareDiy * 1.2).toInt()
                ss = "${squareDix}x${squareDiy}"
            } else if (w < h) {
                squareDiy = (squareDix * 1.2).toInt()
                ss = "${squareDix}y${squareDiy}"
            }

            if (isVideo) {
                filterComplex.append("[main${i}]scale=$squareDix:$squareDiy:force_original_aspect_ratio=decrease[fg_scaled${i}];\n")
            } else {
                val fgChain = mutableListOf<String>()
                if (enableZoomPan) {
                    val (zoomExpr, xExpr, yExpr) = gerarZoompanExpressao(path, squareDix, squareDiy, frames, i)
                    fgChain.add("scale=${squareDix}:${squareDiy}:force_original_aspect_ratio=decrease," +
                                "pad=${squareDix}:${squareDiy}:(ow-iw)/2:(oh-ih)/2:color=black," +
                                "setsar=1," +
                                "zoompan=z=$zoomExpr:s=${ss}:d=$frames:x=$xExpr:y=$yExpr:fps=$videoFps")
                }
                fgChain.add("crop=$squareDix:$squareDiy")
                if (videoHdMotion) {
                    fgChain.add("minterpolate=fps=$videoFps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
                }
                filterComplex.append("  [main${i}]${fgChain.joinToString(",")}[fg_scaled${i}];\n")
            }

            filterComplex.append("  [fg_scaled${i}]pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0.0[fg_padded${i}];\n")
            filterComplex.append("  [bg_final${i}][fg_padded${i}]overlay=0:0[composite${i}];\n")

            val finalEffects = mutableListOf<String>()
            finalEffects.add("format=pix_fmts=rgba")
            finalEffects.add("fps=$videoFps")
            finalEffects.add("trim=duration=$durationExata")
            finalEffects.add("setpts=PTS-STARTPTS")

            filterComplex.append("  [composite${i}]${finalEffects.joinToString(separator = ",")}$outputPad;\n")
        }

        val tiposDeTransicao = listOf(
            "fade", "slidedown", "circleopen", "circleclose",
            "rectcrop", "distance", "fadeblack", "fadewhite"
        )
        val random = java.util.Random()

        val videoStreamFinal: String = when {
            enableSceneTransitions && processedMediaPads.size > 1 -> {
                processedMediaPads.forEachIndexed { index, padName ->
                    filterComplex.append("  $padName setpts=PTS-STARTPTS[sc_trans$index];\n")
                }
                var currentStream = "[sc_trans0]"
                var durationOfCurrentStream = duracaoCenas[0]
                for (i in 0 until processedMediaPads.size - 1) {
                    val nextSceneStream = "[sc_trans${i + 1}]"
                    val nextSceneOriginalDuration = duracaoCenas[i+1]
                    val xfadeOutputStreamName = if (i == processedMediaPads.size - 2) "[vc_final_batch]" else "[xfade_out_trans$i]"
                    val xfadeOffset = max(0.0, durationOfCurrentStream)

                    val tipoTransicao = tiposDeTransicao[random.nextInt(tiposDeTransicao.size)]

                    filterComplex.append(
                        "  $currentStream$nextSceneStream xfade=transition=$tipoTransicao:duration=${tempoDeTransicaoEfetivo}:offset=$xfadeOffset$xfadeOutputStreamName;\n"
                    )
                    currentStream = xfadeOutputStreamName
                    durationOfCurrentStream = durationOfCurrentStream + nextSceneOriginalDuration
                    durationOfCurrentStream = max(0.1, durationOfCurrentStream)
                }
                currentStream
            }
            processedMediaPads.isNotEmpty() && processedMediaPads.size > 1 -> {
                processedMediaPads.forEachIndexed { index, pad ->
                    filterComplex.append("  $pad setpts=PTS-STARTPTS[s_concat$index];\n")
                }
                val concatInputs = processedMediaPads.indices.joinToString("") { "[s_concat$it]" }
                filterComplex.append("  $concatInputs concat=n=${processedMediaPads.size}:v=1:a=0[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            processedMediaPads.isNotEmpty() -> {
                filterComplex.append("  ${processedMediaPads[0]} copy[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            else -> {
                val totalDurationFallback = duracaoCenas.sum().takeIf { it > 0.0 } ?: 0.1
                filterComplex.append("color=c=black:s=${larguraVideo}x${alturaVideo}:d=${max(0.1, totalDurationFallback)}[vc_final_batch];\n")
                "[vc_final_batch]"
            }
        }

        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$videoStreamFinal\" ")
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ") 
        cmd.append("-movflags +faststart ")
        val batchDuration = duracaoCenas.sum() + (mediaPaths.size - 1) * tempoDeTransicaoEfetivo
        cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, batchDuration)))
        cmd.append("\"$outputPath\"")
        return cmd.toString()
    }

    private suspend fun _concatenateSubVideos(appContext: Context, videoPreferencesDataStoreManager: VideoPreferencesDataStoreManager, subVideoPaths: List<String>, outputPath: String, logCallback: (String) -> Unit) {
        //if (!isActive) throw CancellationException("Concatenation canceled.")

        val listFile = File(getProjectSpecificDirectory(appContext, videoPreferencesDataStoreManager.videoProjectDir.first(), "temp_ffmpeg_assets")!!, "concat_list_${UUID.randomUUID()}.txt")
        try {
            listFile.bufferedWriter().use { out ->
                subVideoPaths.forEach { path ->
                    out.write("file '$path'\n")
                }
            }

            val command = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"$outputPath\""
            _executeFFmpegCommand(command, outputPath, "Concatenar Lotes", logCallback)
        } finally {
            listFile.delete()
        }
    }

    private suspend fun buildAudioAndSubtitleMixingCommand(
        videoPath: String,
        audioPath: String,
        musicaPath: String,
        legendaPath: String?, 
        fonteArialPath: String,
        usarLegendas: Boolean,
        outputVideoPath: String,
        videoFps: Int, // <<-- Passado como parâmetro para ser usado na string
        totalDuration: Double
    ): String {
        val cmd = StringBuilder("-y -hide_banner ")
        val filterComplex = StringBuilder()

        cmd.append("-i \"$videoPath\" ") 
        cmd.append("-i \"$audioPath\" ") 

        if (musicaPath.isNotBlank()) {
            cmd.append("-i \"$musicaPath\" ") 
            filterComplex.append("  [2:a]volume=0.18,adelay=500|500[bgm];\n")
            filterComplex.append("  [1:a][bgm]amix=inputs=2:duration=first:dropout_transition=3[a_out_mixed];\n")
        } else {
            filterComplex.append("  [1:a]acopy[a_out_mixed];\n")
        }

        var finalVideoStreamPad = "[0:v]" 

        if (usarLegendas) {
            val escapedLegendaPath = legendaPath// "${legendaPath.replace("'", "'\\''").replace("\\", "/").replace(":", "\\\\:")}"

            val fonteDir = File(fonteArialPath).parent?.replace("\\", "/")?.replace(":", "\\\\:") ?: "."

            filterComplex.append("  $finalVideoStreamPad subtitles=filename='$escapedLegendaPath'")
            filterComplex.append(":fontsdir='$fonteDir'")
            filterComplex.append(":force_style='Alignment=2'") 
            filterComplex.append("[v_out_with_subs];\n")
            finalVideoStreamPad = "[v_out_with_subs]"
        } else {
            filterComplex.append("  $finalVideoStreamPad copy[v_out_copy];\n")
            finalVideoStreamPad = "[v_out_copy]"
        }

        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$finalVideoStreamPad\" -map \"[a_out_mixed]\" ")
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ") // <<-- Usando $videoFps aqui
        cmd.append("-movflags +faststart ")
        cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, totalDuration)))
        cmd.append("\"$outputVideoPath\"")

        return cmd.toString()
    }


    fun Double.format(digits: Int): String = String.format("%.${digits}f", this)


    fun obterDimensoesImagem(path: String): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            null
        }
    }


    fun gerarZoompanExpressao(
        imgCaminho: String,
        larguraVideo: Int,
        alturaVideo: Int,
        frames: Int,
        cenaIdx: Int = -1
    ): Triple<String, String, String> {
        val (larguraImg, alturaImg) = obterDimensoesImagem(imgCaminho) ?: (1 to 1) 

        val escalaX = larguraVideo.toDouble() / larguraImg.toDouble()
        val escalaY = alturaVideo.toDouble() / alturaImg.toDouble()
        val zoomAjuste = minOf(escalaX, escalaY)
        val larguraAjuste = larguraImg.toDouble() * zoomAjuste
        val alturaAjuste = alturaImg.toDouble() * zoomAjuste
        val escalaX1 = larguraVideo.toDouble() / larguraAjuste.toDouble()
        val escalaY1 = alturaVideo.toDouble() / alturaAjuste.toDouble()
        val zoomFixo = maxOf(escalaX1, escalaY1)
        val zoom = zoomFixo
        var padX = (((larguraAjuste * zoomFixo) - larguraAjuste) / 2)
        var padY = (((alturaAjuste * zoomFixo) - alturaAjuste) / 2)

        var xExpr = "'$padX'"
        var yExpr = "'$padY'"

        if (escalaY1 == zoomFixo) {
            var t = padX
            xExpr = "'($t-(($t/$frames)*on))'"
        } else if (escalaX1 == zoomFixo) {
            var t = padY
            yExpr = "'($t-(($t/$frames)*on))'"
        }

        val zoomExpr = "'$zoom + ((on * ($frames - 1 - on) / (($frames - 1) / 2))/1000)'"

        Log.i(TAG, "Cena $cenaIdx - Img: ${larguraImg}x${alturaImg} | Video: ${larguraVideo}x${alturaVideo}")
        Log.i(TAG, "Cena $cenaIdx - escalaX: $escalaX | escalaY: $escalaY | zoomAjuste: $zoomAjuste")
        Log.i(TAG, "Cena $cenaIdx - larguraAjuste: $larguraAjuste | alturaAjuste: $alturaAjuste")
        Log.i(TAG, "Cena $cenaIdx - escalaX1: $escalaX1 | escalaY1: $escalaY1 | zoomFixo: $zoomFixo")
        Log.i(TAG, "Cena $cenaIdx - xExpr: $xExpr | yExpr: $yExpr | zoom: $zoom")

        return Triple(zoomExpr, xExpr, yExpr)
    }


    private fun copiarFonteParaCache(context: Context, nomeFonte: String): String {
        val assetPath = "fonts/$nomeFonte"
        val outFile = File(context.cacheDir, nomeFonte)
        if (!outFile.exists() || outFile.length() == 0L) {
            Log.d(TAG, "Copiando fonte '$nomeFonte' para o cache: ${outFile.absolutePath}")
            try {
                context.cacheDir.mkdirs()
                context.assets.open(assetPath).use { inputStream ->
                    outFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (!outFile.exists() || outFile.length() == 0L) {
                    throw RuntimeException("Arquivo da fonte não foi criado ou está vazio após a cópia.")
                }
                Log.i(TAG, "Fonte '$nomeFonte' copiada com sucesso para ${outFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao copiar fonte '$nomeFonte' do asset '$assetPath' para '${outFile.absolutePath}': ${e.message}", e)
                throw RuntimeException("Falha ao copiar fonte necessária '$nomeFonte': ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Fonte '$nomeFonte' já existe no cache: ${outFile.absolutePath}")
        }
        return outFile.absolutePath
    }

    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File? {
        val baseAppDir: File?
        if (projectDirName.isNotBlank()) {
            baseAppDir = context.getExternalFilesDir(null)
            if (baseAppDir != null) {
                val projectPath = File(baseAppDir, projectDirName)
                val finalDir = File(projectPath, subDir)
                if (!finalDir.exists() && !finalDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório do projeto (externo): ${finalDir.absolutePath}")
                    return null 
                }
                return finalDir
            } else {
                Log.w(TAG, "Armazenamento externo para vídeos não disponível. Usando fallback para armazenamento interno para o projeto '$projectDirName'.")
                val internalProjectPath = File(context.filesDir, projectDirName)
                val finalInternalDir = File(internalProjectPath, subDir)
                if (!finalInternalDir.exists() && !finalInternalDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório interno do projeto (fallback A): ${finalInternalDir.absolutePath}")
                    return null 
                }
                return finalInternalDir
            }
        }
        val defaultParentDirName = "video_editor_default"
        Log.w(TAG, "Nome do diretório do projeto para vídeos está em branco. Usando diretório de fallback interno: '$defaultParentDirName/$subDir'")
        val fallbackDir = File(File(context.filesDir, defaultParentDirName), subDir)
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            Log.e(TAG, "Falha ao criar diretório de fallback interno: ${fallbackDir.absolutePath}")
            return null 
        }
        return fallbackDir
    }


    private fun createOutputFilePath(context: Context, prefix: String, projectDirName: String): String {
        val subDiretorioVideos = "edited_videos"
        val outputDir = getProjectSpecificDirectory(context, projectDirName, subDiretorioVideos)
            ?: File(context.cacheDir, "edited_videos_fallback") 
        if (!outputDir.exists()) outputDir.mkdirs() 
        
        val timestamp = System.currentTimeMillis()
        val filename = "${prefix}_${timestamp}.mp4"
        val outputFile = File(outputDir, filename)
        return outputFile.absolutePath.also {
            Log.d(TAG, "📄 Caminho do arquivo de saída definido para vídeo: $it")
        }
    }
    
    private fun extractSrtForTimeRange(fullSrtPath: String, startTimeSeconds: Double, endTimeSeconds: Double): String {
        val srtFile = File(fullSrtPath)
        if (!srtFile.exists()) return ""

        val srtContent = srtFile.readText()
        val srtBlocks = srtContent.split("\n\n").filter { it.isNotBlank() }
        val extractedBlocks = mutableListOf<String>()

        for (block in srtBlocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.size < 2) continue

            val timingLine = lines[1]
            val times = timingLine.split(" --> ")
            if (times.size != 2) continue

            val srtStartTime = convertSrtTimeToSeconds(times[0].trim())
            val srtEndTime = convertSrtTimeToSeconds(times[1].trim())

            if (srtStartTime < endTimeSeconds && srtEndTime > startTimeSeconds) {
                val adjustedSrtStartTime = srtStartTime - startTimeSeconds
                val adjustedSrtEndTime = srtEndTime - startTimeSeconds

                val newTimingLine = "${formatSecondsToSrtTime(adjustedSrtStartTime)} --> ${formatSecondsToSrtTime(adjustedSrtEndTime)}"
                extractedBlocks.add("${lines[0]}\n$newTimingLine\n${lines.drop(2).joinToString("\n")}")
            }
        }
        return extractedBlocks.joinToString("\n\n")
    }

    private fun convertSrtTimeToSeconds(srtTime: String): Double {
        val parts = srtTime.split(":", ",")
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val milliseconds = parts[3].toInt()
        return (hours * 3600 + minutes * 60 + seconds).toDouble() + milliseconds / 1000.0
    }

    private fun formatSecondsToSrtTime(totalSeconds: Double): String {
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        val milliseconds = ((totalSeconds - seconds.toDouble() - minutes * 60 - hours * 3600) * 1000).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }


    class VideoGenerationException(message: String) : Exception(message)
    private data class Octuple<A, B, C, D, E, F, G, H>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F, val seventh: G, val eighth: H)
}