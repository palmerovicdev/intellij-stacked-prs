package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GitCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishPlansTest {

    private val repo = GitHubRepo("github.com", "acme", "app")
    private val url1019 = "https://github.com/acme/app/pull/1019"
    private val url1020 = "https://github.com/acme/app/pull/1020"

    private fun lines(calls: List<GhCall>) = calls.map { GhCommands.display(it.args, it.tool) }

    private fun layer(name: String, pr: Int? = null) =
        StackLayer(name, isCurrent = false, isMerged = false, isQueued = false, needsRebase = false, pr = pr?.let { PrRef(it, "", "OPEN") })

    @Test
    fun `the top layer without a pull request is published with the ones below as drafts`() {
        val stack = StackSnapshot("main", "feat/c", listOf(layer("feat/a", 1019), layer("feat/b"), layer("feat/c")))
        assertEquals(listOf("feat/c"), PublishPlans.unpublishedAbove(stack, stack.layers[1]).map { it.branch })
        assertEquals(emptyList<StackLayer>(), PublishPlans.unpublishedAbove(stack, stack.layers[2]))
        assertEquals(listOf("feat/b"), PublishPlans.unpublishedBelow(stack, stack.layers[2]).map { it.branch })
        assertEquals(
            listOf(
                "gh stack submit --auto",
                "gh pr edit feat/c --repo acme/app --title Flows --body ''",
                "gh pr ready feat/c --repo acme/app",
            ),
            lines(PublishPlans.layerPlan(stack, stack.layers[2], PrText("Flows", ""), ready = true, repo, "feat/c").calls),
        )
        val draft = PublishPlans.layerPlan(stack, stack.layers[2], PrText("Flows", ""), ready = false, repo, "feat/c")
        assertEquals(listOf("gh stack submit --auto", "gh pr edit feat/c --repo acme/app --title Flows --body ''"), lines(draft.calls))
        // Sin rehacer la pila no hay nada que deshacer.
        assertEquals(emptyList<String>(), lines(draft.cleanup(1)))
    }

    @Test
    fun `a layer with unpublished layers above is submitted with the stack tracked only up to it`() {
        val stack = StackSnapshot("main", "feat/d", listOf(layer("feat/a", 1019), layer("feat/b"), layer("feat/c"), layer("feat/d")))
        val plan = PublishPlans.layerPlan(stack, stack.layers[2], PrText("Flows", ""), ready = true, repo, "feat/a")
        assertEquals(
            listOf(
                "gh stack unstack --local",
                "gh stack init --base main feat/a feat/b feat/c",
                "gh stack submit --auto",
                "gh pr edit feat/c --repo acme/app --title Flows --body ''",
                "gh pr ready feat/c --repo acme/app",
                "gh stack unstack --local",
                "gh stack init --base main feat/a feat/b feat/c feat/d",
                "git switch feat/a",
            ),
            lines(plan.calls),
        )
        // Solo el submit admite --remote.
        assertEquals(listOf(false, false, true, false, false, false, false, false), plan.calls.map { it.acceptsRemote })
        // En la capa de arriba, init ya deja HEAD alli.
        assertEquals(
            "gh stack init --base main feat/a feat/b feat/c feat/d",
            lines(PublishPlans.layerPlan(stack, stack.layers[2], PrText("Flows", ""), ready = false, repo, "feat/d").calls).last(),
        )
    }

    @Test
    fun `cut while the stack is tracked only up to the layer, it is started again whole and HEAD goes back`() {
        val stack = StackSnapshot("main", "feat/c", listOf(layer("feat/a", 1019), layer("feat/b"), layer("feat/c")))
        val plan = PublishPlans.layerPlan(stack, stack.layers[1], PrText("Flows", ""), ready = false, repo, "feat/a")
        val whole = listOf("gh stack init --base main feat/a feat/b feat/c", "git switch feat/a")
        // unstack, init parcial, submit, edit, unstack, init, switch.
        assertEquals(7, plan.calls.size)
        assertEquals(emptyList<String>(), lines(plan.cleanup(0)))
        assertEquals(whole, lines(plan.cleanup(1)))
        for (completed in 2..4) assertEquals(listOf("gh stack unstack --local") + whole, lines(plan.cleanup(completed)))
        assertEquals(whole, lines(plan.cleanup(5)))
        assertEquals(emptyList<String>(), lines(plan.cleanup(6)))
    }

    @Test
    fun `with merged layers only a layer with no unpublished layer above can be published`() {
        val merged = layer("feat/a", 1019).copy(isMerged = true)
        val stack = StackSnapshot("main", "feat/c", listOf(merged, layer("feat/b"), layer("feat/c")))
        assertTrue(PublishPlans.layerBlocked(stack, stack.layers[1]))
        assertFalse(PublishPlans.layerBlocked(stack, stack.layers[2]))
        val open = stack.copy(layers = listOf(layer("feat/a", 1019)) + stack.layers.drop(1))
        assertFalse(PublishPlans.layerBlocked(open, open.layers[1]))
    }

    @Test
    fun `submits first, then titles the new pull requests and changes only what differs`() {
        val calls = PublishPlans.calls(
            listOf(
                // Ya lista y se deja lista: nada.
                PublishLayer("feat/booking", url1019, isDraft = false, ready = true),
                // Draft que pasa a lista.
                PublishLayer("feat/templates", url1020, isDraft = true, ready = true),
                // Nueva y lista: titulo y despues ready, por rama.
                PublishLayer("feat/flows", null, isDraft = null, ready = true, text = PrText("Flow view", "")),
            ),
            repo,
        )
        assertEquals(
            listOf(
                "gh stack submit --auto",
                "gh pr ready $url1020",
                "gh pr edit feat/flows --repo acme/app --title 'Flow view' --body ''",
                "gh pr ready feat/flows --repo acme/app",
            ),
            lines(calls),
        )
        // Solo el submit admite --remote.
        assertEquals(listOf(true, false, false, false), calls.map { it.acceptsRemote })
    }

    @Test
    fun `turns a ready pull request back into a draft and leaves unknown ones alone`() {
        val calls = PublishPlans.calls(
            listOf(
                PublishLayer("feat/booking", url1019, isDraft = false, ready = false),
                PublishLayer("feat/templates", url1020, isDraft = null, ready = true),
                PublishLayer("feat/closed", url1020, isDraft = true, ready = null),
            ),
            repo,
        )
        assertEquals(listOf("gh stack submit --auto", "gh pr ready $url1019 --undo"), lines(calls))
    }

    @Test
    fun `a new layer left as draft only gets its title`() {
        val calls = PublishPlans.calls(
            listOf(PublishLayer("feat/flows", null, isDraft = null, ready = false, text = PrText("Flow view", "Body"))),
            GitHubRepo("ghe.acme.com", "acme", "app"),
        )
        assertEquals(
            listOf("gh stack submit --auto", "gh pr edit feat/flows --repo ghe.acme.com/acme/app --title 'Flow view' --body Body"),
            lines(calls),
        )
    }

    @Test
    fun `without a known GitHub repository new pull requests are named by branch alone`() {
        val calls = PublishPlans.calls(listOf(PublishLayer("feat/flows", null, null, ready = true, text = PrText("T", "B"))), null)
        assertEquals(listOf("gh stack submit --auto", "gh pr edit feat/flows --title T --body B", "gh pr ready feat/flows"), lines(calls))
    }

    @Test
    fun `proposes the branch name as title and the commits as description`() {
        val one = listOf(CommitMessage("feat(flows): flow view of booking", "Adds the flow view.\n\nCloses #12"))
        assertEquals(PrText("feat/flows", "Adds the flow view.\n\nCloses #12"), PublishPlans.proposal("feat/flows", one))

        val several = listOf(CommitMessage("feat: first", "x"), CommitMessage("fix: second", ""), CommitMessage("chore: third", "y"))
        assertEquals(PrText("feat/flows", "- feat: first\n- fix: second\n- chore: third"), PublishPlans.proposal("feat/flows", several))

        assertEquals(PrText("feat/flows", ""), PublishPlans.proposal("feat/flows", emptyList()))
    }

    @Test
    fun `parses git log with multi-line bodies and empty ones`() {
        val output = "\u001efeat: first\u001fLine one\nLine two\n\n\u001efix: second\u001f\n"
        assertEquals(
            listOf(CommitMessage("feat: first", "Line one\nLine two"), CommitMessage("fix: second", "")),
            PublishPlans.parseLog(output),
        )
        assertTrue(PublishPlans.parseLog("").isEmpty())
    }

    @Test
    fun `git log lists the layer's own commits oldest first`() {
        assertEquals(
            "git log --reverse --no-merges --format=%x1e%s%x1f%b main..feat/flows --",
            GhCommands.display(GitCommands.log("main", "feat/flows"), com.upstack.gh.Tool.GIT),
        )
    }
}
