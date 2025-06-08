package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build // Usado em salvarRecorte para compressão WEBP
import android.util.Log
import com.carlex.euia.data.VideoPreferencesDataStoreManager // Importado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first // Importado
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min // Adicionado para redimensionamento

class ExtrairMl {

    private val TAG = "ExtrairMl"
    private val MIN_IMAGE_DIMENSION = 350 // Mantido para filtro inicial de imagens muito pequenas

    // Dimensões padrão para recorte se não houver preferência ou forem inválidas
    // Idealmente, estas devem corresponder ao aspect ratio padrão (9:16 do DataStore)
    private val DEFAULT_RECORTE_WIDTH = 720
    private val DEFAULT_RECORTE_HEIGHT = 1280

    suspend fun extrairDados(url: String, context: Context): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
            val projectDirName = videoPreferencesManager.videoProjectDir.first()
            // Buscar largura e altura das preferências para o recorte
            val larguraPreferidaRecorte = videoPreferencesManager.videoLargura.first()
            val alturaPreferidaRecorte = videoPreferencesManager.videoAltura.first()

            Log.d(TAG, "Iniciando extração de dados para URL: $url. Diretório do projeto: '$projectDirName'")
            Log.d(TAG, "Dimensões preferidas para recorte (LxA): ${larguraPreferidaRecorte ?: "Padrão"}x${alturaPreferidaRecorte ?: "Padrão"}")

            val dadosProduto = mutableMapOf<String, Any>()

            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/536.36")
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.connect()

                val html = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val nomeRegex = Regex("""<h1.*?ui-pdp-title.*?>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
                dadosProduto["titulo"] = nomeRegex.find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("<.*?>"), "") ?: ""
                Log.d(TAG, "Título extraído: ${dadosProduto["titulo"]}")

                val descRegex = Regex("""<p.*?ui-pdp-description__content.*?>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                dadosProduto["descricao"] = descRegex.find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("<.*?>"), "") ?: ""
                Log.d(TAG, "Descrição extraída (primeiros 100 chars): ${dadosProduto["descricao"].toString().take(100)}")

                val imagensEncontradas = mutableSetOf<String>()
                val imagensSalvasCaminhosOriginais = mutableListOf<String>()
                var imageCounter = 0

                val imageUrlRegexes = listOf(
                    """"url":"(https:[^"]+)"""",
                    """data-src="(https:[^"]+)"""",
                    """src="(https:[^"]+)""""
                )

