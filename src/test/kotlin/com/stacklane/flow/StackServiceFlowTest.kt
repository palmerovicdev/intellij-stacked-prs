package com.stacklane.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.Messages
import com.stacklane.actions.StackFlows
import com.stacklane.gh.RebaseScope
import com.stacklane.stack.GhCall
import com.stacklane.stack.Operation
import com.stacklane.stack.StackState
import org.junit.Assume.assumeTrue

/** Las escrituras de [com.stacklane.stack.StackService]: remotos, avisos por codigo de salida, orden y exclusion. */
class StackServiceFlowTest : FlowTestCase() {

    private val submitDrafts = listOf("stack", "submit", "--auto")

    private fun loadStack(): StackState.Loaded {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        return awaitState { !it.detailsLoading }
    }

    private fun pr(vararg args: String) = GhCall(listOf("pr") + args)

    private fun operation(title: String, vararg calls: GhCall) = Operation(title, calls.toList(), repository)

    // ---------------------------------------------------------------- remotos

    fun `test several remotes ask once, retry with the chosen one and remember it`() {
        loadStack()
        gh.respond(submitDrafts, exit = 1, stderr = "error: multiple remotes found, set remote.pushDefault or use --remote\n")
        gh.respond(submitDrafts + listOf("--remote", "origin"))
        gh.respond(listOf("stack", "sync", "--remote", "origin"))
        answerNextDialog(accept = true)

        onEdt { StackFlows.publish(project, ready = false) }
        awaitNotificationWith(msg("op.publish.draft.done"))
        assertEquals(listOf(submitDrafts, submitDrafts + listOf("--remote", "origin")), gh.writes())
        assertEquals("origin", preferredRemote)

        // La siguiente ya sale con el remoto elegido, sin fallar antes.
        awaitIdle()
        onEdt { StackFlows.sync(project) }
        awaitNotificationWith(msg("op.sync.done"))
        assertEquals(listOf("stack", "sync", "--remote", "origin"), gh.writes().last())
        assertEquals(3, gh.writes().size)
    }

    fun `test cancelling the remote choice reports the original error and does not retry`() {
        loadStack()
        gh.respond(submitDrafts, exit = 1, stderr = "error: multiple remotes found\n")
        answerNextDialog(accept = false)

        onEdt { StackFlows.publish(project, ready = false) }
        val error = awaitNotificationWith(msg("operation.failed", msg("op.publish.draft")))
        assertEquals(NotificationType.ERROR, error.type)
        assertEquals("error: multiple remotes found", error.content)
        assertEquals(listOf(submitDrafts), gh.writes())
        assertNull(preferredRemote)
    }

    fun `test a remembered remote that no longer exists is not passed`() {
        loadStack()
        preferredRemote = "fork"
        gh.respond(listOf("stack", "sync"))

        onEdt { StackFlows.sync(project) }
        awaitNotificationWith(msg("op.sync.done"))
        assertEquals(listOf(listOf("stack", "sync")), gh.writes())
    }

    // ---------------------------------------------------------------- conflictos y codigos de salida

    fun `test a rebase stopped on conflicts offers resolve, continue and abort`() {
        loadStack()
        git("config", "gh-stack.rerere-declined", "true")
        val rebase = listOf("stack", "rebase", "--upstack", "--no-trunk")
        gh.respond(rebase, exit = 3, stderr = "CONFLICT (content): Merge conflict in README.md\n")
        gh.respond(listOf("stack", "rebase", "--abort"))
        leaveStackRebaseInProgress()

        service.rebase(RebaseScope.UPSTACK, repository)
        val warning = awaitNotificationWith(msg("conflict.rebase"))
        assertEquals(NotificationType.WARNING, warning.type)
        assertEquals(
            listOf(msg("action.resolve.conflicts"), msg("action.rebase.continue"), msg("action.rebase.abort"), msg("notification.show.log")),
            warning.actions.map { it.templateText },
        )

        awaitIdle()
        press(warning, msg("action.rebase.abort"))
        awaitNotificationWith(msg("op.rebase.aborted"))
        assertEquals(listOf(rebase, listOf("stack", "rebase", "--abort")), gh.writes())
    }

    fun `test a sync with conflicts and no rebase in progress offers to rebase the stack`() {
        loadStack()
        gh.respond(listOf("stack", "sync"), exit = 3, stderr = "conflicts rebasing feat/b; restored all branches\n")

        onEdt { StackFlows.sync(project) }
        val warning = awaitNotificationWith(msg("conflict.sync"))
        assertEquals(listOf(msg("action.rebase.stack"), msg("notification.show.log")), warning.actions.map { it.templateText })
    }

    fun `test gh-stack exit codes have their own message`() {
        val cases = mapOf(8 to "error.locked", 9 to "error.unavailable", 10 to "error.modify")
        for ((code, key) in cases) {
            gh.respond(listOf("stack", "sync", "--code", code.toString()), exit = code, stderr = "exit $code\n")
            service.execute(Operation("Sync $code", listOf(GhCall(listOf("stack", "sync", "--code", code.toString()))), repository))
            val notification = awaitNotificationWith(msg(key))
            assertEquals("Sync $code", notification.title)
            assertEquals(if (code == 8) NotificationType.WARNING else NotificationType.ERROR, notification.type)
            awaitIdle()
        }
    }

