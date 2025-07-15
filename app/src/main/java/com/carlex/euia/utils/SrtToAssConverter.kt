// File: euia/utils/SrtToAssConverter.kt
package com.carlex.euia.utils

import android.graphics.Color // Importar Color para usar constantes de cor
import android.util.Log
import java.lang.StringBuilder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Converte o conteúdo de um arquivo SubRip (.srt) para um formato Advanced SubStation Alpha (.ass) simples.
 * O arquivo ASS gerado terá um estilo padrão que posiciona a legenda na parte inferior central da tela,
 * com fonte Arial branca e contorno preto, e um tamanho de fonte que se adapta a uma resolução base.
 *
 * O objetivo é fornecer uma base para customizações mais avançadas de legendas com FFmpeg.
 *
 * @param primaryColor Hexadecimal ASS de 8 dígitos para a cor primária do texto (ex: "&H00FFFFFF" para branco opaco).
 * @param outlineColor Hexadecimal ASS de 8 dígitos para a cor do contorno (ex: "&H00000000" para preto opaco).
 * @param shadowColor Hexadecimal ASS de 8 dígitos para a cor da sombra (ex: "&H80000000" para preto semi-transparente).
 * @param enableFadeEffect Se TRUE, adiciona um efeito de fade-in/out à legenda.
 * @param enableZoomInEffect Se TRUE, adiciona um efeito de zoom-in (escala) no texto.
 * @param enableTiltEffect Se TRUE, adiciona um efeito de inclinação (rotação Z) ao texto.
 * @param outlineSizePixels Tamanho do contorno (borda) do texto em pixels.
 * @param shadowOffsetPixels Deslocamento horizontal/vertical da sombra do texto em pixels.
 * @param fontSizeBaseMultiplier Multiplicador para o tamanho base da fonte, em relação à altura do vídeo.
 * @param textBlockWidthPercentage A porcentagem da largura do vídeo que o bloco de texto deve ocupar (ex: 80 para 80%).
 * @param fixedCenterYRatio O ponto vertical fixo (0.0 a 1.0) onde o centro da legenda deve estar, em relação à altura do vídeo total.
 * @param fixedCenterYRatioInImage O ponto vertical fixo (0.0 a 1.0) onde o centro da legenda deve estar, em relação à altura da imagem visível (quando há letterboxing).
 * @param tiltAngleDegrees O ângulo máximo de inclinação em graus para o efeito de tilt.
 * @param tiltAnimationDurationMs A duração da animação de inclinação em milissegundos.
 * @param zoomInAnimationDurationMs A duração da animação de zoom-in em milissegundos.
 * @param zoomOutAnimationDurationMs A duração da animação de zoom-out em milissegundos.
 * @param initialZoomScalePercentage A escala inicial do texto em porcentagem para o efeito de zoom-in.
 * @param fadeDurationMs A duração do fade-in e fade-out em milissegundos.
 * @param minFontSizeRatio Proporção mínima da altura do vídeo para o tamanho da fonte. (NOVO)
 * @param maxFontSizeRatio Proporção máxima da altura do vídeo para o tamanho da fonte. (NOVO)
 * @param idealCharsForScaling O número de caracteres que é considerado "ideal" para uma linha de texto antes que o tamanho da fonte comece a ser reduzido. (NOVO)
 */
