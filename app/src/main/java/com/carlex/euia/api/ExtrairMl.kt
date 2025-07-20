// File: euia/api/ExtrairMl.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.math.max
import kotlin.math.min

class ExtrairMl {

    private val TAG = "ExtrairMl"
    private val MIN_IMAGE_DIMENSION = 350
    private val DEFAULT_RECORTE_WIDTH = 720
    private val DEFAULT_RECORTE_HEIGHT = 1280

    suspend fun extrairDados(originalUrl: String, context: Context): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
            val projectDirName = videoPreferencesManager.videoProjectDir.first()
            val larguraPreferidaRecorte = videoPreferencesManager.videoLargura.first()
            val alturaPreferidaRecorte = videoPreferencesManager.videoAltura.first()

            val cleanUrl = originalUrl.substringBefore("#")
            Log.i(TAG, "--- INÍCIO DA EXTRAÇÃO HÍBRIDA ---")
            Log.d(TAG, "URL Limpa: $cleanUrl")

            try {
                Log.d(TAG, "ETAPA 1: Baixando página principal...")
                val connection = URL(cleanUrl).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36")
                connection.connect()
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                
                val debugDir = getProjectSpecificDirectory(context, projectDirName, "debug_html")
                File(debugDir, "0_pagina_principal.html").writeText(html)

                Log.i(TAG, "Tentativa 1: Extrair dados via JSON __PRELOADED_STATE__.")
                val jsonRegex = Regex("""<script id="__PRELOADED_STATE__"[^>]*>(.*?)</script>""")
                val jsonMatch = jsonRegex.find(html)

                if (jsonMatch != null) {
                    Log.d(TAG, "Bloco __PRELOADED_STATE__ encontrado! Processando via JSON.")
                    val preloadedStateJson = JSONObject(jsonMatch.groupValues[1])
                    return@withContext processarViaJson(preloadedStateJson, context, projectDirName, larguraPreferidaRecorte, alturaPreferidaRecorte)
                }
                
                Log.w(TAG, "Bloco __PRELOADED_STATE__ não encontrado. Usando método de fallback com Regex no HTML.")
                return@withContext processarViaRegex(html, cleanUrl, context, projectDirName, larguraPreferidaRecorte, alturaPreferidaRecorte)

            } catch (e: Exception) {
                Log.e(TAG, "--- ERRO CRÍTICO NA EXTRAÇÃO ---", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun processarViaJson(preloadedState: JSONObject, context: Context, projectDirName: String, largura: Int?, altura: Int?): Result<Map<String, Any>> {
        val dadosProduto = mutableMapOf<String, Any>()
        val midiasParaProcessar = mutableSetOf<String>()

        try {
            val initialState = preloadedState.getJSONObject("pageState").getJSONObject("initialState")
            val components = initialState.getJSONObject("components")
            
            dadosProduto["titulo"] = components.getJSONObject("header").getString("title").trim()
            dadosProduto["descricao"] = components.getJSONObject("description").getString("content").trim()

            val gallery = components.getJSONObject("fixed").getJSONObject("gallery")
            val pictureConfig = gallery.getJSONObject("picture_config")
            val pictures = gallery.getJSONArray("pictures")

            for (i in 0 until pictures.length()) {
                val picture = pictures.getJSONObject(i)
                val imageUrl = pictureConfig.getString("template_2x").replace("{id}", picture.getString("id")).replace("{sanitizedTitle}", "")
                midiasParaProcessar.add(imageUrl)
            }
            Log.i(TAG, "[JSON] Encontradas ${pictures.length()} imagens na galeria.")

            val videos = gallery.optJSONArray("videos")
            videos?.let {
                for (i in 0 until it.length()) {
                    val manifestUrl = it.getJSONObject(i).getJSONObject("source").getString("url")
                    midiasParaProcessar.add(manifestUrl)
                    Log.i(TAG, "[JSON] Encontrado manifesto de vídeo: $manifestUrl")
                }
            }
            
            val assetsSalvos = processarMidias(midiasParaProcessar, context, projectDirName, largura, altura)
            dadosProduto["imagensSalvas"] = assetsSalvos
            
            return Result.success(dadosProduto)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar via JSON.", e)
            return Result.failure(e)
        }
    }

    private suspend fun processarViaRegex(html: String, url: String, context: Context, projectDirName: String, largura: Int?, altura: Int?): Result<Map<String, Any>> {
        val dadosProduto = mutableMapOf<String, Any>()
        val paginasParaProcessar = mutableSetOf(url)
        val midiasParaProcessar = mutableSetOf<String>()
        
        try {
            dadosProduto["titulo"] = Regex("""<h1.*?ui-pdp-title.*?>(.*?)</h1>""").find(html)?.groupValues?.get(1)?.trim() ?: ""
            dadosProduto["descricao"] = Regex("""<p.*?ui-pdp-description__content.*?>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("<.*?>"), "") ?: ""

            val linkRegex = Regex("""<a\s+href="([^"]+)"[^>]*>""")
            linkRegex.findAll(html).forEach { match ->
                var urlVariacao = match.groupValues[1].replace("&", "&")
                if (urlVariacao.contains("_JM?attributes=")) {
                    if (urlVariacao.startsWith("/MLB-")) urlVariacao = "https://produto.mercadolivre.com.br$urlVariacao"
                    paginasParaProcessar.add(urlVariacao)
                }
            }
            
            val imageUrlRegex = Regex("""(https://http2\.mlstatic\.com/D_NQ_NP_.*?-[A-Z]\.(?:jpg|webp))""")
            val videoManifestRegex = Regex("""<video[^>]*>.*?<source[^>]*src="([^"]*storage/shorts-api[^"]+)"""", RegexOption.DOT_MATCHES_ALL)

            for (paginaUrl in paginasParaProcessar) {
                if (!currentCoroutineContext().isActive) break
                val htmlPagina = if (paginaUrl == url) html else {
                    val conn = URL(paginaUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }

                imageUrlRegex.findAll(htmlPagina).forEach { midiasParaProcessar.add(it.value) }
                videoManifestRegex.findAll(htmlPagina).forEach { midiasParaProcessar.add(it.groupValues[1]) }
            }
            
            Log.i(TAG, "[REGEX] Mídias encontradas para processar: ${midiasParaProcessar.size}")
            val assetsSalvos = processarMidias(midiasParaProcessar, context, projectDirName, largura, altura)
            dadosProduto["imagensSalvas"] = assetsSalvos
            
            return Result.success(dadosProduto)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar via REGEX.", e)
            return Result.failure(e)
        }
    }
    
    // <<< FUNÇÃO CORRIGIDA >>>
    private suspend fun processarMidias(urls: Set<String>, context: Context, projectDirName: String, largura: Int?, altura: Int?): List<String> {
        val assetsSalvos = mutableListOf<String>()
        var assetCounter = 0
        for (mediaUrl in urls) {
            // A verificação de coroutine ativa deve ser feita aqui
            if (!currentCoroutineContext().isActive) break
            
            val pathSalvo = if (mediaUrl.contains("storage/shorts-api")) {
                processarVideo(mediaUrl, context, projectDirName, assetCounter)
            } else {
                processarImagem(mediaUrl, context, projectDirName, assetCounter, largura, altura)
            }
            if (pathSalvo != null) {
                assetsSalvos.add(pathSalvo) // Agora o tipo é garantido como String
                assetCounter++
            }
        }
        return assetsSalvos
    }
    
    // As funções abaixo agora são membros da classe e podem chamar umas às outras.
    
    private suspend fun processarImagem(imageUrl: String, context: Context, projectDirName: String, counter: Int, largura: Int?, altura: Int?): String? {
        Log.d(TAG, "  [Processando Imagem] URL: $imageUrl")
        try {
            val imageConnection = URL(imageUrl).openConnection() as HttpURLConnection
            imageConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            imageConnection.connect()
            val byteArray = imageConnection.inputStream.use { it.readBytes() }
            imageConnection.disconnect()

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
            if (min(options.outHeight, options.outWidth) < MIN_IMAGE_DIMENSION) return null

            val baseName = "imgML_${System.currentTimeMillis()}_$counter"
            val originalPath = salvarImagemOriginal(byteArray, baseName, imageUrl, context, projectDirName)

            val bitmapOriginal = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            if (bitmapOriginal != null) {
                val recorteBitmap = criarRecorteBitmap(bitmapOriginal, largura ?: DEFAULT_RECORTE_WIDTH, altura ?: DEFAULT_RECORTE_HEIGHT)
                if (recorteBitmap != null) {
                    salvarRecorte(recorteBitmap, baseName, context, projectDirName)
                    recorteBitmap.recycle()
                }
                bitmapOriginal.recycle()
            }
            return originalPath
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun processarVideo(manifestoUrl: String, context: Context, projectDirName: String, counter: Int): String? {
        Log.i(TAG, "  [Processando Vídeo] URL do Manifesto: $manifestoUrl")
        try {
            val connection = URL(manifestoUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            connection.connect()
            if (connection.responseCode != 200) return null
            val manifestoContent = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val playlistUrl = Regex("""(https://clips\.mlstatic\.com/[^"\s]+)""").find(manifestoContent)?.groupValues?.get(1)
            if (playlistUrl.isNullOrBlank()) return null

            val videoFile = downloadAndConvertHls(playlistUrl, context, projectDirName, "videoML_$counter") ?: return null
            val thumbnailPath = extrairThumbnail(videoFile.absolutePath, context, projectDirName, "thumb_from_${videoFile.nameWithoutExtension}")
            if (thumbnailPath == null) {
                videoFile.delete()
                return null
            }
            return thumbnailPath
        } catch (e: Exception) {
            return null
        }
    }
    
    private suspend fun downloadAndConvertHls(playlistUrl: String, context: Context, projectDirName: String, baseName: String): File? = withContext(Dispatchers.IO) {
        val videoDir = getProjectSpecificDirectory(context, projectDirName, "ref_videos")
        val outputFile = File(videoDir, "$baseName.mp4")
        val command = "-y -i \"$playlistUrl\" -c copy -bsf:a aac_adtstoasc \"${outputFile.absolutePath}\""
        val session = FFmpegKit.execute(command)
        if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 1024) {
            return@withContext outputFile
        } else {
            outputFile.delete()
            return@withContext null
        }
    }

    private suspend fun extrairThumbnail(videoPath: String, context: Context, projectDirName: String, thumbName: String): String? = withContext(Dispatchers.IO) {
        val thumbDir = getProjectSpecificDirectory(context, projectDirName, "ref_images")
        val thumbFile = File(thumbDir, "$thumbName.webp")
        val command = "-i \"$videoPath\" -vframes 1 -q:v 2 \"${thumbFile.absolutePath}\""
        val session = FFmpegKit.execute(command)
        return@withContext if (ReturnCode.isSuccess(session.returnCode) && thumbFile.exists()) {
            thumbFile.absolutePath
        } else {
            null
        }
    }

    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File {
        val baseAppDir = context.getExternalFilesDir(null) ?: File(context.filesDir, "fallback_dir").apply { mkdirs() }
        val projectPath = File(baseAppDir, projectDirName)
        val finalDir = File(projectPath, subDir)
        if (!finalDir.exists()) finalDir.mkdirs()
        return finalDir
    }

    private fun salvarImagemOriginal(byteArray: ByteArray, baseName: String, imageUrl: String, context: Context, projectDirName: String): String {
        val extension = imageUrl.substringAfterLast(".", "jpg").substringBefore("?").lowercase(Locale.ROOT)
        val nomeArquivo = "${baseName}_original.$extension"
        val fileDir = getProjectSpecificDirectory(context, projectDirName, "imagens_ml_originais")
        val file = File(fileDir, nomeArquivo)
        return try { FileOutputStream(file).use { it.write(byteArray) }; file.absolutePath } catch (e: IOException) { "" }
    }

    fun criarRecorteBitmap(bitmap: Bitmap, larguraFinalDesejada: Int, alturaFinalDesejada: Int): Bitmap? {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        if (originalWidth <= 0 || originalHeight <= 0) return null

        val newBitmap = Bitmap.createBitmap(larguraFinalDesejada, alturaFinalDesejada, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        
        val scale = max(larguraFinalDesejada.toFloat() / originalWidth.toFloat(), alturaFinalDesejada.toFloat() / originalHeight.toFloat())
        val newScaledWidth = scale * originalWidth
        val newScaledHeight = scale * originalHeight
        val xOffset = (larguraFinalDesejada - newScaledWidth) / 2f
        val yOffset = (alturaFinalDesejada - newScaledHeight) / 2f

        return try {
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(xOffset, yOffset)
            canvas.drawBitmap(bitmap, matrix, null)
            newBitmap
        } catch (e: Exception) {
            newBitmap.recycle()
            null
        }
    }

    private fun salvarRecorte(recorteBitmap: Bitmap, baseName: String, context: Context, projectDirName: String): String {
        val nomeArquivo = "${baseName}_recorte.webp"
        val fileDir = getProjectSpecificDirectory(context, projectDirName, "imagens_ml_recortes")
        val file = File(fileDir, nomeArquivo)
        return try {
            FileOutputStream(file).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    recorteBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    @Suppress("DEPRECATION")
                    recorteBitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
            }
            file.absolutePath
        } catch (e: Exception) { "" }
    }
}