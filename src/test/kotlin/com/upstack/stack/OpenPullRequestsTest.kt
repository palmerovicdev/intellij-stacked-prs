package com.upstack.stack

import com.upstack.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPullRequestsTest {
    @Test
    fun `reads review information and metadata for each pull request`() {
        val response = """[{"data":{"repository":{"pullRequests":{"nodes":[{
          "number":1374,"title":"Improve editor","url":"https://github.com/a/b/pull/1374",
          "headRefName":"feature","baseRefName":"dev","isDraft":true,"state":"OPEN",
          "author":{"login":"victor"},"createdAt":"2026-11-25T10:00:00Z",
          "reviewDecision":"CHANGES_REQUESTED","mergeable":"CONFLICTING","mergeStateStatus":"DIRTY",
          "additions":42,"deletions":3,"changedFiles":2,
          "labels":{"nodes":[{"name":"bug","color":"d73a4a"}]},
          "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"FAILURE"}}}]}
        }],"pageInfo":{"hasNextPage":false}}}}}]"""
        val pr = OpenPullRequests.parse(response)!!.single()
        assertEquals("victor", pr.author)
        assertEquals("2026-11-25T10:00:00Z", pr.createdAt)
        assertEquals(ReviewDecision.CHANGES_REQUESTED, pr.details?.review)
        assertEquals(ChecksState.FAILURE, pr.details?.checks)
        assertEquals(PrSize(42, 3, 2), pr.details?.size)
        assertEquals(listOf(PrLabel("bug", "d73a4a")), pr.details?.labels)
        assertTrue(pr.details!!.hasConflicts)
    }
    @Test
    fun `listing explicitly selects repository host and paginates`() {
        val args = GhCommands.openPullRequests(GitHubRepo("ghe.example.com", "acme", "store"))
        assertEquals(listOf("api", "graphql", "--paginate", "--slurp", "--hostname", "ghe.example.com"), args.take(6))
        assertTrue("owner=acme" in args)
        assertTrue("name=store" in args)
        val query = args.single { it.startsWith("query=") }
        assertTrue(query.contains("states: OPEN"))
        assertTrue(query.contains("after: \$endCursor"))
        assertTrue(query.contains("hasNextPage endCursor"))
        assertEquals(listOf("stack", "view", "--json"), GhCommands.view())
    }
    @Test
    fun `listing asks only for the requested states`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        fun query(states: Set<PullRequestState>) = GhCommands.openPullRequests(repo, states).single { it.startsWith("query=") }
        assertTrue(query(setOf(PullRequestState.CLOSED)).contains("states: CLOSED,"))
        assertTrue(query(setOf(PullRequestState.MERGED)).contains("states: MERGED,"))
        assertTrue(query(PullRequestState.entries.toSet()).contains("states: [OPEN, CLOSED, MERGED],"))
        assertEquals(GhCommands.openPullRequests(repo), GhCommands.openPullRequests(repo, setOf(PullRequestState.OPEN)))
    }
    @Test
    fun `reads every page of open pull requests`() {
        val pages = """[
          {"data":{"repository":{"pullRequests":{"nodes":[
            {"number":1,"title":"First","url":"https://github.com/a/b/pull/1","headRefName":"feature","baseRefName":"main","isDraft":true}
          ],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}},
          {"data":{"repository":{"pullRequests":{"nodes":[
            {"number":2,"title":"Second","url":"https://github.com/a/b/pull/2","headRefName":"fix","baseRefName":"main","isDraft":false}
          ],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
        ]"""
        val prs = OpenPullRequests.parse(pages)!!
        assertEquals(listOf(1, 2), prs.map { it.number })
        assertEquals("feature", prs.first().head)
        assertTrue(prs.first().draft)
    }

    @Test
    fun `empty list is distinct from errors and incomplete pages`() {
        assertEquals(emptyList<OpenPullRequest>(), OpenPullRequests.parse("""[{"data":{"repository":{"pullRequests":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}]"""))
        assertNull(OpenPullRequests.parse("[]"))
        assertNull(OpenPullRequests.parse("""[{"errors":[{"message":"denied"}]}]"""))
        assertNull(OpenPullRequests.parse("""[{"data":{"repository":{"pullRequests":{"nodes":[],"pageInfo":{"hasNextPage":true}}}}}]"""))
    }
}
