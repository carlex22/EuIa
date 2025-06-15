// File: utils/BitmapUtils.kt
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

    // ... (demais funções como decodeSampledBitmapFromUri, decodeBitmapFromByteArray, etc., permanecem iguais) ...
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
                //Log.e(TAG, "Falha ao abrir InputStream para URI: $uri")
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
                //Log.e(TAG, "Falha ao REABRIR InputStream para URI: $uri")
                return@withContext null
            }
            val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            if (bitmap == null) {
                //Log.e(TAG, "BitmapFactory.decodeStream retornou null para URI: $uri com inSampleSize=${options.inSampleSize}")
            }
            return@withContext bitmap
        } catch (e: FileNotFoundException) {
            //Log.e(TAG, "Arquivo não encontrado para URI: $uri", e)
            return@withContext null
        } catch (e: IOException) {
            //Log.e(TAG, "IOException ao decodificar Bitmap da URI: $uri", e)
            return@withContext null
        } catch (e: OutOfMemoryError) {
            //Log.e(TAG, "OutOfMemoryError ao decodificar Bitmap da URI: $uri. Tente reduzir reqWidth/reqHeight.", e)
            return@withContext null
        } catch (e: Exception) {
            //Log.e(TAG, "Erro inesperado ao decodificar Bitmap da URI: $uri", e)
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
        } catch (e: OutOfMemoryError) {
            //Log.e(TAG, "OutOfMemoryError ao decodificar Bitmap de byteArray.", e)
            return@withContext null
        } catch (e: Exception) {
            //Log.e(TAG, "Erro ao decodificar Bitmap de byteArray.", e)
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
        } catch (e: IllegalArgumentException) {
            //Log.e(TAG, "String Base64 inválida.", e)
            null
        } catch (e: Exception) {
            //Log.e(TAG, "Erro ao decodificar Bitmap de Base64.", e)
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
        //Log.d(TAG, "Original: ${width}x$height, Requerido: ${reqWidth}x$reqHeight, SampleSize: $inSampleSize")
        return inSampleSize
    }

    // <<< --- INÍCIO DA FUNÇÃO MODIFICADA --- >>>
    /**
     * Redimensiona um Bitmap para as dimensões finais desejadas.
     * Cria um fundo usando a imagem original esticada, desfocada e esbranquiçada.
     * Sobrepõe a imagem original redimensionada proporcionalmente (letterbox/pillarbox).
     *
     * @param sourceBitmap O Bitmap original.
     * @param targetWidth A largura final desejada.
     * @param targetHeight A altura final desejada.
     * @return Um novo Bitmap com o fundo processado e a imagem proporcional sobreposta, ou null em caso de erro.
     *         O sourceBitmap NÃO é reciclado por esta função.
     */
    fun resizeWithTransparentBackground( // O nome "TransparentBackground" pode ser menos preciso agora
        sourceBitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val originalWidth = sourceBitmap.width
        val originalHeight = sourceBitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            //Log.e(TAG, "Bitmap original com dimensões inválidas: ${originalWidth}x${originalHeight}. Retornando null.")
            return null
        }

        val finalWidth = targetWidth.coerceAtLeast(1)
        val finalHeight = targetHeight.coerceAtLeast(1)
        //Log.d(TAG, "Redimensionando com fundo processado: ${originalWidth}x${originalHeight} -> ${finalWidth}x${finalHeight}")

        var resultBitmap: Bitmap? = null
        var blurredBackgroundBitmap: Bitmap? = null
        var scaledBitmapProportional: Bitmap? = null

        try {
            // 1. Criar o bitmap de fundo (desfocado e esbranquiçado)
            // Primeiro, escalar a imagem original para um tamanho menor para o desfoque (performance)
            // e depois escalar para o tamanho final do canvas.
            val downscaleFactorForBlur = 0.25f // Reduz para 1/4 do tamanho para desfoque mais rápido
            val blurTempWidth = (finalWidth * downscaleFactorForBlur).toInt().coerceAtLeast(1)
            val blurTempHeight = (finalHeight * downscaleFactorForBlur).toInt().coerceAtLeast(1)

            var tempScaledForBlur: Bitmap? = null
            if (originalWidth > 0 && originalHeight > 0) {
                 tempScaledForBlur = Bitmap.createScaledBitmap(sourceBitmap, blurTempWidth, blurTempHeight, true)
            }


            if (tempScaledForBlur != null) {
                // Aplicar desfoque (usando RenderScript seria ideal para performance, mas Paint pode ser usado para simplicidade)
                // Esta é uma simulação de desfoque com Paint. Para um desfoque real, use RenderScript ou uma biblioteca.
                // Aumentar o blurRadius para um efeito mais forte.
                val blurRadius = 25f // Raio do desfoque (ajuste conforme necessário)
                val blurPaint = Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    isFilterBitmap = true // Essencial para o BlurMaskFilter funcionar bem
                    if (blurRadius > 0) {
                        maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                    }
                }

                // Criar um bitmap temporário para desenhar o desfoque
                // Aumentar um pouco o padding para o desfoque não cortar nas bordas
                val blurPadding = (blurRadius * 2).toInt()
                blurredBackgroundBitmap = Bitmap.createBitmap(blurTempWidth + blurPadding, blurTempHeight + blurPadding, Bitmap.Config.ARGB_8888)
                val blurCanvas = Canvas(blurredBackgroundBitmap)
                blurCanvas.drawBitmap(tempScaledForBlur, blurRadius, blurRadius, blurPaint) // Desenha com offset para o padding
                tempScaledForBlur.recycle() // Recicla o bitmap escalado para desfoque
            } else {
                //Log.w(TAG, "Não foi possível criar bitmap temporário para desfoque.")
                // Como fallback, pode usar a imagem original esticada sem desfoque, ou cor sólida.
                // Aqui, vamos prosseguir e a camada de "vidro jateado" será desenhada sobre o que for.
            }


            // 2. Criar o bitmap final e desenhar o fundo desfocado e esbranquiçado
            resultBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) // Limpa o canvas

            if (blurredBackgroundBitmap != null) {
                val srcRectBlurred = Rect(0, 0, blurredBackgroundBitmap.width, blurredBackgroundBitmap.height)
                val dstRectFBlurred = RectF(0f, 0f, finalWidth.toFloat(), finalHeight.toFloat())
                val backgroundPaint = Paint().apply { // Paint para esticar o fundo desfocado e aplicar o "vidro"
                    isAntiAlias = true
                    isDither = true
                    isFilterBitmap = true
                }
                canvas.drawBitmap(blurredBackgroundBitmap, srcRectBlurred, dstRectFBlurred, backgroundPaint)
                blurredBackgroundBitmap.recycle() // Recicla o bitmap de fundo desfocado
                //Log.d(TAG, "Fundo desfocado desenhado e esticado.")
            } else {
                // Fallback: desenha a imagem original esticada sem desfoque (ou uma cor sólida)
                val fallbackBgPaint = Paint()
                canvas.drawBitmap(sourceBitmap, Rect(0,0,originalWidth, originalHeight), RectF(0f,0f,finalWidth.toFloat(), finalHeight.toFloat()), fallbackBgPaint)
                //Log.w(TAG, "Usando fallback para o fundo (sem desfoque).")
            }

            // Aplicar uma camada de "vidro jateado" (cor branca semi-transparente) sobre o fundo
            val frostedGlassPaint = Paint().apply {
                color = Color.WHITE
                alpha = 100 // Ajuste a transparência (0-255). 100 = ~40% opaco.
            }
            canvas.drawRect(0f, 0f, finalWidth.toFloat(), finalHeight.toFloat(), frostedGlassPaint)
            //Log.d(TAG, "Camada de 'vidro jateado' (branco semi-transparente) aplicada sobre o fundo.")


            // 3. Desenhar a Imagem Original Proporcionalmente por Cima (sem filtro de fundo)
            val foregroundPaint = Paint().apply {
                isAntiAlias = true
                isDither = true
                isFilterBitmap = true
            }

            val aspectRatioSource = originalWidth.toFloat() / originalHeight
            val aspectRatioTarget = finalWidth.toFloat() / finalHeight

            var scaledWidth: Int
            var scaledHeight: Int
            var xOffset = 0f
            var yOffset = 0f

            if (aspectRatioSource > aspectRatioTarget) {
                scaledWidth = finalWidth
                scaledHeight = (finalWidth / aspectRatioSource).toInt()
                yOffset = (finalHeight - scaledHeight) / 2f
            } else {
                scaledHeight = finalHeight
                scaledWidth = (finalHeight * aspectRatioSource).toInt()
                xOffset = (finalWidth - scaledWidth) / 2f
            }
            scaledWidth = scaledWidth.coerceAtLeast(1)
            scaledHeight = scaledHeight.coerceAtLeast(1)

            if (scaledWidth > 0 && scaledHeight > 0) {
                scaledBitmapProportional = Bitmap.createScaledBitmap(sourceBitmap, scaledWidth, scaledHeight, true)
                canvas.drawBitmap(scaledBitmapProportional, xOffset, yOffset, foregroundPaint)
                //Log.d(TAG, "Imagem proporcional desenhada sobre o fundo processado.")
            } else {
                //Log.w(TAG, "Dimensões calculadas para imagem proporcional são inválidas: ${scaledWidth}x${scaledHeight}")
            }
            return resultBitmap

        } catch (e: Exception) {
            //Log.e(TAG, "Erro ao redimensionar com fundo processado: ${e.message}", e)
            resultBitmap?.recycle()
            blurredBackgroundBitmap?.recycle() // Garante que este também seja reciclado em caso de erro
            return null
        } finally {
            scaledBitmapProportional?.takeIf { it != sourceBitmap && !it.isRecycled }?.recycle()
        }
    }
    // <<< --- FIM DA FUNÇÃO MODIFICADA --- >>>


    suspend fun saveBitmapToFile(
        context: Context,
        bitmap: Bitmap,
        projectDirName: String,
        subDir: String,
        baseName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 85
    ): String? = withContext(Dispatchers.IO) {
        val directory = getAppSpecificDirectory(context, projectDirName, subDir)
        if (directory == null) {
            //Log.e(TAG, "Não foi possível obter/criar o diretório de destino.")
            return@withContext null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val extension = when (format) {
            Bitmap.CompressFormat.JPEG -> "jpg"
            Bitmap.CompressFormat.PNG -> "png"
            Bitmap.CompressFormat.WEBP, Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
            else -> "img"
        }
        val fileName = "${baseName}_${timestamp}_$uuid.$extension"
        val file = File(directory, fileName)

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            val success = bitmap.compress(format, quality, fos)
            fos.flush()
            if (success) {
                //Log.i(TAG, "Bitmap salvo com sucesso em: ${file.absolutePath} (Formato: $format, Qualidade: $quality)")
                return@withContext file.absolutePath
            } else {
                //Log.e(TAG, "Falha ao comprimir bitmap para: ${file.absolutePath}")
                file.delete()
                return@withContext null
            }
        } catch (e: FileNotFoundException) {
            //Log.e(TAG, "Arquivo não encontrado para escrita: ${file.absolutePath}", e)
            return@withContext null
        } catch (e: IOException) {
            //Log.e(TAG, "IOException ao salvar bitmap: ${file.absolutePath}", e)
            return@withContext null
        } catch (e: Exception) {
            //Log.e(TAG, "Erro inesperado ao salvar bitmap: ${file.absolutePath}", e)
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
            //Log.w(TAG, "Armazenamento externo não disponível. Usando armazenamento interno para $sanitizedProjectDirName/$subDir.")
            val internalProjectPath = File(context.filesDir, sanitizedProjectDirName)
            finalDir = File(internalProjectPath, subDir)
        }

        if (!finalDir.exists()) {
            if (!finalDir.mkdirs()) {
                //Log.e(TAG, "Falha ao criar diretório: ${finalDir.absolutePath}")
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
            //Log.d(TAG, "Bitmap reciclado por: $from")
        } else if (bitmap == null) {
            //Log.w(TAG, "Tentativa de reciclar Bitmap nulo por: $from")
        }
    }
}