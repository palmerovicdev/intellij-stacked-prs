package com.stacklane.gh

import com.stacklane.stack.GitHubRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhCommandsTest {

    @Test
    fun `the flow from the review, command by command`() {
        assertEquals("gh stack init feat/payment-domain", GhCommands.display(GhCommands.init(listOf("feat/payment-domain"), null)))
        assertEquals("gh stack submit --auto", GhCommands.display(GhCommands.submit(ready = false)))
        assertEquals("gh stack submit --auto --open", GhCommands.display(GhCommands.submit(ready = true)))
        assertEquals("gh stack add feat/payment-api", GhCommands.display(GhCommands.add("feat/payment-api", null)))
        val url = "https://github.com/acme/shop/pull/13"
        assertEquals("gh pr ready $url", GhCommands.display(GhCommands.markReady(url)))
        assertEquals("gh pr ready $url --undo", GhCommands.display(GhCommands.markDraft(url)))
        assertEquals(
            "gh pr edit $url --add-label stack-final",
            GhCommands.display(GhCommands.editLabels(url, listOf("stack-final"), emptyList())),
        )
    }

    @Test
    fun `init with trunk and add with commit`() {
        assertEquals(listOf("stack", "init", "--base", "develop", "a", "b"), GhCommands.init(listOf("a", "b"), "develop"))
        assertEquals(
            listOf("stack", "add", "-A", "-m", "Add API", "feat/api"),
            GhCommands.add("feat/api", LayerCommit("Add API", Staging.ALL)),
        )
        assertEquals(
            listOf("stack", "add", "-m", "Add API", "feat/api"),
            GhCommands.add("feat/api", LayerCommit("Add API", Staging.STAGED)),
        )
    }

    @Test
    fun `rebase scopes and push`() {
        assertEquals("gh stack rebase", GhCommands.display(GhCommands.rebase()))
        // Upstack sin trunk: desde la capa de abajo, --upstack solo rebasaria tambien sobre el trunk.
        assertEquals("gh stack rebase --upstack --no-trunk", GhCommands.display(GhCommands.rebase(RebaseScope.UPSTACK)))
        assertEquals("gh stack rebase --downstack", GhCommands.display(GhCommands.rebase(RebaseScope.DOWNSTACK)))
        assertEquals("gh stack rebase --no-trunk", GhCommands.display(GhCommands.rebase(RebaseScope.LAYERS)))
        assertEquals("gh stack push", GhCommands.display(GhCommands.push()))
    }

    @Test
    fun `git reads for the rebase flow`() {
        assertEquals("git status --porcelain --untracked-files=no", GhCommands.display(GitCommands.statusTracked(), Tool.GIT))
        assertEquals(
            listOf("config", "--type=bool", "--get-regexp", "^(rerere\\.enabled|gh-stack\\.rerere-declined)$"),
            GitCommands.rerereConfig(),
        )
        assertEquals("git config rerere.enabled true", GhCommands.display(GitCommands.configSet("rerere.enabled", "true"), Tool.GIT))
    }

    @Test
    fun `display quotes what the shell would split`() {
        assertEquals("gh stack add -u -m 'Fix it'\\''s bug' x", GhCommands.display(GhCommands.add("x", LayerCommit("Fix it's bug", Staging.TRACKED))))
        assertEquals(
            "gh pr edit u --add-label 'needs review' --remove-label wip",
            GhCommands.display(GhCommands.editLabels("u", listOf("needs review"), listOf("wip"))),
        )
    }

    @Test
    fun `graphql query has one alias per pull request and hides itself in the log`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        val args = GhCommands.prDetails(repo, listOf(12, 13, 12))
        val query = args.last().removePrefix("query=")
        assertTrue(query.contains("pr12: pullRequest(number: 12)"))
        assertTrue(query.contains("pr13: pullRequest(number: 13)"))
        assertEquals(1, Regex("pr12:").findAll(query).count())
        assertTrue(args.containsAll(listOf("--hostname", "github.com", "owner=acme", "name=shop")))
        assertTrue(GhCommands.display(args).endsWith("query='…'"))
    }

    @Test
    fun `only push, sync and submit leave nothing rebased to push`() {
        listOf(GhCommands.push(), GhCommands.sync(), GhCommands.submit(ready = false), GhCommands.submit(ready = true))
            .forEach { assertTrue(GhCommands.display(it), GhCommands.pushesStack(it)) }
        listOf(GhCommands.rebase(), GhCommands.rebase(RebaseScope.UPSTACK), GhCommands.rebaseContinue(), GhCommands.view(), GhCommands.top())
            .forEach { assertFalse(GhCommands.display(it), GhCommands.pushesStack(it)) }
    }

    @Test
    fun `branch names`() {
        listOf("feat/payment-api", "fix-1", "user/feat.x").forEach { assertNull(it, GhCommands.branchNameProblem(it)) }
        assertEquals(BranchNameProblem.EMPTY, GhCommands.branchNameProblem(" "))
        assertEquals(BranchNameProblem.WHITESPACE, GhCommands.branchNameProblem("feat x"))
        assertEquals(BranchNameProblem.CHARACTERS, GhCommands.branchNameProblem("feat:x"))
        assertEquals(BranchNameProblem.BOUNDARY, GhCommands.branchNameProblem("-x"))
        assertEquals(BranchNameProblem.BOUNDARY, GhCommands.branchNameProblem("feat/"))
        assertEquals(BranchNameProblem.BOUNDARY, GhCommands.branchNameProblem("x.lock"))
        assertEquals(BranchNameProblem.SEQUENCE, GhCommands.branchNameProblem("a..b"))
        assertEquals(BranchNameProblem.SEQUENCE, GhCommands.branchNameProblem("feat/.hidden"))
    }
}
