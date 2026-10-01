package com.stacklane.flow

import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.Messages
import com.stacklane.actions.LayerSelection
import com.stacklane.actions.PrTarget
import com.stacklane.actions.StackFlows
import com.stacklane.stack.GitHubRepo
import com.stacklane.stack.StackState

/**
 * Los flujos que escriben en GitHub, de la accion al comando: que se ejecuta exactamente, en
 * que orden, y que se le dice al usuario. Lo que todavia no se puede deshacer en GitHub sin
 * entrar en la web (cerrar PRs, fusionar) se apoyara en estos.
 */
class GitHubWritesFlowTest : FlowTestCase() {

    private val github = requireNotNull(GitHubRepo.fromPullRequestUrl(Samples.URL_B))

    private fun loadStack(): StackState.Loaded {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        return awaitState { !it.detailsLoading }
    }

    /** Un PR elegido en la ventana Stacks, con lo que ya se sabe de el. */
    private fun fromStacks(state: StackState.Loaded, branch: String): PrTarget {
        val layer = state.snapshot.layers.first { it.branch == branch }
        val pr = requireNotNull(layer.pr)
        return PrTarget(pr.url, pr.number, github, LayerSelection(state, layer), null)
    }

    /** Un PR elegido en la ventana Pull Requests: solo su URL. */
    private fun fromPullRequests(url: String, number: Int) = PrTarget(url, number, github, null, null)

    // ---------------------------------------------------------------- publicar

    fun `test publishing ready for review confirms first and opens every pull request`() {
        loadStack()
        gh.respond(listOf("stack", "submit", "--auto", "--open"))
        answerMessages(Messages.YES)

        onEdt { StackFlows.publish(project, ready = true) }
        awaitNotificationWith(msg("op.publish.ready.done"))
        assertEquals(listOf(listOf("stack", "submit", "--auto", "--open")), gh.writes())
    }

    fun `test the ready confirmation can still publish as drafts`() {
        loadStack()
        gh.respond(listOf("stack", "submit", "--auto"))
        answerMessages(Messages.NO)

        onEdt { StackFlows.publish(project, ready = true) }
        awaitNotificationWith(msg("op.publish.draft.done"))
        assertEquals(listOf(listOf("stack", "submit", "--auto")), gh.writes())
    }

    fun `test cancelling the ready confirmation publishes nothing`() {
        loadStack()
        answerMessages(Messages.CANCEL)

        onEdt { StackFlows.publish(project, ready = true) }
        awaitIdle()
        assertEquals(emptyList<Any>(), gh.writes())
        assertEquals(0, notifications.size)
    }

    fun `test pull request commands never get the stack remote`() {
        loadStack()
        preferredRemote = "origin"
        gh.respond(listOf("pr", "ready", Samples.URL_A))

        onEdt { StackFlows.setDraft(project, fromPullRequests(Samples.URL_A, 11), draft = false) }
        awaitNotificationWith(msg("op.ready.done", 11))
        assertEquals(listOf(listOf("pr", "ready", Samples.URL_A)), gh.writes())
    }

    // ---------------------------------------------------------------- un PR

    fun `test converting to draft`() {
        gh.respond(listOf("pr", "ready", Samples.URL_B, "--undo"))

        onEdt { StackFlows.setDraft(project, fromPullRequests(Samples.URL_B, 12), draft = true) }
        awaitNotificationWith(msg("op.draft.done", 12))
        assertEquals(listOf(listOf("pr", "ready", Samples.URL_B, "--undo")), gh.writes())
    }

    fun `test changing the base says which one it had`() {
        val state = loadStack()
        gh.respond(listOf("pr", "edit", Samples.URL_B, "--base", "feat/a"))

        onEdt { StackFlows.changeBase(project, fromStacks(state, "feat/b"), "feat/a") }
        // La base que tenia sale de la consulta de la ventana: feat/a en las muestras.
        awaitNotificationWith(msg("op.base.done", 12, "feat/a", "feat/a"))
        assertEquals(listOf(listOf("pr", "edit", Samples.URL_B, "--base", "feat/a")), gh.writes())
    }

    fun `test a failed base change reports what GitHub said`() {
        val state = loadStack()
        gh.respond(listOf("pr", "edit", Samples.URL_A, "--base", "main"), exit = 1, stderr = "GraphQL: Base ref must be a branch\n")

        onEdt { StackFlows.changeBase(project, fromStacks(state, "feat/a"), "main") }
        val error = awaitNotificationWith(msg("operation.failed", msg("op.base", 11, "main")))
        assertEquals(NotificationType.ERROR, error.type)
        assertEquals("GraphQL: Base ref must be a branch", error.content)
    }

    // ---------------------------------------------------------------- label de capa final

    fun `test the final label moves from the layer that had it`() {
        val state = loadStack()
        gh.respond(listOf("pr", "edit", Samples.URL_A, "--add-label", "stack-final"))
        gh.respond(listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final"))

        onEdt { StackFlows.toggleFinal(project, fromStacks(state, "feat/a")) }
        awaitNotificationWith(msg("op.final.moved", "stack-final", 11, "#12"))
        assertEquals(
            listOf(
                listOf("pr", "edit", Samples.URL_A, "--add-label", "stack-final"),
                listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final"),
            ),
            gh.writes(),
        )
    }

    fun `test the final label is removed from the layer that has it`() {
        val state = loadStack()
        gh.respond(listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final"))

        onEdt { StackFlows.toggleFinal(project, fromStacks(state, "feat/b")) }
        awaitNotificationWith(msg("op.final.removed", "stack-final", 12))
        assertEquals(listOf(listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final")), gh.writes())
    }

    fun `test from Pull Requests the labels are read first`() {
        gh.respond(listOf("pr", "view", Samples.URL_B, "--json", "labels"), stdout = """{"labels":[{"name":"Stack-Final"}]}""")
        gh.respond(listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final"))

        onEdt { StackFlows.toggleFinal(project, fromPullRequests(Samples.URL_B, 12)) }
        awaitNotificationWith(msg("op.final.removed", "stack-final", 12))
        assertEquals(
            listOf(
                listOf("pr", "view", Samples.URL_B, "--json", "labels"),
                listOf("pr", "edit", Samples.URL_B, "--remove-label", "stack-final"),
            ),
            gh.writes(),
        )
    }

    fun `test a final label that does not exist is created and added again`() {
        val add = listOf("pr", "edit", Samples.URL_A, "--add-label", "stack-final")
        gh.respond(listOf("pr", "view", Samples.URL_A, "--json", "labels"), stdout = """{"labels":[]}""")
        gh.respond(add, exit = 1, stderr = "could not add label: 'stack-final' not found\n", times = 1)
        gh.respond(listOf("label", "create", "stack-final"), prefix = true)
        gh.respond(add)

        onEdt { StackFlows.toggleFinal(project, fromPullRequests(Samples.URL_A, 11)) }
        val missing = awaitNotificationWith(msg("final.missing", "stack-final", "acme/shop"))
        assertEquals(NotificationType.WARNING, missing.type)
        // Ni el aviso generico de error: el flujo ya explico que pasa.
        assertNull(notifications.firstOrNull { it.type == NotificationType.ERROR })

        awaitIdle()
        press(missing, msg("final.create"))
        awaitNotificationWith(msg("op.final.added", "stack-final", 11))
        val writes = gh.writes()
        assertEquals(add, writes[1])
        assertEquals(listOf("label", "create", "stack-final", "--repo", "acme/shop"), writes[2].take(5))
        assertTrue("--force" in writes[2])
        assertEquals(add, writes[3])
        assertEquals(4, writes.size)
    }
}
