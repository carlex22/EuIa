// File: euia/utils/CircularProgressBarView.kt
package com.carlex.euia.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.*

class CircularProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ... (as outras propriedades e o init permanecem os mesmos) ...
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rectF = RectF()
    private var animationJob: Job? = null
    private var startAngle = 270f

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }
        
    var msg: String= ""
        set(value) {
            field = value
            invalidate()
        }

    // <<< INÍCIO DA MUDANÇA >>>
    var isIndeterminate: Boolean = false
        set(value) {
            field = value
            if (value) {
                startIndeterminateAnimation()
            } else {
                stopIndeterminateAnimation()
            }
            invalidate() // Redesenha a view
        }
    // <<< FIM DA MUDANÇA >>>

    var pbProgressColor: Int = Color.WHITE
        set(value) {
            field = value
            progressPaint.color = field
            invalidate()
        }

    var pbBackgroundColor: Int = Color.DKGRAY
        set(value) {
            field = value
            backgroundPaint.color = field
            invalidate()
        }

    var pbTextColor: Int = Color.WHITE
        set(value) {
            field = value
            textPaint.color = field
            invalidate()
        }

    var pbStrokeWidth: Float = dpToPx(8).toFloat()
        set(value) {
            field = value
            backgroundPaint.strokeWidth = field
            progressPaint.strokeWidth = field
            invalidate()
        }

    var pbTextSize: Float = dpToPx(14).toFloat()
        set(value) {
            field = value
            textPaint.textSize = field
            invalidate()
        }

    init {
        pbProgressColor = Color.parseColor("#99eeFFFF")
        pbBackgroundColor = Color.parseColor("#80FFFFFF")
        pbTextColor = Color.WHITE
        pbStrokeWidth = dpToPx(3).toFloat()
        pbTextSize = dpToPx(14).toFloat()
    }
    
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = dpToPx(40)
        setMeasuredDimension(desiredSize, desiredSize)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfStroke = pbStrokeWidth / 2f
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
    }
    
    // <<< INÍCIO DAS NOVAS FUNÇÕES DE ANIMAÇÃO >>>
    private fun startIndeterminateAnimation() {
        animationJob?.cancel() // Cancela qualquer animação anterior
        animationJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                startAngle = (startAngle + 10) % 360 // Aumenta o ângulo inicial para girar
                invalidate() // Pede para redesenhar a view a cada frame
                delay(16) // Aproximadamente 60fps
            }
        }
    }

    private fun stopIndeterminateAnimation() {
        animationJob?.cancel()
        animationJob = null
        startAngle = 270f // Reseta o ângulo para a posição inicial
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopIndeterminateAnimation() // Garante que a coroutine pare quando a view é removida
    }
    // <<< FIM DAS NOVAS FUNÇÕES DE ANIMAÇÃO >>>

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)

        // <<< INÍCIO DA LÓGICA DE DESENHO ATUALIZADA >>>
        if (isIndeterminate) {
            // Desenha um arco de tamanho fixo (ex: 90 graus) que gira
            canvas.drawArc(rectF, startAngle, 90f, false, progressPaint)
        } else {
            // Desenha o arco de progresso normal, como antes
            val angle = 360 * progress / 100f
            canvas.drawArc(rectF, 270f, angle, false, progressPaint)
        }
        // <<< FIM DA LÓGICA DE DESENHO ATUALIZADA >>>

        val text = if (isIndeterminate || progress <= 0) msg else "$progress%"
        val xPos = width / 2f
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, xPos, yPos, textPaint)
    }
}