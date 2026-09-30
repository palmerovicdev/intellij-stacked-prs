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

/** Una etiqueta en forma de pastilla: estado, label de GitHub, review o CI. */
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
            val arc = height
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            outline?.let {
                g2.color = it
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
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

        /** Una label de GitHub con su color. El texto, blanco o negro segun el fondo. */
        fun label(name: String, hex: String): Chip {
            val color = ColorUtil.fromHex(hex, null) ?: StackColors.DRAFT
            return Chip(name, if (ColorUtil.isDark(color)) Color.WHITE else Color.BLACK, color)
        }
    }
}
