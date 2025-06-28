// File: euia/services/VideoProgressOverlayService.kt
package com.carlex.euia.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "OverlayService"
private const val NOTIFICATION_ID_OVERLAY = 1337
private const val NOTIFICATION_CHANNEL_ID_OVERLAY = "VideoProgressOverlayChannel"

class VideoProgressOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val progressFlow = MutableStateFlow(0f)

    // Implementação de LifecycleOwner e SavedStateRegistryOwner para o ComposeView
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    companion object {
        const val ACTION_START = "com.carlex.euia.START_OVERLAY"
        const val ACTION_STOP = "com.carlex.euia.STOP_OVERLAY"
        const val ACTION_UPDATE_PROGRESS = "com.carlex.euia.UPDATE_PROGRESS"
        const val ACTION_SHOW = "com.carlex.euia.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.carlex.euia.HIDE_OVERLAY"
        const val EXTRA_PROGRESS = "extra_progress"
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        when (action) {
            ACTION_START -> {
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                progressFlow.value = progress
                startOverlay()
            }
            ACTION_UPDATE_PROGRESS -> {
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                progressFlow.value = progress
            }
            ACTION_SHOW -> overlayView?.visibility = View.VISIBLE
            ACTION_HIDE -> overlayView?.visibility = View.GONE
            ACTION_STOP -> stopOverlay()
        }

        return START_NOT_STICKY
    }

    private fun startOverlay() {
        if (overlayView != null) return

        startForeground(NOTIFICATION_ID_OVERLAY, createNotification())

        overlayView = ComposeView(this).apply {
            // Configurar o ComposeView para ter um LifecycleOwner
            setViewTreeLifecycleOwner(this@VideoProgressOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VideoProgressOverlayService)
            setContent {
                val progress = progressFlow.value

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
                        .clickable {
                            // Ao clicar, abre o aplicativo
                            val openAppIntent = Intent(this@VideoProgressOverlayService, MainActivity::class.java)
                            openAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(openAppIntent)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(70.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 16.sp
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        try {
            overlayView?.visibility = View.GONE // Começa invisível
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar view de overlay", e)
        }
    }

    private fun stopOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover view de overlay", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): NotificationCompat.Builder {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_OVERLAY)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_content))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_OVERLAY,
                "Canal de Progresso de Vídeo",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopOverlay()
        Log.d(TAG, "OverlayService destruído.")
    }
}