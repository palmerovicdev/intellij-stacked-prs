package com.upstack.flow

import com.upstack.stack.StackState
import java.nio.file.Files

/** Lo que pinta la ventana segun lo que conteste `gh stack view --json`. */
class StackStateFlowTest : FlowTestCase() {

    fun `test a stack with pull requests is loaded with their details`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)

        val state = awaitState<StackState.Loaded> { !it.detailsLoading }
        assertEquals(listOf("feat/a", "feat/b"), state.snapshot.layers.map { it.branch })
        assertEquals(repository.root, state.repo.root)
        assertEquals("acme/shop", state.repo.github?.cliName)
        assertFalse(state.repo.stackRebaseInProgress)
        assertTrue(state.details.getValue(11).isDraft)
        assertEquals("feat/a", state.details.getValue(12).baseRef)
        assertNull(state.detailsError)
    }

    fun `test details that fail keep the stack and say why`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, exit = 1, stderr = "HTTP 502: Bad Gateway\n")

        val state = awaitState<StackState.Loaded> { !it.detailsLoading }
        assertEquals(2, state.snapshot.layers.size)
        assertEquals("HTTP 502: Bad Gateway", state.detailsError)
    }

    fun `test exit code 2 is a branch outside any stack`() {
        gh.respond(VIEW, exit = 2, stderr = "current branch \"main\" is not part of a stack")

        val state = awaitState<StackState.NotInStack>()
        assertEquals("main", state.branch)
        assertEquals(emptyList<Any>(), state.localStacks)
    }

    fun `test exit code 6 is a branch in several stacks`() {
        gh.respond(VIEW, exit = 6, stderr = "branch \"main\" belongs to multiple stacks")

        val state = awaitState<StackState.InSeveralStacks>()
        assertEquals("main", state.branch)
    }

    fun `test gh without the extension asks to install it`() {
        gh.respond(VIEW, exit = 1, stderr = "unknown command \"stack\" for \"gh\"\n")

        awaitState<StackState.ExtensionMissing>()
    }

    fun `test any other failure is shown with its error`() {
        gh.respond(VIEW, exit = 4, stderr = "GraphQL: Could not resolve to a Repository\n")

        val state = awaitState<StackState.Failed>()
        assertEquals("GraphQL: Could not resolve to a Repository", state.message)
    }

    fun `test on the trunk of its only stack the window lists the stacks`() {
        writeLocalStack()
        val onTrunk = gh.respond(VIEW, stdout = VIEW_ON_TRUNK)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)

        assertTrue(awaitState<StackState.Loaded> { !it.detailsLoading }.listShown)

        service.openStack()
        assertFalse(loaded().listShown)
        // Otra lectura en la misma rama no vuelve a la lista.
        gh.respond(VIEW, stdout = VIEW_ON_TRUNK.replace(""""needsRebase":false""", """"needsRebase":true"""))
        onTrunk.remove()
        assertFalse(awaitState<StackState.Loaded> { it.snapshot.layers.any { l -> l.needsRebase } && !it.detailsLoading }.listShown)
    }

    fun `test back to stacks lists them until the branch changes`() {
        writeLocalStack()
        val onB = gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)

        assertFalse(awaitState<StackState.Loaded> { !it.detailsLoading }.listShown)

        service.showStackList()
        assertTrue(loaded().listShown)
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS.replace(""""currentBranch":"feat/b"""", """"currentBranch":"feat/a""""))
        onB.remove()
        assertFalse(awaitState<StackState.Loaded> { it.snapshot.currentBranch == "feat/a" && !it.detailsLoading }.listShown)
    }

    fun `test without its local stack there is no list to go back to`() {
        gh.respond(VIEW, stdout = VIEW_ON_TRUNK)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)

        assertFalse(awaitState<StackState.Loaded> { !it.detailsLoading }.listShown)
        service.showStackList()
        assertFalse(loaded().listShown)
    }

    fun `test a stopped stack rebase is visible in the state`() {
        leaveStackRebaseInProgress()
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)

        val state = awaitState<StackState.Loaded>()
        assertTrue(state.repo.stackRebaseInProgress)
    }

    /** La pila de [Samples.VIEW_TWO_LAYERS] en `.git/gh-stack`, como la deja gh-stack. */
    private fun writeLocalStack() {
        Files.writeString(
            repoDir.resolve(".git/gh-stack"),
            """{"schemaVersion":1,"stacks":[{"trunk":{"branch":"main"},"branches":[{"branch":"feat/a"},{"branch":"feat/b"}]}]}""",
        )
    }

    private fun loaded(): StackState.Loaded = service.state.value as StackState.Loaded

    private companion object {
        /** [Samples.VIEW_TWO_LAYERS] desde main: gh-stack abre la unica pila que sale de el. */
        val VIEW_ON_TRUNK = Samples.VIEW_TWO_LAYERS
            .replace(""""currentBranch":"feat/b"""", """"currentBranch":"main"""")
            .replace(""""isCurrent":true""", """"isCurrent":false""")
    }
}
