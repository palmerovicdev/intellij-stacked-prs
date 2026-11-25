package com.upstack.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCommands
import com.upstack.settings.UpstackSettings
import com.upstack.stack.PrFile
import com.upstack.stack.PrTemplate
import com.upstack.stack.PrText
import java.awt.CardLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Donde se escribe el titulo y la descripcion de un PR, en los dialogos de publicar y de editar:
 * en sus campos o en el Markdown de la capa ([PrFile]), y con una plantilla de Settings o vacio.
 *
 * En los campos, lo escrito aqui es lo que va a GitHub. En el md, lo que va es lo que ponga el
 * archivo al aceptar el dialogo, que es modal: para editarlo en el IDE *Open in Editor* cierra
 * el dialogo ([onOpen]) y lo abre; se vuelve a publicar cuando este listo.
 *
 * [file] es null si no se sabe la raiz del repositorio: entonces solo hay campos.
 */
internal class PrTextEditor(
    private val project: Project,
    initial: PrText,
    private val branch: String,
    /** Lo que sustituye a `{commits}` en las plantillas. */
    private val commits: String,
    private val file: Path?,
    bodyRows: Int,
    private val onOpen: () -> Unit,
    private val templates: List<PrTemplate> = UpstackSettings.getInstance().prTemplates,
    startInFile: Boolean = UpstackSettings.getInstance().prTextInFile,
    /** Pregunta antes de pisar un md con otro contenido: la ruta que se ensena. Los tests la sustituyen. */
    private val confirmOverwrite: (String) -> Boolean = ::askOverwrite,
) {

    /** Una opcion del desplegable: vacio (sin plantilla) o una plantilla. */
    private class Choice(val label: String, val template: PrTemplate?)

    val titleField = JBTextField(initial.title, 40)
    val bodyArea = JBTextArea(initial.body, bodyRows, 40).apply { lineWrap = true; wrapStyleWord = true }

    private val inDialog = JBRadioButton(message("pr.text.dialog"), true)
    private val inFile = JBRadioButton(message("pr.text.file", PrFile.display(branch)))
    private val choices = listOf(Choice(message("pr.template.empty"), null)) + templates.map { Choice(it.name, it) }
    private val templateBox = ComboBox(choices.toTypedArray()).apply { renderer = textListCellRenderer("") { it.label } }
    private val applyButton = JButton(message("pr.template.apply"))
    private val previewTitle = JBLabel().apply { font = JBFont.label().asBold() }
    private val previewBody = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true; isEditable = false }
    private val status = JBLabel().apply { font = JBFont.small() }
    // Fuera del DSL, que pide `buttonsGroup` para los radios.
    private val modes = JPanel(HorizontalLayout(JBUI.scale(12))).apply { add(inDialog); add(inFile) }
    private val cards = JPanel(CardLayout())
    private val listeners = mutableListOf<() -> Unit>()

    /** El md es la fuente del texto. */
    internal fun selectFile(inFile: Boolean) {
        (if (inFile) this.inFile else inDialog).doClick()
    }

    val inFileMode: Boolean get() = file != null && inFile.isSelected

    /** Titulo y descripcion que van a GitHub: los campos, o el md tal como esta ahora. */
    val text: PrText get() = if (inFileMode) PrFile.parse(readFile().orEmpty()) else PrText(titleField.text.trim(), bodyArea.text.trim())

    /** Por que no se puede publicar con lo que hay, o null. */
    val problem: String?
        get() = when {
            !inFileMode -> if (titleField.text.isBlank()) message("title.empty") else null
            readFile() == null -> message("pr.file.missing", PrFile.display(branch))
            text.title.isEmpty() -> message("pr.file.no.title", PrFile.display(branch))
            else -> null
        }

    /** El campo que recibe el foco. */
    val focusTarget: JComponent get() = if (inFileMode) applyButton else titleField

    init {
        ButtonGroup().apply { add(inDialog); add(inFile) }
        inFile.isEnabled = file != null
        val help = listOf(Help.gh(GhCommands.editPr(branch, null, "TITLE", "BODY")))
        inDialog.showHelp(Help(message("help.pr.text.dialog"), help))
        inFile.showHelp(Help(message("help.pr.text.file", PrFile.display(branch)), help))
        templateBox.showHelp(Help(message("help.pr.template.pick")))
        applyButton.showHelp(Help(message("help.pr.template.apply", PrFile.display(branch)), help))
        listOf(titleField, bodyArea).forEach { it.onChange(::fire) }
        inDialog.addActionListener { switch() }
        inFile.addActionListener { switch() }
        applyButton.addActionListener { applyTemplate() }
        cards.add(dialogCard(), DIALOG)
        cards.add(fileCard(), FILE)
        if (startInFile && file != null) {
            inFile.isSelected = true
            ensureFile()
        }
        show()
    }

    val component: JComponent = panel {
        if (file != null) row { cell(modes) }
        row(message("pr.template.label")) {
            cell(templateBox)
            cell(applyButton)
        }
        row { cell(cards).align(Align.FILL) }.resizableRow()
    }

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    /** Despues de cerrar el dialogo con *Open in Editor*: abre el md en el IDE. */
    fun openInEditor() {
        val path = file ?: return
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun dialogCard(): JComponent = panel {
        row(message("title.field")) { cell(titleField).align(AlignX.FILL) }
        row(message("publish.layer.body")) { cell(ScrollPaneFactory.createScrollPane(bodyArea)).align(Align.FILL) }.resizableRow()
    }

    private fun fileCard(): JComponent = panel {
        row { label(PrFile.display(branch)).applyToComponent { font = JBFont.small() } }
        row(message("title.field")) { cell(previewTitle).align(AlignX.FILL) }
        row(message("publish.layer.body")) { cell(ScrollPaneFactory.createScrollPane(previewBody)).align(Align.FILL) }.resizableRow()
        row {
            button(message("pr.file.open")) { open() }
                .applyToComponent { showHelp(Help(message("help.pr.file.open", PrFile.display(branch)))) }
            cell(status)
        }
    }

    /** Se pulso *Open in Editor*: el dialogo se cierra por esto y no por Publish ni por Cancel. */
    var openClicked = false
        private set

    private fun open() {
        if (!ensureFile()) return
        openClicked = true
        onOpen()
    }

    private fun switch() {
        UpstackSettings.getInstance().prTextInFile = inFile.isSelected
        if (inFileMode) ensureFile()
        show()
        fire()
    }

    /** Con el md aun sin crear, lo que hay en los campos pasa a el: no se pierde nada al cambiar. */
    private fun ensureFile(): Boolean {
        if (readFile() != null) return true
        return write(PrText(titleField.text.trim(), bodyArea.text.trim()))
    }

    /** Elige la plantilla de nombre [name] (o la vacia si es null) y la aplica: lo que hace el boton. Publica para los tests. */
    internal fun applyTemplate(name: String?) {
        templateBox.selectedItem = choices.first { it.template?.name == name }
        applyTemplate()
    }

    private fun applyTemplate() {
        val template = (templateBox.selectedItem as? Choice)?.template
        val applied = template?.render(branch, commits) ?: PrText("", "")
        if (inFileMode) {
            val existing = readFile()?.takeIf { it.isNotBlank() }
            if (existing != null && PrFile.parse(existing) != applied && !confirmOverwrite(PrFile.display(branch))) return
            write(applied)
        } else {
            titleField.text = applied.title
            bodyArea.text = applied.body
        }
        show()
        fire()
    }

    private fun show() {
        (cards.layout as CardLayout).show(cards, if (inFileMode) FILE else DIALOG)
        if (!inFileMode) return
        val shown = PrFile.parse(readFile().orEmpty())
        previewTitle.text = shown.title.ifEmpty { message("pr.file.no.title.short") }
        previewBody.text = shown.body
        previewBody.caretPosition = 0
    }

    private fun readFile(): String? {
        val path = file?.takeIf { Files.isRegularFile(it) } ?: return null
        // Lo que se editó en el IDE y aun no esta en disco.
        FileDocumentManager.getInstance().saveAllDocuments()
        return try {
            Files.readString(path)
        } catch (_: IOException) {
            null
        }
    }

    private fun write(text: PrText): Boolean {
        val path = file ?: return false
        return try {
            Files.createDirectories(path.parent)
            Files.writeString(path, PrFile.format(text))
            LocalFileSystem.getInstance().refreshNioFiles(listOf(path))
            status.text = ""
            true
        } catch (e: IOException) {
            status.text = message("pr.file.write.failed", e.message.orEmpty())
            false
        }
    }

    private fun fire() = listeners.forEach { it() }

    private companion object {
        const val DIALOG = "dialog"
        const val FILE = "file"

        fun askOverwrite(path: String): Boolean =
            Messages.showYesNoDialog(message("pr.template.overwrite", path), message("pr.template.overwrite.title"), Messages.getWarningIcon()) == Messages.YES
    }
}
