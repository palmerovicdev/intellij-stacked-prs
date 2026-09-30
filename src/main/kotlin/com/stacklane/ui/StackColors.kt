package com.stacklane.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.PrDetails
import com.stacklane.stack.PrState
import com.stacklane.stack.StackLayer
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JLabel

/** Los colores de estado de GitHub, en sus variantes clara y oscura. */
internal object StackColors {
    val READY = JBColor(0x1A7F37, 0x3FB950)
    val DRAFT = JBColor(0x6E7781, 0x9198A1)
    val MERGED = JBColor(0x8250DF, 0xAB7DF8)
    val QUEUED = JBColor(0x9A6700, 0xD29922)
    val CLOSED = JBColor(0xCF222E, 0xF85149)
    val WARNING = JBColor(0xBC4C00, 0xDB6D28)
    val RAIL = JBColor(0xD0D7DE, 0x3D444D)
}

/** El estado de una capa tal y como se pinta: mezcla lo que sabe gh-stack con lo de GitHub. */
internal enum class LayerStatus(val color: JBColor, private val key: String) {
    MERGED(StackColors.MERGED, "status.merged"),
    QUEUED(StackColors.QUEUED, "status.queued"),
    CLOSED(StackColors.CLOSED, "status.closed"),
    DRAFT(StackColors.DRAFT, "status.draft"),
    READY(StackColors.READY, "status.ready"),

    /** Hay PR pero aun no se sabe si es draft (los datos de GitHub no han llegado). */
    OPEN(StackColors.READY, "status.open"),
    UNPUBLISHED(StackColors.DRAFT, "status.unpublished");

    val text: String get() = message(key)

    companion object {
        fun of(layer: StackLayer, details: PrDetails?): LayerStatus = when {
            layer.isMerged || details?.state == PrState.MERGED -> MERGED
            layer.isQueued -> QUEUED
            details?.state == PrState.CLOSED -> CLOSED
            layer.pr == null -> UNPUBLISHED
            details == null -> OPEN
            details.isDraft -> DRAFT
            else -> READY
        }
    }
}

/**
 * Una etiqueta en forma de pastilla: estado, label de GitHub, review o CI.
 *
 * La pastilla se pinta a su alto natural y centrada: los layouts que rellenan el alto de la
 * linea (el `HorizontalLayout` por defecto, la fila de una casilla) la estiraban hasta
 * dejarla gorda.
 */
internal class Chip(text: String, fg: Color, private val fill: Color, private val outline: Color? = null) : JLabel(text) {

    init {
        font = JBFont.small()
        foreground = fg
        border = JBUI.Borders.empty(1, 7)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val pill = minOf(height, preferredSize.height)
            val y = (height - pill) / 2
            g2.color = fill
            g2.fillRoundRect(0, y, width - 1, pill - 1, pill, pill)
            outline?.let {
                g2.color = it
                g2.drawRoundRect(0, y, width - 1, pill - 1, pill, pill)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        /** Pastilla tenue del color de un estado. */
        fun status(text: String, color: Color): Chip =
            Chip(text, color, ColorUtil.withAlpha(color, 0.12), ColorUtil.withAlpha(color, 0.45))

        /** Una label de GitHub con su color, pintada como la pinta GitHub. Ver [LabelStyle]. */
        fun label(name: String, hex: String): Chip {
            val style = LabelStyle.of(ColorUtil.fromHex(hex, null) ?: StackColors.DRAFT, dark = !JBColor.isBright())
            return Chip(name, style.text, style.fill, style.outline)
        }
    }
}

/**
 * Los colores de una label como los pinta GitHub (Primer).
 *
 * En tema oscuro, el color de la label de fondo muy tenue, el texto de su mismo tono aclarado
 * hasta que se lea y un borde fino. En claro, la pastilla solida con el texto blanco o negro
 * segun lo oscura que sea; las casi blancas llevan borde para no desaparecer en el fondo.
 */
internal class LabelStyle(val text: Color, val fill: Color, val outline: Color?) {

    companion object {
        fun of(color: Color, dark: Boolean): LabelStyle {
            val lightness = perceivedLightness(color)
            val (h, s, l) = hsl(color)
            return if (dark) {
                val lighten = (DARK_THRESHOLD - lightness).coerceAtLeast(0f)
                val text = fromHsl(h, s, (l + lighten).coerceAtMost(1f))
                LabelStyle(text, ColorUtil.withAlpha(color, 0.18), ColorUtil.withAlpha(text, 0.3))
            } else {
                val text = if (lightness < LIGHT_THRESHOLD) Color.WHITE else Color.BLACK
                val outline = if (lightness > BORDER_THRESHOLD) fromHsl(h, s, (l - 0.25f).coerceAtLeast(0f)) else null
                LabelStyle(text, color, outline)
            }
        }

        private const val DARK_THRESHOLD = 0.6f
        private const val LIGHT_THRESHOLD = 0.453f
        private const val BORDER_THRESHOLD = 0.96f

        private fun perceivedLightness(c: Color): Float = (c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f) / 255f

        private fun hsl(c: Color): FloatArray {
            val r = c.red / 255f
            val g = c.green / 255f
            val b = c.blue / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            if (max == min) return floatArrayOf(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            val h = when (max) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
            return floatArrayOf(h, s, l)
        }

        private fun fromHsl(h: Float, s: Float, l: Float): Color {
            if (s == 0f) return Color(l, l, l)
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            return Color(hue(p, q, h + 1f / 3), hue(p, q, h), hue(p, q, h - 1f / 3))
        }

        private fun hue(p: Float, q: Float, t: Float): Float {
            val x = if (t < 0) t + 1 else if (t > 1) t - 1 else t
            return when {
                x < 1f / 6 -> p + (q - p) * 6 * x
                x < 1f / 2 -> q
                x < 2f / 3 -> p + (q - p) * (2f / 3 - x) * 6
                else -> p
            }.coerceIn(0f, 1f)
        }
    }
}
