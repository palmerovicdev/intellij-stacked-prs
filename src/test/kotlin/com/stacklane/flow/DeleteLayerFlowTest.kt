package com.stacklane.flow

import com.stacklane.actions.StackFlows
import com.stacklane.stack.StackState
import java.nio.file.Files

/** *Delete Layer…*: la capa de arriba sin PR sale de la pila sin `gh stack modify`. */
class DeleteLayerFlowTest : FlowTestCase() {

    private val unstack = listOf("stack", "unstack", "--local")
    private val initWithoutTop = listOf("stack", "init", "--base", "main", "feat/a")

    /** main ← feat/a ← feat/b, sin publicar, en feat/b. */
    private fun loadStack(): StackState.Loaded {
        for (name in listOf("feat/a", "feat/b")) {
            git("switch", "-c", name)
            Files.writeString(repoDir.resolve("$name.txt".replace('/', '-')), "$name\n")
            git("add", ".")
            git("commit", "-m", name)
        }
        gh.respond(VIEW, stdout = Samples.VIEW_UNPUBLISHED)
        return awaitState()
    }

    private fun branches() = git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    fun `test the top layer leaves the stack and its branch is deleted`() {
        val state = loadStack()
        gh.respond(unstack)
        gh.respond(initWithoutTop)
        answerNextDialog(accept = true)

        onEdt { StackFlows.deleteLayer(project, state.snapshot.layers.last()) }
        awaitNotificationWith(msg("op.delete.layer.done", "feat/b", "feat/a"))

        assertEquals(listOf(unstack, initWithoutTop), gh.writes())
        assertEquals("feat/a", git("branch", "--show-current").trim())
        assertEquals(listOf("feat/a", "main"), branches())
        awaitQuiet()
    }

    fun `test if init fails the stack is started again as it was and HEAD goes back`() {
        val state = loadStack()
        gh.respond(unstack)
        gh.respond(initWithoutTop, exit = 1, stderr = "boom\n")
        gh.respond(listOf("stack", "init", "--base", "main", "feat/a", "feat/b"))
        answerNextDialog(accept = true)

        onEdt { StackFlows.deleteLayer(project, state.snapshot.layers.last()) }
        awaitNotificationWith(msg("operation.failed", msg("op.delete.layer", "feat/b")))
        awaitIdle()

        assertEquals(listOf(unstack, initWithoutTop, listOf("stack", "init", "--base", "main", "feat/a", "feat/b")), gh.writes())
        assertEquals("feat/b", git("branch", "--show-current").trim())
        assertTrue("feat/b" in branches())
        awaitQuiet()
    }

    fun `test cancelling the dialog runs nothing`() {
        val state = loadStack()
        answerNextDialog(accept = false)

        onEdt { StackFlows.deleteLayer(project, state.snapshot.layers.last()) }

        assertEquals(emptyList<List<String>>(), gh.writes())
        assertTrue("feat/b" in branches())
    }
}
