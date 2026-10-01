package com.stacklane.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.stacklane.actions.StackFlows
import com.stacklane.gh.MergeMethod
import com.stacklane.settings.StacklaneProjectSettings
import com.stacklane.stack.StackState
import com.stacklane.ui.MergeStackDialog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * *Merge Stack…* y *Merge Up to Here…* de la accion al ultimo comando: la lectura de como fusiona
 * el repositorio, el dialogo, la comprobacion del numero y `gh stack merge`.
 */
class MergeStackFlowTest : FlowTestCase() {

    private val stack = "main ← feat/a ← feat/b"

    /** Va antes que la consulta de la ventana: las dos son `gh api graphql` y gana la primera. */
    private fun mergeSettings(queue: Boolean = false, default: String = "SQUASH") {
        gh.respond(
            listOf("api", "graphql", "--hostname", "github.com", "-f", "owner=acme", "-f", "name=shop", "-f", "base=main"),
            prefix = true,
            stdout = """{"data":{"repository":{"mergeCommitAllowed":true,"squashMergeAllowed":true,"rebaseMergeAllowed":true,
                "viewerDefaultMergeMethod":"$default","mergeQueue":${if (queue) """{"id":"MQ_1"}""" else "null"},"ref":null}}}""",
        )
    }

    private fun noStackNumbered(number: Int) {
        gh.respond(stackRead(number), exit = 1, stderr = "gh: Not Found (HTTP 404)\n")
    }

    private fun stackRead(number: Int) = listOf("api", "repos/acme/shop/stacks/$number", "--hostname", "github.com")

    private fun loadStack(): StackState.Loaded {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_READY)
        return awaitState { !it.detailsLoading }
    }

    fun `test merges the whole stack with the method used last time`() {
        StacklaneProjectSettings.getInstance(project).mergeMethod = MergeMethod.REBASE
        mergeSettings()
        loadStack()
        noStackNumbered(12)
        gh.respond(listOf("stack", "merge", "12", "--yes", "--rebase"), stderr = "✓ Merged #11, #12 into main (abc1234)\n")
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project) }
        awaitNotificationWith(msg("op.merge.done", "#11, #12", "main"))
        awaitIdle()
        awaitQuiet()

        assertEquals(listOf(listOf("stack", "merge", "12", "--yes", "--rebase")), gh.writes())
        // Antes del merge, la comprobacion de que no hay una pila con ese numero.
        assertTrue(gh.calls().indexOf(stackRead(12)) < gh.calls().indexOf(listOf("stack", "merge", "12", "--yes", "--rebase")))
    }

    fun `test merges up to a layer with the default method of the user and remembers it`() {
        mergeSettings(default = "MERGE")
        loadStack()
        noStackNumbered(11)
        gh.respond(listOf("stack", "merge", "11", "--yes", "--merge"), stderr = "✓ Merged #11 into main\n")
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project, upTo = "feat/a") }
        awaitNotificationWith(msg("op.merge.done", "#11", "main"))
        awaitIdle()
        awaitQuiet()

        assertEquals(listOf(listOf("stack", "merge", "11", "--yes", "--merge")), gh.writes())
        assertEquals(MergeMethod.MERGE, StacklaneProjectSettings.getInstance(project).mergeMethod)
    }

    fun `test a stack with the same number as the pull request is never merged`() {
        mergeSettings()
        loadStack()
        gh.respond(stackRead(12), stdout = """{"number":12,"pull_requests":[{"number":3},{"number":4}]}""")
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project) }
        val warning = awaitNotificationWith(msg("merge.number.taken", 12))
        assertEquals(NotificationType.WARNING, warning.type)
        awaitIdle()
        awaitQuiet()
        assertEquals(emptyList<Any>(), gh.writes())
    }

    fun `test with a merge queue no method is sent and it says they were queued`() {
        StacklaneProjectSettings.getInstance(project).mergeMethod = MergeMethod.SQUASH
        mergeSettings(queue = true)
        loadStack()
        noStackNumbered(12)
        gh.respond(listOf("stack", "merge", "12", "--yes"), stderr = "✓ Added #11, #12 to the merge queue for main\n")
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project) }
        awaitNotificationWith(msg("op.merge.queued", "#11, #12", "main"))
        awaitIdle()
        awaitQuiet()
        assertEquals(listOf(listOf("stack", "merge", "12", "--yes")), gh.writes())
    }

    fun `test conflicts on GitHub are reported as nothing merged, not as a local rebase`() {
        mergeSettings()
        loadStack()
        noStackNumbered(12)
        gh.respond(listOf("stack", "merge", "12", "--yes", "--squash"), exit = 3, stderr = "✗ merge failed: merge conflict\n")
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project) }
        val error = awaitNotificationWith(msg("merge.conflict", "main"))
        assertEquals(NotificationType.ERROR, error.type)
        awaitIdle()
        awaitQuiet()
        assertTrue(notifications.none { it.content == msg("conflict.sync") })
    }

    fun `test after the merge, Sync and Prune deletes the merged branches`() {
        mergeSettings()
        loadStack()
        noStackNumbered(12)
        gh.respond(listOf("stack", "merge", "12", "--yes", "--squash"), stderr = "✓ Merged #11, #12 into main\n")
        gh.respond(listOf("stack", "sync", "--prune"))
        answerNextDialog(accept = true)

        onEdt { StackFlows.mergeStack(project) }
        val done = awaitNotificationWith(msg("op.merge.done", "#11, #12", "main"))
        awaitIdle()
        press(done, msg("action.sync.prune"))
        awaitNotificationWith(msg("op.sync.prune.done"))
        awaitIdle()
        awaitQuiet()
        assertEquals(
            listOf(listOf("stack", "merge", "12", "--yes", "--squash"), listOf("stack", "sync", "--prune")),
            gh.writes(),
        )
    }

    fun `test a draft at the bottom leaves nothing to merge`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        awaitState<StackState.Loaded> { !it.detailsLoading }

        onEdt { StackFlows.mergeStack(project) }
        awaitNotificationWith(msg("merge.nothing.blocked", "#11 feat/a", msg("merge.block.draft")))
        awaitQuiet()
        assertEquals(emptyList<Any>(), gh.writes())
    }

    fun `test cancelling the dialog runs nothing`() {
        mergeSettings()
        loadStack()
        val shown = AtomicBoolean()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<MergeStackDialog>(MergeStackDialog::class.java) {
            override fun doIntercept(component: MergeStackDialog) {
                shown.set(true)
                component.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        })

        onEdt { StackFlows.mergeStack(project) }
        waitUntil("the dialog is shown") { shown.get() }
        awaitIdle()
        awaitQuiet()
        assertEquals(emptyList<Any>(), gh.writes())
        assertFalse(gh.calls().any { it.take(2) == listOf("api", "repos/acme/shop/stacks/12") })
    }

    fun `test merged layers that still have their branch are what Sync and Prune deletes`() {
        git("branch", "feat/a")
        repository.update()
        val view = Samples.VIEW_TWO_LAYERS.replaceFirst("\"isMerged\":false", "\"isMerged\":true")
        gh.respond(VIEW, stdout = view)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_READY)
        val state = awaitState<StackState.Loaded> { !it.detailsLoading }
        assertEquals(listOf("feat/a"), StackFlows.mergedWithLocalBranch(repository, state.snapshot))
        awaitQuiet()
    }
}
