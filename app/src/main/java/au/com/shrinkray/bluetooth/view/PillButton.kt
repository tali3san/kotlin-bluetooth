package au.com.shrinkray.bluetooth.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.support.v7.widget.AppCompatButton
import android.util.AttributeSet
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.view.setPadding
import au.com.shrinkray.bluetooth.R


class PillButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                           defStyle: Int = android.support.v7.appcompat.R.attr.buttonStyle) : AppCompatButton(context, attrs, defStyle) {

    companion object {
        val PILL_BUTTON_DEFAULT_FILL_COLOR = Color.parseColor("#FF3E54")
    }

    private var fillColor: Int = PILL_BUTTON_DEFAULT_FILL_COLOR
    private var borderColor: Int = Color.TRANSPARENT
    private var borderWidth: Float = 0f

    init {
        attrs?.run {
            context.obtainStyledAttributes(attrs, R.styleable.PillButton, defStyle, 0)?.run {
                fillColor = getColor(R.styleable.PillButton_pb_fillColor, PILL_BUTTON_DEFAULT_FILL_COLOR)
                borderColor = getColor(R.styleable.PillButton_pb_borderColor, Color.TRANSPARENT)
                borderWidth = getDimension(R.styleable.PillButton_pb_borderWidth, 0f)
            }
        }

        val states = arrayOf(
                arrayOf(-android.R.attr.state_enabled).toIntArray(),
                arrayOf(android.R.attr.state_pressed).toIntArray(),
                arrayOf<Int>().toIntArray())

        background = StateListDrawable().apply {
            addState(states[0], PillDrawable(context, fillColor, borderColor, borderWidth).apply { alpha = 72 })
            addState(states[1], PillDrawable(context, fillColor, borderColor, borderWidth).apply { alpha = 212 })
            addState(states[2], PillDrawable(context, fillColor, borderColor, borderWidth))
        }

        val textColor = currentTextColor
        val pressedTextColor = Color.argb(212, currentTextColor.red, currentTextColor.green, currentTextColor.blue)
        val disabledTextColor = Color.argb(72, currentTextColor.red, currentTextColor.green, currentTextColor.blue)

        val colors = arrayOf(disabledTextColor, pressedTextColor, textColor).toIntArray()

        setTextColor(ColorStateList(states, colors))

        setPadding(0)
    }
}

class PillDrawable(val context: Context, private val fillColor: Int, private val borderColor: Int, private val borderWidth: Float) : Drawable() {

    private var alphaValue: Int = 0xff
    private var colorFilterValue: ColorFilter? = null

    private var fillPaint: Paint? = null
    private var pathPaint: Paint? = null

    private val fillPath = Path()
    private val borderPath = Path()

    init {
        createPaint()
    }

    private fun createPaint() {
        fillPaint = Paint().apply {
            color = fillColor
            style = Paint.Style.FILL
            isAntiAlias = true
            alpha = fillColor.alpha * alphaValue / 255
        }
        pathPaint = Paint().apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            isAntiAlias = true
            alpha = borderColor.alpha * alphaValue / 255
        }
    }

    override fun onBoundsChange(bounds: Rect?) {
        super.onBoundsChange(bounds)
        bounds?.let {
            val widthF = it.width().toFloat()
            val heightF = it.height().toFloat()
            val inset = borderWidth / 2
            fillPath.reset()
            fillPath.arcTo(0f, 0f, heightF, heightF, 90f, 190f, true)
            fillPath.lineTo(widthF - heightF, 0f)
            fillPath.arcTo(widthF - heightF, 0f, widthF, heightF, 270f, 180f, false)
            fillPath.close()
            borderPath.reset()
            borderPath.arcTo(inset, inset, heightF - inset, heightF - inset, 90f, 190f, true)
            borderPath.lineTo(widthF - heightF - inset, inset)
            borderPath.arcTo(widthF - heightF, inset, widthF - inset, heightF - inset, 270f, 180f, false)
            borderPath.close()
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        createPaint()
    }

    override fun getAlpha(): Int {
        return alphaValue
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorFilterValue = colorFilter
        createPaint()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun draw(canvas: Canvas?) {
        canvas?.apply {
            drawPath(fillPath, fillPaint)
            drawPath(borderPath, pathPaint)
        }
    }

}
