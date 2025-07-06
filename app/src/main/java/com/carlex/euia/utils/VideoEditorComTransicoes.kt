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
    val tempoTransicaoPadrao = 0.5

    private const val DEFAULT_VIDEO_WIDTH = 720
    private const val DEFAULT_VIDEO_HEIGHT = 1280

    // Função auxiliar para criar imagem preta (COMO DEFINIDO ACIMA)
    private fun createTemporaryBlackImage(context: Context, width: Int, height: Int, projectDirName: String): String? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val tempDir = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
        

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
        context: Context,
        scenes: List<SceneLinkData>,
        audioPath: String,
        musicaPath: String,
        legendaPath: String, // Este é o caminho do arquivo SRT gerado
        logCallback: (String) -> Unit
    ): String {
        Log.d(TAG, "🎬 Iniciando gerarVideo com ${scenes.size} cenas SceneLinkData")
        require(scenes.isNotEmpty()) { "A lista de cenas não pode estar vazia" }
        // <<<< INÍCIO DA MODIFICAÇÃO >>>>
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
            Log.d(TAG, "Preferências lidas: Dir=$dirName, LxA=${largura ?: "N/D"}x${altura ?: "N/D"}, Legendas=$subtitles, Transições=$transitions, ZoomPan=$zoomPan, FPS=$fps, HDMotion=$hdMotion")
            Octuple(dirName, largura, altura, subtitles, transitions, zoomPan, fps, hdMotion)
        }

        Log.d(TAG, "Diretório do projeto para salvar vídeo: '$projectDirName'")
        val larguraVideoParaProcessamento = larguraVideoPref ?: DEFAULT_VIDEO_WIDTH
        val alturaVideoParaProcessamento = alturaVideoPref ?: DEFAULT_VIDEO_HEIGHT
        
        
        Log.d(TAG, "Dimensões do vídeo (LxA): ${larguraVideoParaProcessamento}x${alturaVideoParaProcessamento}")
        Log.d(TAG, "Habilitar Legendas (preferência): $enableSubtitlesPref")
        Log.d(TAG, "Habilitar Transições (preferência): $enableSceneTransitionsPref")
        Log.d(TAG, "Habilitar ZoomPan (preferência): $enableZoomPanPref")
        Log.d(TAG, "FPS do Vídeo (preferência): $videoFpsPref")
        Log.d(TAG, "Habilitar HD Motion (preferência): $videoHdMotionPref")
        // <<<< FIM DA MODIFICAÇÃO >>>>

        val outputPath = createOutputFilePath(context, "video_final_editado", projectDirName)

        val sceneMediaInputs = mutableListOf<Pair<SceneLinkData, String>>()
        for (scene in scenes) {
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

        // Transformar para listas imutáveis para o restante do processamento,
        // mas declará-las como 'var' para que possam ser reatribuídas após adicionar a cena preta.
        var finalValidScenesOriginal = sceneMediaInputs.map { it.first }
        var finalMediaPathsOriginal = sceneMediaInputs.map { it.second }
        var duracaoCenasOriginal = finalValidScenesOriginal.map { it.tempoFim!! - it.tempoInicio!! }

        // --- INÍCIO DA LÓGICA PARA ADICIONAR CENA PRETA ---
        var blackImagePathTemporary: String? = null // Para limpar depois
        if (finalMediaPathsOriginal.isNotEmpty()) {
            val lastMediaPath = finalMediaPathsOriginal.last()
            val isLastSceneVideo = File(lastMediaPath).name.endsWith(".mp4", ignoreCase = true)

            if (isLastSceneVideo) {
                logCallback("Última cena é um vídeo. Adicionando cena preta de 1s no final.")
                Log.i(TAG, "Última cena real é um vídeo. Tentando adicionar cena preta de preenchimento.")

                blackImagePathTemporary = createTemporaryBlackImage(
                    context,
                    larguraVideoParaProcessamento,
                    alturaVideoParaProcessamento,
                    projectDirName
                )

                if (blackImagePathTemporary != null) {
                    val lastActualSceneEndTime = finalValidScenesOriginal.lastOrNull()?.tempoFim ?: duracaoCenasOriginal.sum()

                    val blackPaddingScene = SceneLinkData(
                        id = UUID.randomUUID().toString(),
                        cena = "BLACK_PADDING_END",
                        tempoInicio = lastActualSceneEndTime, // Informativo
                        tempoFim = lastActualSceneEndTime + tempoTransicaoPadrao, // Informativo
                        imagemReferenciaPath = blackImagePathTemporary, // Para consistência, mas não é uma "referência"
                        descricaoReferencia = "Preenchimento preto final",
                        promptGeracao = null,
                        imagemGeradaPath = blackImagePathTemporary, // Caminho para a imagem preta
                        similaridade = null,
                        aprovado = true, // Aprovada por padrão
                        exibirProduto = false,
                        isGenerating = false,
                        isChangingClothes = false,
                        generationAttempt = 0,
                        clothesChangeAttempt = 0
                    )
                    // Atualiza as listas para incluir a cena preta
                    finalValidScenesOriginal = finalValidScenesOriginal + blackPaddingScene
                    finalMediaPathsOriginal = finalMediaPathsOriginal + blackImagePathTemporary
                    duracaoCenasOriginal = duracaoCenasOriginal + tempoTransicaoPadrao // Adiciona 1 segundo de duração
                    Log.i(TAG, "Cena preta adicionada. Novo total de mídias: ${finalMediaPathsOriginal.size}")
                } else {
                    logCallback("Falha ao criar imagem preta temporária. Vídeo será gerado sem preenchimento extra.")
                    Log.w(TAG, "Não foi possível criar a imagem preta temporária.")
                }
            }
        }
        // --- FIM DA LÓGICA PARA ADICIONAR CENA PRETA ---

        // <<< INÍCIO DA NOVA LÓGICA DE CONVERSÃO SRT para ASS >>>
        var finalLegendaFilePath: String = "" // Caminho para o arquivo ASS temporário
        var tempAssFile: File? = null // Variável para rastrear o arquivo ASS temporário para limpeza

        if (enableSubtitlesPref && legendaPath.isNotBlank()) {
            val srtFile = File(legendaPath)
            if (srtFile.exists()) {
                try {
                    val srtContent = srtFile.readText()
                    
                    // <<< CHAMADA DO CONVERSOR COM TODOS OS NOVOS PARÂMETROS >>>
                    val assConverter = SrtToAssConverter(
                        // Cores
                        primaryColor = "&H00FFFFFF", // Branco
                        outlineColor = "&H00000000", // Preto
                        shadowColor = "&H80000000",  // Preto 50% transparente

                        // Flags de Efeito
                        enableFadeEffect = true,
                        enableZoomInEffect = true,
                        enableTiltEffect = true,
                        
                        // Tamanhos/Valores de Estilo e Posição
                        outlineSizePixels = 2,
                        shadowOffsetPixels = 4,
                        fontSizeBaseMultiplier = 0.14f, // Base para o cálculo dinâmico da fonte
                        textBlockWidthPercentage = 80, // Largura que o bloco de texto deve ocupar

                        // Parâmetros de Alinhamento Vertical
                        fixedCenterYRatio = 0.85f, // Ponto fixo para o centro da legenda (85% da altura do vídeo)
                        fixedCenterYRatioInImage = 0.90f, // Ponto fixo para o centro da legenda dentro da imagem (90% da altura da imagem visível)

                        // Parâmetros de Animação
                        tiltAngleDegrees = 5,
                        tiltAnimationDurationMs = 150,
                        zoomInAnimationDurationMs = 200,
                        zoomOutAnimationDurationMs = 200,
                        initialZoomScalePercentage = 80,
                        fadeDurationMs = 100,

                        // NOVOS PARÂMETROS PARA CONTROLE DE FONTE DINÂMICA
                        minFontSizeRatio = 0.14f, 
                        maxFontSizeRatio = 0.25f, 
                        idealCharsForScaling = 12,
                        marginInferior = 0.15
                    )
                    
                    val assContent = assConverter.convertSrtToAss(
                        srtContent = srtContent,
                        videoWidth = larguraVideoParaProcessamento,
                        videoHeight = alturaVideoParaProcessamento
                    )
                    // <<< FIM DA CHAMADA >>>

                    // Salvar o conteúdo ASS em um novo arquivo temporário .ass
                    val tempAssDir = getProjectSpecificDirectory(context, projectDirName, "temp_ffmpeg_assets")
                    if (tempAssDir == null) {
                        Log.e(TAG, "Falha ao criar diretório temporário para ASS. Gerando vídeo sem legendas.")
                        logCallback("❌ Erro interno: não foi possível criar diretório para legendas ASS. Gerando sem legendas.")
                        finalLegendaFilePath = ""
                    } else {
                        val uniqueAssFileName = "temp_legenda_${UUID.randomUUID()}.ass"
                        tempAssFile = File(tempAssDir, uniqueAssFileName)
                        tempAssFile.writeText(assContent)
                        finalLegendaFilePath = tempAssFile.absolutePath
                        Log.i(TAG, "Legenda SRT convertida para ASS e salva temporariamente em: $finalLegendaFilePath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao converter SRT para ASS ou salvar arquivo temporário: ${e.message}", e)
                    logCallback("❌ Erro ao preparar legendas. Gerando vídeo sem legendas. Detalhes: ${e.message}")
                    finalLegendaFilePath = "" // Garante que nenhuma legenda seja usada se a conversão falhar
                }
            } else {
                Log.w(TAG, "Arquivo de legenda SRT não encontrado em: $legendaPath. Não serão usadas legendas.")
                logCallback("⚠️ Arquivo de legenda SRT não encontrado. Gerando vídeo sem legendas.")
                finalLegendaFilePath = ""
            }
        } else {
            Log.d(TAG, "Legendas desabilitadas ou caminho da legenda vazio. Não serão usadas legendas.")
            finalLegendaFilePath = ""
        }
        // <<< FIM DA NOVA LÓGICA DE CONVERSÃO SRT para ASS >>>

        Log.d(TAG, "🖼️ Mídias FINAIS para FFmpeg (${finalMediaPathsOriginal.size}): ${finalMediaPathsOriginal.joinToString { File(it).name }}")
        Log.d(TAG, "⏱️ Durações Cenas FINAIS (s) para FFmpeg: $duracaoCenasOriginal")
        Log.d(TAG, "🔊 Áudio principal: $audioPath")
        Log.d(TAG, "🎵 Música: $musicaPath")
        Log.d(TAG, "📝 Legenda original: $legendaPath, Legenda ajustada para uso: $finalLegendaFilePath (Usar legendas no vídeo: $enableSubtitlesPref)")

        finalMediaPathsOriginal.forEach {
            if (!File(it).exists()) throw IllegalArgumentException("Arquivo de mídia não encontrado: $it")
        }
        if (!File(audioPath).exists()) throw IllegalArgumentException("Áudio principal não encontrado: $audioPath")
        if (musicaPath.isNotBlank() && !File(musicaPath).exists()) throw IllegalArgumentException("Música de fundo não encontrada: $musicaPath")

        // Agora, a verificação de existência do arquivo de legenda deve ser feita para o `finalLegendaFilePath`
        if (enableSubtitlesPref && finalLegendaFilePath.isNotBlank() && !File(finalLegendaFilePath).exists()) {
            throw IllegalArgumentException("Arquivo de legenda ajustada '$finalLegendaFilePath' não encontrado, mas legendas estão habilitadas.")
        }

        val fonteArialPath = try {
            copiarFonteParaCache(context, "Arial.ttf")
        } catch (e: Exception) {
            Log.e(TAG, "Falha crítica ao preparar fonte: ${e.message}")
            throw RuntimeException("Não foi possível preparar a fonte necessária para legendas.", e)
        }

        val startTime = System.currentTimeMillis()

        val comandoFFmpeg = buildFFmpeg(
            mediaPaths = finalMediaPathsOriginal, // Usa a lista potencialmente modificada
            duracaoCenas = duracaoCenasOriginal,   // Usa a lista potencialmente modificada
            audioPath = audioPath,
            musicaPath = musicaPath,
            legendaPath = finalLegendaFilePath, // Passa o caminho do arquivo ASS temporário
            outputPath = outputPath,
            fonteArialPath = fonteArialPath,
            usarLegendas = enableSubtitlesPref,
            usarTransicoes = enableSceneTransitionsPref,
            usarZoomPan = enableZoomPanPref,
            larguraVideoPreferida = larguraVideoPref, // Passa a preferência original
            alturaVideoPreferida = alturaVideoPref,     // Passa a preferência original
            fps = videoFpsPref, // <<<< NOVO
            hdMotion = videoHdMotionPref // <<<< NOVO
        )
        Log.d(TAG, "🛠️ Comando FFmpeg:\n$comandoFFmpeg")

        return suspendCancellableCoroutine { cont ->
            Log.i(TAG, "🚀 Iniciando execução do FFmpeg...")
            val session = FFmpegKit.executeAsync(comandoFFmpeg, { completedSession ->
                // Limpa a imagem preta temporária SE ELA FOI CRIADA
                blackImagePathTemporary?.let {
                    val tempFile = File(it)
                    if (tempFile.exists()) {
                        if (tempFile.delete()) {
                            Log.i(TAG, "Imagem preta temporária ($it) excluída.")
                        } else {
                            Log.w(TAG, "Falha ao deletar imagem preta temporária ($it).")
                        }
                    }
                }

                // <<< INÍCIO DA LIMPEZA DO ARQUIVO ASS TEMPORÁRIO >>>
                tempAssFile?.let { file ->
                    if (file.exists()) {
                        if (file.delete()) {
                            Log.i(TAG, "Arquivo ASS temporário deletado: ${file.name}")
                        } else {
                            Log.w(TAG, "Falha ao deletar arquivo ASS temporário: ${file.name}")
                        }
                    }
                }
                // <<< FIM DA LIMPEZA DO ARQUIVO ASS TEMPORÁRIO >>>

                val returnCode = completedSession.returnCode
                val logs = completedSession.allLogsAsString
                val timeElapsed = (System.currentTimeMillis() - startTime) / 1000.0

                if (ReturnCode.isSuccess(returnCode)) {
                    //logCallback("✅ FFmpeg executado com SUCESSO em ${"%.2f".format(Locale.US, timeElapsed)}s.")
                    val outFile = File(outputPath)
                    if (outFile.exists() && outFile.length() > 100) {
                        //logCallback("Arquivo de saída verificado: $outputPath (Tamanho: ${outFile.length()} bytes)")
                        cont.resume(outputPath)
                    } else {
                        val reason = if (!outFile.exists()) "não foi encontrado" else "está vazio ou muito pequeno (${outFile.length()} bytes)"
                        logCallback("❌ FFmpeg reportou sucesso, mas o arquivo de saída '$outputPath' $reason.")
                        Log.e(TAG, "--- Logs Completos (Sucesso Aparente, Arquivo Inválido) --- \n$logs\n --- Fim dos Logs ---")
                        cont.resumeWithException(VideoGenerationException("FFmpeg sucesso aparente, mas arquivo final inválido ($reason). Logs:\n$logs"))
                    }
                } else {
                    logCallback("❌ FFmpeg FALHOU com código de retorno: $returnCode (Tempo: ${"%.2f".format(Locale.US, timeElapsed)}s)")
                    Log.e(TAG, "--- Logs Completos da Falha --- \n$logs\n --- Fim dos Logs ---")
                    File(outputPath).delete() // Tenta deletar o arquivo de saída parcial em caso de falha
                    cont.resumeWithException(VideoGenerationException("Falha na execução do FFmpeg (Código: $returnCode). Logs:\n$logs"))
                }
            }, { log ->
                 logCallback(log.message)
                 Log.v(TAG, "FFmpegLog: ${log.message}")
               },
               { stat ->
                 val statMessage = "📊 FFmpeg Stats: Tempo=${stat.time}ms, Tamanho=${stat.size}, Taxa=${"%.2f".format(Locale.US, stat.bitrate)}, Vel=${"%.2f".format(Locale.US, stat.speed)}x"
                 Log.d(TAG, statMessage)
               })

            cont.invokeOnCancellation {
                Log.w(TAG,"🚫 Operação FFmpeg cancelada!")
                logCallback("🚫 Operação FFmpeg cancelada!")
                FFmpegKit.cancel(session.sessionId)
                // Limpa a imagem preta temporária também em caso de cancelamento
                blackImagePathTemporary?.let {
                    val tempFile = File(it)
                    if (tempFile.exists()) tempFile.delete()
                }
                // <<< INÍCIO DA LIMPEZA DO ARQUIVO ASS TEMPORÁRIO EM CASO DE CANCELAMENTO >>>
                tempAssFile?.let { file ->
                    if (file.exists()) file.delete()
                }
                // <<< FIM DA LIMPEZA DO ARQUIVO ASS TEMPORÁRIO EM CASO DE CANCELAMENTO >>>
            }
        }
    }
    
    fun Double.format(digits: Int): String = String.format("%.${digits}f", this)


    
   

private fun buildFFmpeg(
    mediaPaths: List<String>,
    duracaoCenas: List<Double>,
    audioPath: String,
    musicaPath: String,
    legendaPath: String, // Este agora será o caminho para o arquivo ASS
    outputPath: String,
    fonteArialPath: String,
    usarLegendas: Boolean,
    usarTransicoes: Boolean,
    usarZoomPan: Boolean,
    larguraVideoPreferida: Int?,
    alturaVideoPreferida: Int?,
    fps: Int,
    hdMotion: Boolean
): String {
    val cmd = StringBuilder("-y -hide_banner ")
    val filterComplex = StringBuilder()
    val larguraFinalVideo = (larguraVideoPreferida ?: DEFAULT_VIDEO_WIDTH).coerceAtLeast(100)
    val alturaFinalVideo = (alturaVideoPreferida ?: DEFAULT_VIDEO_HEIGHT).coerceAtLeast(100)
    Log.i(TAG, "FFmpeg VIDEO ${larguraFinalVideo}x${alturaFinalVideo} | FPS: $fps | HD Motion: $hdMotion | Transições: $usarTransicoes | ZoomPan: $usarZoomPan | Legendas: $usarLegendas")
    val tempoDeTransicaoEfetivo = if (usarTransicoes && mediaPaths.size > 1) tempoTransicaoPadrao else 0.0
    
    // Apenas a fonte para as legendas, não precisa do `legendaPath` aqui.
    val fonteDir = File(fonteArialPath).parent?.replace("\\", "/")?.replace(":", "\\\\:") ?: "."
    val fonteNome = File(fonteArialPath).nameWithoutExtension
    
    var inputIndex = 0
    if (musicaPath.isNotBlank()) {
        cmd.append("-i \"$musicaPath\" ")
        inputIndex++
    }
    val mediaInputStartIndex = inputIndex
    var ii=0
    mediaPaths.forEachIndexed { index, path ->
        val isVideoInput = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
        val duracaoDestaCena = duracaoCenas[index]
        val isLast = (index == mediaPaths.lastIndex)
        val inputDurationParaComando =
            if (isVideoInput) duracaoDestaCena
            else duracaoDestaCena + if (!isLast) tempoDeTransicaoEfetivo else 0.0
        if (isVideoInput) {
            Log.d(TAG, "Cena MidiaAdd $ii | duracaoDestaCena: %.2f".format(duracaoDestaCena))
            cmd.append(String.format(Locale.US, "-stream_loop -1 -t %.4f -i \"%s\" -an ", duracaoDestaCena, path))
        } else {
            Log.d(TAG, "Cena MidiaAdd $ii | inputDurationParaComando: %.4f".format(inputDurationParaComando))
            cmd.append(String.format(Locale.US, "-loop 1 -r %d -t %.4f -i \"%s\" ", fps, inputDurationParaComando, path))
        }
        ii++
        inputIndex++
    }
    val audioInputIndex = inputIndex
    cmd.append("-i \"$audioPath\" ")
    
    
    
    // Filtros de vídeo
    filterComplex.append("\n")
    val processedMediaPads = mutableListOf<String>()
    mediaPaths.forEachIndexed { i, path ->
        val outputPad = "[processed_m$i]"
        processedMediaPads.add(outputPad)

        val isVideo = path.endsWith(".mp4", true) || path.endsWith(".webm", true)
        val durBase = duracaoCenas[i]
        val isLast = (i == mediaPaths.lastIndex)
        val trim = durBase + (if (!isLast && usarTransicoes) tempoDeTransicaoEfetivo else 0.0)
        
        val frames = (trim * fps).toInt().coerceAtLeast(1)
        val durationExata = frames.toDouble() / fps
        val w = larguraFinalVideo
        val h = alturaFinalVideo
        val input = "[${i + mediaInputStartIndex}:v]"

        // --- INÍCIO DA NOVA LÓGICA DE COMPOSIÇÃO DE FRAME ---
        
        // 1. Divide o stream de entrada para processar fundo e frente separadamente
        filterComplex.append("  ${input}split=2[main${i}][bg${i}];\n")

        // 2. Cria o fundo desfocado e ampliado
        filterComplex.append("  [bg${i}]scale='max(iw,${w}*2)':'max(ih,${h}*2)',crop=${w}:${h},boxblur=40:2,setsar=1[bg_final${i}];\n")

        // 3. Processa a imagem/vídeo da frente
        
        var squareDix = w
        var squareDiy = h
        var ss = "${w}x${h}"
        
        if (w>h){
           squareDix = (squareDiy * 1.2).toInt()
            ss = "${squareDix}x${squareDiy}"
        } else if (w<h){
            squareDiy = (squareDix * 1.2).toInt()
            ss = "${squareDix}y${squareDiy}"
        }
        
        if (isVideo) {
            // Para vídeos, aplica letterbox/pillarbox para caber no quadro quadrado
            filterComplex.append("[main${i}]scale=$squareDix:$squareDiy:force_original_aspect_ratio=decrease[fg_scaled${i}];\n")
        } else {
            // Para imagens, aplica o zoompan primeiro (se habilitado), depois corta para o quadrado
            val fgChain = mutableListOf<String>()
            if (usarZoomPan) {
                val (zoomExpr, xExpr, yExpr) = gerarZoompanExpressao(path, squareDix, squareDiy, frames, i)
                fgChain.add("scale=${squareDix}:${squareDiy}:force_original_aspect_ratio=decrease,"
                          + "pad=${squareDix}:${squareDiy}:(ow-iw)/2:(oh-ih)/2:color=black," 
                          + "setsar=1,"
                          + "zoompan=z=$zoomExpr:s=${ss}:d=$frames:x=$xExpr:y=$yExpr:fps=$fps")
            }
            fgChain.add("crop=$squareDix:$squareDiy")
             if (hdMotion) {
                fgChain.add("minterpolate=fps=$fps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
            }
            filterComplex.append("  [main${i}]${fgChain.joinToString(",")}[fg_scaled${i}];\n")
        }

        // 4. Cria o quadro composto (frente + fundo)
        // Primeiro, o pad da frente para ter o tamanho final do vídeo com fundo transparente
        filterComplex.append("  [fg_scaled${i}]pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0.0[fg_padded${i}];\n")
        // Depois, sobrepõe a frente com pad sobre o fundo
        filterComplex.append("  [bg_final${i}][fg_padded${i}]overlay=0:0[composite${i}];\n")

        
        // 5. Adiciona os filtros finais de duração e timestamp
        val finalEffects = mutableListOf<String>()
        finalEffects.add("format=pix_fmts=rgba") 
        finalEffects.add("fps=$fps")            
        finalEffects.add("trim=duration=$durationExata")
        finalEffects.add("setpts=PTS-STARTPTS")

        filterComplex.append("  [composite${i}]${finalEffects.joinToString(separator = ",")}$outputPad;\n")
    }



    val tiposDeTransicao = listOf(
        "fade", 
        //"slideleft", "slideright", 
        "slidedown",
        "circleopen", "circleclose", 
        "rectcrop", "distance", 
        "fadeblack", "fadewhite", 
        //"dissolve", 
        //"wipeleft", "wiperight"
    )


    val random = java.util.Random()

val videoStreamFinal: String = when {
    usarTransicoes && processedMediaPads.size > 1 -> {
        processedMediaPads.forEachIndexed { index, padName ->
            filterComplex.append("  $padName setpts=PTS-STARTPTS[sc_trans$index];\n")
        }
        var currentStream = "[sc_trans0]"
        var durationOfCurrentStream = duracaoCenas[0]
        for (i in 0 until processedMediaPads.size - 1) {
            val nextSceneStream = "[sc_trans${i + 1}]"
            val nextSceneOriginalDuration = duracaoCenas[i+1] - tempoDeTransicaoEfetivo
            val xfadeOutputStreamName = if (i == processedMediaPads.size - 2) "[vc_final_effect]" else "[xfade_out_trans$i]"
            val xfadeOffset = max(0.0, durationOfCurrentStream)

            // Escolhe aleatoriamente um tipo de transição para esta mudança de cena
            val tipoTransicao = tiposDeTransicao[random.nextInt(tiposDeTransicao.size)]

            filterComplex.append(
                "  $currentStream$nextSceneStream xfade=transition=$tipoTransicao:duration=$tempoDeTransicaoEfetivo:offset=$xfadeOffset$xfadeOutputStreamName;\n"
            )
            currentStream = xfadeOutputStreamName
            durationOfCurrentStream = durationOfCurrentStream + nextSceneOriginalDuration + tempoDeTransicaoEfetivo
            durationOfCurrentStream = max(0.1, durationOfCurrentStream)
            Log.i(TAG, "Cena $i Transicao| tipo=$tipoTransicao | durationOfCurrentStream: %.2f".format(durationOfCurrentStream))
        }
        currentStream
    }
    processedMediaPads.isNotEmpty() && processedMediaPads.size > 1 -> {
        processedMediaPads.forEachIndexed { index, pad ->
            filterComplex.append("  $pad setpts=PTS-STARTPTS[s_concat$index];\n")
        }
        val concatInputs = processedMediaPads.indices.joinToString("") { "[s_concat$it]" }
        filterComplex.append("  $concatInputs concat=n=${processedMediaPads.size}:v=1:a=0[vc_final_effect];\n")
        "[vc_final_effect]"
    }
    processedMediaPads.isNotEmpty() -> {
        filterComplex.append("  ${processedMediaPads[0]} copy[vc_final_effect];\n")
        "[vc_final_effect]"
    }
    else -> {
        val totalDurationFallback = duracaoCenas.sum().takeIf { it > 0.0 } ?: 1.0
        filterComplex.append("color=c=black:s=${larguraFinalVideo}x${alturaFinalVideo}:d=${max(0.1, totalDurationFallback)}[vc_final_effect];\n")
        "[vc_final_effect]"
    }
}


    // Legendas (usando o caminho do arquivo ASS, se definido)
    val videoComLegendasPad: String =
        if (usarLegendas && legendaPath.isNotBlank()) {
            val escapedLegendaPath = legendaPath.replace("'", "'\\''") // Escapa aspas simples
                .replace("\\", "/") // Normaliza barras (Windows para Unix-like)
                .replace(":", "\\\\:") // Escapa dois pontos (para paths em Windows)

            filterComplex.append("  $videoStreamFinal subtitles=filename='$escapedLegendaPath'")
            filterComplex.append(":fontsdir='$fonteDir'")
            // Ao usar ASS, o estilo é definido NO ARQUIVO ASS. O force_style aqui deve ser mínimo para não sobrescrever o ASS.
            // Apenas definimos Alignment e Margins se necessário, para garantir consistência ou corrigir falhas no ASS.
            // Para efeitos avançados, é crucial que o estilo venha do ASS, não do FFmpeg `force_style`.
            // O filtro `subtitles` lê o estilo do .ass, então podemos remover a maioria dos parâmetros daqui.
            // Podemos manter Alignment e Margins se quisermos um fallback ou ajuste final universal.
            // Remover `FontName` daqui, pois ele já está no ASS.
            filterComplex.append(":force_style='Alignment=2'") // Ajustado para ser menos intrusivo
            filterComplex.append("[v_out];\n")
            "[v_out]"
        } else {
            filterComplex.append("  $videoStreamFinal copy[v_out];\n")
            "[v_out]"
        }

    // Áudio
    val audioPrincipalInputString = "[$audioInputIndex:a]"
    filterComplex.append("  $audioPrincipalInputString volume=1.0[voice];\n")

    if (musicaPath.isNotBlank()) {
        val musicaInputString = "[0:a]"
        filterComplex.append("  $musicaInputString volume=0.18,adelay=500|500[bgm];\n")
        filterComplex.append("  [voice][bgm]amix=inputs=2:duration=first:dropout_transition=3[a_out];\n")
    } else {
        filterComplex.append("  [voice]acopy[a_out];\n")
    }

    // Finalização do filter_complex
    cmd.append("-filter_complex \"${filterComplex}\" ")
    cmd.append("-map \"$videoComLegendasPad\" -map \"[a_out]\" ")
    // NÃO use -r 30 aqui! O FPS já está travado lá em cima, para não gerar drop/double frames!
    //cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p ")
    cmd.append("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -r $fps ")
    cmd.append("-movflags +faststart ")
    val duracaoTotalVideoCalculada =
        duracaoCenas.sum() + (if (usarTransicoes && mediaPaths.size > 1) (mediaPaths.size - 1) * tempoDeTransicaoEfetivo else 0.0)
    cmd.append(String.format(Locale.US, "-t %.4f ", max(0.1, duracaoTotalVideoCalculada)))
    cmd.append("\"$outputPath\"")
    return cmd.toString()
}

// ... (imports e outras funções) ...


// Utilitário para pegar dimensões da imagem
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
    val (larguraImg, alturaImg) = obterDimensoesImagem(imgCaminho) ?: (1 to 1) // Evita zero!
    
    val escalaX =  larguraVideo.toDouble() / larguraImg.toDouble()
    val escalaY =  alturaVideo.toDouble() / alturaImg.toDouble() 
    val zoomAjuste = minOf(escalaX, escalaY)
    val larguraAjuste = larguraImg.toDouble() * zoomAjuste
    val alturaAjuste = alturaImg.toDouble() * zoomAjuste
    val escalaX1 = larguraVideo.toDouble() / larguraAjuste.toDouble() 
    val escalaY1 = alturaVideo.toDouble() / alturaAjuste.toDouble()
    val zoomFixo = maxOf(escalaX1, escalaY1)
    val zoom = zoomFixo
    var padX = (((larguraAjuste*zoomFixo)-larguraAjuste)/2)
    var padY = (((alturaAjuste*zoomFixo)-alturaAjuste)/2)

    var xExpr = "'$padX'"
    var yExpr = "'$padY'"
    
    if (larguraAjuste <= larguraVideo && alturaAjuste < alturaVideo){
        var t = padX 
        xExpr = "'($t-(($t/$frames)*on))'"
    }
    if (larguraAjuste < larguraVideo && alturaAjuste <= alturaVideo){
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
            Log.d(TAG,"Copiando fonte '$nomeFonte' para o cache: ${outFile.absolutePath}")
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
                Log.i(TAG,"Fonte '$nomeFonte' copiada com sucesso para ${outFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao copiar fonte '$nomeFonte' do asset '$assetPath' para '${outFile.absolutePath}': ${e.message}", e)
                throw RuntimeException("Falha ao copiar fonte necessária '$nomeFonte': ${e.message}", e)
            }
        } else {
            Log.d(TAG,"Fonte '$nomeFonte' já existe no cache: ${outFile.absolutePath}")
        }
        return outFile.absolutePath
    }

    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File? { // Retorna nullable agora
        val baseAppDir: File?
        if (projectDirName.isNotBlank()) {
            baseAppDir = context.getExternalFilesDir(null)
            if (baseAppDir != null) {
                val projectPath = File(baseAppDir, projectDirName)
                val finalDir = File(projectPath, subDir)
                if (!finalDir.exists() && !finalDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório do projeto (externo): ${finalDir.absolutePath}")
                    return null // Retorna null em caso de falha na criação
                }
                return finalDir
            } else {
                Log.w(TAG, "Armazenamento externo para vídeos não disponível. Usando fallback para armazenamento interno para o projeto '$projectDirName'.")
                val internalProjectPath = File(context.filesDir, projectDirName)
                val finalInternalDir = File(internalProjectPath, subDir)
                 if (!finalInternalDir.exists() && !finalInternalDir.mkdirs()) {
                     Log.e(TAG, "Falha ao criar diretório interno do projeto (fallback A): ${finalInternalDir.absolutePath}")
                     return null // Retorna null em caso de falha na criação
                 }
                return finalInternalDir
            }
        }
        val defaultParentDirName = "video_editor_default"
        Log.w(TAG, "Nome do diretório do projeto para vídeos está em branco. Usando diretório de fallback interno: '$defaultParentDirName/$subDir'")
        val fallbackDir = File(File(context.filesDir, defaultParentDirName), subDir)
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            Log.e(TAG, "Falha ao criar diretório de fallback interno: ${fallbackDir.absolutePath}")
            return null // Retorna null em caso de falha na criação
        }
        return fallbackDir
    }


    private fun createOutputFilePath(context: Context, prefix: String, projectDirName: String): String {
        val subDiretorioVideos = "edited_videos"
        val outputDir = getProjectSpecificDirectory(context, projectDirName, subDiretorioVideos)
            ?: File(context.cacheDir, "edited_videos_fallback") // Fallback para cacheDir se a criação falhar
        if (!outputDir.exists()) outputDir.mkdirs() // Tenta criar novamente se o fallback não existir
        
        val timestamp = System.currentTimeMillis()
        val filename = "${prefix}_${timestamp}.mp4"
        val outputFile = File(outputDir, filename)
        return outputFile.absolutePath.also {
            Log.d(TAG, "📄 Caminho do arquivo de saída definido para vídeo: $it")
        }
    }

    class VideoGenerationException(message: String) : Exception(message)    
    // Definindo Octuple para acomodar os novos parâmetros
    private data class Octuple<A, B, C, D, E, F, G, H>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F, val seventh: G, val eighth: H)
}