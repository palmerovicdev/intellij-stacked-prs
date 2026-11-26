package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A filter as IntelliJ draws them in its pull request lists: icon, the whole text of the chosen value and a
 * chevron on the bare panel, as wide as that text needs (up to [MAX_CHARS] characters). Its choices open as
 * a popup list with a search field. It keeps its items and selection like a combo box does, and tells its
 * [ActionListener]s when the selection changes through [selectedItem] or [selectedIndex].
 *
 * [shown] gives the icon and the text of a value on the chip (the filter's own name while it is unset); the
 * popup lists every value by its [itemText] with the same icon.
 */
internal class FilterChip<T : Any>(
    private val shown: (T) -> Pair<Icon, String>,
    private val itemText: (T) -> String = { it.toString() },
) : JComponent() {
    private val items = ArrayList<T>()
    private val listeners = ArrayList<ActionListener>()
    private var selected = -1
    private var popup: JBPopup? = null
    private var closedAt = 0L

    init {
        isOpaque = false
        font = UIUtil.getLabelFont()
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isEnabled && e.button == MouseEvent.BUTTON1) {
                    requestFocusInWindow()
                    toggle()
                }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_DOWN) {
                    toggle()
                    e.consume()
                }
            }
        })
    }

    val itemCount: Int get() = items.size

    fun getItemAt(index: Int): T = items[index]

    fun addItem(item: T) {
        items += item
        if (selected < 0) selected = 0
        refreshSize()
    }

    fun removeAllItems() {
        items.clear()
        selected = -1
        refreshSize()
    }

    var selectedIndex: Int
        get() = selected
        set(value) {
            if (value !in items.indices || value == selected) return
            selected = value
            refreshSize()
            val event = ActionEvent(this, ActionEvent.ACTION_PERFORMED, "")
            listeners.toList().forEach { it.actionPerformed(event) }
        }

    /** The chosen item; setting one that is not among the items changes nothing. */
    var selectedItem: T?
        get() = items.getOrNull(selected)
        set(value) {
            val index = items.indexOf(value)
            if (index >= 0) selectedIndex = index
        }

    fun addActionListener(listener: ActionListener) {
        listeners += listener
    }

    private fun current(): Pair<Icon, String>? = selectedItem?.let(shown)

    private fun label(): String? = current()?.second?.let { if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1) + "…" else it }

    private fun refreshSize() {
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        val icon = current()?.first
        val text = label().orEmpty()
        val width = (if (icon != null) icon.iconWidth + JBUI.scale(GAP) else 0) + metrics.stringWidth(text) +
            JBUI.scale(GAP) + AllIcons.General.ChevronDown.iconWidth
        val height = maxOf(icon?.iconHeight ?: 0, metrics.height, AllIcons.General.ChevronDown.iconHeight)
        return Dimension(width + JBUI.scale(2 * PAD_X), height + JBUI.scale(2 * PAD_Y))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val (icon, _) = current() ?: return
        val text = label().orEmpty()
        val metrics = g.getFontMetrics(font)
        var x = JBUI.scale(PAD_X)
        icon.paintIcon(this, g, x, (height - icon.iconHeight) / 2)
        x += icon.iconWidth + JBUI.scale(GAP)
        g.font = font
        g.color = UIUtil.getLabelForeground()
        GraphicsUtil.setupAAPainting(g)
        g.drawString(text, x, (height - metrics.height) / 2 + metrics.ascent)
        x += metrics.stringWidth(text) + JBUI.scale(GAP)
        val chevron = AllIcons.General.ChevronDown
        chevron.paintIcon(this, g, x, (height - chevron.iconHeight) / 2)
    }

    private fun toggle() {
        val open = popup
        if (open != null && open.isVisible) return open.cancel()
        // The press that closed the popup (a click outside it) must not open it again.
        if (System.nanoTime() - closedAt < 250_000_000L) return
        val choices = items.toList()
        val chooser = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setRenderer(listCellRenderer<T> {
                icon(shown(value).first)
                text(itemText(value))
            })
            .setNamerForFiltering(itemText)
            .setSelectedValue(selectedItem, true)
            .setItemChosenCallback { selectedItem = it }
            .createPopup()
        chooser.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { closedAt = System.nanoTime() }
        })
        popup = chooser
        chooser.showUnderneathOf(this)
    }

    companion object {
        /** Longer values are cut with an ellipsis. */
        const val MAX_CHARS = 50
        private const val GAP = 4
        private const val PAD_X = 6
        private const val PAD_Y = 4
    }
}

/** Shows [title] while [isUnset] holds for the value and the value itself once a choice is made; every item has an [icon]. */
internal fun <T : Any> chip(title: String?, isUnset: (T) -> Boolean, icon: (T) -> Icon): FilterChip<T> =
    FilterChip({ value -> icon(value) to (if (title != null && isUnset(value)) title else value.toString()) })
