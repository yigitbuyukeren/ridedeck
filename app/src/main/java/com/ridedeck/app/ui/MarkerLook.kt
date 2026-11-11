package com.ridedeck.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import com.ridedeck.app.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MarkerShape { MODERN, PUCK, CLASSIC }

/** Accent colours offered for the position marker (ARGB). */
val MARKER_COLORS = listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2, 0xFFF2F2F7)

data class MarkerLook(val shape: MarkerShape = MarkerShape.MODERN, val colorIndex: Int = 0) {
    val color: Int get() = MARKER_COLORS[colorIndex.coerceIn(0, MARKER_COLORS.lastIndex)].toInt()
}

/** The rider's chosen map marker; shared by the full map and the dashboard mini map. */
object MarkerStyle {
    private val _look = MutableStateFlow(MarkerLook())
    val look: StateFlow<MarkerLook> = _look

    fun load(context: Context) {
        val shape = runCatching { MarkerShape.valueOf(Prefs.markerShape(context)) }.getOrDefault(MarkerShape.MODERN)
        _look.value = MarkerLook(shape, Prefs.markerColor(context))
    }

    fun set(context: Context, look: MarkerLook) {
        _look.value = look
        Prefs.setMarker(context, look.shape.name, look.colorIndex)
    }
}

internal fun markerBitmap(look: MarkerLook, size: Int = 128): Bitmap = when (look.shape) {
    MarkerShape.MODERN -> modernMarker(look.color, size)
    MarkerShape.PUCK -> puckMarker(look.color, size)
    MarkerShape.CLASSIC -> classicMarker(look.color, size)
}

private fun shade(color: Int, factor: Float): Int {
    val hsv = FloatArray(3)
    AColor.colorToHSV(color, hsv)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    if (factor > 1f) hsv[1] = (hsv[1] / factor).coerceIn(0f, 1f)
    return AColor.HSVToColor(hsv)
}

/** A navigation chevron: white outline, soft shadow and a lit left facet. */
private fun modernMarker(color: Int, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = size / 128f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val tip = 12f * s
    val base = 108f * s
    val notch = 86f * s
    val mid = size / 2f
    val half = 36f * s
    val whole = Path().apply {
        moveTo(mid, tip); lineTo(mid + half, base); lineTo(mid, notch); lineTo(mid - half, base); close()
    }
    p.color = 0x66000000
    p.maskFilter = BlurMaskFilter(6f * s, BlurMaskFilter.Blur.NORMAL)
    c.save(); c.translate(0f, 4f * s); c.drawPath(whole, p); c.restore()
    p.maskFilter = null
    p.style = Paint.Style.FILL_AND_STROKE
    p.strokeWidth = 12f * s
    p.strokeJoin = Paint.Join.ROUND
    p.color = AColor.WHITE
    c.drawPath(whole, p)
    p.style = Paint.Style.FILL
    p.color = color
    c.drawPath(whole, p)
    val lit = Path().apply { moveTo(mid, tip); lineTo(mid, notch); lineTo(mid - half, base); close() }
    p.color = shade(color, 1.25f)
    c.drawPath(lit, p)
    return bmp
}

/** A dot with a glow and a small heading pointer. */
private fun puckMarker(color: Int, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = size / 128f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val mid = size / 2f
    p.color = (color and 0x00FFFFFF) or 0x33000000
    c.drawCircle(mid, mid, 58f * s, p)
    val pointer = Path().apply {
        moveTo(mid, 6f * s); lineTo(mid + 15f * s, 36f * s); lineTo(mid - 15f * s, 36f * s); close()
    }
    p.style = Paint.Style.FILL_AND_STROKE
    p.strokeWidth = 6f * s
    p.strokeJoin = Paint.Join.ROUND
    p.color = AColor.WHITE
    c.drawPath(pointer, p)
    p.style = Paint.Style.FILL
    p.color = color
    c.drawPath(pointer, p)
    p.color = 0x55000000
    p.maskFilter = BlurMaskFilter(4f * s, BlurMaskFilter.Blur.NORMAL)
    c.drawCircle(mid, mid + 3f * s, 27f * s, p)
    p.maskFilter = null
    p.color = AColor.WHITE
    c.drawCircle(mid, mid, 27f * s, p)
    p.color = color
    c.drawCircle(mid, mid, 19f * s, p)
    return bmp
}

/** The original marker: a light disc with an arrow. */
private fun classicMarker(color: Int, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = size / 128f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val mid = size / 2f
    paint.color = 0x55000000
    c.drawCircle(mid, mid + 3f * s, mid - 6f * s, paint)
    paint.color = 0xFFF2F2F7.toInt()
    c.drawCircle(mid, mid, mid - 8f * s, paint)
    val arrow = Path().apply {
        moveTo(mid, 26f * s)
        lineTo(mid + 25f * s, 92f * s)
        lineTo(mid, 78f * s)
        lineTo(mid - 25f * s, 92f * s)
        close()
    }
    paint.color = color
    c.drawPath(arrow, paint)
    return bmp
}
