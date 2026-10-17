package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCommands
import com.upstack.stack.LayerPlans
import com.upstack.stack.Plan
import com.upstack.stack.RemoteRename
import com.upstack.stack.StackLayer
import com.upstack.stack.StackSnapshot
import javax.swing.JComponent

/**
 * *Rename Branch…*: el nombre nuevo de la rama de una capa sin PR y los comandos de
 * [LayerPlans.rename] en el orden en que se ejecutan. Es la confirmacion.
 */
internal class RenameBranchDialog(
    project: Project,
    private val snapshot: StackSnapshot,
    private val layer: StackLayer,
    private val current: String?,
    private val remote: RemoteRename?,
    /** Ramas que ya existen, en local o en el remoto: el nombre nuevo no puede ser ninguna. */
    private val taken: Set<String>,
) : DialogWrapper(project) {

    private val nameField = JBTextField(layer.branch, 32)
    private val preview = CommandPreview()

    val newName: String get() = nameField.text.trim()

    val plan: Plan get() = LayerPlans.rename(snapshot, layer, newName.ifEmpty { "…" }, remote, current)

    init {
        title = message("rename.title", layer.branch)
        setOKButtonText(message("rename.ok"))
        nameField.selectAll()
        nameField.onChange(::update)
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("rename.name")) { cell(nameField).align(AlignX.FILL) }
        row { cell(WrappingText(message("rename.text"), AllIcons.General.Information)).align(AlignX.FILL).resizableColumn() }
        remote?.let {
            row { cell(WrappingText(message("rename.remote", it.remote, it.github.cliName), AllIcons.General.Information)).align(AlignX.FILL).resizableColumn() }
        }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    /** Escribe [name] en el campo, como el usuario. Los tests no pueden teclear en un dialogo sin pantalla. */
    internal fun enterName(name: String) {
        nameField.text = name
    }

    // Publica: los tests comprueban que el nombre que escriben vale.
    public override fun doValidate(): ValidationInfo? {
        val name = newName
        GhCommands.branchNameProblem(name)?.let { return ValidationInfo(branchProblemText(it, name), nameField) }
        if (name == layer.branch) return ValidationInfo(message("rename.same"), nameField)
        if (name == snapshot.trunk || snapshot.layers.any { it.branch == name } || name in taken) {
            return ValidationInfo(message("rename.exists", name), nameField)
        }
        return null
    }

    private fun update() {
        preview.showLines(Help.of(plan.calls))
    }
}

/** *Edit Title…*: el titulo nuevo de un PR y el `gh pr edit` que lo cambia. */
internal class EditTitleDialog(project: Project, private val number: Int, private val url: String, title: String) : DialogWrapper(project) {

    private val titleField = JBTextField(title, 48)
    private val preview = CommandPreview()

    val newTitle: String get() = titleField.text.trim()

    init {
        this.title = message("title.title", number)
        setOKButtonText(message("title.ok"))
        titleField.selectAll()
        titleField.onChange(::update)
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("title.field")) { cell(titleField).align(AlignX.FILL) }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    /** Como [RenameBranchDialog.enterName]. */
    internal fun enterTitle(title: String) {
        titleField.text = title
    }

    // Publica: los tests comprueban que el titulo que escriben vale.
    public override fun doValidate(): ValidationInfo? =
        if (newTitle.isEmpty()) ValidationInfo(message("title.empty"), titleField) else null

    private fun update() {
        preview.showLines(listOf(Help.gh(GhCommands.editTitle(url, newTitle.ifEmpty { "…" }))))
    }
}
