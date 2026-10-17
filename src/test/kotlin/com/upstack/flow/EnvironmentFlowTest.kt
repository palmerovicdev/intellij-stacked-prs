package com.upstack.flow

import com.intellij.openapi.ui.DialogWrapper
import com.upstack.actions.StackFlows
import com.upstack.gh.EnvironmentReport
import com.upstack.gh.GhResult
import com.upstack.gh.LoginPrompt
import com.upstack.gh.StacksSupport
import com.upstack.gh.VersionStatus
import com.upstack.settings.UpstackSettings
import com.upstack.stack.StackState
import com.upstack.ui.EnvironmentPanel
import com.upstack.ui.LoginDialog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P4: lo que el plugin necesita para funcionar (gh, su sesion, gh-stack, pilas en el repositorio),
 * comprobado con los mismos comandos que en la terminal, y lo que se ofrece cuando falta la sesion.
 */
class EnvironmentFlowTest : FlowTestCase() {

    private val signedIn = """
        {"hosts":{"github.com":[{"state":"success","active":true,"host":"github.com","login":"octo","tokenSource":"keyring"}]}}
    """.trimIndent()

    private val noSession =
        "To get started with GitHub CLI, please run:  gh auth login\nAlternatively, populate the GH_TOKEN environment variable with a GitHub API authentication token.\n"

    private val stacksApi = listOf("api", "repos/acme/shop/stacks?per_page=1")

    private fun respondBasics(auth: String = signedIn) {
        gh.respond(listOf("--version"), stdout = "gh version 2.102.0 (2026-09-30)\nhttps://github.com/cli/cli/releases/tag/v2.102.0\n")
        gh.respond(listOf("auth", "status", "--json", "hosts"), stdout = auth)
    }

    private fun check(): EnvironmentReport {
        var report: EnvironmentReport? = null
        val job = service.launch { report = service.checkEnvironment() }
        waitUntil("the environment check finishes") { job.isCompleted }
        return requireNotNull(report)
    }

    fun `test the check reports gh, its session, gh-stack and the repository`() {
        respondBasics()
        gh.respond(stacksApi, stdout = "[]")

        val report = check()
        assertEquals(gh.executable, report.gh)
        assertNull(report.invalidPath)
        assertEquals("2.102.0", report.ghVersion)
        assertEquals(listOf("octo"), report.session?.accounts?.map { it.login })
        assertTrue(report.session?.signedInTo("github.com") == true)
        assertEquals("0.1.1", report.extension?.version)
        assertEquals(VersionStatus.KNOWN, report.extension?.status)
        val repository = requireNotNull(report.repository)
        assertEquals(listOf("origin" to "acme/shop", "upstream" to "acme-upstream/shop"), repository.remotes.map { it.name to it.github?.cliName })
        assertEquals("acme/shop", repository.github?.cliName)
        assertEquals(StacksSupport.AVAILABLE, repository.stacks)
        assertEquals("github.com", report.loginHost)
        // Solo lee.
        assertEquals(listOf(listOf("--version"), listOf("auth", "status", "--json", "hosts")), gh.writes())
    }

    fun `test a repository where stacks are not enabled says so`() {
        respondBasics()
        gh.respond(stacksApi, exit = 1, stdout = """{"message":"Not Found","status":"404"}""", stderr = "gh: Not Found (HTTP 404)\n")

        assertEquals(StacksSupport.UNAVAILABLE, check().repository?.stacks)
    }

    fun `test without a session the repository is not asked about stacks`() {
        respondBasics(auth = "You are not logged into any GitHub hosts. To log in, run: gh auth login\n{\"hosts\":{}}\n")

        val report = check()
        assertFalse(report.session?.signedIn == true)
        assertEquals(StacksSupport.UNKNOWN, report.repository?.stacks)
        assertNull(report.repository?.stacksError)
        assertFalse(gh.calls().any { it == stacksApi })
    }

    fun `test a gh without json in auth status still says whether there is a session`() {
        gh.respond(listOf("--version"), stdout = "gh version 2.40.0 (2023-12-07)\n")
        gh.respond(listOf("auth", "status", "--json", "hosts"), exit = 1, stderr = "unknown flag: --json\n")
        gh.respond(listOf("auth", "status"), stdout = "github.com\n  ✓ Logged in to github.com account octo (keyring)\n")
        gh.respond(stacksApi, stdout = "[]")

        val report = check()
        assertTrue(report.session?.signedIn == true)
        assertEquals("github.com", report.session?.text?.lineSequence()?.first())
        assertEquals(StacksSupport.AVAILABLE, report.repository?.stacks)
    }

