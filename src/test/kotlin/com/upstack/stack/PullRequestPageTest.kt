package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import com.upstack.settings.UpstackSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestPageTest {
    private val repo = GitHubRepo("github.com", "acme", "shop")

    private fun page(next: String?) = """{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
         "labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
        ],"pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" }}}}}}}"""

    @Test
    fun `a page asks for its size newest first, without paginating, and continues from the cursor`() {
        val first = GhCommands.pullRequestsPage(repo, setOf(PullRequestState.CLOSED), null, 20)
        assertFalse("--paginate" in first)
        assertFalse(first.any { it.startsWith("endCursor=") })
        val query = first.single { it.startsWith("query=") }
        assertTrue(query.contains("states: CLOSED, first: 20, orderBy: {field: CREATED_AT, direction: DESC}, after: \$endCursor"))
        assertTrue("endCursor=C1" in GhCommands.pullRequestsPage(repo, setOf(PullRequestState.OPEN), "C1", 5))
    }

    @Test
    fun `reading a page returns the cursor of the next one, and none on the last page`() = runBlocking {
        val more = OpenPullRequestReader.read(repo, setOf(PullRequestState.OPEN), PullRequestPage(null, 20)) { args -> GhResult(args, 0, page("C1"), "") }
        assertEquals("C1", (more as OpenPullRequestRead.Success).nextCursor)
        val last = OpenPullRequestReader.read(repo, setOf(PullRequestState.OPEN), PullRequestPage("C1", 20)) { args -> GhResult(args, 0, page(null), "") }
        assertNull((last as OpenPullRequestRead.Success).nextCursor)
        assertEquals(listOf(12), last.pullRequests.map { it.number })
    }

    @Test
    fun `a page that says there is more without a cursor is incomplete`() = runBlocking {
        val broken = page("C1").replace("\"endCursor\":\"C1\"", "\"endCursor\":null")
        val result = OpenPullRequestReader.read(repo, setOf(PullRequestState.OPEN), PullRequestPage()) { args -> GhResult(args, 0, broken, "") }
        assertTrue((result as OpenPullRequestRead.Failure).invalid)
    }

    @Test
    fun `the page size setting stays between 1 and the GraphQL limit`() {
        assertEquals(20, UpstackSettings.DEFAULT_PAGE_SIZE)
        assertEquals(20, UpstackSettings.pageSize(0))
        assertEquals(20, UpstackSettings.pageSize(-3))
        assertEquals(1, UpstackSettings.pageSize(1))
        assertEquals(100, UpstackSettings.pageSize(5000))
    }
}
