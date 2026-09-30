package com.stacklane.ui

import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.gh.RebaseScope
import com.stacklane.gh.Tool
import com.stacklane.stack.GhCall
import com.stacklane.stack.StackSnapshot
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

/**
 * Lo que hace una opcion y los comandos que ejecuta, tal y como se escribirian en la
 * terminal. Cada accion, boton de banda y casilla lo ensena al pasar el raton.
 *
 * Los comandos con datos que solo se conocen al ejecutar llevan su hueco en mayusculas
 * (`BRANCH`, `MESSAGE`), como en la ayuda de gh. Nunca entre `<>`: la descripcion de una
 * accion llega como HTML al tooltip de la barra de herramientas.
 */
internal class Help(val text: @Nls String, val commands: List<String> = emptyList()) {

    /** Una linea: la descripcion de la accion, que tambien pinta la barra de estado (sin HTML). */
    val plain: @Nls String
        get() = if (commands.isEmpty()) text else "$text (${commands.joinToString(", ")})"

    /** El tooltip: la explicacion y, debajo, un comando por linea en monoespaciada. */
    val html: @Nls String
        get() = HtmlBuilder().append(text).apply {
            commands.forEach { br().append(HtmlChunk.tag("code").addText(it)) }
        }.wrapWithHtmlBody().toString()

    companion object {
        fun gh(args: List<String>): String = GhCommands.display(args)

        fun git(args: List<String>): String = GhCommands.display(args, Tool.GIT)

        /** El checkout del IDE (git4idea), que ejecuta este `git checkout`. */
        fun checkout(branch: String): String = git(listOf("checkout", branch))

        fun of(calls: List<GhCall>): List<String> = calls.map { GhCommands.display(it.args, it.tool) }
    }
}

/**
 * La ayuda de una accion. La descripcion (barra de estado y tooltip de la barra de
 * herramientas) es texto plano; el tooltip de los menus de lista, HTML.
 */
internal fun Presentation.showHelp(help: Help) {
    description = help.plain
    putClientProperty(ActionUtil.TOOLTIP_TEXT, help.html)
}

internal fun JComponent.showHelp(help: Help) {
    toolTipText = help.html
}

/** Las ayudas que se ofrecen desde varios sitios: la barra, las bandas y los enlaces. */
internal object Helps {

    fun refresh() = Help(
        message("action.Stacklane.Refresh.description"),
        listOf(Help.gh(GhCommands.view()), "gh api graphql …"),
    )

    fun initStack() = Help(message("action.Stacklane.InitStack.description"), listOf(INIT))

    fun checkoutStack() = Help(message("help.checkout.stack"), listOf("gh stack checkout STACK | PR | BRANCH"))

    fun installExtension() = Help(message("help.install.extension"), listOf(Help.gh(GhCommands.installExtension())))

    fun rebase(scope: RebaseScope, text: @Nls String) = Help(text, listOf(Help.gh(GhCommands.rebase(scope))))

    fun rebaseStack() = rebase(RebaseScope.STACK, message("action.Stacklane.Rebase.description"))

    /** Desde [branch]: si no es la rama actual, antes el checkout, y al terminar se vuelve. */
    fun rebaseUpstackFrom(snapshot: StackSnapshot, branch: String) = Help(
        message("help.rebase.upstack.from", branch, snapshot.trunk),
        listOfNotNull(
            branch.takeIf { it != snapshot.currentBranch }?.let(Help::checkout),
            Help.gh(GhCommands.rebase(RebaseScope.UPSTACK)),
        ),
    )

    fun continueRebase() = Help(message("help.rebase.continue"), listOf(Help.gh(GhCommands.rebaseContinue())))

    fun abortRebase() = Help(message("help.rebase.abort"), listOf(Help.gh(GhCommands.rebaseAbort())))

    fun resolveConflicts() = Help(message("help.resolve.conflicts"))

    fun push() = Help(message("help.push"), listOf(Help.gh(GhCommands.push())))

    private const val INIT = "gh stack init --base BASE BRANCH…"
}
