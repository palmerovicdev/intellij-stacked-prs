package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.LayerPlans
import com.stacklane.stack.Plan
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackSnapshot
import javax.swing.JComponent

/**
 * *Delete Layer…*: borrar la capa de arriba (ver [LayerPlans.drop]), con o sin su rama, aqui y en
 * el remoto, y los comandos en el orden en que se ejecutan. Es la confirmacion.
 */
internal class DeleteLayerDialog(
    project: Project,
    private val snapshot: StackSnapshot,
    private val layer: StackLayer,
    private val current: String?,
    /** La rama esta en local. Sin ella (P44) solo queda sacarla de la pila. */
    private val layerExists: Boolean,
    /** El remoto donde git sabe que esta la rama, o null. */
    private val remote: String?,
) : DialogWrapper(project) {

    private val localBox = JBCheckBox(message("delete.layer.local", layer.branch), true)
    private val remoteBox = JBCheckBox(message("delete.layer.remote", layer.branch, remote.orEmpty()), false)
    private val preview = CommandPreview()

    val plan: Plan
        get() = LayerPlans.drop(
            snapshot, layer, current,
            deleteLocal = layerExists && localBox.isSelected,
            deleteOn = remote?.takeIf { remoteBox.isSelected },
            layerExists = layerExists,
        )

    init {
        title = message("delete.layer.title", layer.branch)
        setOKButtonText(message("delete.layer.ok"))
        localBox.showHelp(Help(message("help.delete.layer.local"), listOf(Help.git(listOf("branch", "-D", layer.branch)))))
        remote?.let { remoteBox.showHelp(Help(message("help.delete.layer.remote", it), listOf(Help.git(listOf("push", it, "--delete", layer.branch))))) }
        listOf(localBox, remoteBox).forEach { it.addActionListener { update() } }
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(WrappingText(message("delete.layer.text", layer.branch, snapshot.parentOf(layer)))).align(AlignX.FILL).resizableColumn()
        }
        if (current == layer.branch) {
            row { cell(WrappingText(message("delete.layer.leave", snapshot.parentOf(layer)), AllIcons.General.Information)).align(AlignX.FILL).resizableColumn() }
        }
        if (layerExists) row { cell(localBox) }.topGap(TopGap.SMALL)
        if (remote != null) row { cell(remoteBox) }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    private fun update() {
        preview.showLines(Help.of(plan.calls))
    }

    companion object {
        /** Lo que se ejecutaria con las opciones por defecto: para la ayuda de la accion. */
        fun defaultCommands(snapshot: StackSnapshot, layer: StackLayer, current: String?, layerExists: Boolean): List<String> =
            Help.of(LayerPlans.drop(snapshot, layer, current, deleteLocal = layerExists, deleteOn = null, layerExists = layerExists).calls)
    }
}