                for (regexPattern in imageUrlRegexes) {
                    val regex = Regex(regexPattern)
                    regex.findAll(html).forEach { matchResult -> // Renomeado 'match' para 'matchResult'
                        val originalImageUrl = matchResult.groupValues[1]
                        var processedImageUrl = originalImageUrl
                        if (processedImageUrl.contains("/D_Q_NP_")) {
                            processedImageUrl = processedImageUrl.replace("/D_Q_NP_", "/D_NQ_NP_")
                        }
                        if (processedImageUrl.contains("/D_NQ_NP_")) {
                            processedImageUrl = processedImageUrl.replace("/D_NQ_NP_", "/D_NQ_NP_2X_")
                        }

                        if (processedImageUrl !in imagensEncontradas) {
                            imagensEncontradas.add(processedImageUrl)
                            Log.d(TAG, "Processando URL da imagem: $processedImageUrl")

                            try {
                                val imageConnection = URL(processedImageUrl).openConnection() as HttpURLConnection
                                imageConnection.connectTimeout = 10000
                                imageConnection.readTimeout = 15000
                                imageConnection.connect()

                                val byteArray = imageConnection.inputStream.use { it.readBytes() }
                                imageConnection.disconnect()

                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)

                                if (options.outHeight <= 0 || options.outWidth <= 0 || min(options.outHeight, options.outWidth) < MIN_IMAGE_DIMENSION) {
                                    Log.i(TAG, "Ignorando imagem (miniatura ou inválida): $processedImageUrl - Dimensões: ${options.outWidth}x${options.outHeight}")
                                    return@forEach
                                }
                                Log.d(TAG, "Imagem válida encontrada: $processedImageUrl - Dimensões: ${options.outWidth}x${options.outHeight}")

                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                                val urlFileName = processedImageUrl.substringAfterLast("/").substringBefore("?").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                val baseName = "${if (urlFileName.isNotEmpty() && urlFileName.length < 50) urlFileName else "imgML"}_${timestamp}_$imageCounter"

                                val originalPath = salvarImagemOriginal(byteArray, baseName, processedImageUrl, context, projectDirName)
                                Log.d(TAG, "Imagem original salva em: $originalPath")

                                val bitmapOriginal = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                                if (bitmapOriginal != null) {
                                    // Usa as dimensões das preferências para o recorte
                                    val recorteBitmap = criarRecorteBitmap(
                                        bitmapOriginal,
                                        larguraPreferidaRecorte ?: DEFAULT_RECORTE_WIDTH,
                                        alturaPreferidaRecorte ?: DEFAULT_RECORTE_HEIGHT
                                    )
                                    if (recorteBitmap != null) {
                                        val recortePath = salvarRecorte(recorteBitmap, baseName, context, projectDirName)
                                        Log.d(TAG, "Recorte salvo em: $recortePath")
                                        recorteBitmap.recycle() // Recicla o bitmap do recorte
                                    }
                                    bitmapOriginal.recycle() // Recicla o bitmap original decodificado
                                } else {
                                    Log.w(TAG, "Erro ao decodificar bitmap completo para recorte: $processedImageUrl")
                                }

                                imagensSalvasCaminhosOriginais.add(originalPath)
                                imageCounter++

                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao processar imagem $processedImageUrl: ${e.message}", e)
                            }
                        }
                    }
                }
                dadosProduto["imagensSalvas"] = imagensSalvasCaminhosOriginais
                Log.i(TAG, "Extração de dados concluída. ${imagensSalvasCaminhosOriginais.size} imagens originais salvas.")
                Result.success(dadosProduto)
            } catch (e: Exception) {
                Log.e(TAG, "Erro geral na extração de dados da URL $url: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private fun getProjectSpecificDirectory(context: Context, projectDirName: String, subDir: String): File {
        val baseAppDir: File?
        if (projectDirName.isNotBlank()) {
            baseAppDir = context.getExternalFilesDir(null)
            if (baseAppDir != null) {
                val projectPath = File(baseAppDir, projectDirName)
                val finalDir = File(projectPath, subDir)
                if (!finalDir.exists() && !finalDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório do projeto (externo): ${finalDir.absolutePath}")
                }
                return finalDir
            } else {
                Log.w(TAG, "Armazenamento externo não disponível. Usando fallback para interno para o projeto '$projectDirName'.")
                val internalProjectPath = File(context.filesDir, projectDirName)
                val finalInternalDir = File(internalProjectPath, subDir)
                if (!finalInternalDir.exists() && !finalInternalDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório interno do projeto (fallback A): ${finalInternalDir.absolutePath}")
                }
                return finalInternalDir
            }
        }
        val defaultParentDirName = "ml_files_default" // Mantido para ExtrairMl
        Log.w(TAG, "Nome do diretório do projeto está em branco. Usando fallback interno: '$defaultParentDirName/$subDir'")
        val fallbackDir = File(File(context.filesDir, defaultParentDirName), subDir)
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            Log.e(TAG, "Falha ao criar diretório de fallback interno: ${fallbackDir.absolutePath}")
        }
        return fallbackDir
    }

    private fun salvarImagemOriginal(byteArray: ByteArray, baseName: String, imageUrl: String, context: Context, projectDirName: String): String {
        val extension = imageUrl.substringAfterLast(".", "").substringBefore("?").lowercase(Locale.ROOT)
        val fileExtension = when {
            extension.matches(Regex("jpe?g")) -> ".jpg"
            extension == "png" -> ".png"
            extension == "webp" -> ".webp"
            extension.isNotEmpty() && extension.length <= 4 && extension.all { it.isLetterOrDigit() } -> ".$extension"
            else -> ".jpg"
        }
        val nomeArquivo = "${baseName}_original${fileExtension}"
        val fileDir = getProjectSpecificDirectory(context, projectDirName, "imagens_ml_originais")
        val file = File(fileDir, nomeArquivo)
        return try {
            FileOutputStream(file).use { out -> out.write(byteArray) }
            Log.d(TAG, "Imagem original salva: ${file.absolutePath}")
            file.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao salvar imagem original ${file.absolutePath}: ${e.message}", e)
            "Erro ao salvar imagem original: ${e.message}"
        }
    }

    // Modificada para aceitar dimensões finais desejadas
    fun criarRecorteBitmap(bitmap: Bitmap, larguraFinalDesejada: Int, alturaFinalDesejada: Int): Bitmap? {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            Log.w(TAG, "Bitmap inválido para recorte: largura=$originalWidth, altura=$originalHeight")
            return null
        }

        // Usa as dimensões desejadas, garantindo que sejam pelo menos 1
        val finalWidth = larguraFinalDesejada.coerceAtLeast(1)
        val finalHeight = alturaFinalDesejada.coerceAtLeast(1)
        Log.d(TAG, "Criando recorte/redimensionamento de ${originalWidth}x${originalHeight} para ${finalWidth}x${finalHeight}")


        val newBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) // Fundo transparente

        val scale: Float
        var xOffset = 0f
        var yOffset = 0f

        // Lógica para centralizar e escalar a imagem mantendo a proporção (letterbox/pillarbox)
        if (originalWidth.toFloat() / originalHeight > finalWidth.toFloat() / finalHeight) {
            scale = finalWidth.toFloat() / originalWidth
            yOffset = (finalHeight - originalHeight * scale) / 2f
        } else {
            scale = finalHeight.toFloat() / originalHeight
            xOffset = (finalWidth - originalWidth * scale) / 2f
        }

        val newScaledWidth = (originalWidth * scale).toInt().coerceAtLeast(1)
        val newScaledHeight = (originalHeight * scale).toInt().coerceAtLeast(1)

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newScaledWidth, newScaledHeight, true)
            canvas.drawBitmap(scaledBitmap, xOffset, yOffset, null)
            if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) { // Recicla o bitmap escalado intermediário
                scaledBitmap.recycle()
            }
            newBitmap // Retorna o bitmap final com as dimensões desejadas
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar recorte/redimensionamento do bitmap: ${e.message}", e)
            newBitmap.recycle() // Se newBitmap foi criado mas houve erro, recicla-o
            null
        }
    }

    // salvarRecorte agora não precisa mais dos parâmetros de dimensões, pois elas já foram usadas em criarRecorteBitmap
    private fun salvarRecorte(recorteBitmap: Bitmap, baseName: String, context: Context, projectDirName: String): String {
        // O recorteBitmap já está nas dimensões corretas e com fundo transparente.
        // Precisamos garantir que ele seja ARGB_8888 para compressão WebP de qualidade,
        // embora criarRecorteBitmap já o crie assim.

        // Se o recorteBitmap não for ARGB_8888, ou para garantir, podemos convertê-lo.
        // No entanto, criarRecorteBitmap já o cria como ARGB_8888.
        // val argbRecorteBitmap = if (recorteBitmap.config == Bitmap.Config.ARGB_8888) {
        //     recorteBitmap
        // } else {
        //     Log.d(TAG, "Convertendo recorteBitmap para ARGB_8888 antes de salvar.")
        //     recorteBitmap.copy(Bitmap.Config.ARGB_8888, false)
        // }
        // A linha acima é opcional se criarRecorteBitmap sempre retorna ARGB_8888.
        // Por simplicidade, vamos assumir que recorteBitmap já é ARGB_8888.

        val nomeArquivo = "${baseName}_recorte.webp" // Salva sempre como WebP
        val fileDir = getProjectSpecificDirectory(context, projectDirName, "imagens_ml_recortes")
        val file = File(fileDir, nomeArquivo)

        val saveSuccess = try {
            FileOutputStream(file).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    recorteBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out) // Ou WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    recorteBitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Erro IO ao salvar arquivo de recorte ${file.absolutePath}: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral ao salvar arquivo de recorte ${file.absolutePath}: ${e.message}", e)
            false
        }
        // Não recicle recorteBitmap aqui, pois ele é passado como parâmetro e deve ser reciclado pelo chamador (extrairDados)
        // após salvarRecorte retornar.

        return if (saveSuccess) {
            Log.d(TAG, "Recorte salvo: ${file.absolutePath}")
            file.absolutePath
        } else {
            "Erro ao salvar recorte"
        }
    }
}