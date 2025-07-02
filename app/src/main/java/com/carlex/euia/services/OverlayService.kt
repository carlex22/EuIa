// File: euia/services/OverlayService.kt
package com.carlex.euia.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.carlex.euia.R // Certifique-se de que este import está correto para seus recursos
import com.carlex.euia.utils.NotificationUtils // Para usar o ID do canal de notificação

class OverlayService : Service() {

    private val TAG = "OverlayService"
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var messageTextView: TextView

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.carlex.euia.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.carlex.euia.action.HIDE_OVERLAY"
        const val ACTION_UPDATE_MESSAGE = "com.carlex.euia.action.UPDATE_MESSAGE"
        const val EXTRA_OVERLAY_MESSAGE = "extra_overlay_message"

        private const val OVERLAY_NOTIFICATION_ID = 101 // ID único para a notificação do serviço
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Inflar o layout do overlay
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_layout, null) // Você precisará criar este layout XML
        messageTextView = overlayView.findViewById(R.id.overlay_message_text) // ID do TextView no layout

        // Configurar os parâmetros do layout do overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY // Para Android O e superior
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE // Para Android N e inferior
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Garante que respeite a barra de status/navegação
            PixelFormat.TRANSLUCENT // Permite transparência
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // Posição na tela
            x = 0
            y = 0 // Posição Y inicial (pode ser ajustada)
        }

        overlayView.layoutParams = params
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val message = intent.getStringExtra(EXTRA_OVERLAY_MESSAGE) ?: getString(R.string.overlay_default_message)
                messageTextView.text = message
                if (!overlayView.isAttachedToWindow) {
                    try {
                        windowManager.addView(overlayView, overlayView.layoutParams)
                        Log.i(TAG, "Overlay adicionado à janela.")
                    } catch (e: WindowManager.BadTokenException) {
                        Log.e(TAG, "BadTokenException ao adicionar overlay. Provavelmente o contexto da janela é inválido.", e)
                        stopSelf() // Parar o serviço se não puder adicionar o overlay
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao adicionar overlay: ${e.message}", e)
                        stopSelf()
                    }
                } else {
                    Log.d(TAG, "Overlay já está na janela, apenas atualizando texto.")
                }
                startForeground(OVERLAY_NOTIFICATION_ID, createForegroundNotification(message))
            }
            ACTION_HIDE_OVERLAY -> {
                if (overlayView.isAttachedToWindow) {
                    try {
                        windowManager.removeView(overlayView)
                        Log.i(TAG, "Overlay removido da janela.")
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "IllegalArgumentException ao remover overlay. View não estava anexada ou já foi removida.", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao remover overlay: ${e.message}", e)
                    }
                }
                stopForeground(true) // Remover notificação e parar serviço
                stopSelf() // Parar o serviço completamente
            }
            ACTION_UPDATE_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_OVERLAY_MESSAGE) ?: getString(R.string.overlay_default_message)
                messageTextView.text = message
                // Atualiza a notificação do Foreground Service também
                startForeground(OVERLAY_NOTIFICATION_ID, createForegroundNotification(message))
            }
        }

        return START_STICKY // O sistema tentará recriar o serviço se ele for morto
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        if (overlayView.isAttachedToWindow) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "IllegalArgumentException em onDestroy ao remover overlay.", e)
            }
        }
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Este serviço não é um serviço vinculado
    }

    private fun createForegroundNotification(message: String): Notification {
        // Esta notificação é para o Foreground Service em si, não o overlay visual
        return NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID_VIDEO_RENDER)
            .setContentTitle(getString(R.string.overlay_foreground_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}