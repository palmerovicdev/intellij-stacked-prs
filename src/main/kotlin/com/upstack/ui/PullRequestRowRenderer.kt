package com.upstack.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.upstack.UpstackBundle.message
import com.upstack.stack.OpenPullRequest
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/** A viewport-width list, remeasured whenever the tool window is resized. */
internal class PullRequestList(private val rows: CollectionListModel<OpenPullRequest>) : JBList<OpenPullRequest>(rows) {
    private var measuredWidth = -1

    init {
        cellRenderer = PullRequestRowRenderer()
        setExpandableItemsEnabled(false)
        toolTipText = ""
    }

    override fun getScrollableTracksViewportWidth() = true

    fun checksAt(point: Point): OpenPullRequest? {
        val index = locationToIndex(point)
        if (index < 0) return null
        val bounds = getCellBounds(index, index)?.takeIf { it.contains(point) } ?: return null
        val pr = model.getElementAt(index)
        val cell = cellRenderer.getListCellRendererComponent(this, pr, index, isSelectedIndex(index), false)
        cell.setBounds(0, 0, bounds.width, bounds.height)
        fun layout(component: Component) {
            if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
        }
        layout(cell)
        val under = SwingUtilities.getDeepestComponentAt(cell, point.x - bounds.x, point.y - bounds.y)
        return pr.takeIf { Chip.isChecks(under) }
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width != measuredWidth) {
            measuredWidth = width
            rows.allContentsChanged()
        }
    }

    override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
        val index = locationToIndex(event.point)
        if (index < 0 || getCellBounds(index, index)?.contains(event.point) != true) return null
        val pr = model.getElementAt(index)
        fun escape(text: String) = StringUtil.escapeXmlEntities(text)
        return "<html><b>#${pr.number} ${escape(pr.title)}</b><br>${escape(pr.head)} → ${escape(pr.base)}</html>"
    }
}

internal class PullRequestRowRenderer : ListCellRenderer<OpenPullRequest> {
    override fun getListCellRendererComponent(
        list: JList<out OpenPullRequest>, value: OpenPullRequest, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        val width = (list.width - list.insets.left - list.insets.right - JBUI.scale(20)).coerceAtLeast(1)
        val primary = RenderingUtil.getForeground(list, isSelected)
        val secondary = if (isSelected) primary else NamedColorUtil.getInactiveTextColor()
        val status = Chip.status(message(if (value.draft) "status.draft" else "status.open"), if (value.draft) StackColors.DRAFT else StackColors.READY)
        val title = JLabel().apply {
            font = JBFont.label().asBold()
            foreground = primary
            text = fit(value.title, (width - status.preferredSize.width - JBUI.scale(8)).coerceAtLeast(1), this)
        }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.CENTER)
            add(status, BorderLayout.EAST)
        }
        val metadata = listOfNotNull(
            "#${value.number}", value.createdAt?.let(::formatDate)?.let { message("prs.created", it) },
            value.author?.let { message("prs.author", it) },
        ).joinToString(" · ")
        val subtitle = JLabel().apply { font = JBFont.small(); foreground = secondary; text = fit(metadata, width, this) }
        val chips = mutableListOf<JComponent>()
        value.details?.let { details ->
            details.review?.let { chips += Chip.review(it) }
            details.checks?.let { chips += Chip.checks(it) }
            if (details.hasConflicts) chips += Chip.status(message("pr.conflicts"), StackColors.CLOSED)
            if (details.isBehind) chips += Chip.status(message("pr.behind"), StackColors.QUEUED)
            details.size?.let { size ->
                chips += JPanel(HorizontalLayout(JBUI.scale(3))).apply {
                    isOpaque = false
                    add(JLabel("+${size.additions}").apply { font = JBFont.small(); foreground = StackColors.READY })
                    add(JLabel("−${size.deletions}").apply { font = JBFont.small(); foreground = StackColors.CLOSED })
                }
            }
            details.labels.take(2).forEach { label ->
                val probe = Chip.label(label.name, label.color)
                val labelWidth = (width - probe.insets.left - probe.insets.right).coerceAtLeast(1)
                probe.text = fit(label.name, labelWidth, probe)
                chips += probe
            }
            if (details.labels.size > 2) chips += Chip.status("+${details.labels.size - 2}", StackColors.DRAFT)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = RenderingUtil.getBackground(list, isSelected)
            border = JBUI.Borders.empty(8, 10)
            add(header.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(subtitle.apply { alignmentX = Component.LEFT_ALIGNMENT })
            if (chips.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(5)))
                add(chipRows(chips, width).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
    }

    private fun chipRows(chips: List<JComponent>, width: Int): JComponent {
        val gap = JBUI.scale(4)
        val rows = mutableListOf<MutableList<JComponent>>()
        var used = 0
        for (chip in chips) {
            val w = chip.preferredSize.width
            if (rows.isEmpty() || used + gap + w > width) { rows += mutableListOf(chip); used = w }
            else { rows.last() += chip; used += gap + w }
        }
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            rows.forEachIndexed { index, row ->
                if (index > 0) add(Box.createVerticalStrut(JBUI.scale(3)))
                add(JPanel(HorizontalLayout(gap)).apply { isOpaque = false; row.forEach(::add); alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
    }

    private fun fit(text: String, width: Int, label: JLabel): String {
        val measure: (String) -> Int = { label.text = it; label.preferredSize.width }
        return TextWrap.fit(text, width, 1, measure).lines.firstOrNull().orEmpty()
    }

    private fun formatDate(value: String): String = try {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    } catch (_: java.time.DateTimeException) { value.take(10) }
}
