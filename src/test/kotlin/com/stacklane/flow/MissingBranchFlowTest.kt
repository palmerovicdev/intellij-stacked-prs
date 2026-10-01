package com.stacklane.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.stacklane.actions.StackFlows
import com.stacklane.stack.StackState
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files

/**
 * Una capa cuya rama se borro dentro de una pila activa (P44). gh-stack v0.1.1 la sigue listando,
 * pero `rebase`, `sync`, `push` y `submit` fallan al no encontrarla, y `modify` no abre la pila.
 */
class MissingBranchFlowTest : FlowTestCase() {

    private lateinit var lastCommit: String
    private lateinit var viewRule: FakeGh.Rule

    /** main ← feat/a ← feat/b ← feat/c, con feat/b subida (gh-stack guardo su head) y borrada despues. */
    private fun deleteMiddleLayer() {
        git("switch", "-c", "feat/a")
        Files.writeString(repoDir.resolve("a.txt"), "a\n")
        git("add", "a.txt")
        git("commit", "-m", "Layer a")
        git("switch", "-c", "feat/b")
        Files.writeString(repoDir.resolve("b.txt"), "b\n")
        git("add", "b.txt")
        git("commit", "-m", "Layer b")
        lastCommit = git("rev-parse", "HEAD").trim()
        git("branch", "feat/c")
        git("switch", "feat/a")
        git("branch", "-D", "feat/b")
        viewRule = gh.respond(VIEW, stdout = view(listOf("feat/a", "feat/b", "feat/c")))
    }

    private fun view(layers: List<String>) = """
        {"trunk":"main","currentBranch":"feat/a","branches":[${layers.joinToString(",") { name ->
            val head = if (name == "feat/b") ""","head":"$lastCommit"""" else ""
            """{"name":"$name"$head,"isCurrent":${name == "feat/a"},"isMerged":false,"isQueued":false,"needsRebase":false}"""
        }}]}
    """.trimIndent()

    private fun branchHead(name: String): String? =
        if (gitExit("rev-parse", "--verify", "--quiet", "refs/heads/$name") == 0) git("rev-parse", "refs/heads/$name").trim() else null

    fun `test a layer without its branch is marked with its last known commit`() {
        deleteMiddleLayer()

        val state = awaitState<StackState.Loaded> { it.missing.isNotEmpty() }
        val missing = state.missing.single()
        assertEquals("feat/b", missing.branch)
        assertEquals(lastCommit, missing.lastCommit)
        assertEquals("feat/a", missing.parent)
        assertNull(missing.remoteBranch)
    }

    fun `test restore recreates the branch at that commit without leaving the current one`() {
        deleteMiddleLayer()
        val state = awaitState<StackState.Loaded> { it.missing.isNotEmpty() }

        onEdt { StackFlows.restoreBranches(project, state.missing) }
        awaitNotificationWith(msg("op.restore.done", "feat/b"))

        assertEquals(lastCommit, branchHead("feat/b"))
        assertEquals("feat/a", git("branch", "--show-current").trim())
        assertEquals(emptyList<List<String>>(), gh.writes())
        awaitState<StackState.Loaded> { it.missing.isEmpty() }
        awaitQuiet()
    }

    fun `test a push that fails on the missing branch says so and offers to restore it`() {
        deleteMiddleLayer()
        awaitState<StackState.Loaded> { it.missing.isNotEmpty() }
        preferredRemote = "origin"
        gh.respond(
            listOf("stack", "sync", "--remote", "origin"), exit = 1,
            stderr = "✗ failed to push: failed to run git: error: src refspec refs/heads/feat/b does not match any\n",
        )

        onEdt { StackFlows.sync(project) }
        val error = awaitNotificationWith(msg("error.missing.branch", "feat/b"))
        assertEquals(NotificationType.ERROR, error.type)

        awaitIdle()
        press(error, msg("action.restore.branch"))
        awaitNotificationWith(msg("op.restore.done", "feat/b"))
        assertEquals(lastCommit, branchHead("feat/b"))
        awaitQuiet()
    }

    fun `test remove restores the branch, copies gh stack modify and waits until the layer leaves`() {
        deleteMiddleLayer()
        val state = awaitState<StackState.Loaded> { it.missing.isNotEmpty() }
        answerMessages(Messages.OK)

        onEdt { StackFlows.removeFromStack(project, state.missing.single()) }
        waitUntil("feat/b is pending removal") { service.removals.value[repository.root] == setOf("feat/b") }
        assertEquals(lastCommit, branchHead("feat/b"))
        assertEquals("gh stack modify", CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))

        // `gh stack modify` en la terminal: la capa sale de la pila y deja de estar pendiente.
        viewRule.remove()
        gh.respond(VIEW, stdout = view(listOf("feat/a", "feat/c")))
        awaitState<StackState.Loaded> { it.snapshot.layers.size == 2 }
        assertEquals(emptySet<String>(), service.removals.value[repository.root])
        awaitQuiet()
    }

    fun `test the top layer without a pull request is taken out without restoring it`() {
        deleteMiddleLayer()
        git("branch", "-D", "feat/c")
        viewRule.remove()
        gh.respond(VIEW, stdout = view(listOf("feat/a", "feat/c")))
        val state = awaitState<StackState.Loaded> { it.missing.singleOrNull()?.branch == "feat/c" }
        gh.respond(listOf("stack", "unstack", "--local"))
        gh.respond(listOf("stack", "init", "--base", "main", "feat/a"))
        answerNextDialog(accept = true)

        onEdt { StackFlows.removeFromStack(project, state.missing.single()) }
        awaitNotificationWith(msg("op.delete.layer.done", "feat/c", "feat/a"))

        assertEquals(listOf(listOf("stack", "unstack", "--local"), listOf("stack", "init", "--base", "main", "feat/a")), gh.writes())
        assertNull(branchHead("feat/c"))
        awaitQuiet()
    }
}
