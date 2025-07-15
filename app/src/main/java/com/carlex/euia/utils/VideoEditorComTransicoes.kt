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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

object VideoEditorComTransicoes {

    private const val TAG = "VideoEditorComTransicoes"
    private const val DEFAULT_VIDEO_WIDTH = 720
    private const val DEFAULT_VIDEO_HEIGHT = 1280
    private const val DEFAULT_TRANSITION_DURATION = 0.5
    private const val BATCH_SIZE = 5

    private fun generateBatchHash(
        mediaPaths: List<String>, durations: List<Double>, enableZoomPan: Boolean, enableTransitions: Boolean,
        videoWidth: Int, videoHeight: Int, videoFps: Int
    ): String {
        val inputString = buildString {
            append(mediaPaths.joinToString(","))
            append(durations.joinToString(","))
            append(enableZoomPan)
            append(enableTransitions)
            append(videoWidth)
            append(videoHeight)
            append(videoFps)
        }
        val bytes = inputString.toByteArray()
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun cleanupOldCacheFiles(cacheDir: File, usedCacheFiles: Set<String>) {
        try {
            val allCacheFiles = cacheDir.listFiles() ?: return
            for (file in allCacheFiles) {
                if (file.name !in usedCacheFiles) {
                    if (file.delete()) {
                        Log.i(TAG, "Arquivo de cache antigo removido: ${file.name}")
                    } else {
                        Log.w(TAG, "Falha ao remover arquivo de cache antigo: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar arquivos de cache antigos.", e)
        }
    }

    suspend fun gerarVideoComTransicoes(
        context: Context, scenes: List<SceneLinkData>, audioPath: String, musicaPath: String,
        legendaPath: String, logCallback: (String) -> Unit
    ): String = coroutineScope {
        require(scenes.isNotEmpty()) { "A lista de cenas não pode estar vazia" }

        val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
        val (projectDirName, larguraVideoPref, alturaVideoPref, enableSubtitlesPref, enableSceneTransitionsPref, enableZoomPanPref, videoFpsPref, videoHdMotionPref) = withContext(Dispatchers.IO) {
            val dirName = videoPreferencesManager.videoProjectDir.first()
            val largura = videoPreferencesManager.videoLargura.first()
            val altura = videoPreferencesManager.videoAltura.first()
            val subtitles = videoPreferencesManager.enableSubtitles.first()
            val transitions = videoPreferencesManager.enableSceneTransitions.first()
            val zoomPan = videoPreferencesManager.enableZoomPan.first()
            val fps = videoPreferencesManager.videoFps.first()
            val hdMotion = videoPreferencesManager.videoHdMotion.first()
            Octuple(dirName, largura, altura, subtitles, transitions, zoomPan, fps, hdMotion)
        }

        val larguraFinalVideo = larguraVideoPref ?: DEFAULT_VIDEO_WIDTH
        val alturaFinalVideo = alturaVideoPref ?: DEFAULT_VIDEO_HEIGHT
        
        val sceneMediaInputs = scenes.mapNotNull { scene ->
            scene.videoPreviewPath?.let { Pair(scene, it) }
        }
        
        require(sceneMediaInputs.isNotEmpty()) { "Nenhuma cena válida com mídia encontrada." }

        val finalMediaPaths = sceneMediaInputs.map { it.second }
        val duracaoCenas = sceneMediaInputs.map { it.first.tempoFim!! - it.first.tempoInicio!! }

        var fullLegendaAssPath: String = ""
        var tempFullAssFile: File? = null

        if (enableSubtitlesPref && legendaPath.isNotBlank()) {
            val srtFile = File(legendaPath)
            if (srtFile.exists()) {
                try {
                    val srtContent = srtFile.readText()
                    val assConverter = SrtToAssConverter()
                    val assContent = assConverter.convertSrtToAss(srtContent, larguraFinalVideo, alturaFinalVideo)
                    val tempAssDir = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
                    if (tempAssDir != null) {
                        tempAssDir.mkdirs()
                        val uniqueAssFileName = "full_legenda_${UUID.randomUUID()}.ass"
                        tempFullAssFile = File(tempAssDir, uniqueAssFileName)
                        tempFullAssFile.writeText(assContent)
                        fullLegendaAssPath = tempFullAssFile.absolutePath
                    }
                } catch (e: Exception) {
                    logCallback("❌ Erro ao preparar legendas: ${e.message}")
                }
            } else {
                logCallback("⚠️ Arquivo de legenda SRT não encontrado.")
            }
        }

        val fonteArialPath = try {
            copiarFonteParaCache(context, "Arial.ttf")
        } catch (e: Exception) {
            throw RuntimeException("Não foi possível preparar a fonte necessária para legendas.", e)
        }

        val subVideoPaths = mutableListOf<String>()
        val totalBatches = ((finalMediaPaths.size + BATCH_SIZE - 1) / BATCH_SIZE)+2
        val batchCacheDir = getProjectSpecificDirectory(context, projectDirName, "batch_cache")
            ?: throw IOException("Não foi possível criar o diretório de cache.")


        var batchNumber = 0
        
        val subDura= mutableListOf<Double>()
        val subDura1= mutableListOf<Double>()
        
        for (i in finalMediaPaths.indices step BATCH_SIZE) {
            if (!isActive) throw CancellationException("Processo cancelado durante o processamento de lotes.")
            batchNumber = (i / BATCH_SIZE) + 1
            logCallback("Processando lote $batchNumber de $totalBatches...")

            val currentBatchMediaPaths = finalMediaPaths.subList(i, min(i + BATCH_SIZE, finalMediaPaths.size))
            val currentBatchDurations = duracaoCenas.subList(i, min(i + BATCH_SIZE, duracaoCenas.size))
            val batchHash = generateBatchHash(currentBatchMediaPaths, currentBatchDurations, enableZoomPanPref, enableSceneTransitionsPref, larguraFinalVideo, alturaFinalVideo, videoFpsPref)
            val cachedFile = File(batchCacheDir, "batch_${batchNumber}_${batchHash}.mp4")

            if (cachedFile.exists() && cachedFile.length() > 100) {
                logCallback("Lote $batchNumber encontrado no cache.")
                subVideoPaths.add(cachedFile.absolutePath)
                continue
            }
            
            
                
            val batchOutputFilePath = cachedFile.absolutePath
            val (batchCommand, duration) =  stitchClipsWithTransitions(
                context = context,
                clipPaths = currentBatchMediaPaths,
                outputPath = batchOutputFilePath,
                enableTransitions = enableSceneTransitionsPref, // Você pode tornar isso uma preferência do usuário
                logCallback = logCallback,
                subDura = subDura1
            )
            
            subDura.add(duration + if (enableSceneTransitionsPref) DEFAULT_TRANSITION_DURATION else 0.0)
            

            _executeFFmpegCommand(batchCommand, batchOutputFilePath, "Lote $batchNumber de ${totalBatches}, Duracao: ${duration*1000}", logCallback)
            subVideoPaths.add(batchOutputFilePath)
        }
        
        batchNumber++
        
        
   
        
        logCallback("Concatenando lotes...")
        val finalOutputPathConcat = createOutputFilePath(context, "video_final_concatenado", projectDirName)
    
        val (batchCommandConcat, duration) =  stitchClipsWithTransitions(
                context = context,
                clipPaths = subVideoPaths,
                outputPath = finalOutputPathConcat,
                enableTransitions = enableSceneTransitionsPref, // Você pode tornar isso uma preferência do usuário
                logCallback = logCallback,
                subDura = subDura1
            )
       
        
        _executeFFmpegCommand(batchCommandConcat, finalOutputPathConcat, "Lote $batchNumber de ${totalBatches}, Duracao: ${subDura.sum()*1000}", logCallback)
        
        val durationFim =  getClipDuration(audioPath) ?: throw IOException("Não foi possível obter a duração do clipe: $audioPath")
     
        
        logCallback("Mixando áudio e legendas...")
        val finalVideoWithAudioAndSubsPath = createOutputFilePath(context, "video_final_com_audio_legendas", projectDirName)
        val ffmpegAudioSubsCommand = buildAudioAndSubtitleMixingCommand(
            finalOutputPathConcat, audioPath, musicaPath, fullLegendaAssPath, fonteArialPath,
            enableSubtitlesPref, finalVideoWithAudioAndSubsPath, videoFpsPref, durationFim
        )
        
        batchNumber++
        _executeFFmpegCommand(ffmpegAudioSubsCommand, finalVideoWithAudioAndSubsPath, "Mixagem Final Lote $batchNumber de ${totalBatches}, Duracao: ${durationFim*1000}", logCallback)

        tempFullAssFile?.delete()
        return@coroutineScope finalVideoWithAudioAndSubsPath
    }
    
    
    
    /**
     * Obtém a duração de um arquivo de mídia em segundos usando ffprobe.
     *
     * @param filePath O caminho para o arquivo de vídeo ou áudio.
     * @return A duração em segundos como um Double, ou null em caso de erro.
     */
    private suspend fun getClipDuration(filePath: String): Double? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Obtendo duração para: $filePath")
        val session = FFprobeKit.execute("-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$filePath\"")
        if (ReturnCode.isSuccess(session.returnCode)) {
            val durationString = session.output.trim()
            val duration = durationString.toDoubleOrNull()
            if (duration == null) {
                Log.e(TAG, "Falha ao converter a duração '$durationString' para Double.")
            }
            return@withContext duration
        } else {
            Log.e(TAG, "ffprobe falhou ao obter a duração para $filePath. Logs: ${session.allLogsAsString}")
            return@withContext null
        }
    }


    /**
     * Junta uma lista de clipes de vídeo MP4 com transições xfade entre eles.
     * Esta função lê a duração de cada clipe automaticamente.
     *
     * @param context O contexto da aplicação.
     * @param clipPaths A lista de caminhos absolutos para os arquivos MP4 de entrada.
     * @param outputPath O caminho absoluto para o arquivo de vídeo final de saída.
     * @param enableTransitions Se as transições devem ser aplicadas. Se false, usa uma concatenação simples.
     * @param transitionDuration A duração de cada transição em segundos.
     * @param videoFps A taxa de quadros (FPS) para o vídeo de saída.
     * @param logCallback Callback para receber logs de progresso do FFmpeg.
     * @return O caminho do arquivo de saída em caso de sucesso.
     * @throws IOException se a lista de clipes estiver vazia ou se ocorrer um erro no FFmpeg.
     */
    suspend fun stitchClipsWithTransitions(
        context: Context,
        clipPaths: List<String>,
        outputPath: String,
        enableTransitions: Boolean = true,
        transitionDuration: Double = DEFAULT_TRANSITION_DURATION,
        videoFps: Int = 30,
        logCallback: (String) -> Unit,
        subDura: List<Double>
    ): Pair<String, Double> {
        if (clipPaths.isEmpty()) {
            throw IOException("A lista de clipes para juntar não pode estar vazia.")
        }
        
        
        logCallback("Iniciando junção de ${clipPaths.size} clipes com transições.")
        // 1. Obter a duração de todos os clipes de entrada.
        var durations = clipPaths.map { path ->
            getClipDuration(path) ?: throw IOException("Não foi possível obter a duração do clipe: $path")
        }

        // Se houver apenas um clipe, simplesmente copie-o para o destino para economizar processamento.
        if (clipPaths.size == 1) {
            logCallback("Apenas um clipe fornecido. Copiando para o destino...")
            val command = "-y -i \"${clipPaths.first()}\" -c copy \"$outputPath\""
            return Pair(outputPath, durations.sum())
        }
        
        if (subDura.size >0)
            durations = subDura

        

        

        val cmd = StringBuilder("-y -hide_banner ")
        val filterComplex = StringBuilder()
        val tempoDeTransicaoEfetivo = if (enableTransitions) transitionDuration else 0.0

        // 2. Definir todos os arquivos como inputs.
        clipPaths.forEach { path ->
            cmd.append("-i \"$path\" ")
        }

        val videoStreamFinal: String

        if (enableTransitions) {
            // Lógica de transição com XFADE
            logCallback("Construindo filtro xfade...")

            // Normaliza cada stream de entrada
            clipPaths.forEachIndexed { index, _ ->
                filterComplex.append("[$index:v]setpts=PTS-STARTPTS,format=yuv420p[sc$index];\n")
            }

            var currentStream = "[sc0]"
            var accumulatedDuration = durations[0]

            for (i in 0 until clipPaths.size - 1) {
                val nextSceneStream = "[sc${i + 1}]"
                val outputStreamName = if (i == clipPaths.size - 2) "[v_final]" else "[xfade${i}]"
                val xfadeOffset = max(0.0, accumulatedDuration - tempoDeTransicaoEfetivo)
                val transitionType = listOf("fade", "wipeleft", "slideright", "circleopen").random()

                filterComplex.append(
                    "$currentStream$nextSceneStream xfade=transition=$transitionType:duration=$tempoDeTransicaoEfetivo:offset=$xfadeOffset$outputStreamName;\n"
                )
                currentStream = outputStreamName
                accumulatedDuration += durations[i + 1] - tempoDeTransicaoEfetivo
            }
            videoStreamFinal = "[v_final]"

        } else {
            // Lógica de concatenação simples (sem transições)
            logCallback("Construindo filtro de concatenação simples...")
            val concatInputs = clipPaths.indices.joinToString("") { "[$it:v:0]" }
            filterComplex.append("$concatInputs concat=n=${clipPaths.size}:v=1:a=0[v_final];\n")
            videoStreamFinal = "[v_final]"
        }

        // 3. Montar o comando final
        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$videoStreamFinal\" ")
        cmd.append("-an ") // Ignora o áudio dos clipes de entrada
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ")
        cmd.append("-movflags +faststart ")
        cmd.append("\"$outputPath\"")

        return Pair(cmd.toString(), durations.sum())
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
                        cont.resume(Unit)
                    } else {
                        val reason = if (!outFile.exists()) "não foi encontrado" else "está vazio (${outFile.length()} bytes)"
                        val errMsg = "FFmpeg '$taskName' reportou sucesso, mas arquivo de saída $reason."
                        Log.e(TAG, "$errMsg Logs:\n$logs")
                        outFile.delete()
                        cont.resumeWithException(VideoGenerationException(errMsg))
                    }
                } else {
                    val errMsg = "FFmpeg '$taskName' FALHOU com código: $returnCode."
                    Log.e(TAG, "$errMsg Logs:\n$logs")
                    File(outputPath).delete()
                    cont.resumeWithException(VideoGenerationException(errMsg))
                }
            }, { log ->
                logCallback(log.message)
            }, { stat ->
                val statMessage = "$taskName, Concluido=${stat.time}"
                Log.d(TAG, statMessage)
                logCallback(statMessage)
            })

            cont.invokeOnCancellation {
                Log.w(TAG, "🚫 Operação FFmpeg '$taskName' (ID: ${session.sessionId}) foi cancelada!")
                FFmpegKit.cancel(session.sessionId)
                File(outputPath).delete()
            }
        }
    }

    suspend fun _concatenateSubVideos(appContext: Context, videoPreferencesDataStoreManager: VideoPreferencesDataStoreManager, subVideoPaths: List<String>, outputPath: String, logCallback: (String) -> Unit) {
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
        globalOffsetSeconds: Double,
        addBlurBackground: Boolean
    ): String {
        val cmd = StringBuilder("-y -hide_banner ")
        val filterComplex = StringBuilder()
        val tempoDeTransicaoEfetivo = if (enableSceneTransitions && mediaPaths.size > 1) DEFAULT_TRANSITION_DURATION else 0.0
    
        
        // --- SEÇÃO 1: LEITURA DOS INPUTS (CORRIGIDA) ---
        var inputIndex = 0
        mediaPaths.forEachIndexed { index, path ->
            val isVideoInput = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val duracaoDestaCena = duracaoCenas[index]
            val isLastInBatch = (index == mediaPaths.lastIndex)
            
            if (isVideoInput) {
                val duracaoLeituraVideo = duracaoDestaCena - tempoDeTransicaoEfetivo
                cmd.append(String.format(Locale.US, "-t %.4f -i \"%s\" -an ", duracaoLeituraVideo, path))
            } else {
                val duracaoInputImagem = duracaoDestaCena -  tempoDeTransicaoEfetivo
                cmd.append(String.format(Locale.US, "-loop 1 -r %d -t %.4f -i \"%s\" ", videoFps, duracaoInputImagem, path))
            }
            inputIndex++
        }
    
        // --- SEÇÃO 2: PROCESSAMENTO DE CADA MÍDIA ---
        val processedMediaPads = mutableListOf<String>()
        mediaPaths.forEachIndexed { i, path ->
            val outputPad = "[processed_m$i]"
            processedMediaPads.add(outputPad)
    
            val isVideo = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val durBase = duracaoCenas[i]
            val isLast = (i == mediaPaths.lastIndex)
            val trimDuration = durBase - (if (!isLast && enableSceneTransitions) tempoDeTransicaoEfetivo else 0.0)
    
            val frames = (trimDuration * videoFps).toInt().coerceAtLeast(1)
            val durationExata = frames.toDouble() / videoFps
            val w = larguraVideo
            val h = alturaVideo
            val input = "[${i}:v]" 
    
            
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
            
            // Processamento condicional do background
            if (addBlurBackground) {
                // Usar split quando precisar do background blur
                filterComplex.append("  ${input}split=2[main${i}][bg${i}];\n")
                filterComplex.append("  [bg${i}]scale='max(iw,${w}*2)':'max(ih,${h}*2)',crop=${w}:${h},boxblur=40:2,setsar=1[bg_final${i}];\n")
            } else {
                // Usar diretamente o input quando não precisar do background blur
                filterComplex.append("  ${input}copy[main${i}];\n")
            }
            
            
            // Processamento do foreground
            if (isVideo || !enableZoomPan) {
                filterComplex.append("[main${i}]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h[fg_scaled${i}];\n")
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
            
            // Overlay condicional
            if (addBlurBackground) {
                filterComplex.append("  [bg_final${i}][fg_padded${i}]overlay=0:0[composite${i}];\n")
            } else {
                filterComplex.append("  [fg_padded${i}]copy[composite${i}];\n")
            }
            
            val finalEffects = mutableListOf<String>()
            finalEffects.add("format=pix_fmts=rgba")
            finalEffects.add("fps=$videoFps")
            finalEffects.add("trim=duration=$durationExata")
            finalEffects.add("setpts=PTS-STARTPTS")
            filterComplex.append("  [composite${i}]${finalEffects.joinToString(separator = ",")}$outputPad;\n")
        }
    
        // --- SEÇÃO 3: TRANSIÇÕES E CONCATENAÇÃO ---
        val tiposDeTransicao = listOf(
            "fade", "slidedown", "circleopen", "circleclose",
            "rectcrop", "distance", "fadeblack", "fadewhite"
        )
        val random = java.util.Random()
        
        //enableSceneTransitions = true
    
        val videoStreamFinal: String = when {
            enableSceneTransitions && processedMediaPads.size > 1 -> {
                // Transições com xfade
                processedMediaPads.forEachIndexed { index, padName ->
                    filterComplex.append("  $padName setpts=PTS-STARTPTS[sc_trans$index];\n")
                }
                
                val offsets = calcularOffsetsMagicos(duracaoCenas, tempoDeTransicaoEfetivo)
                var currentStream = "[sc_trans0]"
                
                for (i in 0 until processedMediaPads.size - 1) {
                    val nextSceneStream = "[sc_trans${i + 1}]"
                    val xfadeOffset = offsets[i]
                    val tipoTransicao = tiposDeTransicao[random.nextInt(tiposDeTransicao.size)]
                    val xfadeOutputStreamName = if (i == processedMediaPads.size - 2) "[vc_final_batch]" else "[xfade_out_trans$i]"
                
                    filterComplex.append(
                        "  $currentStream$nextSceneStream xfade=transition=$tipoTransicao:duration=$tempoDeTransicaoEfetivo:offset=$xfadeOffset$xfadeOutputStreamName;\n"
                    )
                
                    currentStream = xfadeOutputStreamName
                }
                currentStream
            }
            
            processedMediaPads.isNotEmpty() && processedMediaPads.size > 1 -> {
                // Concatenação simples sem transições
                processedMediaPads.forEachIndexed { index, pad ->
                    filterComplex.append("  $pad setpts=PTS-STARTPTS[s_concat$index];\n")
                }
                val concatInputs = processedMediaPads.indices.joinToString("") { "[s_concat$it]" }
                filterComplex.append("  $concatInputs concat=n=${processedMediaPads.size}:v=1:a=0[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            processedMediaPads.isNotEmpty() -> {
                // Vídeo único
                filterComplex.append("  ${processedMediaPads[0]} copy[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            else -> {
                // Fallback para vídeo preto
                val totalDurationFallback = duracaoCenas.sum().takeIf { it > 0.0 } ?: 0.1
                filterComplex.append("color=c=black:s=${larguraVideo}x${alturaVideo}:d=${max(0.1, totalDurationFallback)}[vc_final_batch];\n")
                "[vc_final_batch]"
            }
        }
    
        // --- SEÇÃO 4: COMANDO FINAL DE OUTPUT (CORRIGIDO) ---
        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$videoStreamFinal\" ")
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ")
        cmd.append("-movflags +faststart ")
        
        // A duração total do lote é a soma das durações das cenas
       // val batchDuration = duracaoCenas.sum()
        //cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, duracaoCenas.sum())))
        cmd.append("\"$outputPath\"")
        
        return cmd.toString()
    }
    
    fun calcularOffsetsMagicos(duracoes: List<Double>, tempoTransicao: Double): List<Double> {
        val offsets = mutableListOf<Double>()
        offsets.add(duracoes[0] - tempoTransicao) // offset da primeira transição
        
        for (i in 1 until duracoes.size - 1) {
            val novoOffset = offsets[i - 1] + duracoes[i]
            offsets.add("%.2f".format(Locale.US, novoOffset).toDouble())
        }
        return offsets
    }

    
    
    private fun buildFFmpegCommandForBatch1(
        mediaPaths: List<String>, duracaoCenas: List<Double>, larguraVideo: Int, alturaVideo: Int,
        enableSceneTransitions: Boolean, enableZoomPan: Boolean, videoFps: Int, videoHdMotion: Boolean, outputPath: String
    ): String {
        val cmd = StringBuilder("-y -hide_banner ")
        val filterComplex = StringBuilder()
        val tempoDeTransicaoEfetivo = if (enableSceneTransitions && mediaPaths.size > 1) DEFAULT_TRANSITION_DURATION else 0.0

        mediaPaths.forEachIndexed { index, path ->
            val isVideoInput = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val duracaoLeitura = duracaoCenas[index] + if (index < mediaPaths.lastIndex) tempoDeTransicaoEfetivo else 0.0
            if (isVideoInput) {
                cmd.append(String.format(Locale.US, "-t %.4f -i \"%s\" -an ", duracaoLeitura, path))
            } else {
                cmd.append(String.format(Locale.US, "-loop 1 -r %d -t %.4f -i \"%s\" ", videoFps, duracaoLeitura, path))
            }
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

            if (isVideo || !enableZoomPan) {
                filterComplex.append("[main${i}]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h[fg_scaled${i}];\n")
            } else {
                val fgChain = mutableListOf<String>()
                val (zoomExpr, xExpr, yExpr) = gerarZoompanExpressao(path, w, h, frames, i)
                fgChain.add("scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,zoompan=z=$zoomExpr:s=${w}x${h}:d=$frames:x=$xExpr:y=$yExpr:fps=$videoFps")
                fgChain.add("crop=$w:$h")
                if (videoHdMotion) {
                    fgChain.add("minterpolate=fps=$videoFps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
                }
                filterComplex.append("  [main${i}]${fgChain.joinToString(",")}[fg_scaled${i}];\n")
            }

            filterComplex.append("  [fg_scaled${i}]pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0.0[fg_padded${i}];\n")
            filterComplex.append("  [bg_final${i}][fg_padded${i}]overlay=0:0[composite${i}];\n")
            filterComplex.append("  [composite${i}]format=pix_fmts=rgba,fps=$videoFps,trim=duration=$durationExata,setpts=PTS-STARTPTS$outputPad;\n")
        }

        val videoStreamFinal: String
        if (enableSceneTransitions && processedMediaPads.size > 1) {
            processedMediaPads.forEachIndexed { index, padName ->
                filterComplex.append("  $padName setpts=PTS-STARTPTS[sc_trans$index];\n")
            }
            var currentStream = "[sc_trans0]"
            var durationOfCurrentStream = duracaoCenas[0] + tempoDeTransicaoEfetivo
            for (i in 0 until processedMediaPads.size - 1) {
                val nextSceneStream = "[sc_trans${i + 1}]"
                val nextSceneOriginalDuration = duracaoCenas[i + 1]
                val xfadeOutputStreamName = if (i == processedMediaPads.size - 2) "[vc_final_batch]" else "[xfade_out_trans$i]"
                val xfadeOffset = max(0.0, durationOfCurrentStream) - tempoDeTransicaoEfetivo
                val tipoTransicao = listOf("fade", "slidedown", "circleopen", "circleclose", "rectcrop", "distance", "fadeblack", "fadewhite").random()
                filterComplex.append("  $currentStream$nextSceneStream xfade=transition=$tipoTransicao:duration=${tempoDeTransicaoEfetivo}:offset=$xfadeOffset$xfadeOutputStreamName;\n")
                currentStream = xfadeOutputStreamName
                durationOfCurrentStream = max(0.1, durationOfCurrentStream + nextSceneOriginalDuration)
            }
            videoStreamFinal = currentStream
        } else if (processedMediaPads.size > 1) {
            processedMediaPads.forEachIndexed { index, pad -> filterComplex.append("  $pad setpts=PTS-STARTPTS[s_concat$index];\n") }
            val concatInputs = processedMediaPads.indices.joinToString("") { "[s_concat$it]" }
            filterComplex.append("  $concatInputs concat=n=${processedMediaPads.size}:v=1:a=0[vc_final_batch];\n")
            videoStreamFinal = "[vc_final_batch]"
        } else {
            filterComplex.append("  ${processedMediaPads.firstOrNull() ?: ""} copy[vc_final_batch];\n")
            videoStreamFinal = "[vc_final_batch]"
        }

        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$videoStreamFinal\" ")
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps -movflags +faststart \"$outputPath\"")

        return cmd.toString()
    }
    
    private fun buildAudioAndSubtitleMixingCommand(
        videoPath: String, audioPath: String, musicaPath: String, legendaPath: String?, fonteArialPath: String,
        usarLegendas: Boolean, outputVideoPath: String, videoFps: Int, totalDuration: Double
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
        if (usarLegendas && !legendaPath.isNullOrBlank()) {
            val escapedLegendaPath = legendaPath.replace("'", "'\\''").replace("\\", "/").replace(":", "\\\\:")
            val fonteDir = File(fonteArialPath).parent?.replace("\\", "/")?.replace(":", "\\\\:") ?: "."
            filterComplex.append("  $finalVideoStreamPad subtitles=filename='$escapedLegendaPath':fontsdir='$fonteDir':force_style='Alignment=2'[v_out_with_subs];\n")
            finalVideoStreamPad = "[v_out_with_subs]"
        } else {
            filterComplex.append("  $finalVideoStreamPad copy[v_out_copy];\n")
            finalVideoStreamPad = "[v_out_copy]"
        }
        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$finalVideoStreamPad\" -map \"[a_out_mixed]\" ")
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ")
        cmd.append("-movflags +faststart ")
        cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, totalDuration)))
        cmd.append("\"$outputVideoPath\"")
        return cmd.toString()
    }

    private fun obterDimensoesImagem(path: String): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0 && options.outHeight > 0) Pair(options.outWidth, options.outHeight) else null
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
        return Triple(zoomExpr, xExpr, yExpr)
    }

    private fun gerarZoompanExpressao2(
        imgCaminho: String, larguraVideo: Int, alturaVideo: Int, frames: Int, cenaIdx: Int
    ): Triple<String, String, String> {
        val (larguraImg, alturaImg) = obterDimensoesImagem(imgCaminho) ?: (larguraVideo to alturaVideo)
        val escalaX = larguraVideo.toDouble() / larguraImg
        val escalaY = alturaVideo.toDouble() / alturaImg
        val zoomAjuste = min(escalaX, escalaY)
        val larguraAjuste = larguraImg * zoomAjuste
        val alturaAjuste = alturaImg * zoomAjuste
        val escalaX1 = larguraVideo / larguraAjuste
        val escalaY1 = alturaVideo / alturaAjuste
        val zoomFixo = max(escalaX1, escalaY1)
        val padX = ((larguraAjuste * zoomFixo) - larguraAjuste) / 2
        val padY = ((alturaAjuste * zoomFixo) - alturaAjuste) / 2
        var xExpr = "'$padX'"
        var yExpr = "'$padY'"
        if (escalaY1 == zoomFixo) xExpr = "'($padX-(($padX/$frames)*on))'"
        else if (escalaX1 == zoomFixo) yExpr = "'($padY-(($padY/$frames)*on))'"
        val zoomExpr = "'$zoomFixo + ((on * ($frames - 1 - on) / (($frames - 1) / 2))/1000)'"
        return Triple(zoomExpr, xExpr, yExpr)
    }

    private fun copiarFonteParaCache(context: Context, nomeFonte: String): String {
        val outFile = File(context.cacheDir, nomeFonte)
        if (!outFile.exists() || outFile.length() == 0L) {
            try {
                context.cacheDir.mkdirs()
                context.assets.open("fonts/$nomeFonte").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                throw RuntimeException("Falha ao copiar fonte: ${e.message}", e)
            }
        }
        return outFile.absolutePath
    }

    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File? {
        val baseAppDir = context.getExternalFilesDir(null) ?: context.filesDir
        val projectPath = File(baseAppDir, projectDirName.takeIf { it.isNotBlank() } ?: "DefaultProject")
        val finalDir = File(projectPath, subDir)
        if (!finalDir.exists() && !finalDir.mkdirs()) {
            return null
        }
        return finalDir
    }

    private fun createOutputFilePath(context: Context, prefix: String, projectDirName: String): String {
        val outputDir = getProjectSpecificDirectory(context, projectDirName, "edited_videos")
            ?: File(context.cacheDir, "edited_videos_fallback").apply { mkdirs() }
        return File(outputDir, "${prefix}_${System.currentTimeMillis()}.mp4").absolutePath
    }

    class VideoGenerationException(message: String) : Exception(message)
    private data class Octuple<A, B, C, D, E, F, G, H>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F, val seventh: G, val eighth: H)


    // <<< INÍCIO DA NOVA FUNÇÃO >>>
    /**
     * Renderiza um clipe de vídeo para uma única cena, aplicando a animação de zoom/pan.
     * Esta função é otimizada para gerar prévias rápidas.
     *
     * @param context Contexto da aplicação.
     * @param scene A cena a ser renderizada.
     * @param audioSnippetPath O caminho para o trecho de áudio já recortado desta cena.
     * @param outputPreviewPath O caminho completo onde o arquivo de vídeo da prévia será salvo.
     * @param videoPreferences As preferências de vídeo (dimensões, FPS, etc.).
     * @param logCallback Callback para logs do FFmpeg.
     * @return Boolean indicando sucesso ou falha.
     */
    suspend fun gerarPreviaDeCenaUnica(
        context: Context,
        scene: SceneLinkData,
        audioSnippetPath: String,
        outputPreviewPath: String,
        videoPreferences: VideoPreferencesDataStoreManager,
        logCallback: (String) -> Unit
    ): Boolean = coroutineScope {
        Log.i(TAG, "Queimando previa da cena $outputPreviewPath")
        Log.i(TAG, "audioSnippetPath $audioSnippetPath")
        val imagePath = scene.imagemGeradaPath
        if (imagePath.isNullOrBlank()) {
            Log.e(TAG, "Cena ${scene.id} não tem imagem gerada para criar prévia.")
            return@coroutineScope false
        }

        val larguraVideo = videoPreferences.videoLargura.first() ?: DEFAULT_VIDEO_WIDTH
        val alturaVideo = videoPreferences.videoAltura.first() ?: DEFAULT_VIDEO_HEIGHT
        val videoFps = videoPreferences.videoFps.first()
        val enableZoomPan = videoPreferences.enableZoomPan.first()
        val videoHdMotion = videoPreferences.videoHdMotion.first()
        val duracao = (scene.tempoFim ?: 0.0) - (scene.tempoInicio ?: 0.0)
        if (duracao <= 0) return@coroutineScope false
        
        var mediaPaths = mutableListOf<String>()
        var duracaoCenas = mutableListOf<Double>()
        mediaPaths.add(imagePath)
        duracaoCenas.add(duracao)
        
        Log.i(TAG, "Queimando previa da cena $outputPreviewPath")
        Log.i(TAG, "audioSnippetPath $audioSnippetPath")
        Log.i(TAG, "imagePath $imagePath")
        Log.i(TAG, "duracao $duracao")
        
        var addBlurBackground = true
        
        var enableSceneTransitions = false
        var tempoDeTransicaoEfetivo = 0.0
        
        val filterComplex = StringBuilder()
        val cmd = StringBuilder("-y -hide_banner ")
        
        val outputPath = outputPreviewPath
     

         // --- SEÇÃO 1: LEITURA DOS INPUTS (CORRIGIDA) ---
        var inputIndex = 0
        mediaPaths.forEachIndexed { index, path ->
            val isVideoInput = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
            val duracaoDestaCena = duracaoCenas[index]
            val isLastInBatch = (index == mediaPaths.lastIndex)
            
            if (isVideoInput) {
                val duracaoLeituraVideo = duracaoDestaCena + tempoDeTransicaoEfetivo
                cmd.append(String.format(Locale.US, "-t %.4f -i \"%s\" ", duracaoLeituraVideo, path))
            } else {
                val duracaoInputImagem = duracaoDestaCena + tempoDeTransicaoEfetivo
                cmd.append(String.format(Locale.US, "-loop 1 -r %d -t %.4f -i \"%s\" ", videoFps, duracaoInputImagem, path))
            }
            inputIndex++
        }
        
        // ADICIONAR INPUT DE ÁUDIO
        cmd.append(String.format(Locale.US, "-i \"%s\" ", audioSnippetPath))
        
           
        
        
    
        // --- SEÇÃO 2: PROCESSAMENTO DE CADA MÍDIA ---
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
            
            // Processamento condicional do background
            if (addBlurBackground) {
                // Usar split quando precisar do background blur
                filterComplex.append("  ${input}split=2[main${i}][bg${i}];\n")
                filterComplex.append("  [bg${i}]scale='max(iw,${w}*2)':'max(ih,${h}*2)',crop=${w}:${h},boxblur=40:2,setsar=1[bg_final${i}];\n")
            } else {
                // Usar diretamente o input quando não precisar do background blur
                filterComplex.append("  ${input}copy[main${i}];\n")
            }
            
            
            // <<< INÍCIO DA CORREÇÃO >>>
            // Processamento do foreground
            if (isVideo || !enableZoomPan) {
                // Para vídeos OU para imagens sem zoom-pan, redimensiona para caber dentro, mantendo a proporção.
                filterComplex.append("[main${i}]scale=$w:$h:force_original_aspect_ratio=decrease[fg_scaled${i}];\n")
            } else {
                // Esta lógica agora é apenas para imagens COM zoom-pan habilitado.
                val fgChain = mutableListOf<String>()
                val (zoomExpr, xExpr, yExpr) = gerarZoompanExpressao(path, squareDix, squareDiy, frames, i)
                fgChain.add("scale=${squareDix}:${squareDiy}:force_original_aspect_ratio=decrease," +
                            "pad=${squareDix}:${squareDiy}:(ow-iw)/2:(oh-ih)/2:color=black," +
                            "setsar=1," +
                            "zoompan=z=$zoomExpr:s=${ss}:d=$frames:x=$xExpr:y=$yExpr:fps=$videoFps")
                fgChain.add("crop=$squareDix:$squareDiy")
                if (videoHdMotion) {
                    fgChain.add("minterpolate=fps=$videoFps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
                }
                filterComplex.append("  [main${i}]${fgChain.joinToString(",")}[fg_scaled${i}];\n")
            }
            // <<< FIM DA CORREÇÃO >>>
            
            
            filterComplex.append("  [fg_scaled${i}]pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0.0[fg_padded${i}];\n")
            
            // Overlay condicional
            if (addBlurBackground) {
                filterComplex.append("  [bg_final${i}][fg_padded${i}]overlay=0:0[composite${i}];\n")
            } else {
                filterComplex.append("  [fg_padded${i}]copy[composite${i}];\n")
            }
            
            val finalEffects = mutableListOf<String>()
            finalEffects.add("format=pix_fmts=rgba")
            finalEffects.add("fps=$videoFps")
            finalEffects.add("trim=duration=$durationExata")
            finalEffects.add("setpts=PTS-STARTPTS")
            filterComplex.append("  [composite${i}]${finalEffects.joinToString(separator = ",")}$outputPad;\n")
        }
    
        // --- SEÇÃO 3: TRANSIÇÕES E CONCATENAÇÃO ---
        val tiposDeTransicao = listOf(
            "fade", "slidedown", "circleopen", "circleclose",
            "rectcrop", "distance", "fadeblack", "fadewhite"
        )
        val random = java.util.Random()
    
        val videoStreamFinal: String = when {
            enableSceneTransitions && processedMediaPads.size > 1 -> {
                // Transições com xfade
                processedMediaPads.forEachIndexed { index, padName ->
                    filterComplex.append("  $padName setpts=PTS-STARTPTS[sc_trans$index];\n")
                }
                var currentStream = "[sc_trans0]"
                var durationOfCurrentStream = duracaoCenas[0] + tempoDeTransicaoEfetivo
                for (i in 0 until processedMediaPads.size - 1) {
                    val nextSceneStream = "[sc_trans${i + 1}]"
                    val nextSceneOriginalDuration = duracaoCenas[i+1] 
                    val xfadeOutputStreamName = if (i == processedMediaPads.size - 2) "[vc_final_batch]" else "[xfade_out_trans$i]"
                    val xfadeOffset = max(0.0, durationOfCurrentStream) - tempoDeTransicaoEfetivo
    
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
                // Concatenação simples sem transições
                processedMediaPads.forEachIndexed { index, pad ->
                    filterComplex.append("  $pad setpts=PTS-STARTPTS[s_concat$index];\n")
                }
                val concatInputs = processedMediaPads.indices.joinToString("") { "[s_concat$it]" }
                filterComplex.append("  $concatInputs concat=n=${processedMediaPads.size}:v=1:a=0[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            processedMediaPads.isNotEmpty() -> {
                // Vídeo único
                filterComplex.append("  ${processedMediaPads[0]} copy[vc_final_batch];\n")
                "[vc_final_batch]"
            }
            else -> {
                // Fallback para vídeo preto
                val totalDurationFallback = duracaoCenas.sum().takeIf { it > 0.0 } ?: 0.1
                filterComplex.append("color=c=black:s=${larguraVideo}x${alturaVideo}:d=${max(0.1, totalDurationFallback)}[vc_final_batch];\n")
                "[vc_final_batch]"
            }
        }
    
        // --- SEÇÃO 4: COMANDO FINAL DE OUTPUT (CORRIGIDO) ---
        cmd.append("-filter_complex \"${filterComplex}\" ")
        cmd.append("-map \"$videoStreamFinal\" ")
        cmd.append("-map ${inputIndex}:a ") // Mapear o áudio do último input adicionado
        cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $videoFps ")
        cmd.append("-movflags +faststart ")
        
        // A duração total do lote é a soma das durações das cenas
        val batchDuration = duracaoCenas.sum()
        cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, batchDuration + tempoDeTransicaoEfetivo)))
        cmd.append("\"$outputPath\"")

        try {
            _executeFFmpegCommand(cmd.toString(), outputPreviewPath, "Prévia Cena ${scene.cena}", logCallback)
            return@coroutineScope true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gerar prévia para cena ${scene.id}", e)
            return@coroutineScope false
        }
    }
    
}