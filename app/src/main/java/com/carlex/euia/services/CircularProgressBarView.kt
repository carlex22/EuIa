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

/**
 * Uma View customizada para exibir um progresso circular ao redor de um texto percentual.
 */
class CircularProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paint objects for drawing
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND // Para pontas arredondadas do arco
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND // Para pontas arredondadas do arco
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Retângulo para definir os limites do arco de progresso
    private val rectF = RectF()

    // Propriedades mutáveis para o progresso e aparência
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100) // Garante que o valor esteja entre 0 e 100
            invalidate() // Solicita um redesenho da View
        }
        
        
     var msg: String= ""
        set(value) {
            field = value // Garante que o valor esteja entre 0 e 100
            invalidate() // Solicita um redesenho da View
        }   

    // <<< INÍCIO DA CORREÇÃO: Renomeando as propriedades >>>
    var pbProgressColor: Int = Color.WHITE // Renomeado de progressColor
        set(value) {
            field = value
            progressPaint.color = field
            invalidate()
        }

    var pbBackgroundColor: Int = Color.DKGRAY // Renomeado de backgroundColor
        set(value) {
            field = value
            backgroundPaint.color = field
            invalidate()
        }

    var pbTextColor: Int = Color.WHITE // Renomeado de textColor
        set(value) {
            field = value
            textPaint.color = field
            invalidate()
        }

    var pbStrokeWidth: Float = dpToPx(8).toFloat() // Renomeado de strokeWidth
        set(value) {
            field = value
            backgroundPaint.strokeWidth = field
            progressPaint.strokeWidth = field
            invalidate()
        }

    var pbTextSize: Float = dpToPx(10).toFloat() // Renomeado de textSize
        set(value) {
            field = value
            textPaint.textSize = field
            invalidate()
        }
    // <<< FIM DA CORREÇÃO >>>

    init {
        // Configurações iniciais (usando os novos nomes das propriedades)
        pbProgressColor = Color.parseColor("#FFA000") // Laranja/Âmbar
        pbBackgroundColor = Color.parseColor("#80FFFFFF") // Branco semi-transparente para o fundo do arco
        pbTextColor = Color.WHITE
        pbStrokeWidth = dpToPx(8).toFloat()
        pbTextSize = dpToPx(14).toFloat() // Ajuste o tamanho do texto %
    }

    /** Helper para converter DP para Pixels */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /** Define o tamanho da View (um quadrado, para o círculo) */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = dpToPx(60) // Tamanho fixo para o círculo (ex: 120dp)
        setMeasuredDimension(desiredSize, desiredSize)
    }

    /** Chamado quando o tamanho da View muda, para recalcular o retângulo do arco */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfStroke = pbStrokeWidth / 2f // <<< Usando o novo nome >>>
        // Ajusta o retângulo para que o arco desenhe dentro dos limites da View
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
    }

    /** Lógica de desenho principal */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Desenha o círculo de fundo (trilho do progresso)
        canvas.drawArc(rectF, 0f, 360f, false, backgroundPaint)

        // Desenha o arco de progresso (começa do topo, 270 graus)
        val angle = 360 * progress / 100f
        canvas.drawArc(rectF, 270f, angle, false, progressPaint)

        // Desenha o texto percentual no centro
        val text = "$msg"
        val xPos = width / 2f
        // Calcula a posição Y para centralizar verticalmente o texto
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, xPos, yPos, textPaint)
    }
}