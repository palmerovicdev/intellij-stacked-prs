package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenPullRequestLabelsTest {
    private val repo = GitHubRepo("ghe.example.com", "acme", "shop")
    private val initial = """[{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":12,"title":"Editor","url":"https://ghe.example.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
         "labels":{"nodes":[{"name":"first","color":"abcdef"}],"labelPageInfo":{"hasNextPage":true,"endCursor":"first-end"}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
        ],"pageInfo":{"hasNextPage":false}}}}}]"""
    private val fullLabels = """[
        {"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[{"name":"first","color":"abcdef"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}},
        {"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[{"name":"after-first-page","color":"123456"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
        ]"""

    @Test
    fun `reader completes labels for PRs with additional pages`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val result = OpenPullRequestReader.read(repo) { args ->
            calls += args
            GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) initial else fullLabels, "")
        }
        assertTrue(result is OpenPullRequestRead.Success)
        assertEquals(listOf("first", "after-first-page"), (result as OpenPullRequestRead.Success).pullRequests.single().details!!.labels.map { it.name })
        assertEquals(listOf(GhCommands.openPullRequests(repo), GhCommands.pullRequestLabels(repo, 12)), calls)
    }

    @Test
    fun `incomplete malformed or failed labels fail the whole refresh`() = runBlocking {
        for (reply in listOf("[]", fullLabels.replace("\"number\":12", "\"number\":13"), fullLabels.replace("\"hasNextPage\":false", "\"hasNextPage\":true"), "[{\"errors\":[{\"message\":\"denied\"}]}]")) {
            val result = OpenPullRequestReader.read(repo) { args ->
                GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) initial else reply, "")
            }
            assertTrue(result is OpenPullRequestRead.Failure)
        }
        val failure = OpenPullRequestReader.read(repo) { args ->
            if (args == GhCommands.openPullRequests(repo)) GhResult(args, 0, initial, "")
            else GhResult(args, 1, "", "Permission denied")
        }
        assertTrue(failure is OpenPullRequestRead.Failure)
        assertEquals("Permission denied", (failure as OpenPullRequestRead.Failure).message)
    }

    @Test
    fun `missing or malformed initial label metadata is not treated as empty`() = runBlocking {
        val broken = listOf(
            initial.replace("\"labels\":{", "\"missingLabels\":{"),
            initial.replace("\"labelPageInfo\":{", "\"missingPageInfo\":{"),
            initial.replace("\"endCursor\":\"first-end\"", "\"endCursor\":null"),
        )
        for (response in broken) {
            val result = OpenPullRequestReader.read(repo) { args -> GhResult(args, 0, response, "") }
            assertTrue(result is OpenPullRequestRead.Failure)
            assertTrue((result as OpenPullRequestRead.Failure).invalid)
        }
    }

    @Test
    fun `complete initial labels require no secondary command`() = runBlocking {
        val complete = initial.replace("\"hasNextPage\":true", "\"hasNextPage\":false")
        val calls = mutableListOf<List<String>>()
        val result = OpenPullRequestReader.read(repo) { args -> calls += args; GhResult(args, 0, complete, "") }
        assertTrue(result is OpenPullRequestRead.Success)
        assertEquals(listOf(GhCommands.openPullRequests(repo)), calls)
        assertEquals("first", (result as OpenPullRequestRead.Success).pullRequests.single().details!!.labels.single().name)
    }

    @Test
    fun `commands isolate the two paginated connections`() {
        val initialQuery = GhCommands.openPullRequests(repo).single { it.startsWith("query=") }
        assertTrue(initialQuery.contains("labelPageInfo: pageInfo"))
        val args = GhCommands.pullRequestLabels(repo, 12)
        assertTrue(args.containsAll(listOf("--hostname", "ghe.example.com", "owner=acme", "name=shop", "number=12", "--paginate", "--slurp")))
        assertTrue(args.single { it.startsWith("query=") }.contains("labels(first: 100, after: \$endCursor)"))
    }

    @Test
    fun `reads over 100 labels across two PR pages and mixed complete states`() = runBlocking {
        fun label(n: Int) = """{"name":"label-$n","color":"abcdef"}"""
        fun pr(n: Int, more: Boolean) = """{"number":$n,"title":"PR $n","url":"https://ghe.example.com/acme/shop/pull/$n","headRefName":"branch","baseRefName":"main","isDraft":false,"labels":{"nodes":[${label(n)}],"labelPageInfo":{"hasNextPage":$more,"endCursor":"initial"}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}"""
        fun page(n: Int, morePrs: Boolean, moreLabels: Boolean) = """{"data":{"repository":{"pullRequests":{"nodes":[${pr(n, moreLabels)}],"pageInfo":{"hasNextPage":$morePrs,"endCursor":"prs"}}}}}"""
        fun labelPage(range: IntRange, more: Boolean) = """{"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[${range.joinToString(",") { label(it) }}],"pageInfo":{"hasNextPage":$more,"endCursor":"labels"}}}}}}"""
        val calls = mutableListOf<List<String>>()
        val result = OpenPullRequestReader.read(repo) { args ->
            calls += args
            GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) "[${page(12, true, true)},${page(13, false, false)}]"
                else "[${labelPage(1..100, true)},${labelPage(101..101, false)}]", "")
        } as OpenPullRequestRead.Success
        assertEquals(listOf(12, 13), result.pullRequests.map { it.number })
        assertEquals(101, result.pullRequests.first().details!!.labels.size)
        assertEquals("label-101", result.pullRequests.first().details!!.labels.last().name)
        assertEquals("label-13", result.pullRequests.last().details!!.labels.single().name)
        assertEquals(listOf(GhCommands.openPullRequests(repo), GhCommands.pullRequestLabels(repo, 12)), calls)
    }
}
