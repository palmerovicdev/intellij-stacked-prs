package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewDiffTest {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private val mergeBase = "c".repeat(40)
    private val repo = GitHubRepo("ghe.example.com", "acme", "shop")

    @Test
    fun `reads immutable revisions and actual merge base`() {
        val revisions = ReviewDiff.parseRevisions("""{"baseRefOid":"$base","headRefOid":"$head","changedFiles":2}""")!!
        assertEquals(base, revisions.base)
        assertEquals(head, revisions.head)
        assertEquals(2, revisions.changedFiles)
        assertEquals(mergeBase, ReviewDiff.parseMergeBase("""{"merge_base_commit":{"sha":"$mergeBase"}}"""))
        assertNull(ReviewDiff.parseRevisions("""{"baseRefOid":"branch","headRefOid":"$head","changedFiles":1}"""))
    }

    @Test
    fun `reads all file pages preserving renamed added and deleted paths`() {
        val pages = """[[{"filename":"new name.kt","previous_filename":"old name.kt","status":"renamed"}],
          [{"filename":"created.kt","status":"added"},{"filename":"deleted.kt","status":"removed"}]]"""
        val files = ReviewDiff.parseFiles(pages, 3)!!
        assertEquals("old name.kt", files[0].oldPath)
        assertEquals("new name.kt", files[0].newPath)
        assertNull(files[1].oldPath)
        assertNull(files[2].newPath)
        assertNull(ReviewDiff.parseFiles(pages, 4))
        assertNull(ReviewDiff.parseFiles(pages, 3001))
    }

    @Test
    fun `commands select exact PR repository revisions and encode file names`() {
        assertEquals(listOf("pr", "view", "https://ghe.example.com/acme/shop/pull/12", "--json", "baseRefOid,headRefOid,changedFiles"),
            GhCommands.prRevisions("https://ghe.example.com/acme/shop/pull/12"))
        assertTrue(GhCommands.prMergeBase(repo, base, head).contains("repos/acme/shop/compare/$base...$head?per_page=1"))
        assertTrue(GhCommands.prFiles(repo, 12).contains("repos/acme/shop/pulls/12/files?per_page=100"))
        val fileArgs = GhCommands.prFileContent(repo, "lib/hello world+#.kt", head)
        assertTrue(fileArgs.contains("expression=$head:lib/hello world+#.kt"))
        assertTrue(fileArgs.containsAll(listOf("api", "graphql", "--hostname", "ghe.example.com")))
        assertThrows(IllegalArgumentException::class.java) { GhCommands.prFileContent(repo, "../secret", head) }
    }

    @Test
    fun `loads immutable snapshot and rejects PRs changing during read`() = runBlocking {
        val url = "https://ghe.example.com/acme/shop/pull/12"
        var revisionReads = 0
        val result = ReviewDiffReader.read(repo, 12, url) { args ->
            val output = when {
                args.take(2) == listOf("pr", "view") -> {
                    revisionReads++
                    """{"baseRefOid":"$base","headRefOid":"${if (revisionReads == 1) head else mergeBase}","changedFiles":1}"""
                }
                args.any { it.contains("/compare/") } -> """{"merge_base_commit":{"sha":"$mergeBase"}}"""
                args.any { it.contains("/pulls/12/files") } -> """[[{"filename":"changed.kt","status":"modified"}]]"""
                else -> """[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}}]"""
            }
            GhResult(args, 0, output, "")
        }
        assertTrue(result is ReviewDiffRead.Failure)
        assertEquals(2, revisionReads)
    }

    @Test
    fun `successful snapshot retains merge base and review comments`() = runBlocking {
        val url = "https://ghe.example.com/acme/shop/pull/12"
        var revisionReads = 0
        val result = ReviewDiffReader.read(repo, 12, url) { args ->
            val output = when {
                args.take(2) == listOf("pr", "view") -> {
                    revisionReads++
                    """{"baseRefOid":"$base","headRefOid":"$head","changedFiles":1}"""
                }
                args.any { it.contains("/compare/") } -> """{"merge_base_commit":{"sha":"$mergeBase"}}"""
                args.any { it.contains("/pulls/12/files") } -> """[[{"filename":"changed.kt","status":"modified"}]]"""
                else -> """[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"T1","path":"changed.kt",
                  "line":5,"diffSide":"RIGHT","isOutdated":false,"isResolved":false,"comments":{"totalCount":1,
                  "nodes":[{"id":"C1","body":"Fix this","url":"https://ghe.example.com/acme/shop/pull/12#discussion_r1","author":{"login":"alice"}}]}}],
                  "pageInfo":{"hasNextPage":false}}}}}}]"""
            }
            GhResult(args, 0, output, "")
        }
        assertTrue(result is ReviewDiffRead.Success)
        val snapshot = (result as ReviewDiffRead.Success).snapshot
        assertEquals(mergeBase, snapshot.mergeBase)
        assertEquals("changed.kt", snapshot.files.single().newPath)
        assertEquals("Fix this", snapshot.threads.single().comments.single().body)
        assertEquals(2, revisionReads)
    }

    @Test
    fun `mismatched host is rejected before making any request`() = runBlocking {
        var calls = 0
        val result = ReviewDiffReader.read(GitHubRepo("alias", "acme", "shop"), 12, "https://github.com/acme/shop/pull/12") { args ->
            calls++
            GhResult(args, 0, "", "")
        }
        assertTrue(result is ReviewDiffRead.Failure)
        assertEquals(0, calls)
    }

    @Test
    fun `empty side of added or removed file needs no request and binary content is rejected`() = runBlocking {
        var calls = 0
        val empty = ReviewDiffReader.readContent(repo, null, head) { args -> calls++; GhResult(args, 0, "", "") }
        assertEquals(ReviewFileRead.Text(""), empty)
        assertEquals(0, calls)
        val binary = ReviewDiffReader.readContent(repo, "image.png", head) { args ->
            GhResult(args, 0, """{"data":{"repository":{"object":{"__typename":"Blob","isBinary":true,"isTruncated":false,"text":null}}}}""", "")
        }
        assertTrue(binary is ReviewFileRead.Failure)
    }

    @Test
    fun `file reader uses exact git blob and rejects truncated and non-blob objects`() = runBlocking {
        suspend fun read(response: String) = ReviewDiffReader.readContent(repo, "link", head) { args -> GhResult(args, 0, response, "") }
        val linkBlob = """{"data":{"repository":{"object":{"__typename":"Blob","isBinary":false,"isTruncated":false,"text":"target/path"}}}}"""
        assertEquals(ReviewFileRead.Text("target/path"), read(linkBlob))
        val replacementCharacter = """{"data":{"repository":{"object":{"__typename":"Blob","isBinary":false,"isTruncated":false,"text":"�"}}}}"""
        assertEquals(ReviewFileRead.Text("�"), read(replacementCharacter))
        assertTrue(read("""{"data":{"repository":{"object":{"__typename":"Blob","isBinary":false,"isTruncated":true,"text":"partial"}}}}""") is ReviewFileRead.Failure)
        assertTrue(read("""{"data":{"repository":{"object":{"__typename":"Commit"}}}}""") is ReviewFileRead.Failure)
    }

    @Test
    fun `only current in-range comments are anchored on their correct side`() {
        fun thread(id: String, path: String, side: ReviewSide, line: Int?, outdated: Boolean = false) =
            ReviewThread(id, path, side, line, null, 4, null, null, false, outdated, emptyList(), 0)
        val file = ReviewFile("old.kt", "new.kt", "renamed")
        val threads = listOf(
            thread("right", "new.kt", ReviewSide.RIGHT, 3),
            thread("left", "new.kt", ReviewSide.LEFT, 2),
            thread("outdated", "old.kt", ReviewSide.RIGHT, null, outdated = true),
            thread("outside", "new.kt", ReviewSide.RIGHT, 99),
            thread("other", "another.kt", ReviewSide.RIGHT, 1),
        )
        val positions = ReviewDiff.positionThreads(file, threads, leftLines = 5, rightLines = 5)
        assertEquals(listOf("right", "left"), positions.anchored.map { it.thread.id })
        assertEquals(listOf(2, 1), positions.anchored.map { it.lineIndex })
        assertEquals(listOf("outdated", "outside"), positions.unplaced.map { it.id })
    }
}