    fun `test gh without the extension is reported as not installed`() {
        respondBasics()
        gh.respond(STACK_VERSION, exit = 1, stderr = "unknown command \"stack\" for \"gh\"\n")
        gh.respond(stacksApi, stdout = "[]")

        val extension = requireNotNull(check().extension)
        assertFalse(extension.installed)
    }

    // ---------------------------------------------------------------- iniciar sesion

    fun `test log in hands over the one-time code and ends when gh does`() {
        val login = listOf("auth", "login", "--web", "--hostname", "github.com")
        gh.respond(
            login,
            stderr = "\n! One-time code (1A2B-3C4D) copied to clipboard\nOpen this URL to continue in your web browser: https://github.com/login/device\n" +
                "✓ Authentication complete.\n✓ Logged in as octo\n",
        )

        val prompts = CopyOnWriteArrayList<LoginPrompt>()
        var result: GhResult? = null
        val job = service.launch { result = service.logIn("github.com") { prompts += it } }
        waitUntil("gh auth login finishes") { job.isCompleted }

        assertTrue(result?.ok == true)
        assertEquals(LoginPrompt("1A2B-3C4D", "https://github.com/login/device"), prompts.last())
        assertTrue(login in gh.writes())
    }

    fun `test pull request details without a session offer to log in`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, exit = 4, stderr = noSession)

        val state = awaitState<StackState.Loaded> { !it.detailsLoading }
        assertEquals("github.com", state.loginHost)
        assertEquals("To get started with GitHub CLI, please run:  gh auth login", state.detailsError)
    }

    fun `test details that fail for another reason do not offer to log in`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, exit = 1, stderr = "HTTP 502: Bad Gateway\n")

        val state = awaitState<StackState.Loaded> { !it.detailsLoading }
        assertNull(state.loginHost)
    }

    fun `test a write that fails without a session offers to log in`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        awaitState<StackState.Loaded> { !it.detailsLoading }
        gh.respond(listOf("stack", "submit", "--auto"), exit = 4, stderr = noSession)

        onEdt { StackFlows.publish(project, ready = false) }
        val notification = awaitNotificationWith(msg("error.signed.out", "github.com"))
        assertEquals(listOf(msg("action.log.in"), msg("notification.show.log")), notification.actions.map { it.templateText })
        awaitIdle()
        awaitQuiet()
    }
}

/** El aviso de una version de gh-stack que no es la de referencia: una vez, y sin bloquear nada. */
class StackVersionFlowTest : FlowTestCase() {

    override fun stackVersion(): String = if ("newer" in name) "0.2.0" else "0.1.0"

    override fun beforeFirstRefresh() {
        if ("already" in name) UpstackSettings.getInstance().warnedStackVersion = "0.1.0"
    }

    fun `test an older gh-stack warns once and offers to upgrade`() {
        val notification = awaitNotificationWith(msg("version.title"))
        assertEquals(msg("version.older", "0.1.0", "0.1.1"), notification.content)
        assertEquals(listOf(msg("action.upgrade.extension"), msg("action.open.settings")), notification.actions.map { it.templateText })
        // Es un aviso: la pila se lee igual.
        awaitState<StackState.NotInStack>()

        gh.respond(listOf("extension", "upgrade", "gh-stack"), stdout = "[stack]: upgraded from v0.1.0 to v0.1.1\n")
        press(notification, msg("action.upgrade.extension"))
        val done = awaitNotificationWith(msg("op.upgrade.done"))
        assertEquals("[stack]: upgraded from v0.1.0 to v0.1.1", done.content)
        assertEquals(listOf(listOf("extension", "upgrade", "gh-stack")), gh.writes())
        awaitIdle()
        awaitQuiet()
    }

    fun `test a newer gh-stack warns without offering an upgrade`() {
        val notification = awaitNotificationWith(msg("version.title"))
        assertEquals(msg("version.newer", "0.2.0", "0.1.1"), notification.content)
        assertEquals(listOf(msg("action.open.settings")), notification.actions.map { it.templateText })
    }

