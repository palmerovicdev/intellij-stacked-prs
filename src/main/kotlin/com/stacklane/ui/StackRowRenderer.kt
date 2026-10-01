package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.PrDetails
import com.stacklane.stack.PrSize
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Pinta la pila como un grafo vertical: un rail a la izquierda que une las capas con el
 * trunk, y a la derecha rama, PR, estado, titulo, labels, review y CI.
 *
 * **La fila nunca se sale del ancho de la lista.** La primera linea cede el nombre de la rama,
 * que se corta con puntos suspensivos; el titulo se envuelve en hasta [MAX_LINES] lineas y, si
 * se deja algo fuera, un chevron al final lo despliega entero ([isExpanded]); los distintivos
 * van a la derecha del titulo si caben con el en una linea, y si no, debajo, en tantas filas
 * como haga falta. Para eso la lista sigue el ancho del viewport y se vuelve a medir cuando
 * cambia (ver `StackList`).
 *
 * Se construye un componente nuevo por fila: la lista tiene pocas filas y asi no hay
 * estado que limpiar entre una y otra.
 */
internal class StackRowRenderer(private val isExpanded: (StackRow) -> Boolean) : ListCellRenderer<StackRow> {

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
        val insets = list.insets
        val width = graphContentWidth(list.width - insets.left - insets.right)
        return when (value) {
            is StackRow.Layer -> layerRow(value, colors, width, isExpanded(value))
            is StackRow.Trunk -> trunkRow(value, colors, width)
            is StackRow.Local -> localRow(value, colors, width, isExpanded(value))
        }
    }

    private class RowColors(val background: Color, val primary: Color, val secondary: Color)

    private fun layerRow(row: StackRow.Layer, colors: RowColors, width: Int, expanded: Boolean): JComponent {
        val layer = row.layer
        val status = row.status

        val missing = row.missing
        val name = JLabel(layer.branch).apply {
            foreground = if (layer.isMerged || missing != null) colors.secondary else colors.primary
            font = if (layer.isCurrent) JBFont.label().asBold() else JBFont.label()
        }
        val badges = listOfNotNull(
            layer.pr?.let { pr -> JLabel("#${pr.number}").apply { foreground = colors.secondary } },
            Chip.status(status.text, status.color),
            missing?.let {
                val remote = it.remoteBranch?.substringBefore('/')
                if (remote != null) Chip.status(message("layer.branch.remote", remote), StackColors.WARNING)
                else Chip.status(message("layer.branch.deleted"), StackColors.WARNING)
            },
            if (layer.needsRebase) Chip.status(message("layer.needs.rebase"), StackColors.WARNING) else null,
            if (row.wrongBase != null) Chip.status(message("layer.wrong.base"), StackColors.WARNING) else null,
        )
        val head = if (layer.isCurrent) Chip.status(message("layer.head"), colors.secondary) else null
        val first = headline(name, badges, head, width)

        // Sin PR, la pastilla ya lo dice: no hay segunda linea.
        val title = when {
            layer.pr == null -> ""
            row.details != null -> row.details.title
            row.detailsLoading -> message("layer.loading")
            else -> ""
        }
        val second = description(title, JBFont.small(), colors.secondary, row.details?.let(::badges).orEmpty(), width, expanded)

        return graphRow(
            first, second, colors.background,
            Rail.Node(if (layer.isCurrent) Rail.Shape.FILLED else Rail.Shape.RING, if (missing != null) StackColors.WARNING else status.color),
            lineAbove = !row.isTop,
            lineBelow = true,
        )
    }

    private fun badges(details: PrDetails): List<JComponent> {
        val badges = mutableListOf<JComponent>()
        details.size?.let { badges += size(it) }
        val visible = details.labels.take(MAX_LABELS)
        visible.forEach { badges += Chip.label(it.name, it.color) }
        if (details.labels.size > visible.size) {
            badges += Chip.status("+${details.labels.size - visible.size}", StackColors.DRAFT)
        }
        details.review?.let { badges += Chip.review(it) }
        details.checks?.let { badges += Chip.checks(it) }
        if (details.hasConflicts) badges += Chip.status(message("pr.conflicts"), StackColors.CLOSED)
        if (details.isBehind) badges += Chip.status(message("pr.behind"), StackColors.QUEUED)
        return badges
    }

    /** `+120 −30`, en verde y rojo como en GitHub. Los ficheros, en el tooltip. */
    private fun size(size: PrSize): JComponent = JPanel(HorizontalLayout(JBUI.scale(3))).apply {
        isOpaque = false
        add(JLabel("+${size.additions}").apply { font = JBFont.small(); foreground = StackColors.READY })
        add(JLabel("−${size.deletions}").apply { font = JBFont.small(); foreground = StackColors.CLOSED })
    }

    private fun trunkRow(row: StackRow.Trunk, colors: RowColors, width: Int): JComponent {
        val name = JLabel(row.name).apply {
            foreground = colors.primary
            font = if (row.isCurrent) JBFont.label().asBold() else JBFont.label()
        }
        val first = headline(name, listOf(Chip.status(message("layer.trunk"), StackColors.DRAFT)), null, width)
        return graphRow(first, null, colors.background, Rail.Node(Rail.Shape.SQUARE, StackColors.DRAFT), lineAbove = true, lineBelow = false)
    }

    private fun localRow(row: StackRow.Local, colors: RowColors, width: Int, expanded: Boolean): JComponent {
        val entry = row.entry
        val stack = entry.stack
        val name = JLabel(stack.branches.last()).apply {
            foreground = if (entry.isStale) colors.secondary else colors.primary
        }
        val badges = listOfNotNull(
            Chip.status(message("local.layers", stack.branches.size), StackColors.DRAFT),
            when {
                entry.isStale -> Chip.status(message("local.stale"), StackColors.WARNING)
                entry.localBranches.isEmpty() -> Chip.status(message("local.remote.only"), StackColors.QUEUED)
                else -> null
            },
        )
        // La rama actual esta en esta pila (y en otra): como la capa actual, nodo relleno y HEAD.
        val head = if (row.head != null) Chip.status(message("layer.head"), colors.secondary) else null
        val first = headline(name, badges, head, width)
        val chain = (listOf(stack.trunk) + stack.branches).joinToString(" ← ")
        val second = wrapped(chain, JBFont.small(), colors.secondary, width, expanded)
        val color = if (entry.isStale) StackColors.WARNING else StackColors.DRAFT
        val shape = if (row.head != null) Rail.Shape.FILLED else Rail.Shape.RING
        return graphRow(first, second, colors.background, Rail.Node(shape, color), lineAbove = false, lineBelow = false)
    }

    /**
     * La primera linea: [name], detras sus [badges] y [trailing] contra el borde derecho. Si no
     * cabe todo, cede el nombre, que se corta con puntos suspensivos (el tooltip lo da entero):
     * los distintivos se ven siempre.
     */
    private fun headline(name: JLabel, badges: List<JComponent>, trailing: JComponent?, width: Int): JComponent {
        val gap = JBUI.scale(6)
        if (width > 0) {
            val others = badges.sumOf { it.preferredSize.width + gap } + (trailing?.let { it.preferredSize.width + gap } ?: 0)
            name.text = TextWrap.fit(name.text, (width - others).coerceAtLeast(1), 1, measurer(name.font)).lines.firstOrNull().orEmpty()
        }
        val left = JPanel(HorizontalLayout(gap)).apply {
            isOpaque = false
            add(name)
            badges.forEach(::add)
        }
        return JPanel(BorderLayout(gap, 0)).apply {
            isOpaque = false
            add(left, BorderLayout.WEST)
            trailing?.let { add(it, BorderLayout.EAST) }
        }
    }

    /**
     * El titulo y los distintivos. Si caben juntos en una linea, los distintivos van a la
     * derecha; si no, el titulo se envuelve con todo el ancho y ellos bajan debajo.
     */
    private fun description(text: String, font: Font, color: Color, badges: List<JComponent>, width: Int, expanded: Boolean): JComponent? {
        if (badges.isEmpty()) return if (text.isBlank()) null else wrapped(text, font, color, width, expanded)
        val gap = JBUI.scale(8)
        val row = chipRow(badges)
        if (width <= 0 || measurer(font)(text) + gap + row.preferredSize.width <= width) {
            return JPanel(BorderLayout(gap, 0)).apply {
                isOpaque = false
                add(label(text, font, color), BorderLayout.CENTER)
                add(row, BorderLayout.EAST)
            }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            if (text.isNotBlank()) {
                add(wrapped(text, font, color, width, expanded).apply { alignmentX = Component.LEFT_ALIGNMENT })
                add(Box.createVerticalStrut(JBUI.scale(4)))
            }
            add(flow(badges, width).apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    /**
     * [text] envuelto en [width]. Plegado se queda en [MAX_LINES] lineas; si se deja algo fuera,
     * un [ExpandToggle] al final lo despliega entero. Desplegado no tiene tope. Si el texto
     * cabe plegado no hay chevron, este desplegado o no: no habria nada que ensenar ni esconder.
     */
    private fun wrapped(text: String, font: Font, color: Color, width: Int, expanded: Boolean): JComponent {
        val measure = measurer(font)
        val collapsed = TextWrap.fit(text, width, MAX_LINES, measure)
        if (!collapsed.clipped) return lines(collapsed.lines, font, color)
        val gap = JBUI.scale(4)
        val toggle = ExpandToggle(expanded)
        val room = (width - toggle.preferredSize.width - gap).coerceAtLeast(1)
        val fit = TextWrap.fit(text, room, if (expanded) Int.MAX_VALUE else MAX_LINES, measure)
        return JPanel(BorderLayout(gap, 0)).apply {
            isOpaque = false
            add(lines(fit.lines, font, color), BorderLayout.CENTER)
            add(toggle, BorderLayout.EAST)
        }
    }

    private fun lines(lines: List<String>, font: Font, color: Color): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        lines.forEach { add(label(it, font, color).apply { alignmentX = Component.LEFT_ALIGNMENT }) }
    }

    private fun label(text: String, font: Font, color: Color): JLabel = JLabel(text).apply {
        this.font = font
        foreground = color
    }

    /** Los distintivos repartidos en tantas filas como hagan falta para no salirse de [width]. */
    private fun flow(chips: List<JComponent>, width: Int): JComponent {
        val gap = JBUI.scale(CHIP_GAP)
        val rows = mutableListOf<MutableList<JComponent>>()
        var used = 0
        for (chip in chips) {
            val chipWidth = chip.preferredSize.width
            if (rows.isEmpty() || used + gap + chipWidth > width) {
                rows += mutableListOf(chip)
                used = chipWidth
            } else {
                rows.last() += chip
                used += gap + chipWidth
            }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            rows.forEachIndexed { index, chipsInRow ->
                if (index > 0) add(Box.createVerticalStrut(JBUI.scale(3)))
                add(chipRow(chipsInRow).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
    }

    private fun chipRow(chips: List<JComponent>): JComponent = JPanel(HorizontalLayout(JBUI.scale(CHIP_GAP))).apply {
        isOpaque = false
        chips.forEach(::add)
    }

    /**
     * Mide con una `JLabel` de la misma fuente y no con `FontMetrics`: es lo que pedira la
     * etiqueta al pintarse, y si se le da un pixel menos de lo que pide, pone puntos
     * suspensivos por su cuenta.
     */
    private fun measurer(font: Font): (String) -> Int {
        val probe = JLabel().apply { this.font = font }
        return { text ->
            probe.text = text
            probe.preferredSize.width
        }
    }

    private companion object {
        const val MAX_LABELS = 3
        const val MAX_LINES = 2
        const val CHIP_GAP = 4
    }
}

/** El chevron que despliega o pliega el texto de una fila. `StackList` lo encuentra por su clase. */
internal class ExpandToggle(val expanded: Boolean) : JLabel(if (expanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown) {
    init {
        verticalAlignment = SwingConstants.BOTTOM
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
        border = JBUI.Borders.empty(6, 0, 6, LINES_RIGHT)
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

/** Lo que le queda al texto de una fila de [total] pixeles, o 0 si aun no se sabe. */
internal fun graphContentWidth(total: Int): Int =
    if (total <= 0) 0 else (total - JBUI.scale(RAIL_WIDTH) - JBUI.scale(LINES_RIGHT)).coerceAtLeast(1)

private const val RAIL_WIDTH = 28
private const val LINES_RIGHT = 10

/** La columna del grafo: una linea continua entre filas y el nodo de la capa. */
internal class Rail(
    private val node: Node,
    private val lineAbove: Boolean,
    private val lineBelow: Boolean,
    private val centerY: () -> Int,
) : JComponent() {

    enum class Shape { FILLED, RING, SQUARE }

    class Node(val shape: Shape, val color: Color)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(RAIL_WIDTH), JBUI.scale(16))

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
