// File: euia/utils/NotificationUtils.kt
package com.carlex.euia.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.carlex.euia.R

/**
 * Objeto utilitário para centralizar a criação de todos os canais de notificação da aplicação.
 * Deve ser chamado uma única vez, na inicialização do app (em MyApplication.onCreate).
 */
object NotificationUtils {

    // Constantes dos canais (centralizadas para referência por toda a aplicação)
    const val CHANNEL_ID_AUDIO = "AudioNarrativeChannelEUIA"
    const val CHANNEL_ID_IMAGE_PROCESSING = "ImageProcessingChannelEUIA"
    const val CHANNEL_ID_VIDEO_PROCESSING = "VideoProcessingChannelEUIA"
    const val CHANNEL_ID_VIDEO_RENDER = "VideoRenderChannel"
    const val CHANNEL_ID_URL_IMPORT = "UrlImportChannelEUIA"
    const val CHANNEL_ID_REF_IMAGE_ANALYSIS = "RefImageAnalysisChannelEUIA"
    const val CHANNEL_ID_POST_PRODUCTION = "PostProductionChannelEUIA"

    /**
     * Cria todos os canais de notificação necessários para o aplicativo.
     * Seguro para ser chamado múltiplas vezes, pois a criação de um canal existente não faz nada.
     *
     * @param context O contexto da aplicação.
     */
    fun createAllNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channels = listOf(
                // Canal para o AudioNarrativeWorker
                NotificationChannel(
                    CHANNEL_ID_AUDIO,
                    context.getString(R.string.notification_channel_name_audio),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description_audio)
                },

                // Canal para o ImageProcessingWorker (o que estava causando o erro)
                NotificationChannel(
                    CHANNEL_ID_IMAGE_PROCESSING,
                    context.getString(R.string.notification_channel_name_image),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description_image)
                },

                // Canal para o VideoProcessingWorker
                NotificationChannel(
                    CHANNEL_ID_VIDEO_PROCESSING,
                    context.getString(R.string.video_processing_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.video_processing_notification_channel_desc)
                },

                // Canal para o VideoRenderWorker
                NotificationChannel(
                    CHANNEL_ID_VIDEO_RENDER,
                    context.getString(R.string.video_render_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.video_render_notification_channel_desc)
                },

                // Canal para o UrlImportWorker
                NotificationChannel(
                    CHANNEL_ID_URL_IMPORT,
                    context.getString(R.string.notification_channel_name_url),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description_url)
                },

                // Canal para o RefImageAnalysisWorker
                NotificationChannel(
                    CHANNEL_ID_REF_IMAGE_ANALYSIS,
                    context.getString(R.string.notification_channel_name_ref_image),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description_ref_image)
                },
                
                // Canal para o PostProductionWorker
                NotificationChannel(
                    CHANNEL_ID_POST_PRODUCTION,
                    context.getString(R.string.notification_channel_name_post),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description_post)
                }
            )

            // Registra todos os canais no sistema de uma vez
            notificationManager.createNotificationChannels(channels)
        }
    }
}