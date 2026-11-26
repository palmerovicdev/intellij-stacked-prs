package com.upstack.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Small colored dots for the filter options: one per color, side by side, centered in a 16px square. */
internal class DotsIcon(private val colors: List<Color>) : Icon {
    constructor(color: Color) : this(listOf(color))

    private val diameter get() = JBUI.scale(if (colors.size == 1) 8 else 5)
    private val gap get() = JBUI.scale(1)

    override fun getIconWidth(): Int = JBUI.scale(16)
    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val total = colors.size * diameter + (colors.size - 1) * gap
            var left = x + (iconWidth - total) / 2
            val top = y + (iconHeight - diameter) / 2
            for (color in colors) {
                g2.color = color
                g2.fillOval(left, top, diameter, diameter)
                left += diameter + gap
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** A label's own GitHub color (hex without `#`), or a neutral gray when it is missing or malformed. */
        fun forLabel(hex: String?): DotsIcon = DotsIcon(
            hex?.let { runCatching { Color(it.removePrefix("#").toInt(16)) }.getOrNull() } ?: UIUtil.getInactiveTextColor(),
        )
    }
}
