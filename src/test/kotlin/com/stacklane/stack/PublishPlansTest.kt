package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.GitCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishPlansTest {

    private val repo = GitHubRepo("github.com", "acme", "app")
    private val url1019 = "https://github.com/acme/app/pull/1019"
    private val url1020 = "https://github.com/acme/app/pull/1020"

    private fun lines(calls: List<GhCall>) = calls.map { GhCommands.display(it.args, it.tool) }

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
    fun `proposes the commit itself for one commit and a list for several`() {
        val one = listOf(CommitMessage("feat(flows): flow view of booking", "Adds the flow view.\n\nCloses #12"))
        assertEquals(PrText("feat(flows): flow view of booking", "Adds the flow view.\n\nCloses #12"), PublishPlans.proposal("feat/flows", one))

        val several = listOf(CommitMessage("feat: first", "x"), CommitMessage("fix: second", ""), CommitMessage("chore: third", "y"))
        assertEquals(PrText("feat: first", "- feat: first\n- fix: second\n- chore: third"), PublishPlans.proposal("feat/flows", several))

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
            GhCommands.display(GitCommands.log("main", "feat/flows"), com.stacklane.gh.Tool.GIT),
        )
    }
}
