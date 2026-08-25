package com.rober.weeklybudget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale

/** Simple grouped bar chart: one income + one expense bar per week. */
class WeekBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bar(val label: String, val income: Long, val expense: Long)

    private var bars: List<Bar> = emptyList()
    private var capCents: Long = 0

    private val incomeColor = context.getColor(R.color.income_green)
    private val expenseColor = context.getColor(R.color.expense_red)
    private val capColor = context.getColor(R.color.cap_warn)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val capTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val linePaint = Paint()
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    init {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        val secondary = if (tv.resourceId != 0) context.getColor(tv.resourceId) else 0xFF888888.toInt()
        axisTextPaint.color = secondary
        linePaint.color = secondary
        linePaint.alpha = 50
        val sp = resources.displayMetrics.scaledDensity
        axisTextPaint.textSize = 10 * sp
        valueTextPaint.textSize = 9 * sp
        capTextPaint.textSize = 9 * sp
        capTextPaint.color = capColor
        capPaint.color = capColor
    }

    fun setData(data: List<Bar>) {
        bars = data
        invalidate()
    }

    fun setCap(cents: Long) {
        capCents = cents
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty() || width == 0 || height == 0) return

        val labelH = axisTextPaint.textSize * 1.8f
        val valueH = valueTextPaint.textSize * 1.5f
        val chartBottom = height - labelH
        val chartH = chartBottom - valueH
        if (chartH <= 0) return

        val maxVal = maxOf(
            bars.maxOf { maxOf(it.income, it.expense) },
            if (capCents > 0) capCents else 0L
        ).coerceAtLeast(1L)
        val slot = width.toFloat() / bars.size
        val barW = slot * 0.26f
        val gap = slot * 0.06f

        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, linePaint)

        if (capCents > 0) {
            val capY = chartBottom - chartH * capCents / maxVal
            val p = Path()
            p.moveTo(0f, capY)
            p.lineTo(width.toFloat(), capY)
            canvas.drawPath(p, capPaint)
            canvas.drawText("cap", width - 4f, capY - 5f, capTextPaint)
        }

        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            val incomeLeft = cx - gap / 2 - barW
            val expenseLeft = cx + gap / 2

            val ih = chartH * b.income / maxVal
            val eh = chartH * b.expense / maxVal

            if (b.income > 0) {
                barPaint.color = incomeColor
                canvas.drawRoundRect(
                    incomeLeft, chartBottom - ih, incomeLeft + barW, chartBottom, 6f, 6f, barPaint
                )
                valueTextPaint.color = incomeColor
                canvas.drawText(compact(b.income), incomeLeft + barW / 2, chartBottom - ih - 6f, valueTextPaint)
            }
            if (b.expense > 0) {
                barPaint.color = expenseColor
                canvas.drawRoundRect(
                    expenseLeft, chartBottom - eh, expenseLeft + barW, chartBottom, 6f, 6f, barPaint
                )
                valueTextPaint.color = expenseColor
                canvas.drawText(compact(b.expense), expenseLeft + barW / 2, chartBottom - eh - 6f, valueTextPaint)
            }
            canvas.drawText(b.label, cx, height - labelH / 2 + axisTextPaint.textSize / 2 - 2f, axisTextPaint)
        }
    }

    private fun compact(cents: Long): String {
        val d = cents / 100.0
        return when {
            d >= 10000 -> String.format(Locale.US, "$%.0fk", d / 1000)
            d >= 1000 -> String.format(Locale.US, "$%.1fk", d / 1000)
            else -> String.format(Locale.US, "$%.0f", d)
        }
    }
}