    fun `test any other failure shows the end of what gh printed`() {
        gh.respond(listOf("pr", "ready", Samples.URL_A), exit = 1, stderr = "line 1\n<b>not html</b>\n")

        service.execute(operation("Ready", pr("ready", Samples.URL_A)))
        val error = awaitNotificationWith(msg("operation.failed", "Ready"))
        assertEquals("line 1<br>&lt;b&gt;not html&lt;/b&gt;", error.content)
    }

    // ---------------------------------------------------------------- orden

    fun `test the first failing call stops the sequence and cleanup gets what completed`() {
        gh.respond(listOf("pr", "ready", "1"))
        gh.respond(listOf("pr", "ready", "2"), exit = 1, stderr = "boom\n")
        gh.respond(listOf("pr", "ready", "1", "--undo"))
        val completedSeen = mutableListOf<Int>()

        val job = service.execute(
            Operation(
                "Three",
                listOf(pr("ready", "1"), pr("ready", "2"), pr("ready", "3")),
                repository,
                cleanup = { completed ->
                    completedSeen += completed
                    if (completed == 1) listOf(pr("ready", "1", "--undo")) else emptyList()
                },
            )
        )
        awaitIdle(job)
        assertEquals(listOf(1), completedSeen)
        assertEquals(
            listOf(listOf("pr", "ready", "1"), listOf("pr", "ready", "2"), listOf("pr", "ready", "1", "--undo")),
            gh.writes(),
        )
        awaitNotificationWith(msg("operation.failed", "Three"))
    }

    fun `test a failure the operation handles itself has no generic notification`() {
        gh.respond(listOf("pr", "ready", "1"), exit = 1, stderr = "handled\n")
        var handled: String? = null

        val job = service.execute(
            Operation("Handled", listOf(pr("ready", "1")), repository, onFailure = { handled = it.errorText; true })
        )
        awaitIdle(job)
        assertEquals("handled", handled)
        assertEquals(describeNotifications(), 0, notifications.size)
    }

    fun `test a second write does not start while another one runs`() {
        val first = gh.respond(listOf("pr", "ready", "1"), hold = true)
        gh.respond(listOf("pr", "ready", "2"))

        val running = service.execute(operation("First", pr("ready", "1")))
        waitUntil("first write reaches gh") { listOf("pr", "ready", "1") in gh.writes() }
        assertEquals("First", service.running.value)

        val second = service.execute(operation("Second", pr("ready", "2")))
        val busy = awaitNotificationWith(msg("operation.busy", "First"))
        assertEquals("Second", busy.title)
        waitUntil("second write gives up") { second.isCompleted }

        first.release()
        awaitIdle(running)
        assertEquals(listOf(listOf("pr", "ready", "1")), gh.writes())
    }

    // ---------------------------------------------------------------- rebase, rerere y push

    fun `test after a rebase the stack can be pushed and the banner goes away`() {
        val state = loadStack()
        git("config", "gh-stack.rerere-declined", "true")
        gh.respond(listOf("stack", "rebase"))
        gh.respond(listOf("stack", "push"))

        service.rebase(RebaseScope.STACK, repository)
        val done = awaitNotificationWith(msg("op.rebase.done"))
        assertEquals(setOf("feat/a", "feat/b"), service.pushPending.value[state.repo.root])

        // El aviso sale antes de soltar la escritura (ver C13): se pulsa cuando ya ha terminado.
        awaitIdle()
        press(done, msg("action.push.stack"))
        awaitNotificationWith(msg("op.push.done"))
        assertEquals(listOf(listOf("stack", "rebase"), listOf("stack", "push")), gh.writes())
        waitUntil("push clears pending layers") { service.pushPending.value[state.repo.root] == null }
    }

    private fun assumeRerereUnanswered() {
        // gh-stack lee tambien la configuracion global: si ahi ya hay respuesta, no pregunta.
        assumeTrue("rerere is answered in the global git config", gitConfig("rerere.enabled") == null && gitConfig("gh-stack.rerere-declined") == null)
    }

    fun `test the first rebase asks about rerere and enabling it writes the same keys as gh-stack`() {
        assumeRerereUnanswered()
        gh.respond(listOf("stack", "rebase"))
        answerMessages(Messages.YES)

        service.rebase(RebaseScope.STACK, repository)
        awaitNotificationWith(msg("op.rebase.done"))
        assertEquals("true", gitConfig("rerere.enabled"))
        assertEquals("true", gitConfig("rerere.autoupdate"))
        assertEquals(listOf(listOf("stack", "rebase")), gh.writes())
    }

    fun `test declining rerere is remembered and the rebase still runs`() {
        assumeRerereUnanswered()
        gh.respond(listOf("stack", "rebase"))
        answerMessages(Messages.NO)

        service.rebase(RebaseScope.STACK, repository)
        awaitNotificationWith(msg("op.rebase.done"))
        assertEquals("true", gitConfig("gh-stack.rerere-declined"))
        assertNull(gitConfig("rerere.enabled"))
    }

    fun `test cancelling the rerere question does not rebase`() {
        assumeRerereUnanswered()
        answerMessages(Messages.CANCEL)

        val job = service.rebase(RebaseScope.STACK, repository)
        waitUntil("rebase gives up") { job.isCompleted }
        assertEquals(emptyList<Any>(), gh.writes())
        assertNull(gitConfig("gh-stack.rerere-declined"))
    }
}
