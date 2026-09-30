package com.stacklane.ui

import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.ChecksState
import com.stacklane.stack.PrDetails
import com.stacklane.stack.ReviewDecision
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Pinta la pila como un grafo vertical: un rail a la izquierda que une las capas con el
 * trunk, y a la derecha rama, PR, estado, titulo, labels, review y CI.
 *
 * Se construye un componente nuevo por fila: la lista tiene pocas filas y asi no hay
 * estado que limpiar entre una y otra.
 */
internal class StackRowRenderer : ListCellRenderer<StackRow> {

    override fun getListCellRendererComponent(
        list: JList<out StackRow>,
        value: StackRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val colors = RowColors(
            background = RenderingUtil.getBackground(list, isSelected),
            primary = RenderingUtil.getForeground(list, isSelected),
            secondary = if (isSelected) RenderingUtil.getForeground(list, true) else NamedColorUtil.getInactiveTextColor(),
        )
        return when (value) {
            is StackRow.Layer -> layerRow(value, colors)
            is StackRow.Trunk -> trunkRow(value, colors)
            is StackRow.Local -> localRow(value, colors)
        }
    }

    private class RowColors(val background: Color, val primary: Color, val secondary: Color)

    private fun layerRow(row: StackRow.Layer, colors: RowColors): JComponent {
        val layer = row.layer
        val status = row.status

        val first = JPanel(BorderLayout()).apply { isOpaque = false }
        val left = JPanel(HorizontalLayout(JBUI.scale(6))).apply { isOpaque = false }
        left.add(JLabel(layer.branch).apply {
            foreground = if (layer.isMerged) colors.secondary else colors.primary
            font = if (layer.isCurrent) JBFont.label().asBold() else JBFont.label()
        })
        layer.pr?.let { pr -> left.add(JLabel("#${pr.number}").apply { foreground = colors.secondary }) }
        left.add(Chip.status(status.text, status.color))
        if (layer.needsRebase) left.add(Chip.status(message("layer.needs.rebase"), StackColors.WARNING))
        first.add(left, BorderLayout.WEST)
        if (layer.isCurrent) first.add(Chip.status(message("layer.head"), colors.secondary), BorderLayout.EAST)

        val second = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        val title = when {
            layer.pr == null -> message("layer.unpublished")
            row.details != null -> row.details.title
            row.detailsLoading -> message("layer.loading")
            else -> ""
        }
        second.add(JLabel(title).apply {
            foreground = colors.secondary
            font = if (layer.pr == null) JBFont.small().asItalic() else JBFont.small()
        }, BorderLayout.CENTER)
        row.details?.let { second.add(badges(it), BorderLayout.EAST) }

        return graphRow(
            first, second, colors.background,
            Rail.Node(if (layer.isCurrent) Rail.Shape.FILLED else Rail.Shape.RING, status.color),
            lineAbove = !row.isTop,
            lineBelow = true,
        )
    }

    private fun badges(details: PrDetails): JComponent {
        val panel = JPanel(HorizontalLayout(JBUI.scale(4))).apply { isOpaque = false }
        val visible = details.labels.take(MAX_LABELS)
        visible.forEach { panel.add(Chip.label(it.name, it.color)) }
        if (details.labels.size > visible.size) {
            panel.add(Chip.status("+${details.labels.size - visible.size}", StackColors.DRAFT))
        }
        details.review?.let { review ->
            val color = when (review) {
                ReviewDecision.APPROVED -> StackColors.READY
                ReviewDecision.CHANGES_REQUESTED -> StackColors.CLOSED
                ReviewDecision.REVIEW_REQUIRED -> StackColors.DRAFT
            }
            panel.add(Chip.status(reviewText(review), color))
        }
        details.checks?.let { checks ->
            val color = when (checks) {
                ChecksState.SUCCESS -> StackColors.READY
                ChecksState.FAILURE -> StackColors.CLOSED
                ChecksState.PENDING -> StackColors.QUEUED
            }
            panel.add(Chip.status(checksText(checks), color))
        }
        return panel
    }

