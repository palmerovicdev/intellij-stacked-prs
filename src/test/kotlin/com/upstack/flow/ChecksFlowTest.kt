package com.upstack.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.UiInterceptors
import com.upstack.actions.LayerSelection
import com.upstack.actions.StackDataKeys
import com.upstack.stack.StackState
import com.upstack.ui.ChecksDialog
import java.util.concurrent.atomic.AtomicReference

/**
 * *Checks…* on a layer: the window with every check of its pull request, read when it opens, and
 * on each failed one *Why it failed*, *Re-run* (GitHub Actions only) and *Open on GitHub*.
 */
class ChecksFlowTest : FlowTestCase() {

    private val checks = listOf("pr", "checks", "12", "--repo", "acme/shop", "--json", "name,state,bucket,link,workflow,description")
    private val failedLog = listOf("run", "view", "7001", "--repo", "acme/shop", "--log-failed")
    private val rerun = listOf("run", "rerun", "7001", "--failed", "--repo", "acme/shop")

    private val actions = "build / test"
    private val vercel = "Vercel"

    private var dialog: ChecksDialog? = null

    override fun tearDown() {
        try {
            dialog?.let { shown -> onEdt { if (!shown.isDisposed) shown.close(DialogWrapper.CANCEL_EXIT_CODE) } }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** As plugin.xml registers it in the layer menu: with its text and description. */
    private fun action(): AnAction {
        val action = ActionManager.getInstance().getAction("Upstack.Checks")
        assertNotNull("Upstack.Checks is not registered", action)
        return action!!
    }

    private fun loadStack(): StackState.Loaded {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_FAILING_CHECKS)
        return awaitState { !it.detailsLoading }
    }

    /** The menu event on the layer of #12, as the Stacks list gives it. */
    private fun event(action: AnAction, loaded: StackState.Loaded): AnActionEvent {
        val layer = loaded.snapshot.layers.first { it.pr?.number == 12 }
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(StackDataKeys.LAYER, LayerSelection(loaded, layer))
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    /** *Checks…* from the menu of #12; the window, without a screen, once it shows the checks. */
    private fun openChecks(): ChecksDialog {
        val loaded = loadStack()
        val shown = AtomicReference<ChecksDialog>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<ChecksDialog>(ChecksDialog::class.java) {
            override fun doIntercept(component: ChecksDialog) = shown.set(component)
        })
        val action = action()
        val event = event(action, loaded)
        onEdt { action.update(event) }
        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)

        onEdt { action.actionPerformed(event) }
        waitUntil("the checks window opens") { shown.get() != null }
        val opened = shown.get()
        dialog = opened
        waitUntil({ "the checks are shown, status: ${onEdt { opened.shownStatus }}" }) { onEdt { opened.isLoaded } }
        return opened
    }

    private fun links(dialog: ChecksDialog, check: String): List<String> = onEdt { dialog.linksOf(check).map { it.text } }

    private fun click(dialog: ChecksDialog, check: String, link: String) = onEdt {
        val found = dialog.linksOf(check).firstOrNull { it.text == link }
        assertNotNull("no link “$link” on $check: ${dialog.linksOf(check).map { it.text }}", found)
        found!!.doClick()
    }

    private fun texts(dialog: ChecksDialog, check: String): String = onEdt { dialog.textsOf(check).joinToString("\n") }

    /** FakeGh only knows the reads of the stack: `gh pr checks` and `gh run view` also only read. */
    private fun writes(): List<List<String>> = gh.writes().filterNot { it.take(2) == listOf("pr", "checks") || it.take(2) == listOf("run", "view") }

    fun `test opening the window reads the checks of the pull request, failed first`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        val event = event(action(), loadStack())
        onEdt { action().update(event) }
        assertEquals(msg("action.Upstack.Checks.text"), event.presentation.text)
        assertTrue(event.presentation.description, event.presentation.description.contains("gh pr checks 12 --repo acme/shop --json name,state,bucket,link,workflow,description"))
        assertNotNull(event.presentation.getClientProperty(ActionUtil.TOOLTIP_TEXT))

        val dialog = openChecks()

