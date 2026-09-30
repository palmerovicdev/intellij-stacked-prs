package com.stacklane.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.stack.RepoLabel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Las labels del repositorio con las del PR marcadas. Al aceptar se aplica la diferencia en
 * un solo `gh pr edit --add-label ... --remove-label ...`.
 */
internal class LabelsDialog(
    project: Project,
    private val prUrl: String,
    prNumber: Int,
    labels: List<RepoLabel>,
    private val applied: Set<String>,
) : DialogWrapper(project) {

    private class Item(val label: RepoLabel, var checked: Boolean)

    // Una label aplicada que ya no esta en el repositorio tambien se ensena, para poder quitarla.
    private val items: List<Item> = (labels + applied.filter { name -> labels.none { it.name == name } }.map { RepoLabel(it, "", "") })
        .sortedBy { it.name.lowercase() }
        .map { Item(it, it.name in applied) }

    private val model = CollectionListModel(items)
    private val list = JBList(model)
    private val search = SearchTextField(false)
    private val changes = JBLabel()
    private val preview = CommandPreview()

    init {
        title = message("labels.title", prNumber)
        setOKButtonText(message("labels.ok"))
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = LabelRenderer()
        list.emptyText.text = message("labels.empty")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val index = list.locationToIndex(e.point)
                if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) toggle(index)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE && list.selectedIndex >= 0) {
                    toggle(list.selectedIndex)
                    e.consume()
                }
            }
        })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        // Flechas desde el buscador: se mueve la seleccion sin soltar el foco.
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN, KeyEvent.VK_UP -> {
                        val delta = if (e.keyCode == KeyEvent.VK_DOWN) 1 else -1
                        val next = (list.selectedIndex + delta).coerceIn(0, model.size - 1)
                        if (model.size > 0) {
                            list.selectedIndex = next
                            list.ensureIndexIsVisible(next)
                        }
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> if (list.selectedIndex >= 0 && e.modifiersEx == 0) {
                        toggle(list.selectedIndex)
                        e.consume()
                    }
                }
            }
        })
        changes.foreground = NamedColorUtil.getInactiveTextColor()
        init()
        update()
    }

    fun selectedLabels(): Set<String> = items.filter { it.checked }.mapTo(LinkedHashSet()) { it.label.name }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(420), JBUI.scale(320)) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            add(changes, BorderLayout.NORTH)
            add(preview, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    private fun toggle(index: Int) {
        val item = model.getElementAt(index)
        item.checked = !item.checked
        list.repaint(list.getCellBounds(index, index))
        update()
    }

    private fun filter() {
        val query = search.text.trim()
        model.replaceAll(items.filter { query.isEmpty() || it.label.name.contains(query, true) || it.label.description.contains(query, true) })
        if (model.size > 0) list.selectedIndex = 0
    }

    private fun update() {
        val selected = selectedLabels()
        val toAdd = selected - applied
        val toRemove = applied - selected
        changes.text = if (toAdd.isEmpty() && toRemove.isEmpty()) message("labels.no.changes")
        else (toAdd.map { "+$it" } + toRemove.map { "−$it" }).joinToString("  ")
        preview.show(listOf(GhCommands.editLabels(prUrl, toAdd, toRemove)))
        isOKActionEnabled = toAdd.isNotEmpty() || toRemove.isNotEmpty()
    }

    private class LabelRenderer : ListCellRenderer<Item> {
        override fun getListCellRendererComponent(
            list: JList<out Item>,
            value: Item,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val checkBox = JBCheckBox(null, value.checked).apply { isOpaque = false }
            // La descripcion en el centro: si no cabe, acaba en puntos suspensivos en vez de
            // cortarse contra el borde.
            val content = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(if (value.label.color.isEmpty()) JLabel(value.label.name) else Chip.label(value.label.name, value.label.color), BorderLayout.WEST)
                if (value.label.description.isNotEmpty()) {
                    add(JLabel(value.label.description).apply {
                        font = JBFont.small()
                        foreground = if (isSelected) RenderingUtil.getForeground(list, true) else NamedColorUtil.getInactiveTextColor()
                    }, BorderLayout.CENTER)
                }
            }
            return JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                background = RenderingUtil.getBackground(list, isSelected)
                border = JBUI.Borders.empty(3, 4)
                add(checkBox, BorderLayout.WEST)
                add(content, BorderLayout.CENTER)
            }
        }
    }
}
