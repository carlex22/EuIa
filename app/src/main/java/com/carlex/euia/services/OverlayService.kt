package com.carlex.euia.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.carlex.euia.R
import com.carlex.euia.utils.NotificationUtils
import kotlin.math.abs

class OverlayService : Service() {

    private val TAG = "OverlayService"
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var messageTextView: TextView
    private lateinit var percentTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var trashView: View
    
    var isOverlayShowing = false
    private var isTrash = false
    
    // Variáveis para arrastar o overlay
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    // **Variáveis de conteúdo do overlay**
    private var overlayMessageText = ""
    private var overlayMessagePercent = ""
    private var overlayProgressBar = 0

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.carlex.euia.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.carlex.euia.action.HIDE_OVERLAY"
        const val ACTION_UPDATE_MESSAGE = "com.carlex.euia.action.UPDATE_MESSAGE"
        const val EXTRA_OVERLAY_MESSAGE = "extra_overlay_message"
        const val EXTRA_OVERLAY_PROGRESSO = "extra_overlay_progresso"
        private const val OVERLAY_NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()
        createTrashView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_OVERLAY_MESSAGE) ?: ""
        // **CORRIGIDO: Usar getStringExtra e converter para Int**
        val progressValue = intent?.getStringExtra(EXTRA_OVERLAY_PROGRESSO)?.toIntOrNull() ?: 0
        val porcentagem = "$progressValue%"

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                if (!isOverlayShowing && !isTrash) {
                    overlayMessageText = message
                    overlayProgressBar = progressValue
                    overlayMessagePercent = porcentagem
                    Log.d(TAG, "ACTION_SHOW_OVERLAY overlayProgressBar")
                    showOverlay()
                    isOverlayShowing = true
                    isTrash = false
                    startForeground(OVERLAY_NOTIFICATION_ID, createForegroundNotification(message))
                }
            } // **CORRIGIDO: Fechamento da chave**

            ACTION_HIDE_OVERLAY -> {
                if (isOverlayShowing) {
                    Log.d(TAG, "ACTION_HIDE_OVERLAY")
                    hideOverlay()
                    isOverlayShowing = false
                    isTrash = false
                    stopForeground(true)
                    stopSelf()
                }
            } // **CORRIGIDO: Fechamento da chave**

            ACTION_UPDATE_MESSAGE -> {
                if (!isTrash && isOverlayShowing) {
                    Log.d(TAG, "ACTION_UPDATE_MESSAGE $message")
                    overlayMessageText = message
                    overlayProgressBar = progressValue
                    overlayMessagePercent = porcentagem
                    updateOverlayContent()
                } else {
                    Log.d(TAG, "ACTION_UPDATE_MESSAGE isTrash")
                }
            } // **CORRIGIDO: Fechamento da chave**
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        
        try {
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
            }
            if (::trashView.isInitialized && trashView.isAttachedToWindow) {
                windowManager.removeView(trashView)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException em onDestroy ao remover overlay.", e)
        }
        
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createOverlayView() {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            background = createRoundedBackground()
        }

        messageTextView = TextView(this).apply {
            text = overlayMessageText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(200), dpToPx(8)).apply {
                topMargin = dpToPx(8)
            }
            max = 100
            progress = overlayProgressBar
        }

        percentTextView = TextView(this).apply {
            text = overlayMessagePercent
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
        }

        mainLayout.addView(messageTextView)
        mainLayout.addView(progressBar)
        mainLayout.addView(percentTextView)
        overlayView = mainLayout

        overlayView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }
    }

    private fun createTrashView() {
        val trashImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
            setImageResource(android.R.drawable.ic_menu_delete)
            background = createCircularBackground()
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            alpha = 0.7f
        }
        trashView = trashImageView
    }

    private fun createRoundedBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#80000000"))
        }
    }

    private fun createCircularBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC000000"))
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 100
        
        try {
            windowManager.addView(overlayView, layoutParams)
            updateOverlayContent()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar overlay", e)
        }
    }

    private fun hideOverlay() {
        try {
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
            }
            if (::trashView.isInitialized && trashView.isAttachedToWindow) {
                windowManager.removeView(trashView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover overlay", e)
        }
    }

    private fun updateOverlayContent() {
        messageTextView.text = overlayMessageText
        percentTextView.text = overlayMessagePercent
        progressBar.progress = overlayProgressBar
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                val layoutParams = overlayView.layoutParams as WindowManager.LayoutParams
                initialX = layoutParams.x
                initialY = layoutParams.y
                isDragging = false
                showTrash()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                
                if (!isDragging && (abs(deltaX) > 10 || abs(deltaY) > 10)) {
                    isDragging = true
                }
                
                if (isDragging) {
                    val layoutParams = overlayView.layoutParams as WindowManager.LayoutParams
                    layoutParams.x = (initialX + deltaX).toInt()
                    layoutParams.y = (initialY + deltaY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    checkTrashCollision(event.rawX, event.rawY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    if (isOverTrash(event.rawX, event.rawY)) {
                        val intent = Intent(this, OverlayService::class.java)
                        intent.action = ACTION_HIDE_OVERLAY
                        startService(intent)
                    }
                }
                hideTrash()
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun showTrash() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = 50
        layoutParams.y = 50
        
        try {
            if (!trashView.isAttachedToWindow) {
                windowManager.addView(trashView, layoutParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mostrar lixeira", e)
        }
    }

    private fun hideTrash() {
        try {
            if (::trashView.isInitialized && trashView.isAttachedToWindow) {
                windowManager.removeView(trashView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao esconder lixeira", e)
        }
    }

    private fun checkTrashCollision(x: Float, y: Float) {
        if (isOverTrash(x, y)) {
            trashView.alpha = 1.0f
            isTrash = true
        } else {
            trashView.alpha = 0.7f
            isTrash = false
        }
    }

    private fun isOverTrash(x: Float, y: Float): Boolean {
        if (!::trashView.isInitialized || !trashView.isAttachedToWindow) return false
        
        val location = IntArray(2)
        trashView.getLocationOnScreen(location)
        val trashX = location[0]
        val trashY = location[1]
        val trashWidth = trashView.width
        val trashHeight = trashView.height
        
        return x >= trashX && x <= trashX + trashWidth && 
               y >= trashY && y <= trashY + trashHeight
    }

    private fun createForegroundNotification(message: String): Notification {
        return NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID_VIDEO_RENDER)
            .setContentTitle(getString(R.string.overlay_foreground_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
