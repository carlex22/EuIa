// File: euia/utils/YouTubeUploader.kt
package com.carlex.euia.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.carlex.euia.R // GARANTA QUE ESTE IMPORT ESTÁ CORRETO
import java.io.File

object YouTubeUploader {

    private const val TAG = "YouTubeUploader"
    private const val YOUTUBE_PACKAGE_NAME = "com.google.android.apps.youtube.creator"

    /**
     * Tenta iniciar o fluxo de upload de vídeo para o aplicativo oficial do YouTube.
     * Se o aplicativo do YouTube não estiver instalado ou não puder lidar com a Intent,
     * um seletor de aplicativos genérico será exibido.
     *
     * @param context O contexto da aplicação/atividade.
     * @param videoPath O caminho absoluto do arquivo de vídeo local a ser compartilhado.
     * @param videoTitle O título sugerido para o vídeo (pode ser usado por alguns apps de compartilhamento).
     * @param videoDescription A descrição sugerida para o vídeo.
     */
    fun uploadVideoToYouTube(
        context: Context,
        videoPath: String,
        videoTitle: String = context.getString(R.string.youtube_upload_default_title),
        videoDescription: String = context.getString(R.string.youtube_upload_default_description)
    ) {
        if (videoPath.isBlank()) {
            Toast.makeText(context, R.string.youtube_upload_no_video_to_share, Toast.LENGTH_SHORT).show()
            return
        }

        val videoFile = File(videoPath)
        if (!videoFile.exists() || !videoFile.isFile) {
            Toast.makeText(context, R.string.youtube_upload_video_file_not_found, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "uploadVideoToYouTube: Arquivo de vídeo não encontrado ou não é um arquivo: $videoPath")
            return
        }

        val videoUri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider", // Autoridade do FileProvider
                videoFile
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "uploadVideoToYouTube: O FileProvider não conseguiu obter a URI para $videoPath. Verifique a configuração do FileProvider no AndroidManifest.xml.", e)
            Toast.makeText(context, R.string.youtube_upload_file_provider_error, Toast.LENGTH_LONG).show()
            return
        }

        val uploadIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            // EXTRA_TITLE e EXTRA_SUBJECT podem ser interpretados como título em alguns apps
            putExtra(Intent.EXTRA_TITLE, videoTitle)
            putExtra(Intent.EXTRA_SUBJECT, videoTitle)
            putExtra(Intent.EXTRA_TEXT, videoDescription) // Alguns apps podem usar isso para a descrição
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Tenta encontrar e direcionar diretamente para o YouTube
        val packageManager: PackageManager = context.packageManager
        val resolvedActivities = packageManager.queryIntentActivities(uploadIntent, PackageManager.MATCH_DEFAULT_ONLY)
        var youtubeActivityFound = false

        for (info in resolvedActivities) {
            if (info.activityInfo.packageName == YOUTUBE_PACKAGE_NAME) {
                // Encontrou o YouTube, tenta iniciar a Intent especificamente para ele
                uploadIntent.setPackage(YOUTUBE_PACKAGE_NAME)
                youtubeActivityFound = true
                Log.d(TAG, "uploadVideoToYouTube: Tentando iniciar Intent para o YouTube (${info.activityInfo.name})")
                break
            }
        }

        try {
            if (youtubeActivityFound) {
                context.startActivity(uploadIntent)
                Toast.makeText(context, R.string.youtube_upload_starting_youtube_app, Toast.LENGTH_SHORT).show()
            } else {
                // Se o YouTube não foi encontrado explicitamente, abre o seletor padrão
                val chooser = Intent.createChooser(uploadIntent, context.getString(R.string.youtube_upload_share_video_via))
                context.startActivity(chooser)
                Toast.makeText(context, R.string.youtube_upload_no_youtube_found, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadVideoToYouTube: Erro ao iniciar atividade para upload: ${e.message}", e)
            Toast.makeText(context, R.string.youtube_upload_general_error_starting_activity, Toast.LENGTH_LONG).show()
        }
    }
}