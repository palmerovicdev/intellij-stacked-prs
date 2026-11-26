package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewThreadsTest {
    private val firstPage = """{"data":{"repository":{"pullRequest":{"reviewThreads":{
      "nodes":[{"id":"T1","path":"src/example.kt","line":15,"startLine":13,"originalLine":15,"originalStartLine":13,
        "diffSide":"RIGHT","startDiffSide":"RIGHT","isOutdated":false,"isResolved":false,
        "comments":{"totalCount":2,"nodes":[{"id":"C1","body":"Please fix this","url":"https://github.com/a/b/pull/12#discussion_r1","author":{"login":"alice"}}]}}],
      "pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}}"""
    private val secondPage = """{"data":{"repository":{"pullRequest":{"reviewThreads":{
      "nodes":[{"id":"T2","path":"old.kt","line":null,"startLine":null,"originalLine":7,"originalStartLine":null,
        "diffSide":"LEFT","startDiffSide":null,"isOutdated":true,"isResolved":true,
        "comments":{"totalCount":1,"nodes":[{"id":"C2","body":"Old comment","url":"https://github.com/a/b/pull/12#discussion_r2","author":null}]}}],
      "pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}"""

    @Test
    fun `preserves review context and notices replies not yet loaded`() {
        val threads = ReviewThreads.parse("[$firstPage,$secondPage]")!!
        assertEquals(listOf("T1", "T2"), threads.map { it.id })
        val current = threads.first()
        assertEquals("src/example.kt", current.path)
        assertEquals(ReviewSide.RIGHT, current.side)
        assertEquals(15, current.line)
        assertEquals(13, current.startLine)
        assertEquals("alice", current.comments.single().author)
        assertTrue(current.hasMissingComments)
        val old = threads.last()
        assertNull(old.line)
        assertEquals(7, old.originalLine)
        assertEquals(ReviewSide.LEFT, old.side)
        assertTrue(old.outdated)
        assertTrue(old.resolved)
        assertNull(old.comments.single().author)
    }

    @Test
    fun `rejects incomplete or errored pages rather than showing a partial list`() {
        assertNull(ReviewThreads.parse("[$firstPage]"))
        assertNull(ReviewThreads.parse("[{\"errors\":[{\"message\":\"denied\"}]}]"))
        assertNull(ReviewThreads.parse("[]"))
        assertEquals(emptyList<ReviewThread>(), ReviewThreads.parse("""[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}}]"""))
    }

    @Test
    fun `thread comments are read from every page`() {
        val pages = """[
          {"data":{"node":{"id":"T1","comments":{"nodes":[{"id":"C1","body":"First","url":"u1","author":{"login":"a"}}],"pageInfo":{"hasNextPage":true}}}}},
          {"data":{"node":{"id":"T1","comments":{"nodes":[{"id":"C2","body":"Reply","url":"u2","author":{"login":"b"}}],"pageInfo":{"hasNextPage":false}}}}}
        ]"""
        assertEquals(listOf("First", "Reply"), ReviewThreads.parseComments(pages, "T1")?.map { it.body })
        assertNull(ReviewThreads.parseComments(pages, "T2"))
        assertNull(ReviewThreads.parseComments("""[{"data":{"node":{"id":"T1","comments":{"nodes":[],"pageInfo":{"hasNextPage":true}}}}}]""", "T1"))
    }

    @Test
    fun `graphql commands paginate only the intended connection`() {
        val repo = GitHubRepo("ghe.example.com", "acme", "shop")
        val threadArgs = GhCommands.reviewThreads(repo, 12)
        assertTrue(threadArgs.containsAll(listOf("--hostname", "ghe.example.com", "--paginate", "--slurp", "number=12")))
        val query = threadArgs.single { it.startsWith("query=") }
        assertTrue(query.contains("reviewThreads(first: 100, after: \$endCursor)"))
        assertTrue(query.contains("isOutdated isResolved"))
        val commentArgs = GhCommands.reviewThreadComments(repo, "THREAD_ID")
        assertTrue(commentArgs.containsAll(listOf("--paginate", "--slurp", "threadId=THREAD_ID")))
        assertTrue(commentArgs.single { it.startsWith("query=") }.contains("comments(first: 100, after: \$endCursor)"))
        assertEquals(listOf("stack", "view", "--json"), GhCommands.view())
    }

    @Test
    fun `reader completes truncated conversations without losing other threads`() = runBlocking {
        val repo = GitHubRepo("github.com", "a", "b")
        val calls = mutableListOf<List<String>>()
        val result = ReviewThreadReader.read(repo, 12) { args ->
            calls += args
            val response = if (args.contains("threadId=T1")) """[{"data":{"node":{"id":"T1","comments":{"nodes":[
              {"id":"C1","body":"Please fix this","url":"u1","author":{"login":"alice"}},
              {"id":"C3","body":"Done","url":"u3","author":{"login":"bob"}}
            ],"pageInfo":{"hasNextPage":false}}}}}]""" else "[$firstPage,$secondPage]"
            GhResult(args, 0, response, "")
        }
        assertTrue(result is ReviewThreadRead.Success)
        val threads = (result as ReviewThreadRead.Success).threads
        assertEquals(listOf("T1", "T2"), threads.map { it.id })
        assertEquals(listOf("Please fix this", "Done"), threads.first().comments.map { it.body })
        assertFalse(threads.first().hasMissingComments)
        assertEquals(2, calls.size)
    }

    @Test
    fun `reader reports an error rather than publishing incomplete replies`() = runBlocking {
        val repo = GitHubRepo("github.com", "a", "b")
        val result = ReviewThreadReader.read(repo, 12) { args ->
            if (args.contains("threadId=T1")) GhResult(args, 1, "", "Permission denied")
            else GhResult(args, 0, "[$firstPage,$secondPage]", "")
        }
        assertTrue(result is ReviewThreadRead.Failure)
        assertEquals("Permission denied", (result as ReviewThreadRead.Failure).message)
    }
}
