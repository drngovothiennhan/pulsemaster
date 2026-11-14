package com.yhct.pulsemaster.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.yhct.pulsemaster.core.*

/** Thanh lực ấn thời gian thực: 3 vùng Phù/Trung/Trầm, viền đậm quanh vùng mục tiêu, vạch đỏ là lực hiện tại. */
class ForceGaugeView(context: Context) : View(context) {
    var force: Double = 0.0
        set(v) { field = v.coerceIn(0.0, 1.0); postInvalidateOnAnimation() }
    var target: Depth? = null
        set(v) { field = v; postInvalidateOnAnimation() }
    var inZone: Boolean = false
        set(v) { field = v; postInvalidateOnAnimation() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = sp(12f); textAlign = Paint.Align.CENTER }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 47, 47) }
    private val colors = mapOf(
        Depth.NONE to Color.rgb(238, 238, 238),
        Depth.PHU to Color.rgb(187, 222, 251),
        Depth.TRUNG to Color.rgb(165, 214, 167),
        Depth.TRAM to Color.rgb(255, 204, 128)
    )

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 8f * resources.displayMetrics.density
        val top = pad; val bottom = h - pad
        fun yOf(f: Double) = (bottom - (bottom - top) * f).toFloat()
        stroke.strokeWidth = 2f; stroke.color = Color.GRAY
        for (d in Depth.entries) {
            val r = Zones.range(d)
            fill.color = colors.getValue(d)
            val rect = RectF(pad, yOf(r.endInclusive), w - pad, yOf(r.start))
            c.drawRect(rect, fill)
            if (d != Depth.NONE) c.drawText(d.label.substringBefore(' '), w / 2, rect.centerY() + text.textSize / 3, text)
        }
        target?.let {
            val r = Zones.range(it)
            stroke.strokeWidth = 5f * resources.displayMetrics.density / 2
            stroke.color = if (inZone) Color.rgb(46, 125, 50) else Color.rgb(21, 101, 192)
            c.drawRect(RectF(pad, yOf(r.endInclusive), w - pad, yOf(r.start)), stroke)
        }
        val y = yOf(force)
        c.drawRect(pad - 4, y - 3, w - pad + 4, y + 3, marker)
        c.drawCircle(w / 2, y, 7f * resources.displayMetrics.density / 2, marker)
    }
}

/**
 * Đồ thị sóng mạch 60 fps: vẽ lại theo từng frame (postInvalidateOnAnimation) từ chính đường cong
 * đang điều khiển động cơ rung, cửa sổ trượt [windowMs]. Giá trị = biên độ tức thời tại thời điểm t.
 */
class PulseWaveView(context: Context) : View(context) {
    private data class Rec(val start: Long, val peak: Double, val wave: BeatWaveform, val silent: Boolean)

    private val beats = ArrayDeque<Rec>()
    private val windowMs = 4000L
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(198, 40, 40); style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply { color = Color.rgb(224, 224, 224); strokeWidth = 1f }
    private val skip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 12f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER }
    private val path = Path()

    fun addBeat(e: BeatEvent) {
        val plan = e.plan ?: return
        beats.addLast(Rec(e.startMs, e.peak, plan.waveform, e.silent))
        postInvalidateOnAnimation()
    }

    fun clear() { beats.clear(); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        while (beats.isNotEmpty() && beats.first().start + beats.first().wave.durationMs < now - windowMs) beats.removeFirst()
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 6f * resources.displayMetrics.density
        for (i in 1..3) c.drawLine(0f, h * i / 4, w, h * i / 4, grid)

        path.rewind()
        val stepPx = 3f
        var x = 0f
        var first = true
        while (x <= w) {
            val t = now - windowMs + (x / w * windowMs).toLong()
            var v = 0.0
            for (b in beats) {
                val dt = (t - b.start).toDouble()
                if (dt >= 0 && dt < b.wave.durationMs) v = maxOf(v, b.wave.levelAt(dt) * b.peak)
            }
            val y = h - pad - (h - 2 * pad) * v.toFloat()
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += stepPx
        }
        c.drawPath(path, line)
        // đánh dấu nhịp bị bỏ (Xúc/Kết/Đại)
        for (b in beats) if (b.silent) {
            val bx = ((b.start - (now - windowMs)).toFloat() / windowMs) * w
            if (bx in 0f..w) c.drawText("nghỉ", bx, pad + skip.textSize, skip)
        }
        if (beats.isNotEmpty()) postInvalidateOnAnimation() // 60 fps theo vsync, nghỉ khi không còn nhịp
    }
}

interface PadTouchListener {
    fun onPadTouch(region: Region, event: MotionEvent)
    fun onPadRelease(region: Region)
}

/** Vùng bắt mạch cho một bộ (Thốn/Quan/Xích). Chỉ chuyển sự kiện cảm ứng; logic nằm ở transport. */
class PulsePadView(context: Context, val region: Region) : View(context) {
    var touchListener: PadTouchListener? = null
    var depth: Depth = Depth.NONE
        set(v) { field = v; postInvalidateOnAnimation() }
    var highlighted: Boolean = false
        set(v) { field = v; postInvalidateOnAnimation() }
    /** Chế độ kéo dọc: vẽ vạch ranh giới Phù/Trung/Trầm theo chiều cao pad. */
    var dragMode: Boolean = false
        set(v) { field = v; postInvalidateOnAnimation() }

    private val zoneLine = Paint().apply { color = Color.LTGRAY; strokeWidth = 2f }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textAlign = Paint.Align.RIGHT; textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textAlign = Paint.Align.CENTER; textSize = 18f * resources.displayMetrics.scaledDensity
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textAlign = Paint.Align.CENTER; textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        bg.color = when (depth) {
            Depth.NONE -> Color.rgb(250, 250, 250)
            Depth.PHU -> Color.rgb(187, 222, 251)
            Depth.TRUNG -> Color.rgb(165, 214, 167)
            Depth.TRAM -> Color.rgb(255, 204, 128)
        }
        val r = RectF(4 * d, 4 * d, width - 4 * d, height - 4 * d)
        c.drawRoundRect(r, 16 * d, 16 * d, bg)
        border.strokeWidth = if (highlighted) 4 * d else 1.5f * d
        border.color = if (highlighted) Color.rgb(21, 101, 192) else Color.LTGRAY
        c.drawRoundRect(r, 16 * d, 16 * d, border)
        if (dragMode) {
            val names = listOf("Trầm", "Trung", "Phù")
            val tops = listOf(1.0, Zones.range(Depth.TRUNG).endInclusive, Zones.range(Depth.PHU).endInclusive)
            for (i in 0..2) {
                val y = (height * (1.0 - tops[i])).toFloat()
                if (i > 0) c.drawLine(4 * d, y, width - 4 * d, y, zoneLine)
                c.drawText(names[i], width - 10 * d, y + 16 * d, zoneText)
            }
        }
        c.drawText(region.label, width / 2f, height / 2f, label)
        c.drawText(depth.label.substringBefore(' '), width / 2f, height / 2f + 22 * d, sub)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchListener?.onPadTouch(region, e)
            }
            MotionEvent.ACTION_MOVE -> touchListener?.onPadTouch(region, e)
            MotionEvent.ACTION_UP -> { touchListener?.onPadRelease(region); performClick() }
            MotionEvent.ACTION_CANCEL -> touchListener?.onPadRelease(region)
            else -> return false
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
