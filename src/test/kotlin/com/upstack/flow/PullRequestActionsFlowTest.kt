package com.upstack.flow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.UiInterceptors
import com.upstack.actions.PrTarget
import com.upstack.actions.PullRequestDataKeys
import com.upstack.stack.OpenPullRequest
import com.upstack.stack.StackJson
import com.upstack.ui.EditPrDialog
import com.upstack.ui.PullRequestList
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicInteger

class PullRequestActionsFlowTest : FlowTestCase() {
    private val changes = AtomicInteger()

    private fun target(draft: Boolean = false): PrTarget {
        val details = StackJson.parsePrDetails(Samples.DETAILS_TWO_LAYERS).getValue(12).copy(isDraft = draft)
        val pr = OpenPullRequest(12, details.title, Samples.URL_B, "feat/b", "feat/a", draft, details = details)
        return requireNotNull(PrTarget.fromPullRequest(pr) { changes.incrementAndGet() })
    }

    private fun event(id: String, target: PrTarget): com.intellij.openapi.actionSystem.AnActionEvent {
        val action = ActionManager.getInstance().getAction(id)
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PullRequestDataKeys.TARGET, target).build()
        return TestActionEvent.createTestEvent(action, context)
    }

    fun `test list edit loads current title and body and refreshes after success`() {
        val target = target()
        val read = listOf("pr", "view", Samples.URL_B, "--json", "body")
        val edit = listOf("pr", "edit", Samples.URL_B, "--title", "Updated", "--body", "New body")
        gh.respond(read, stdout = "{\"body\":\"Old body\"}")
        gh.respond(edit)
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<EditPrDialog>(EditPrDialog::class.java) {
            override fun doIntercept(component: EditPrDialog) {
                assertEquals("API", component.newTitle)
                assertEquals("Old body", component.editor.bodyArea.text)
                component.enterTitle("Updated")
                component.editor.bodyArea.text = "New body"
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })
        val event = event("Upstack.EditTitle", target)
        val action = ActionManager.getInstance().getAction("Upstack.EditTitle")
        onEdt { action.update(event); assertTrue(event.presentation.isVisible); action.actionPerformed(event) }
        awaitNotificationWith(msg("op.title.done", 12, "Updated"))
        waitUntil("list refreshed") { changes.get() == 1 }
        assertTrue(gh.calls().contains(read))
        assertTrue(gh.calls().contains(edit))
    }

    fun `test draft action is available for list selection and refreshes on success`() {
        val target = target(draft = true)
        val ready = listOf("pr", "ready", Samples.URL_B)
        gh.respond(ready)
        val event = event("Upstack.MarkReady", target)
        val action = ActionManager.getInstance().getAction("Upstack.MarkReady")
        onEdt { action.update(event); assertTrue(event.presentation.isVisible); action.actionPerformed(event) }
        awaitNotificationWith(msg("op.ready.done", 12))
        waitUntil("list refreshed") { changes.get() == 1 }
        assertTrue(gh.calls().contains(ready))
    }

    fun `test list selection rejects a URL for a different pull request`() {
        assertNull(PrTarget.fromPullRequest(OpenPullRequest(99, "Title", Samples.URL_B, "branch", "main", false)) {})
    }

    fun `test list popup reuses PR actions and excludes stack operations`() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Upstack.PullRequest.Popup") as ActionGroup
        val ids = group.getChildren(null).mapNotNull(manager::getId).toSet()
        assertTrue(ids.containsAll(setOf("Upstack.OpenPr", "Upstack.Checks", "Upstack.CopyPrLink", "Upstack.MarkReady", "Upstack.MarkDraft", "Upstack.EditTitle", "Upstack.EditLabels")))
        assertTrue("Upstack.ReviewChanges" in ids)
        assertFalse("Upstack.MergeStack" in ids)
        assertFalse("Upstack.ToggleFinal" in ids)
        val layers = manager.getAction("Upstack.Layer.Popup") as ActionGroup
        assertTrue(layers.getChildren(null).mapNotNull(manager::getId).containsAll(listOf("Upstack.ShowLayerChanges", "Upstack.ReviewChanges")))
    }

    fun `test failure does not report a successful list change`() {
        val ready = listOf("pr", "ready", Samples.URL_B)
        gh.respond(ready, exit = 1, stderr = "Permission denied")
        val event = event("Upstack.MarkReady", target(draft = true))
        val action = ActionManager.getInstance().getAction("Upstack.MarkReady")
        onEdt { action.actionPerformed(event) }
        awaitNotificationWith(msg("operation.failed", msg("op.ready", 12)))
        awaitQuiet()
        assertEquals(0, changes.get())
    }

    fun `test selected list row supplies its target to menu data context`() {
        onEdt {
            val panel = PullRequestsPanel(project)
            Disposer.register(testRootDisposable, panel)
            fun descendants(component: Component): List<Component> =
                listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())
            val list = descendants(panel).filterIsInstance<PullRequestList>().single()
            val details = StackJson.parsePrDetails(Samples.DETAILS_TWO_LAYERS).getValue(12)
            val pr = OpenPullRequest(12, details.title, Samples.URL_B, "feat/b", "feat/a", false, details = details)
            (list.model as CollectionListModel<OpenPullRequest>).replaceAll(listOf(pr))
            list.selectedIndex = 0
            val context = panel.selectionContext()
            val selected = PullRequestDataKeys.TARGET.getData(context)
            assertNotNull(selected)
            assertEquals(12, selected!!.number)
            assertEquals("API", selected.details?.title)
        }
    }
}
