package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.stacklane.Notifier
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.gh.GhNotFoundException
import com.stacklane.gh.GhResult
import com.stacklane.gh.LoginPrompt
import com.stacklane.stack.StackService
import kotlinx.coroutines.Job
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * *Log In*: `gh auth login --web` sin terminal. gh pide a GitHub un codigo de un solo uso, lo
 * imprime y espera; aqui se ensena, se copia y se abre la pagina donde escribirlo. El dialogo se
 * cierra solo cuando gh termina con la sesion guardada, y cancelarlo mata a gh sin cambiar nada.
 * El token lo guarda gh, como siempre: el plugin no lo ve.
 */
internal class LoginDialog(project: Project, private val host: String) : DialogWrapper(project) {

    private val service = StackService.getInstance(project)
    private val code = JBLabel(" ").apply { font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(CODE_FONT_SIZE)) }
    private val status = JBLabel(message("login.requesting"), AnimatedIcon.Default(), SwingConstants.LEADING)
    private val preview = CommandPreview()
    private var prompt: LoginPrompt? = null
    private val job: Job

    /** gh termino bien: hay sesion en [host]. */
    var signedIn = false
        private set

    // Para los tests, que no ven un dialogo sin pantalla: lo que ensena.
    internal val shownCode: String get() = code.text.trim()
    internal val shownStatus: String get() = status.text
    internal val canOpenGitHub: Boolean get() = openAction.isEnabled

    private val openAction = object : DialogWrapperAction(message("login.open")) {
        init {
            isEnabled = false
            putValue(DEFAULT_ACTION, true)
            putValue(Action.SHORT_DESCRIPTION, Help(message("help.login.open", host)).html)
        }

        override fun doAction(e: ActionEvent) {
            val current = prompt ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(current.code))
            BrowserUtil.browse(current.url)
            status.text = message("login.waiting")
        }
    }

    init {
        title = message("login.title", host)
        init()
        preview.show(listOf(GhCommands.login(host)))
        job = service.launch {
            val result = try {
                service.logIn(host) { onEdt { showPrompt(it) } }
            } catch (_: GhNotFoundException) {
                onEdt { fail(message("state.gh.missing")) }
                return@launch
            }
            onEdt { finished(result) }
        }
    }

    override fun createActions(): Array<Action> = arrayOf(openAction, cancelAction)

    override fun createCenterPanel(): JComponent = panel {
        row { cell(WrappingText(message("login.text", host))).align(AlignX.FILL).resizableColumn() }
        row { cell(code) }.topGap(TopGap.SMALL)
        row { cell(status) }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }.apply { preferredSize = JBUI.size(WIDTH, preferredSize.height) }

    override fun dispose() {
        job.cancel()
        super.dispose()
    }

    private fun showPrompt(prompt: LoginPrompt) {
        this.prompt = prompt
        code.text = prompt.code
        status.text = message("login.enter.code", prompt.url)
        openAction.isEnabled = true
    }

    private fun finished(result: GhResult) {
        if (result.ok) {
            signedIn = true
            close(OK_EXIT_CODE)
        } else {
            fail(result.errorText.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit code ${result.exitCode}")
        }
    }

    /** gh termino sin sesion (el codigo caduco, no hay red): se queda abierto diciendolo. */
    private fun fail(text: String) {
        prompt = null
        code.text = " "
        status.icon = AllIcons.General.Error
        status.text = text
        openAction.isEnabled = false
        setCancelButtonText(message("login.close"))
    }

    // Con el dialogo modal abierto, y solo para tocar sus componentes.
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    companion object {
        private const val CODE_FONT_SIZE = 22f
        private const val WIDTH = 460

        /**
         * Abre el dialogo y espera a que termine. Si se inicio sesion, lo dice y vuelve a leer la
         * pila. En el EDT.
         */
        fun logIn(project: Project, host: String): Boolean {
            val dialog = LoginDialog(project, host)
            // show() y no showAndGet(): ver RemoteChooser.
            dialog.show()
            if (!dialog.signedIn) return false
            Notifier.info(project, message("login.done", host))
            StackService.getInstance(project).requestRefresh()
            return true
        }
    }
}