        assertEquals(listOf(actions, vercel, "preview / deploy-preview", "build / lint"), onEdt { dialog.shownChecks() })
        assertEquals(listOf(msg("checks.why"), msg("checks.copy"), msg("checks.rerun"), msg("checks.open")), links(dialog, actions))
        assertTrue(texts(dialog, "preview / deploy-preview"), texts(dialog, "preview / deploy-preview").contains(msg("checks.status.pending")))
        // Read once, when the window opens, and nothing written.
        assertEquals(1, gh.calls().count { it == checks })
        assertEquals(emptyList<List<String>>(), writes())
        assertFalse(gh.calls().any { it.firstOrNull() == "run" })
    }

    fun `test opening the checks of the same pull request again brings back its window`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        val first = openChecks()

        val action = action()
        val event = event(action, service.state.value as StackState.Loaded)
        onEdt { action.actionPerformed(event) }
        awaitQuiet()

        // The same window, not a second one reading the checks again.
        assertFalse(first.isDisposed)
        assertEquals(1, gh.calls().count { it == checks })
    }

    fun `test re-run on a GitHub Actions check runs gh run rerun --failed and reads the checks again`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        gh.respond(rerun)
        val dialog = openChecks()

        click(dialog, actions, msg("checks.rerun"))
        awaitNotificationWith(msg("op.rerun.done", "build", 12))
        awaitIdle()
        waitUntil("the checks are read again") { gh.calls().count { it == checks } == 2 }
        waitUntil({ "the window says it was requested: ${texts(dialog, actions)}" }) { texts(dialog, actions).contains(msg("checks.rerun.requested")) }
        awaitQuiet()

        assertEquals(listOf(rerun), writes())
    }

    fun `test a check of another app offers no re-run and says who can`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        val dialog = openChecks()

        assertEquals(listOf(msg("checks.why"), msg("checks.copy"), msg("checks.open")), links(dialog, vercel))
        assertTrue(texts(dialog, vercel), texts(dialog, vercel).contains(msg("checks.rerun.other", "Vercel")))

        // Why it failed: what the app published, without asking gh for a log it does not have.
        val before = gh.calls().size
        click(dialog, vercel, msg("checks.why"))
        assertTrue(texts(dialog, vercel), texts(dialog, vercel).contains("Deployment has failed"))
        assertTrue(texts(dialog, vercel), texts(dialog, vercel).contains(msg("checks.why.app", "Vercel")))
        assertEquals(before, gh.calls().size)
        assertEquals(emptyList<List<String>>(), writes())
    }

    fun `test why it failed shows the failed steps of the Actions job`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        gh.respond(failedLog, stdout = Samples.FAILED_LOG)
        val dialog = openChecks()

        click(dialog, actions, msg("checks.why"))
        waitUntil({ "the log is shown: ${texts(dialog, actions)}" }) { texts(dialog, actions).contains("expected 3 items, got 2") }

        val shown = texts(dialog, actions)
        assertTrue(shown, shown.contains("▶ test › Run tests\nFAIL src/cart.test.ts\n  expected 3 items, got 2\n##[error]Process completed with exit code 1."))
        // Only the job of this check: the other failed job of the run is not this check's reason.
        assertFalse(shown, shown.contains("timeout waiting for the server"))
        assertEquals(1, gh.calls().count { it == failedLog })
        assertEquals(emptyList<List<String>>(), writes())
    }

    fun `test copy reads the failed steps when they are not shown yet and puts them on the clipboard`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        gh.respond(failedLog, stdout = Samples.FAILED_LOG)
        val dialog = openChecks()

        click(dialog, actions, msg("checks.copy"))
        waitUntil({ "the log is copied: ${onEdt { clipboard() }}" }) { onEdt { clipboard() }.contains("expected 3 items, got 2") }

        assertEquals("▶ test › Run tests\nFAIL src/cart.test.ts\n  expected 3 items, got 2\n##[error]Process completed with exit code 1.", onEdt { clipboard() })
        // Reading it also shows it, and the link says so for a moment.
        assertTrue(texts(dialog, actions), texts(dialog, actions).contains("expected 3 items, got 2"))
        assertTrue(links(dialog, actions).toString(), msg("checks.copied") in links(dialog, actions))
        assertEquals(1, gh.calls().count { it == failedLog })
        assertEquals(emptyList<List<String>>(), writes())
    }

    fun `test copy on a check of another app copies what the app published`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        val dialog = openChecks()

        click(dialog, vercel, msg("checks.copy"))
        waitUntil({ "the summary is copied: ${onEdt { clipboard() }}" }) { onEdt { clipboard().startsWith(vercel) } }

        assertTrue(onEdt { clipboard() }.contains("https://"))
    }

    private fun clipboard(): String =
        com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor).orEmpty()

    fun `test a refused re-run shows the error of gh in the window`() {
        gh.respond(checks, stdout = Samples.CHECKS_FAILING)
        val refusal = "HTTP 403: Must have admin rights to Repository. (https://api.github.com/repos/acme/shop/actions/runs/7001/rerun-failed-jobs)"
        gh.respond(rerun, exit = 1, stderr = "$refusal\n")
        val dialog = openChecks()

        click(dialog, actions, msg("checks.rerun"))
        waitUntil({ "the error is shown: ${texts(dialog, actions)}" }) { texts(dialog, actions).contains(refusal) }
        awaitIdle()
        awaitQuiet()

        assertEquals(listOf(rerun), writes())
        // Said in the window, where the button was pressed: no second, generic error.
        assertFalse(describeNotifications(), notifications.any { it.type == NotificationType.ERROR })
    }
}
