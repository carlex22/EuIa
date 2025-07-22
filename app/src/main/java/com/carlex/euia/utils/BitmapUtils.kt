// File: euia/utils/BitmapUtils.kt
package com.carlex.euia.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import android.content.ContentResolver
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

object BitmapUtils {

    private const val TAG = "BitmapUtils"

    // ... (suas funções existentes como decodeSampledBitmapFromUri, etc., permanecem aqui) ...

    /**
     * Recorta o centro de um bitmap para uma proporção de aspecto específica.
     * @param source O bitmap original.
     * @param targetAspectRatio A proporção desejada (ex: 9f / 16f).
     * @return Um novo bitmap recortado.
     */
    fun cropToAspectRatio(source: Bitmap, targetAspectRatio: Float): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight

        return if (sourceAspectRatio > targetAspectRatio) {
            // A imagem é mais larga que o necessário, corta nas laterais
            val newWidth = (sourceHeight * targetAspectRatio).toInt()
            val xOffset = (sourceWidth - newWidth) / 2
            Bitmap.createBitmap(source, xOffset, 0, newWidth, sourceHeight)
        } else {
            // A imagem é mais alta que o necessário, corta em cima/embaixo
            val newHeight = (sourceWidth / targetAspectRatio).toInt()
            val yOffset = (sourceHeight - newHeight) / 2
            Bitmap.createBitmap(source, 0, yOffset, sourceWidth, newHeight)
        }
    }

    // <<< INÍCIO DA FUNÇÃO ATUALIZADA >>>
    /**
     * Desenha um texto estilizado em um bitmap, quebrando o texto em várias linhas
     * com no máximo 3 palavras e tamanhos de fonte diferentes.
     *
     * @param sourceBitmap O bitmap onde o texto será desenhado.
     * @param text O texto a ser escrito.
     * @return Um novo bitmap com o texto desenhado.
     */
    fun drawTextOnBitmap(sourceBitmap: Bitmap, text: String): Bitmap {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Quebra o texto em linhas com no máximo 3 palavras
        val words = text.split(" ")
        val lines = words.chunked(3) { it.joinToString(" ") }

        val baseTextSize = mutableBitmap.width * 0.10f // Tamanho base da fonte (10% da largura)
        val margin = mutableBitmap.width * 0.05f   // Margem de 5% da largura
        val lineSpacing = baseTextSize * 0.15f     // Espaçamento entre linhas

        // Paint para o contorno (sombra/efeito)
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        // Paint para o preenchimento do texto
        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(5f, 5f, 5f, Color.argb(180, 0, 0, 0))
        }

        // Começa a desenhar da última linha para a primeira, de baixo para cima
        var currentY = mutableBitmap.height - margin
        val x = margin

        for ((index, line) in lines.asReversed().withIndex()) {
            // Diminui o tamanho da fonte para cada linha que sobe (a primeira linha será a maior)
            val scaleFactor = (1.0f - (index * 0.15f)).coerceAtLeast(0.6f)
            val currentTextSize = baseTextSize * scaleFactor
            
            textPaint.textSize = currentTextSize
            strokePaint.textSize = currentTextSize
            strokePaint.strokeWidth = currentTextSize * 0.1f

            // Desenha o contorno e o texto
            canvas.drawText(line, x, currentY, strokePaint)
            canvas.drawText(line, x, currentY, textPaint)

            // Atualiza a posição Y para a próxima linha (acima da atual)
            // Usa fontMetrics para calcular a altura da linha que acabamos de desenhar
            val fontMetrics = textPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            currentY -= (lineHeight + lineSpacing)
        }

        return mutableBitmap
    }
    // <<< FIM DA FUNÇÃO ATUALIZADA >>>

    // ... (o resto das suas funções existentes como saveBitmapToFile, etc., permanecem aqui) ...
    
    suspend fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext null
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext null
            }
            val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            return@withContext bitmap
        } catch (e: Exception) {
            return@withContext null
        } finally {
            inputStream?.close()
        }
    }

    suspend fun decodeBitmapFromByteArray(
        byteArray: ByteArray,
        reqWidth: Int? = null,
        reqHeight: Int? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options()
            if (reqWidth != null && reqHeight != null) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            return@withContext BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
        } catch (e: Exception) {
            return@withContext null
        }
    }

    suspend fun decodeBitmapFromBase64(
        base64String: String,
        reqWidth: Int? = null,
        reqHeight: Int? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            decodeBitmapFromByteArray(decodedBytes, reqWidth, reqHeight)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
     
     fun resizeWithTransparentBackground(
        sourceBitmap1: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val originalWidth = sourceBitmap1.width
        val originalHeight = sourceBitmap1.height
        
        if (originalWidth <= originalHeight ) {
            var sourceBitmap = cropToAspectRatioCenter(sourceBitmap1, 4 , 3)
            return sourceBitmap

        } else {
            var sourceBitmap = cropToAspectRatioCenter(sourceBitmap1, 3 , 4)
            return sourceBitmap
        }
    }
    
    fun cropToAspectRatioCenter(
        source: Bitmap,
        aspectWidth: Int,
        aspectHeight: Int
    ): Bitmap {
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        val targetRatio = aspectWidth.toFloat() / aspectHeight.toFloat()
        val srcRatio = sw / sh
    
        var cropW = sw
        var cropH = sh
        var startX = 0f
        var startY = 0f
    
        if (srcRatio > targetRatio) {
            cropW = sh * targetRatio
            startX = (sw - cropW) / 2
        } else {
            cropH = sw / targetRatio
            startY = (sh - cropH) / 2
        }
    
        return Bitmap.createBitmap(
            source,
            startX.toInt(), startY.toInt(),
            cropW.toInt(), cropH.toInt()
        )
    }

    suspend fun saveBitmapToFile(
        context: Context,
        bitmap: Bitmap,
        projectDirName: String,
        subDir: String,
        baseName: String,
        format: Bitmap.CompressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
        quality: Int = 65
    ): String? = withContext(Dispatchers.IO) {
        val directory = getAppSpecificDirectory(context, projectDirName, subDir)
        if (directory == null) {
            return@withContext null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        
        val extension = "webp"
        
        val fileName = "${baseName}.$extension"
        val file = File(directory, fileName)

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            val success = bitmap.compress(format, quality, fos)
            fos.flush()
            if (success) {
                return@withContext file.absolutePath
            } else {
                file.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        } finally {
            fos?.close()
        }
    }

    fun getAppSpecificDirectory(context: Context, projectDirName: String, subDir: String): File? {
        val sanitizedProjectDirName = projectDirName.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            ?: "DefaultAppFiles"

        val baseAppDir: File? = context.getExternalFilesDir(null)
        val finalDir: File

        if (baseAppDir != null) {
            val projectPath = File(baseAppDir, sanitizedProjectDirName)
            finalDir = File(projectPath, subDir)
        } else {
            val internalProjectPath = File(context.filesDir, sanitizedProjectDirName)
            finalDir = File(internalProjectPath, subDir)
        }

        if (!finalDir.exists()) {
            if (!finalDir.mkdirs()) {
                return null
            }
        }
        return finalDir
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (columnIndex != -1) {
                                fileName = cursor.getString(columnIndex)
                            }
                        }
                    }
            } catch (e: Exception) {
                //Log.e(TAG, "Erro ao obter nome do arquivo da URI de conteúdo: $uri", e)
            }
        }
        if (fileName == null) {
            fileName = uri.path
            val cut = fileName?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                fileName = fileName?.substring(cut + 1)
            }
        }
        return fileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?: "unknown_file_${UUID.randomUUID().toString().substring(0, 6)}"
    }

    fun safeRecycle(bitmap: Bitmap?, from: String) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        } else if (bitmap == null) {
            //Log.w(TAG, "Tentativa de reciclar Bitmap nulo por: $from")
        }
    }
}