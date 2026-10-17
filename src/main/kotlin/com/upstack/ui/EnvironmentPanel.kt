package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.upstack.UpstackBundle.message
import com.upstack.actions.StackFlows
import com.upstack.gh.EnvironmentReport
import com.upstack.gh.GhAccount
import com.upstack.gh.GhSession
import com.upstack.gh.RepositoryEnvironment
import com.upstack.gh.StackExtension
import com.upstack.gh.StacksSupport
import com.upstack.gh.VersionStatus
import com.upstack.stack.StackService
import kotlinx.coroutines.Job
import org.jetbrains.annotations.Nls
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * El resumen de *Check* en los ajustes: una linea por cosa comprobada (gh, la sesion, gh-stack,
 * el repositorio), con su icono y, si algo falta, el enlace que lo arregla. Tras instalar,
 * actualizar o iniciar sesion se comprueba otra vez.
 *
 * [ghPath]: la ruta escrita en el campo, aplicada o no. Se comprueba esa; los enlaces que
 * ejecutan `gh` la aplican antes ([applyGhPath]), para correr con el mismo `gh` que se comprobo.
 */
internal class EnvironmentPanel(
    private val project: Project,
    private val ghPath: () -> String,
    private val applyGhPath: () -> Unit,
) : JPanel(VerticalLayout(JBUI.scale(4))) {

    private val service = StackService.getInstance(project)
    private var job: Job? = null

    init {
        isOpaque = false
        add(secondary(message("environment.hint")))
    }

    /** Lanza la comprobacion; una en curso se descarta. */
    fun check() {
        job?.cancel()
        removeAll()
        add(JBLabel(message("environment.checking"), AnimatedIcon.Default(), JBLabel.LEADING))
        refresh()
        val path = ghPath()
        val modality = ModalityState.stateForComponent(this)
        job = service.launch {
            val report = service.checkEnvironment(path)
            ApplicationManager.getApplication().invokeLater({ show(report) }, modality)
        }
    }

    fun dispose() {
        job?.cancel()
    }

    /** Para los tests: cada linea tal y como se lee, con sus enlaces detras de `|`. */
    internal fun lines(): List<String> = components.map { line ->
        if (line is JBLabel) line.text
        else (line as JPanel).components.joinToString(" | ") { (it as? JBLabel)?.text ?: (it as ActionLink).text }
    }

    private fun show(report: EnvironmentReport) {
        removeAll()
        report.invalidPath?.let { add(line(Level.WARNING, message("environment.path.invalid", it))) }
        if (report.gh == null) {
            add(
                line(
                    Level.ERROR, message("state.gh.missing"),
                    link(message("link.install.gh"), Helps.installGh()) { BrowserUtil.browse(Helps.GH_INSTALL_URL) },
                )
            )
        } else {
            add(line(Level.OK, message("environment.gh", report.ghVersion ?: "?", report.gh.toString())))
            report.session?.let { showSession(it, report.loginHost) }
            report.extension?.let(::showExtension)
        }
        report.repository?.let { showRepository(it, report) }
        refresh()
    }

    private fun showSession(session: GhSession, loginHost: String) {
        val logIn = { host: String -> link(message("action.log.in"), Helps.logIn(host)) { act { StackFlows.logIn(project, host) } } }
        session.accounts.forEach { account ->
            if (account.valid) add(line(if (account.active) Level.OK else Level.INFO, describe(account)))
            else add(line(Level.WARNING, message("environment.account.invalid", account.host, account.login), logIn(account.host)))
        }
        when {
            // Un gh sin `--json`: lo que imprimio, que ya dice la cuenta.
            session.text != null && session.signedIn -> add(line(Level.OK, session.text.lineSequence().first()))
            // Sin sesion en el host del repositorio, y sin una cuenta suya que ya ofrezca entrar.
            !session.signedInTo(loginHost) && session.accounts.none { it.host.equals(loginHost, true) } ->
                add(line(Level.ERROR, message("environment.signed.out", loginHost), logIn(loginHost)))
        }
    }

    private fun describe(account: GhAccount): @Nls String =
        if (account.active) message("environment.account", account.host, account.login, account.source)
        else message("environment.account.inactive", account.host, account.login)

    private fun showExtension(extension: StackExtension) {
        val upgrade = link(message("action.upgrade.extension"), Helps.upgradeExtension()) { act { service.upgradeExtension() } }
        when {
            !extension.installed -> add(
                line(
                    Level.ERROR, message("state.extension.missing"),
                    link(message("link.install.extension"), Helps.installExtension()) { act { StackFlows.installExtension(project) } },
                )
            )
            extension.status == VersionStatus.KNOWN -> add(line(Level.OK, message("environment.stack", extension.version.orEmpty())))
            extension.status == VersionStatus.OLDER ->
                add(line(Level.WARNING, message("version.older", extension.version.orEmpty(), StackExtension.REFERENCE), upgrade))
            // Una mas nueva no se arregla actualizando.
            extension.status == VersionStatus.NEWER ->
                add(line(Level.WARNING, message("version.newer", extension.version.orEmpty(), StackExtension.REFERENCE)))
            else -> add(line(Level.WARNING, message("version.unreadable", StackExtension.REFERENCE), upgrade))
        }
    }

    private fun showRepository(repository: RepositoryEnvironment, report: EnvironmentReport) {
        if (repository.remotes.isEmpty()) {
            add(line(Level.WARNING, message("environment.no.remotes", repository.name)))
            return
        }
        repository.remotes.forEach { remote ->
            if (remote.github != null) add(line(Level.INFO, message("environment.remote", remote.name, remote.github.cliName, remote.url)))
            else add(line(Level.WARNING, message("environment.remote.other", remote.name, remote.url)))
        }
        val github = repository.github ?: return
        // Sin gh o sin sesion no se pregunto: lo que falta ya lo dice su linea.
        if (report.gh == null || (repository.stacks == StacksSupport.UNKNOWN && repository.stacksError == null)) return
        when (repository.stacks) {
            StacksSupport.AVAILABLE -> add(line(Level.OK, message("environment.stacks.available", github.cliName)))
            StacksSupport.UNAVAILABLE -> add(line(Level.ERROR, message("environment.stacks.unavailable", github.cliName)))
            StacksSupport.UNKNOWN -> add(line(Level.WARNING, message("environment.stacks.unknown", github.cliName, repository.stacksError.orEmpty())))
        }
    }

    /** Lo que hace un enlace que ejecuta `gh`: con la ruta del campo, y despues se comprueba otra vez. */
    private fun act(run: () -> Any?) {
        applyGhPath()
        val modality = ModalityState.stateForComponent(this)
        val recheck = { ApplicationManager.getApplication().invokeLater({ check() }, modality) }
        // Instalar y actualizar corren en segundo plano; iniciar sesion ya termino al volver.
        (run() as? Job)?.invokeOnCompletion { recheck() } ?: recheck()
    }

    private enum class Level(val icon: Icon) {
        OK(AllIcons.General.InspectionsOK),
        INFO(AllIcons.General.Information),
        WARNING(AllIcons.General.Warning),
        ERROR(AllIcons.General.Error),
    }

    private fun line(level: Level, text: @Nls String, vararg links: JComponent): JComponent =
        JPanel(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(text, level.icon, JBLabel.LEADING))
            links.forEach(::add)
        }

    private fun link(text: @Nls String, help: Help, action: () -> Unit): JComponent =
        ActionLink(text) { action() }.apply { showHelp(help) }

    private fun secondary(text: @Nls String): JComponent = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun refresh() {
        revalidate()
        repaint()
    }
}
