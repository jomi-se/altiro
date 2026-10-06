package org.altiro.app

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** Shared geometry keeps overlay and Compose controls in the same icon family. */
internal enum class Glyph {
    MIC,
    STOP,
    CLOSE,
    HOME,
    MODEL,
    CONSOLE,
    SETTINGS,
    GLOBE,
    CHECK,
    DOWNLOAD,
    COPY,
    SHARE,
    DELETE,
    ARROW,
    BACK,
}

internal fun drawGlyph(canvas: NativeCanvas, glyph: Glyph, color: Int) {
    val p =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, p)
    fun path(vararg coordinates: Float) {
        val shape =
            Path().apply {
                moveTo(coordinates[0], coordinates[1])
                for (i in 2 until coordinates.size step 2) lineTo(
                    coordinates[i],
                    coordinates[i + 1],
                )
            }
        canvas.drawPath(shape, p)
    }
    when (glyph) {
        Glyph.MIC -> {
            canvas.drawRoundRect(9f, 3f, 15f, 15f, 3f, 3f, p)
            canvas.drawArc(RectF(6f, 8f, 18f, 19f), 0f, 180f, false, p)
            line(6f, 10f, 6f, 13f)
            line(18f, 10f, 18f, 13f)
            line(12f, 19f, 12f, 22f)
        }
        Glyph.STOP -> {
            p.style = Paint.Style.FILL
            canvas.drawRoundRect(7f, 7f, 17f, 17f, 1.5f, 1.5f, p)
        }
        Glyph.CLOSE -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        Glyph.HOME -> {
            path(
                3f,
                11f,
                12f,
                3f,
                21f,
                11f,
                21f,
                21f,
                15f,
                21f,
                15f,
                15f,
                9f,
                15f,
                9f,
                21f,
                3f,
                21f,
                3f,
                11f,
            )
        }
        Glyph.MODEL -> {
            path(12f, 2f, 21f, 7f, 21f, 17f, 12f, 22f, 3f, 17f, 3f, 7f, 12f, 2f)
            path(3f, 7f, 12f, 12f, 21f, 7f)
            line(12f, 12f, 12f, 22f)
        }
        Glyph.CONSOLE -> {
            canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, p)
            path(7f, 9f, 10f, 12f, 7f, 15f)
            line(13f, 15f, 17f, 15f)
        }
        Glyph.SETTINGS -> {
            canvas.drawCircle(12f, 12f, 7f, p)
            canvas.drawCircle(12f, 12f, 2.5f, p)
            for (i in 0..7) {
                canvas.save()
                canvas.rotate(i * 45f, 12f, 12f)
                line(12f, 2f, 12f, 5f)
                canvas.restore()
            }
        }
        Glyph.GLOBE -> {
            canvas.drawCircle(12f, 12f, 9f, p)
            canvas.drawOval(8f, 3f, 16f, 21f, p)
            line(3f, 12f, 21f, 12f)
        }
        Glyph.CHECK -> path(5f, 12f, 10f, 17f, 19f, 7f)
        Glyph.DOWNLOAD -> {
            path(7f, 11f, 12f, 16f, 17f, 11f)
            line(12f, 3f, 12f, 16f)
            path(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f)
        }
        Glyph.COPY -> {
            canvas.drawRoundRect(8f, 7f, 21f, 21f, 2f, 2f, p)
            path(16f, 4f, 16f, 3f, 3f, 3f, 3f, 16f, 5f, 16f)
        }
        Glyph.SHARE -> {
            canvas.drawCircle(18f, 4f, 2.5f, p)
            canvas.drawCircle(5f, 12f, 2.5f, p)
            canvas.drawCircle(18f, 20f, 2.5f, p)
            line(7f, 11f, 16f, 5f)
            line(7f, 13f, 16f, 19f)
        }
        Glyph.DELETE -> {
            line(4f, 6f, 20f, 6f)
            path(8f, 6f, 8f, 3f, 16f, 3f, 16f, 6f)
            path(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f)
            line(10f, 10f, 10f, 17f)
            line(14f, 10f, 14f, 17f)
        }
        Glyph.ARROW -> path(9f, 5f, 16f, 12f, 9f, 19f)
        Glyph.BACK -> path(15f, 5f, 8f, 12f, 15f, 19f)
    }
}

internal class GlyphDrawable(private val glyph: Glyph, private val tint: Int) : Drawable() {
    override fun draw(canvas: NativeCanvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        drawGlyph(canvas, glyph, tint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(filter: android.graphics.ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = 24

    override fun getIntrinsicHeight() = 24
}

@Composable
internal fun AltiroIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier.size(24.dp),
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(size.width / 24f, size.height / 24f)
            drawGlyph(nc, glyph, color.toArgb())
            nc.restore()
        }
    }
}

@Composable
internal fun AltiroTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFFB6D4BE),
                onPrimary = Color(0xFF182E21),
                background = Color(0xFF1C2420),
                surface = Color(0xFF1C2420),
                surfaceVariant = Color(0xFF303A33),
                onSurface = Color(0xFFE7EEE5),
                onSurfaceVariant = Color(0xFFB8C5BB),
                outline = Color(0xFF84958A),
                secondaryContainer = Color(0xFF354C3C),
                onSecondaryContainer = Color(0xFFDCEAD9),
            )
        else
            lightColorScheme(
                primary = Color(0xFF385D48),
                onPrimary = Color(0xFFF4F6F0),
                background = Color(0xFFDCE2DA),
                surface = Color(0xFFDCE2DA),
                surfaceVariant = Color(0xFFF4F6F0),
                onSurface = Color(0xFF222621),
                onSurfaceVariant = Color(0xFF4C5C51),
                outline = Color(0xFF78867D),
                secondaryContainer = Color(0xFFCAD8CC),
                onSecondaryContainer = Color(0xFF243C2D),
            )
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background, content = content)
    }
}