    fun `test a version already warned about is not announced again`() {
        awaitState<StackState.NotInStack>()
        waitUntil("gh stack --version was asked") { STACK_VERSION in gh.calls() }
        awaitQuiet()
        assertTrue(describeNotifications(), notifications.none { it.title == msg("version.title") })
    }
}

/** Lo que se ve: el resumen de *Check* en los ajustes y el dialogo de *Log In*. */
class EnvironmentUiFlowTest : FlowTestCase() {

    private val login = listOf("auth", "login", "--web", "--hostname", "github.com")
    private val prompt = "\n! One-time code (1A2B-3C4D) copied to clipboard\nOpen this URL to continue in your web browser: https://github.com/login/device\n"

    override fun stackVersion(): String = if ("summary" in name) "0.1.0" else super.stackVersion()

    override fun beforeFirstRefresh() {
        // El aviso de la version no es de estos tests.
        UpstackSettings.getInstance().warnedStackVersion = stackVersion()
    }

    fun `test the summary has a line per finding and the link that fixes it`() {
        gh.respond(listOf("--version"), stdout = "gh version 2.102.0 (2026-09-30)\n")
        gh.respond(listOf("auth", "status", "--json", "hosts"), stdout = "{\"hosts\":{}}")

        val panel = onEdt { EnvironmentPanel(project, { gh.executable.toString() }, {}) }
        assertEquals(listOf(msg("environment.hint")), onEdt { panel.lines() })
        onEdt { panel.check() }
        waitUntil({ "the summary, last: ${onEdt { panel.lines() }}" }) { onEdt { panel.lines() }.size > 1 }

        assertEquals(
            listOf(
                msg("environment.gh", "2.102.0", gh.executable.toString()),
                msg("environment.signed.out", "github.com") + " | " + msg("action.log.in"),
                msg("version.older", "0.1.0", "0.1.1") + " | " + msg("action.upgrade.extension"),
                msg("environment.remote", "origin", "acme/shop", "git@github.com:acme/shop.git"),
                msg("environment.remote", "upstream", "acme-upstream/shop", "git@github.com:acme-upstream/shop.git"),
            ),
            onEdt { panel.lines() },
        )
        onEdt { panel.dispose() }
    }

    fun `test the login dialog shows the code and closes itself when gh has the session`() {
        val rule = gh.respond(login, stderr = prompt, linger = true)

        val dialog = onEdt { LoginDialog(project, "github.com") }
        waitUntil({ "the code, status: ${onEdt { dialog.shownStatus }}" }) { onEdt { dialog.shownCode } == "1A2B-3C4D" }
        assertTrue(onEdt { dialog.canOpenGitHub })
        assertEquals(msg("login.enter.code", "https://github.com/login/device"), onEdt { dialog.shownStatus })
        assertFalse(dialog.signedIn)

        rule.finish()
        waitUntil("the dialog closes") { dialog.isDisposed }
        assertTrue(dialog.signedIn)
    }

    fun `test the login dialog stays open saying why gh failed`() {
        gh.respond(login, exit = 1, stderr = prompt + "error: authentication failed: device code expired\n")

        val dialog = onEdt { LoginDialog(project, "github.com") }
        waitUntil({ "the error, status: ${onEdt { dialog.shownStatus }}" }) {
            onEdt { dialog.shownStatus } == "error: authentication failed: device code expired"
        }
        assertFalse(onEdt { dialog.canOpenGitHub })
        assertEquals("", onEdt { dialog.shownCode })
        assertFalse(dialog.signedIn)
        assertFalse(dialog.isDisposed)
        onEdt { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test cancelling the login dialog stops gh and leaves the session as it was`() {
        gh.respond(login, stderr = prompt, linger = true)

        val dialog = onEdt { LoginDialog(project, "github.com") }
        waitUntil("the code") { onEdt { dialog.shownCode } == "1A2B-3C4D" }
        onEdt { dialog.doCancelAction() }

        assertTrue(dialog.isDisposed)
        assertFalse(dialog.signedIn)
        // Si gh siguiera vivo, el test fallaria al cerrar por el hilo que lo lee.
        awaitQuiet()
    }
}
