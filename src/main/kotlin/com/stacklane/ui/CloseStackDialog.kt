package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.stack.CloseChoice
import com.stacklane.stack.ClosePlans
import com.stacklane.stack.CloseTargets
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * *Close Stack…*: que se hace con la pila, sus PRs y sus ramas, con cada PR y rama que se va
 * a tocar debajo de su casilla y los comandos en el orden en que se ejecutan. Es la
 * confirmacion: nada se ejecuta sin pulsar *Close Stack*.
 *
 * Dejar de seguir la pila en local va siempre: es lo que la cierra. Borrar las ramas remotas
 * solo se puede si antes se cierran los PRs abiertos (ver [ClosePlans.canDeleteRemote]).
 */
internal class CloseStackDialog(
    project: Project,
    private val stack: String,
    private val targets: CloseTargets,
) : DialogWrapper(project) {

    private val defaults = CloseChoice.defaults(targets)
    private val localBox = JBCheckBox(message("close.local"), true).apply { isEnabled = false }
    private val githubBox = JBCheckBox(message("close.github"), defaults.unstackOnGitHub)
    private val closeBox = JBCheckBox(message("close.prs", targets.openPrs.size), defaults.closePrs)
    private val commentField = JBTextField(32)
    private val remoteBox = JBCheckBox(message("close.remote", targets.remote.orEmpty(), targets.remoteBranches.size), defaults.deleteRemote)
    private val remoteNote = WrappingText(textColor = UIUtil.getContextHelpForeground())
    private val deleteLocalBox = JBCheckBox(message("close.delete.local", targets.localBranches.size), defaults.deleteLocal)
    private val preview = CommandPreview()

    val choice: CloseChoice
        get() = CloseChoice(
            unstackOnGitHub = targets.onGitHub && githubBox.isSelected,
            closePrs = targets.openPrs.isNotEmpty() && closeBox.isSelected,
            comment = commentField.text,
            deleteRemote = remoteBox.isEnabled && remoteBox.isSelected,
            deleteLocal = targets.localBranches.isNotEmpty() && deleteLocalBox.isSelected,
        )

    init {
        title = message("close.title")
        setOKButtonText(message("close.ok"))
        commentField.emptyText.text = message("close.comment.empty")
        localBox.showHelp(Help(message("help.close.local"), listOf(Help.gh(GhCommands.unstackLocal()))))
        githubBox.showHelp(Help(message("help.close.github"), listOf(Help.gh(GhCommands.unstack()))))
        closeBox.showHelp(Help(message("help.close.prs"), listOf("gh pr close PR [--comment COMMENT]")))
        commentField.showHelp(Help(message("help.close.comment"), listOf("--comment COMMENT")))
        targets.remote?.let { remote ->
            remoteBox.showHelp(Help(message("help.close.remote", remote), listOf("git push $remote --delete BRANCH…")))
        }
        deleteLocalBox.showHelp(Help(message("help.close.delete.local"), listOfNotNull(targets.leave?.let(Help::git), "git branch -D BRANCH…")))
        listOf(githubBox, closeBox, remoteBox, deleteLocalBox).forEach { it.addActionListener { update() } }
        commentField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update()
        })
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("close.stack")) {
            cell(WrappingText(stack, textFont = JBFont.label().asBold())).align(AlignX.FILL).resizableColumn()
        }
        row { cell(localBox) }.topGap(TopGap.SMALL)
        if (targets.onGitHub) {
            row { cell(githubBox) }
        }
        if (targets.openPrs.isNotEmpty()) {
            row { cell(closeBox) }
            indent {
                row { list(targets.openPrs.map { "#${it.number} ${it.branch}" }) }
                row(message("close.comment")) { cell(commentField).align(AlignX.FILL) }
            }
        }
        if (targets.remoteBranches.isNotEmpty()) {
            row { cell(remoteBox) }
            indent {
                row { list(targets.remoteBranches) }
                row { cell(remoteNote).align(AlignX.FILL).resizableColumn() }
            }
        }
        if (targets.localBranches.isNotEmpty()) {
            row { cell(deleteLocalBox) }
            indent {
                row { list(targets.localBranches) }
                targets.leave?.let { leave ->
                    row { note(message("close.leave", leave.last()), AllIcons.General.Information) }
                }
                if (targets.unpushed.isNotEmpty()) {
                    row { note(message("close.unpushed", targets.unpushed.joinToString(", "), targets.remote ?: "—"), AllIcons.General.Warning) }
                }
            }
        }
        targets.keptCheckedOut?.let { branch ->
            row { note(message("close.kept.checked.out", branch), AllIcons.General.Information) }
        }
        if (targets.shared.isNotEmpty()) {
            row { note(message("close.shared", targets.shared.joinToString(", ")), AllIcons.General.Information) }
        }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    /** Lo que se va a tocar, uno por linea, en gris. */
    private fun Row.list(items: List<String>) =
        cell(WrappingText(items.joinToString("\n"), textColor = UIUtil.getContextHelpForeground())).align(AlignX.FILL).resizableColumn()

    private fun Row.note(text: String, icon: Icon) =
        cell(WrappingText(text, icon)).align(AlignX.FILL).resizableColumn()

    private fun update() {
        commentField.isEnabled = closeBox.isSelected
        val canDeleteRemote = ClosePlans.canDeleteRemote(targets.openPrs.isNotEmpty() && closeBox.isSelected, targets)
        remoteBox.isEnabled = canDeleteRemote
        remoteNote.isVisible = !canDeleteRemote
        remoteNote.text = message("close.remote.needs.close")
        preview.showLines(Help.of(ClosePlans.calls(choice, targets)))
        // Las notas se ensenan u ocultan: el dialogo crece con ellas, nunca encoge.
        val window = window ?: return
        if (window.isVisible && window.preferredSize.height > window.height) pack()
    }
}
