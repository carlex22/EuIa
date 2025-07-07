// File: euia/services/OverlayService.kt
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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.carlex.euia.R
import com.carlex.euia.utils.NotificationUtils
import com.carlex.euia.utils.CircularProgressBarView
import kotlin.math.abs

class OverlayService : Service() {

    private val TAG = "OverlayService"
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var messageTextView: TextView
    private lateinit var circularProgressBar: CircularProgressBarView // <<< NOVO: Sua View customizada >>>
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
    private var overlayMessagePercent = "" // Manter para consistência de dados, mas não será exibido por um TextView separado
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
        val progressValue = intent?.getStringExtra(EXTRA_OVERLAY_PROGRESSO)?.toIntOrNull() ?: 0
        val porcentagem = "$message" // Isso será usado internamente pela CircularProgressBarView

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                if (!isOverlayShowing && !isTrash) {
                    overlayMessageText = message
                    if (progressValue>0)
                    overlayProgressBar = progressValue
                    overlayMessagePercent = message // Atribuído, mas não usado por TextView separado
                    Log.d(TAG, "ACTION_SHOW_OVERLAY overlayProgressBar: $overlayProgressBar")
                    showOverlay()
                    isOverlayShowing = true
                    isTrash = false
                    startForeground(OVERLAY_NOTIFICATION_ID, createForegroundNotification(message))
                }
            }
            ACTION_HIDE_OVERLAY -> {
                if (isOverlayShowing) {
                    Log.d(TAG, "ACTION_HIDE_OVERLAY")
                    hideOverlay()
                    isOverlayShowing = false
                    isTrash = false
                    stopForeground(true)
                    stopSelf()
                }
            }
            ACTION_UPDATE_MESSAGE -> {
                if (!isTrash && isOverlayShowing) {
                    Log.d(TAG, "ACTION_UPDATE_MESSAGE: $message, Progress: $progressValue")
                    overlayMessageText = message
                    if (progressValue>-1)
                    overlayProgressBar = progressValue
                    overlayMessagePercent = message
                    updateOverlayContent()
                } else {
                    Log.d(TAG, "ACTION_UPDATE_MESSAGE ignored: isTrash=$isTrash or !isOverlayShowing=$isOverlayShowing")
                }
            }
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
            // Sem padding aqui, o padding será aplicado ao contêiner principal do overlayView
            gravity = Gravity.CENTER_HORIZONTAL // Centraliza os elementos filhos horizontalmente
            // O background será aplicado ao 'overlayView' no final
        }

        // TextView para a mensagem principal (ex: "Importando dados da URL...")
        /*messageTextView = TextView(this).apply {
            text = overlayMessageText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            // Adiciona um fundo para a mensagem principal para legibilidade
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#80000000")) // Preto semi-transparente
            }
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            visibility = View.GONE // Inicialmente escondido, aparece se houver mensagem
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8) // Espaço abaixo da mensagem
            }
        }
        mainLayout.addView(messageTextView)*/

        // Sua nova CircularProgressBarView
        circularProgressBar = CircularProgressBarView(this).apply {
            progress = overlayProgressBar // Define o progresso inicial
            // As cores e largura da linha são configuradas no init da própria classe CircularProgressBarView
            // ou podem ser definidas aqui se você quiser sobrescrever os defaults dela.
        }
        mainLayout.addView(circularProgressBar)

        // O percentTextView e o ProgressBar linear foram removidos.

        overlayView = mainLayout.apply {
            // Fundo principal para todo o overlay, para dar a forma redonda/oval e ser arrastável
            background = createRoundedBackgroundForOverlay() // Corrigido para usar a nova função
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8)) // Padding em torno dos elementos internos
        }

        overlayView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }
    }

    private fun createTrashView() {
        val trashImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)) // Aumentado ligeiramente para facilitar o toque
            setImageResource(android.R.drawable.ic_menu_delete)
            background = createCircularBackground() // Reutiliza a função existente para o círculo da lixeira
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            alpha = 0.7f
        }
        trashView = trashImageView
    }

    // <<< NOVA FUNÇÃO: Fundo para o overlay principal (oval/redondo) >>>
    private fun createRoundedBackgroundForOverlay(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL // Pode ser OVAL ou RECTANGLE com cornerRadius bem grande para um "pill" shape
            setColor(Color.parseColor("#CC000000")) // Fundo preto semi-transparente para o container
            // Removendo cornerRadius para OVAL shape, pois ele não é usado para OVAL.
            // Para ter um pill shape, seria shape = GradientDrawable.RECTANGLE e um cornerRadius alto.
            // Para redondo perfeito, apenas OVAL é suficiente, sem cornerRadius.
        }
    }

    // A função original `createRoundedBackground` que fazia o canto arredondado foi removida.
    // A `createCircularBackground` ainda é usada para a lixeira.
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
            WindowManager.LayoutParams.WRAP_CONTENT, // Adapta-se ao conteúdo
            WindowManager.LayoutParams.WRAP_CONTENT, // Adapta-se ao conteúdo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100 // Posição inicial X
        layoutParams.y = 100 // Posição inicial Y
        
        try {
            windowManager.addView(overlayView, layoutParams)
            updateOverlayContent() // Garante que o conteúdo inicial seja atualizado
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
        // CORREÇÃO AQUI: Substituir `appContext` por `this`
        circularProgressBar.msg = overlayMessageText
        if (overlayProgressBar>0)
        circularProgressBar.progress = overlayProgressBar // Atualiza o progresso na nova View
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
        // CORREÇÃO: Acesso correto ao y da localização na tela
        val trashY = location[1] // Era location[0], o que seria o X novamente
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