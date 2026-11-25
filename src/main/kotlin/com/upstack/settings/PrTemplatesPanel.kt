package com.upstack.settings

import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.stack.PrTemplate
import com.upstack.ui.Help
import com.upstack.ui.WrappingText
import com.upstack.ui.onChange
import com.upstack.ui.showHelp
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * La lista de plantillas de PR de Settings: a la izquierda sus nombres (con Add y Remove) y a la
 * derecha el nombre, el titulo y la descripcion de la que esta seleccionada.
 */
internal class PrTemplatesPanel : JPanel(BorderLayout(JBUI.scale(12), 0)) {

    /** Lo que se edita: una copia de cada plantilla, que solo se guarda con Apply. */
    private class Entry(var name: String, var title: String, var body: String) {
        override fun toString(): String = name.ifBlank { message("settings.templates.unnamed") }
    }

    private val model = CollectionListModel<Entry>()
    private val list = JBList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val nameField = JBTextField(24)
    private val titleField = JBTextField(32)
    private val bodyArea = JBTextArea(8, 32).apply { lineWrap = true; wrapStyleWord = true }
    private var loading = false

    var templates: List<PrTemplate>
        get() = model.items.map { PrTemplate(it.name.trim(), it.title, it.body) }.filter { it.name.isNotEmpty() }
        set(value) {
            model.replaceAll(value.map { Entry(it.name, it.title, it.body) })
            if (model.size > 0) list.selectedIndex = 0 else load(null)
        }

    init {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { add() }
            .setRemoveAction { remove() }
            .disableUpDownActions()
            .createPanel()
        add(decorated.apply { preferredSize = JBUI.size(200, 180) }, BorderLayout.WEST)
        add(
            panel {
                row(message("settings.templates.name")) { cell(nameField).align(AlignX.FILL) }
                row(message("settings.templates.title")) { cell(titleField).align(AlignX.FILL) }
                row(message("settings.templates.body")) { cell(ScrollPaneFactory.createScrollPane(bodyArea)).align(Align.FILL) }.resizableRow()
                row { cell(WrappingText(message("settings.templates.placeholders"))).align(AlignX.FILL) }
            },
            BorderLayout.CENTER,
        )
        nameField.showHelp(Help(message("help.templates.name")))
        titleField.showHelp(Help(message("help.templates.title")))
        bodyArea.showHelp(Help(message("help.templates.body")))
        list.addListSelectionListener { load(list.selectedValue) }
        nameField.onChange { edit { name = nameField.text }; list.repaint() }
        titleField.onChange { edit { title = titleField.text } }
        bodyArea.onChange { edit { body = bodyArea.text } }
    }

    fun isModified(saved: List<PrTemplate>): Boolean = templates != saved || model.size != saved.size

    private fun edit(change: Entry.() -> Unit) {
        if (!loading) list.selectedValue?.change()
    }

    private fun load(entry: Entry?) {
        loading = true
        try {
            nameField.text = entry?.name.orEmpty()
            titleField.text = entry?.title.orEmpty()
            bodyArea.text = entry?.body.orEmpty()
            listOf(nameField, titleField, bodyArea).forEach { it.isEnabled = entry != null }
        } finally {
            loading = false
        }
    }

    private fun add() {
        val entry = Entry(message("settings.templates.new", model.size + 1), "{branch}", "{commits}")
        model.add(entry)
        list.setSelectedValue(entry, true)
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun remove() {
        val index = list.selectedIndex
        if (index < 0) return
        model.remove(index)
        if (model.size > 0) list.selectedIndex = index.coerceAtMost(model.size - 1) else load(null)
    }
}
