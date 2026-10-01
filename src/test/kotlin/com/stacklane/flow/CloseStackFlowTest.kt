package com.stacklane.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.stacklane.actions.StackFlows
import com.stacklane.stack.StackState
import com.stacklane.ui.CloseStackDialog
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * *Close Stack…* de la accion al ultimo comando, con git de verdad: las ramas se borran en un
 * repositorio local que hace de remoto (`fork`), para no salir a la red.
 */
class CloseStackFlowTest : FlowTestCase() {

    private lateinit var fork: String

    override fun setUp() {
        super.setUp()
        fork = createTempDirectoryWithSuffix("fork").toString()
        git("init", "--bare", "-b", "main", fork)
        git("remote", "add", "fork", fork)
        git("switch", "-c", "feat/a")
        commit("a.txt")
        git("switch", "-c", "feat/b")
        commit("b.txt")
        git("push", "fork", "main", "feat/a", "feat/b")
        preferredRemote = "fork"
        repository.update()
    }

    private fun commit(file: String) {
        Files.writeString(repoDir.resolve(file), "$file\n")
        git("add", file)
        git("commit", "-m", file)
    }

    private fun loadStack(): StackState.Loaded {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        return awaitState { !it.detailsLoading }
    }

    private fun branches(vararg args: String) = git("branch", "--format=%(refname:short)", *args).lines().filter { it.isNotBlank() }

    private fun forkBranches() = git("ls-remote", "--heads", fork).lines().filter { it.isNotBlank() }.map { it.substringAfter("refs/heads/") }

    fun `test closes the pull requests from the top down and deletes every branch`() {
        loadStack()
        gh.respond(listOf("stack", "unstack"))
        gh.respond(listOf("pr", "close", Samples.URL_B))
        gh.respond(listOf("pr", "close", Samples.URL_A))
        answerNextDialog(accept = true)

        onEdt { StackFlows.closeStack(project) }
        awaitNotificationWith(msg("op.close.done", "main ← feat/a ← feat/b"))
        awaitIdle()
        awaitQuiet()

        assertEquals(
            listOf(listOf("stack", "unstack"), listOf("pr", "close", Samples.URL_B), listOf("pr", "close", Samples.URL_A)),
            gh.writes(),
        )
        assertEquals(listOf("main"), forkBranches())
        assertEquals(listOf("main"), branches())
        // Estaba en feat/b: se salio al trunk antes de borrarla.
        assertEquals("main", git("branch", "--show-current").trim())
    }

    fun `test a stack that GitHub keeps is left alone`() {
        // gh-stack sale bien pero sigue registrando la pila: hay PRs en cola o con auto-merge.
        Files.writeString(
            repoDir.resolve(".git/gh-stack"),
            """{"schemaVersion":1,"stacks":[{"trunk":{"branch":"main"},"branches":[{"branch":"feat/a"},{"branch":"feat/b"}]}]}""",
        )
        loadStack()
        gh.respond(
            listOf("stack", "unstack"),
            stderr = "Some pull requests are queued for merge or have auto-merge enabled and remain stacked on GitHub\n",
        )
        answerNextDialog(accept = true)

        onEdt { StackFlows.closeStack(project) }
        val kept = awaitNotificationWith(msg("close.kept", "main ← feat/a ← feat/b"))
        assertEquals(NotificationType.WARNING, kept.type)

        awaitIdle()
        awaitQuiet()
        assertEquals(listOf(listOf("stack", "unstack")), gh.writes())
        assertEquals(listOf("feat/a", "feat/b", "main"), forkBranches().sorted())
        assertEquals(listOf("feat/a", "feat/b", "main"), branches().sorted())
    }

    fun `test a failed unstack closes nothing`() {
        loadStack()
        gh.respond(listOf("stack", "unstack"), exit = 4, stderr = "Failed to unstack on GitHub (HTTP 500): boom\n")
        answerNextDialog(accept = true)

        onEdt { StackFlows.closeStack(project) }
        val error = awaitNotificationWith(msg("operation.failed", msg("op.close")))
        assertEquals(NotificationType.ERROR, error.type)

        awaitIdle()
        awaitQuiet()
        assertEquals(listOf(listOf("stack", "unstack")), gh.writes())
        assertEquals(listOf("feat/a", "feat/b", "main"), branches().sorted())
    }

    fun `test cancelling the dialog runs nothing`() {
        loadStack()
        val shown = AtomicBoolean()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<CloseStackDialog>(CloseStackDialog::class.java) {
            override fun doIntercept(component: CloseStackDialog) {
                shown.set(true)
                component.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        })

        onEdt { StackFlows.closeStack(project) }
        // El dialogo se abre despues de leer las ramas del remoto.
        waitUntil("the dialog is shown") { shown.get() }
        awaitIdle()
        awaitQuiet()
        assertEquals(emptyList<Any>(), gh.writes())
        assertEquals(listOf("feat/a", "feat/b", "main"), branches().sorted())
    }
}
