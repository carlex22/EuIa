// File: euia/api/ExtrairMl.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.util.Log
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class ExtrairMl {

    private val TAG = "ExtrairMl"
    private val MIN_IMAGE_DIMENSION = 350
    private val DEFAULT_RECORTE_WIDTH = 720
    private val DEFAULT_RECORTE_HEIGHT = 1280

    private fun decodeUnicode(theString: String): String {
        var aChar: Char
        val len = theString.length
        val outBuffer = StringBuffer(len)
        var x = 0
        while (x < len) {
            aChar = theString[x++]
            if (aChar == '\\') {
                aChar = theString[x++]
                if (aChar == 'u') {
                    var value = 0
                    for (i in 0..3) {
                        aChar = theString[x++]
                        when (aChar) {
                            in '0'..'9' -> value = (value shl 4) + aChar.toString().toInt()
                            in 'a'..'f' -> value = (value shl 4) + 10 + aChar.minus('a')
                            in 'A'..'F' -> value = (value shl 4) + 10 + aChar.minus('A')
                            else -> throw IllegalArgumentException("Malformed \\uxxxx encoding.")
                        }
                    }
                    outBuffer.append(value.toChar())
                } else {
                    if (aChar == 't') aChar = '\t' else if (aChar == 'r') aChar = '\r' else if (aChar == 'n') aChar = '\n'
                    outBuffer.append(aChar)
                }
            } else outBuffer.append(aChar)
        }
        return outBuffer.toString()
    }

    suspend fun extrairDados(url: String, context: Context): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
            val projectDirName = videoPreferencesManager.videoProjectDir.first()
            val larguraPreferidaRecorte = videoPreferencesManager.videoLargura.first()
            val alturaPreferidaRecorte = videoPreferencesManager.videoAltura.first()

            Log.i(TAG, "--- INÍCIO DA EXTRAÇÃO DE DADOS ---")
            Log.d(TAG, "URL de entrada: $url")
            Log.d(TAG, "Diretório do projeto: '$projectDirName'")

            val dadosProduto = mutableMapOf<String, Any>()
            val paginasParaProcessar = mutableSetOf(url)

            try {
                // ETAPA 1: BAIXAR A PÁGINA PRINCIPAL E ENCONTRAR VARIAÇÕES
                Log.d(TAG, "ETAPA 1: Baixando página principal...")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()
                val htmlPrincipal = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                Log.d(TAG, "Página principal baixada.")

                val debugDir = getProjectSpecificDirectory(context, projectDirName, "debug_html")
                File(debugDir, "0_pagina_principal.html").writeText(htmlPrincipal)
                Log.i(TAG, "HTML principal salvo para depuração.")

                dadosProduto["titulo"] = Regex("""<h1.*?ui-pdp-title.*?>(.*?)</h1>""").find(htmlPrincipal)?.groupValues?.get(1)?.trim() ?: ""
                dadosProduto["descricao"] = Regex("""<p.*?ui-pdp-description__content.*?>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(htmlPrincipal)?.groupValues?.get(1)?.trim()?.replace(Regex("<.*?>"), "") ?: ""

                val linkRegex = Regex("""<a\s+href="([^"]+)"[^>]*>""")
                
                linkRegex.findAll(htmlPrincipal).forEach { match ->
                    var urlVariacao = match.groupValues[1].replace("&", "&")
                    
                    if (urlVariacao.contains("_JM?attributes=")) {
                        if (urlVariacao.startsWith("/MLB-")) {
                            urlVariacao = "https://produto.mercadolivre.com.br$urlVariacao"
                        }
                        paginasParaProcessar.add(urlVariacao)
                    }
                }
                
                Log.i(TAG, "Total de páginas (original + variações) para processar: ${paginasParaProcessar.size}")

                // ETAPA 2: ITERAR SOBRE TODAS AS PÁGINAS E EXTRAIR IMAGENS
                Log.i(TAG, "ETAPA 2: Iniciando iteração sobre as páginas para extrair imagens...")
                val imagensEncontradas = mutableSetOf<String>()
                val imagensSalvasCaminhosOriginais = mutableListOf<String>()
                var imageCounter = 0

                val imageUrlRegexes = listOf(
                    """"url":"(https:[^"]+)"""",
                    """data-src="(https:[^"]+)"""",
                    """src="(https:[^"]+)""""
                )

                paginasParaProcessar.forEachIndexed { index, paginaUrl ->
                    if (!isActive) return@forEachIndexed
                    Log.i(TAG, "--> Processando página ${index + 1}/${paginasParaProcessar.size}: $paginaUrl")
                    
                    val htmlPagina = if (paginaUrl == url) htmlPrincipal else {
                        val paginaConnection = URL(paginaUrl).openConnection() as HttpURLConnection
                        paginaConnection.connect()
                        val html = paginaConnection.inputStream.bufferedReader().use { it.readText() }
                        paginaConnection.disconnect()
                        File(debugDir, "pagina_variacao_${index + 1}.html").writeText(html)
                        html
                    }
                    
                    for (regexPattern in imageUrlRegexes) {
                        val regex = Regex(regexPattern)
                        regex.findAll(htmlPagina).forEach { matchResult ->
                            var processedImageUrl = decodeUnicode(matchResult.groupValues[1])
                            
                            
                            if (!processedImageUrl.contains(".svg")) {
                                if (processedImageUrl.contains("/D_Q_NP_")) {
                                    processedImageUrl = processedImageUrl.replace("/D_Q_NP_", "/D_NQ_NP_")
                                }
                                if (processedImageUrl.contains("/D_NQ_NP_")) {
                                    processedImageUrl = processedImageUrl.replace("/D_NQ_NP_", "/D_NQ_NP_2X_")
                                }
                                
                                if (processedImageUrl !in imagensEncontradas) {
                                    imagensEncontradas.add(processedImageUrl)
                                    
                                    try {
                                        Log.i(TAG, "$processedImageUrl")
                        
                                        val imageConnection = URL(processedImageUrl).openConnection() as HttpURLConnection
                                        imageConnection.connect()
                                        val byteArray = imageConnection.inputStream.use { it.readBytes() }
                                        imageConnection.disconnect()
    
                                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
    
                                        if (min(options.outHeight, options.outWidth) < MIN_IMAGE_DIMENSION) {
                                            return@forEach
                                        }
                                        
                                        val fileInfo = getFilenameAndExtension(imageConnection)
                                        val baseName1 = fileInfo?.first ?: "${System.currentTimeMillis()}"
                                        val extension1 = fileInfo?.second ?: "jpg"
       
                                        val baseName = "MLB_$baseName1.$extension1"
                                        val originalPath = salvarImagemOriginal(byteArray, baseName, processedImageUrl, context, projectDirName)
                                        imagensSalvasCaminhosOriginais.add(originalPath)
                                        imageCounter++
                                    } catch (e: Exception) {
                                        Log.i(TAG, "$processedImageUrl Exception")
                                    }
                                }
                            }
                        }
                    }
                }
                
                dadosProduto["imagensSalvas"] = imagensSalvasCaminhosOriginais
                Log.i(TAG, "--- FIM DA EXTRAÇÃO. Total de imagens únicas salvas: ${imagensSalvasCaminhosOriginais.size} ---")
                Result.success(dadosProduto)

            } catch (e: Exception) {
                Log.e(TAG, "--- ERRO CRÍTICO NA EXTRAÇÃO ---", e)
                Result.failure(e)
            }
        }
    }
    
    
    private fun getFilenameAndExtension(connection: HttpURLConnection): Pair<String, String>? {
        var filenameWithExt: String? = null

        // 1. Método Preferencial: Cabeçalho Content-Disposition
        val contentDisposition = connection.getHeaderField("Content-Disposition")
        if (contentDisposition != null) {
            // Regex para extrair o nome do arquivo, lidando com aspas opcionais
            val regex = Regex("""filename="?([^"]+)"?""")
            filenameWithExt = regex.find(contentDisposition)?.groupValues?.get(1)
        }

        // 2. Método de Fallback: Parsing da URL
        if (filenameWithExt.isNullOrBlank()) {
            val path = connection.url.path
            filenameWithExt = path.substringAfterLast('/')
        }

        // 3. Extrai nome e extensão do resultado
        if (!filenameWithExt.isNullOrBlank()) {
            val name = filenameWithExt.substringBeforeLast('.', "")
            val ext = filenameWithExt.substringAfterLast('.', "jpg") // Fallback para jpg se não houver extensão
            
            // Se o nome estiver vazio (ex: URL termina com "/"), retorna null
            if (name.isBlank()) return null

            return Pair(name, ext)
        }

        return null
    }
    
    

    // <<< INÍCIO DA MUDANÇA >>>
    /**
     * Retorna um diretório específico para o projeto DENTRO DO CACHE da aplicação.
     * Ideal para armazenar arquivos temporários que serão processados e depois descartados.
     */
    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File {
        // Usa o diretório de cache como base. É o local apropriado para arquivos temporários.
        val baseAppDir = context.cacheDir
        
        // Cria um subdiretório para o projeto atual para manter a organização
        val projectPath = File(baseAppDir, projectDirName)
        
        // Cria o subdiretório final (ex: 'imagens_ml_originais') dentro da pasta do projeto
        val finalDir = File(projectPath, subDir)
        
        // Garante que a estrutura de diretórios exista
        if (!finalDir.exists()) {
            finalDir.mkdirs()
        }
        
        return finalDir
    }
    // <<< FIM DA MUDANÇA >>>

    
    

    private fun salvarImagemOriginal(byteArray: ByteArray, baseName: String, imageUrl: String, context: Context, projectDirName: String): String {
        val extension = imageUrl.substringAfterLast(".", "webp").substringBefore("?").lowercase(Locale.ROOT)
        val nomeArquivo = "${baseName}_original.$extension"
        val fileDir = getProjectSpecificDirectory(context, projectDirName, "imagens_ml_originais")
        val file = File(fileDir, nomeArquivo)
        return try {
            FileOutputStream(file).use { it.write(byteArray) }
            file.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao salvar imagem original $nomeArquivo", e)
            ""
        }
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
            val matrix = android.graphics.Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(xOffset, yOffset)
            
            canvas.drawBitmap(bitmap, matrix, null)
            
            newBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar recorte/redimensionamento do bitmap: ${e.message}", e)
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
        } catch (e: Exception) {
             Log.e(TAG, "Erro ao salvar recorte $nomeArquivo", e)
            ""
        }
    }
}