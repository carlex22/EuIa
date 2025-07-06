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

    // ... (funções decodeSampledBitmapFromUri, decodeBitmapFromByteArray, etc., permanecem as mesmas) ...
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
     
     fun cropToAspectRatioCenter(
        source: Bitmap,
        aspectWidth: Int,  // Ex: 4 ou 3
        aspectHeight: Int  // Ex: 3 ou 4
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
            // Imagem mais larga que o target — corta nas laterais
            cropW = sh * targetRatio
            startX = (sw - cropW) / 2
        } else {
            // Imagem mais alta que o target — corta no topo/baixo
            cropH = sw / targetRatio
            startY = (sh - cropH) / 2
        }
    
        return Bitmap.createBitmap(
            source,
            startX.toInt(), startY.toInt(),
            cropW.toInt(), cropH.toInt()
        )
    }
    
     
     
    fun resizeWithTransparentBackground( // O nome "TransparentBackground" pode ser menos preciso agora
        sourceBitmap1: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val originalWidth = sourceBitmap1.width
        val originalHeight = sourceBitmap1.height
        
        if (originalWidth <= originalHeight ) {
            //Log.e(TAG, "Bitmap original com dimensões inválidas: ${originalWidth}x${originalHeight}. Retornando null.")
            var sourceBitmap = cropToAspectRatioCenter(sourceBitmap1, 4 , 3)
            return sourceBitmap

        } else {
            var sourceBitmap = cropToAspectRatioCenter(sourceBitmap1, 3 , 4)
            return sourceBitmap
        }
    }

    suspend fun saveBitmapToFile(
        context: Context,
        bitmap: Bitmap,
        projectDirName: String,
        subDir: String,
        baseName: String,
        // OTIMIZAÇÃO: Parâmetros padrão para WebP
        format: Bitmap.CompressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
        quality: Int = 65
    ): String? = withContext(Dispatchers.IO) {
        val directory = getAppSpecificDirectory(context, projectDirName, subDir)
        if (directory == null) {
            //Log.e(TAG, "Não foi possível obter/criar o diretório de destino.")
            return@withContext null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        
        // OTIMIZAÇÃO: Extensão sempre será webp com os novos padrões
        val extension = "webp"
        
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