    private fun trunkRow(row: StackRow.Trunk, colors: RowColors): JComponent {
        val first = JPanel(HorizontalLayout(JBUI.scale(6))).apply { isOpaque = false }
        first.add(JLabel(row.name).apply {
            foreground = colors.primary
            font = if (row.isCurrent) JBFont.label().asBold() else JBFont.label()
        })
        first.add(Chip.status(message("layer.trunk"), StackColors.DRAFT))
        return graphRow(first, null, colors.background, Rail.Node(Rail.Shape.SQUARE, StackColors.DRAFT), lineAbove = true, lineBelow = false)
    }

    private fun localRow(row: StackRow.Local, colors: RowColors): JComponent {
        val entry = row.entry
        val stack = entry.stack
        val first = JPanel(HorizontalLayout(JBUI.scale(6))).apply { isOpaque = false }
        first.add(JLabel(stack.branches.last()).apply {
            foreground = if (entry.isStale) colors.secondary else colors.primary
        })
        first.add(Chip.status(message("local.layers", stack.branches.size), StackColors.DRAFT))
        when {
            entry.isStale -> first.add(Chip.status(message("local.stale"), StackColors.WARNING))
            entry.localBranches.isEmpty() -> first.add(Chip.status(message("local.remote.only"), StackColors.QUEUED))
        }
        val second = JLabel((listOf(stack.trunk) + stack.branches).joinToString(" ← ")).apply {
            foreground = colors.secondary
            font = JBFont.small()
        }
        val color = if (entry.isStale) StackColors.WARNING else StackColors.DRAFT
        return graphRow(first, second, colors.background, Rail.Node(Rail.Shape.RING, color), lineAbove = false, lineBelow = false)
    }

    private companion object {
        const val MAX_LABELS = 3
    }
}

/**
 * Una fila del grafo: el [Rail] a la izquierda y una o dos lineas de texto. La usan la
 * ventana Stacks y la vista previa del dialogo de pila nueva, para que se vean igual.
 * Sin [background], la fila es transparente.
 */
internal fun graphRow(
    first: JComponent,
    second: JComponent?,
    background: Color?,
    node: Rail.Node,
    lineAbove: Boolean,
    lineBelow: Boolean,
): JComponent {
    val lines = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 0, 6, 10)
        first.alignmentX = Component.LEFT_ALIGNMENT
        add(first)
        if (second != null) {
            second.alignmentX = Component.LEFT_ALIGNMENT
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(second)
        }
    }
    val rail = Rail(node, lineAbove, lineBelow) { lines.y + first.y + first.height / 2 }
    return JPanel(BorderLayout()).apply {
        if (background != null) this.background = background else isOpaque = false
        add(rail, BorderLayout.WEST)
        add(lines, BorderLayout.CENTER)
    }
}

/** La columna del grafo: una linea continua entre filas y el nodo de la capa. */
internal class Rail(
    private val node: Node,
    private val lineAbove: Boolean,
    private val lineBelow: Boolean,
    private val centerY: () -> Int,
) : JComponent() {

    enum class Shape { FILLED, RING, SQUARE }

    class Node(val shape: Shape, val color: Color)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(28), JBUI.scale(16))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val x = width / 2
            val y = centerY()
            val radius = JBUI.scale(5)
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.color = StackColors.RAIL
            if (lineAbove) g2.drawLine(x, 0, x, y - radius)
            if (lineBelow) g2.drawLine(x, y + radius, x, height)
            g2.color = node.color
            when (node.shape) {
                Shape.FILLED -> g2.fillOval(x - radius, y - radius, radius * 2, radius * 2)
                Shape.RING -> g2.drawOval(x - radius + 1, y - radius + 1, radius * 2 - 2, radius * 2 - 2)
                Shape.SQUARE -> g2.fillRoundRect(x - radius, y - radius, radius * 2, radius * 2, JBUI.scale(3), JBUI.scale(3))
            }
        } finally {
            g2.dispose()
        }
    }
}
