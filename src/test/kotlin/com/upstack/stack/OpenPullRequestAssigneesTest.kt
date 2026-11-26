package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenPullRequestAssigneesTest {
    private val repo = GitHubRepo("ghe.example.com", "acme", "shop")
    private val initial = """[{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":12,"title":"Editor","url":"https://ghe.example.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
         "labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},
         "assignees":{"nodes":[{"login":"alice"}],"assigneePageInfo":{"hasNextPage":true,"endCursor":"first"}}}
        ],"pageInfo":{"hasNextPage":false}}}}}]"""
    private val fullAssignees = """[
        {"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[{"login":"alice"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}},
        {"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[{"login":"bob"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
        ]"""

    @Test
    fun `reader completes overflow assignees without changing PR model`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val result = OpenPullRequestReader.read(repo) { args ->
            calls += args
            GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) initial else fullAssignees, "")
        } as OpenPullRequestRead.Success
        assertEquals(listOf("alice", "bob"), result.assignees[12])
        assertEquals(listOf(GhCommands.openPullRequests(repo), GhCommands.pullRequestAssignees(repo, 12)), calls)
    }

    @Test
    fun `incomplete invalid or failed assignment pages fail the read`() = runBlocking {
        for (response in listOf("[]", fullAssignees.replace("\"number\":12", "\"number\":13"), fullAssignees.replace("\"hasNextPage\":false", "\"hasNextPage\":true"))) {
            val result = OpenPullRequestReader.read(repo) { args -> GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) initial else response, "") }
            assertTrue(result is OpenPullRequestRead.Failure)
        }
        val missing = initial.replace("\"assigneePageInfo\":", "\"missingPageInfo\":")
        assertTrue(OpenPullRequestReader.read(repo) { args -> GhResult(args, 0, missing, "") } is OpenPullRequestRead.Failure)
        val failure = OpenPullRequestReader.read(repo) { args ->
            if (args == GhCommands.openPullRequests(repo)) GhResult(args, 0, initial, "") else GhResult(args, 1, "", "Assignment denied")
        }
        assertTrue(failure is OpenPullRequestRead.Failure)
        assertEquals("Assignment denied", (failure as OpenPullRequestRead.Failure).message)
    }

    @Test
    fun `graphql commands isolate assignee pagination`() {
        val listing = GhCommands.openPullRequests(repo).single { it.startsWith("query=") }
        assertTrue(listing.contains("assigneePageInfo: pageInfo"))
        val args = GhCommands.pullRequestAssignees(repo, 12)
        assertTrue(args.containsAll(listOf("--hostname", "ghe.example.com", "owner=acme", "name=shop", "number=12", "--paginate", "--slurp")))
        assertTrue(args.single { it.startsWith("query=") }.contains("assignees(first: 100, after: \$endCursor)"))
    }

    @Test
    fun `reads over 100 users across multiple PR pages and rejects invalid users`() = runBlocking {
        fun user(n: Int) = """{"login":"user-$n"}"""
        fun pr(n: Int, more: Boolean) = """{"number":$n,"title":"PR $n","url":"https://ghe.example.com/acme/shop/pull/$n","headRefName":"branch","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[${user(n)}],"assigneePageInfo":{"hasNextPage":$more,"endCursor":"initial"}}}"""
        fun page(n: Int, morePrs: Boolean, moreUsers: Boolean) = """{"data":{"repository":{"pullRequests":{"nodes":[${pr(n, moreUsers)}],"pageInfo":{"hasNextPage":$morePrs,"endCursor":"prs"}}}}}"""
        fun userPage(range: IntRange, more: Boolean) = """{"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[${range.joinToString(",") { user(it) }}],"pageInfo":{"hasNextPage":$more,"endCursor":"users"}}}}}}"""
        val calls = mutableListOf<List<String>>()
        val result = OpenPullRequestReader.read(repo) { args ->
            calls += args
            GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) "[${page(12, true, true)},${page(13, false, false)}]"
                else "[${userPage(1..100, true)},${userPage(101..101, false)}]", "")
        } as OpenPullRequestRead.Success
        assertEquals(listOf(12, 13), result.pullRequests.map { it.number })
        assertEquals(101, result.assignees[12]!!.size)
        assertEquals("user-101", result.assignees[12]!!.last())
        assertEquals(listOf("user-13"), result.assignees[13])
        assertEquals(listOf(GhCommands.openPullRequests(repo), GhCommands.pullRequestAssignees(repo, 12)), calls)
        for (bad in listOf(
            fullAssignees.replace("\"login\":\"bob\"", "\"login\":\"ALICE\""),
            fullAssignees.replace("\"login\":\"bob\"", "\"login\":null"),
            fullAssignees.replace("\"login\":\"bob\"", "\"login\":\"\""),
            fullAssignees.replace("\"login\":\"bob\"", "\"login\":123"),
            "[{\"errors\":[{\"message\":\"denied\"}]}]",
        )) {
            assertTrue(OpenPullRequestReader.read(repo) { args -> GhResult(args, 0, if (args == GhCommands.openPullRequests(repo)) initial else bad, "") } is OpenPullRequestRead.Failure)
        }
    }
}