class SrtToAssConverter(
    // Parâmetros de Cor
    private val primaryColor: String = "&H00FFFFFF", // Branco
    private val outlineColor: String = "&H00000000", // Preto
    private val shadowColor: String = "&H80000000",  // Preto 50% transparente

    // Parâmetros de Efeito - Flags
    private val enableFadeEffect: Boolean = true,
    private val enableZoomInEffect: Boolean = true,
    private val enableTiltEffect: Boolean = true,

    // Parâmetros de Estilo e Posição - Tamanhos/Valores
    private val outlineSizePixels: Int = 2,
    private val shadowOffsetPixels: Int = 4,
    private val fontSizeBaseMultiplier: Float = 0.12f, // Base para o cálculo dinâmico da fonte
    private val textBlockWidthPercentage: Int = 90, // Largura que o bloco de texto deve ocupar

    // Parâmetros de Alinhamento Vertical
    private val fixedCenterYRatio: Float = 0.85f, // Ponto fixo para o centro da legenda (85% da altura do vídeo)
    private val fixedCenterYRatioInImage: Float = 0.90f, // Ponto fixo para o centro da legenda dentro da imagem (90% da altura da imagem visível)

    // Parâmetros de Animação
    private val tiltAngleDegrees: Int = 5,
    private val tiltAnimationDurationMs: Int = 150,
    private val zoomInAnimationDurationMs: Int = 200,
    private val zoomOutAnimationDurationMs: Int = 200,
    private val initialZoomScalePercentage: Int = 80,
    private val fadeDurationMs: Int = 100,

    // <<<<< NOVOS PARÂMETROS PARA CONTROLE DE FONTE DINÂMICA >>>>>
    private val minFontSizeRatio: Float = 0.12f, // 3% da altura do vídeo
    private val maxFontSizeRatio: Float = 0.20f, // 8% da altura do vídeo
    private val idealCharsForScaling: Int = 14, // Número ideal de caracteres para o tamanho base
    private val marginInferior: Double = 0.30
) {

    val TAG = "SrtToAssConverter"

    /**
     * Converte o conteúdo de um arquivo SRT para o formato ASS.
     *
     * @param srtContent O conteúdo completo do arquivo SRT como uma String.
     * @param videoWidth A largura (em pixels) do vídeo final. Usado para definir PlayResX no ASS.
     * @param videoHeight A altura (em pixels) do vídeo final. Usado para definir PlayResY no ASS.
     * @return Uma String contendo o conteúdo do arquivo ASS.
     */
    fun convertSrtToAss(
        srtContent: String,
        videoWidth: Int,
        videoHeight: Int
    ): String {
        Log.d(TAG, "Iniciando conversão de SRT para ASS (LxA: ${videoWidth}x${videoHeight}) com configurações personalizadas.")

        val assBuilder = StringBuilder()

        // --- [Script Info] Section ---
        assBuilder.append("[Script Info]\n")
        assBuilder.append("; Script generated by SrtToAssConverter\n")
        assBuilder.append("Script Type: v4.00+\n")
        assBuilder.append("WrapStyle: 0\n") // 0 = smart wrap, top line wider
        assBuilder.append("PlayResX: $videoWidth\n") // Define a resolução base para o estilo
        assBuilder.append("PlayResY: $videoHeight\n")
        assBuilder.append("ScaledBorderAndShadow: yes\n") // Bordas e sombras escalam com a resolução
        assBuilder.append("Video Aspect Ratio: 0\n") // 0 = Default (não especificado)
        assBuilder.append("\n")

        // --- [V4+ Styles] Section ---
        val horizontalMargin = (videoWidth * (100 - textBlockWidthPercentage) / 200).toInt()
        val defaultVerticalMarginForStyle = (videoHeight * marginInferior).toInt() 

        assBuilder.append("[V4+ Styles]\n")
        assBuilder.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding, AlphaLevels, Drawing\n")
        assBuilder.append(String.format(
            Locale.US,
            "Style: Default,Arial,%d,%s,&H000000FF,%s,%s,0,0,0,0,100,100,0,0,1,%d,%d,2,%d,%d,%d,1\n",
            (videoHeight * fontSizeBaseMultiplier).toInt(), // FontSize para o estilo padrão (será sobrescrito)
            primaryColor,
            outlineColor,
            shadowColor,
            outlineSizePixels,
            shadowOffsetPixels,
            horizontalMargin,
            horizontalMargin,
            defaultVerticalMarginForStyle
        ))
        assBuilder.append("\n")

        // --- [Events] Section ---
        assBuilder.append("[Events]\n")
        assBuilder.append("Format: Layer, Start, End, Style, Actor, MarginL, MarginR, MarginV, Effect, Text\n")

        val srtBlocks = srtContent.split("\n\n").filter { it.isNotBlank() }
        var isEvenBlock = false

        for (block in srtBlocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.size < 2) {
                Log.w(TAG, "Bloco SRT inválido encontrado: '$block'. Pulando.")
                continue
            }

            val timingLine = lines[1]
            val times = timingLine.split(" --> ")
            if (times.size != 2) {
                Log.w(TAG, "Linha de timing SRT inválida: '$timingLine'. Pulando bloco.")
                continue
            }

            val srtStartTime = times[0].trim()
            val srtEndTime = times[1].trim()
            val assStartTime = convertSrtTimeFormatToAss(srtStartTime)
            val assEndTime = convertSrtTimeFormatToAss(srtEndTime)

            val textLines = lines.drop(2)
            val assText = textLines.joinToString("\\N") { it.trim() }

            // <<<<< CÁLCULO DE FONTE E MARGEM POR BLOCO (usa NOVAS propriedades do construtor) >>>>>
            var currentBlockMaxCharsPerLine = 0
            for (lineOfText in textLines) {
                currentBlockMaxCharsPerLine = max(currentBlockMaxCharsPerLine, lineOfText.trim().length)
            }

            val baseFontSizeForBlock = (videoHeight * fontSizeBaseMultiplier).toInt()
            // Usando os novos parâmetros do construtor
            val minCalculatedFontSize = (videoHeight * minFontSizeRatio).toInt().coerceAtLeast(10)
            val maxCalculatedFontSize = (videoHeight * maxFontSizeRatio).toInt().coerceAtLeast(30)

            var dynamicFontSizeForBlock = baseFontSizeForBlock
            
            if (currentBlockMaxCharsPerLine > idealCharsForScaling) { // Usando idealCharsForScaling
                val reductionFactor = currentBlockMaxCharsPerLine.toFloat() / idealCharsForScaling.toFloat()
                dynamicFontSizeForBlock = (baseFontSizeForBlock / reductionFactor).toInt()
            } else if (currentBlockMaxCharsPerLine < idealCharsForScaling / 2 && currentBlockMaxCharsPerLine > 0) {
                val increaseFactor = idealCharsForScaling.toFloat() / (currentBlockMaxCharsPerLine.toFloat().coerceAtLeast(1f))
                dynamicFontSizeForBlock = (baseFontSizeForBlock * (1 + (increaseFactor - 1) * 0.2f)).toInt()
            }
            dynamicFontSizeForBlock = dynamicFontSizeForBlock.coerceIn(minCalculatedFontSize, maxCalculatedFontSize)

            // NOVO CÁLCULO DE MARGEM VERTICAL DINÂMICA
            val estimatedLegendHeight = dynamicFontSizeForBlock // Uma linha simples
            val centerOffset = estimatedLegendHeight / 2 // Offset do centro da base da legenda
            
            var dynamicVerticalMargin = (videoHeight * (1f - fixedCenterYRatio)).toInt()
            dynamicVerticalMargin -= centerOffset
            
            if (videoHeight > (videoWidth * 1.2)) {
                val actualImageHeightInVideo = (videoWidth * 1.2).toInt()
                val verticalOffsetOfImage = (videoHeight - actualImageHeightInVideo) / 2
                
                val targetCenterYInImage = (actualImageHeightInVideo * fixedCenterYRatioInImage).toInt()
                val targetCenterYInVideo = verticalOffsetOfImage + targetCenterYInImage
                
                dynamicVerticalMargin = (videoHeight - targetCenterYInVideo - (estimatedLegendHeight / 2f)).toInt()
                
                dynamicVerticalMargin = dynamicVerticalMargin.coerceAtLeast( (videoHeight * marginInferior).toInt() )
            }
            dynamicVerticalMargin = max(0, dynamicVerticalMargin)
            // <<<<< FIM DO CÁLCULO DE FONTE E MARGEM POR BLOCO >>>>>

            val effectTags = StringBuilder()

            effectTags.append("{\\fs$dynamicFontSizeForBlock}")

            if (enableZoomInEffect) {
                effectTags.append("{\\t(0,${zoomInAnimationDurationMs},\\fscx$initialZoomScalePercentage\\fscy$initialZoomScalePercentage)}")
                
                val durationMs = (parseSrtTimeToMs(srtEndTime) - parseSrtTimeToMs(srtStartTime)).coerceAtLeast(0)
                if (durationMs > zoomInAnimationDurationMs + zoomOutAnimationDurationMs) {
                    effectTags.append("{\\t(${durationMs - zoomOutAnimationDurationMs},${durationMs},\\fscx$initialZoomScalePercentage\\fscy$initialZoomScalePercentage)}")
                }
            }

            if (enableTiltEffect) {
                val currentTiltAngle = if (isEvenBlock) tiltAngleDegrees else -tiltAngleDegrees
                
                effectTags.append("{\\t(0,${tiltAnimationDurationMs},\\frz${currentTiltAngle})}")
                effectTags.append("{\\t(${tiltAnimationDurationMs},${tiltAnimationDurationMs*2},\\frz0)}")

                isEvenBlock = !isEvenBlock
            }

            if (enableFadeEffect) {
                effectTags.append("{\\fad(${fadeDurationMs},${fadeDurationMs})}")
            }
            
            val finalTextWithEffects = effectTags.toString() + assText

            assBuilder.append(String.format(
                Locale.US,
                "Dialogue: 0,%s,%s,Default,,0,0,%d,,%s\n",
                assStartTime,
                assEndTime,
                dynamicVerticalMargin,
                finalTextWithEffects
            ))
        }

        Log.d(TAG, "Conversão de SRT para ASS concluída. Conteúdo gerado:\n${assBuilder.toString().take(500)}...")
        return assBuilder.toString()
    }

    private fun convertSrtTimeFormatToAss(srtTime: String): String {
        val parts = srtTime.split(":", ",")
        if (parts.size != 4) {
            Log.e(TAG, "Formato de tempo SRT inesperado: '$srtTime'. Retornando 0:00:00.00")
            return "0:00:00.00"
        }

        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val milliseconds = parts[3].toInt()

        val centiseconds = milliseconds / 10

        return String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds)
    }

    private fun parseSrtTimeToMs(srtTime: String): Int {
        val parts = srtTime.split(":", ",")
        if (parts.size != 4) return 0
        
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val milliseconds = parts[3].toInt()
        
        return (hours * 3600 * 1000) + (minutes * 60 * 1000) + (seconds * 1000) + milliseconds
    }
}