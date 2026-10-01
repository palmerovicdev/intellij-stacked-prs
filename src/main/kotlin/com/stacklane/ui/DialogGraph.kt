package com.stacklane.ui

import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.stacklane.StacklaneBundle.message
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * La pila dibujada como en la ventana Stacks, dentro de un dialogo (*Publish Stack…*, *Merge…*):
 * una fila de [graphRow] por capa, de arriba abajo, y el trunk al final. Sigue el ancho del
 * dialogo y, si la pila es alta, se desplaza.
 */
internal class DialogGraph : JPanel(VerticalLayout(0)), Scrollable {

    init {
        isOpaque = false
    }

    /** La ultima fila: el trunk, con su pastilla *base*. */
    fun addBase(trunk: String, lineAbove: Boolean) {
        val base = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(JLabel(trunk))
            add(Chip.status(message("layer.trunk"), StackColors.DRAFT))
        }
        add(graphRow(base, null, null, Rail.Node(Rail.Shape.SQUARE, StackColors.DRAFT), lineAbove, lineBelow = false))
    }

    override fun getPreferredScrollableViewportSize(): Dimension =
        preferredSize.let { Dimension(it.width, minOf(it.height, JBUI.scale(MAX_HEIGHT))) }

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val MAX_HEIGHT = 420
    }
}
