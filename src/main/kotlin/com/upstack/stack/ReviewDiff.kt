package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class ReviewRevisions(val base: String, val head: String, val changedFiles: Int)
data class ReviewFile(val oldPath: String?, val newPath: String?, val status: String)
data class PositionedReviewThread(val thread: ReviewThread, val lineIndex: Int)
data class ReviewThreadPositions(val anchored: List<PositionedReviewThread>, val unplaced: List<ReviewThread>)

/** Immutable PR revisions and file identities used to locate review threads in an exact diff. */
object ReviewDiff {
    fun parseRevisions(text: String): ReviewRevisions? {
        val root = parse(text) as? JsonObject ?: return null
        val base = root.string("baseRefOid")?.takeIf(::isCommitId) ?: return null
        val head = root.string("headRefOid")?.takeIf(::isCommitId) ?: return null
        val count = (root["changedFiles"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        return ReviewRevisions(base, head, count)
    }

    fun parseMergeBase(text: String): String? =
        ((parse(text) as? JsonObject)?.get("merge_base_commit") as? JsonObject)?.string("sha")?.takeIf(::isCommitId)

    /** Reads a Git blob, not the contents endpoint, which may dereference symbolic links. */
    fun parseBlob(text: String): String? {
        val root = parse(text) as? JsonObject ?: return null
        if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) return null
        val data = root["data"] as? JsonObject ?: return null
        val repo = data["repository"] as? JsonObject ?: return null
        val blob = repo["object"] as? JsonObject ?: return null
        if (blob.string("__typename") != "Blob") return null
        if ((blob["isBinary"] as? JsonPrimitive)?.booleanOrNull != false) return null
        if ((blob["isTruncated"] as? JsonPrimitive)?.booleanOrNull != false) return null
        return blob.string("text")
    }

    /** GitHub limits its PR-file endpoint to 3000 entries; mismatches are not an empty list. */
    fun parseFiles(text: String, expected: Int): List<ReviewFile>? {
        if (expected !in 0..MAX_FILES) return null
        val pages = parse(text) as? JsonArray ?: return null
        if (pages.isEmpty()) return null
        val files = mutableListOf<ReviewFile>()
        val paths = mutableSetOf<String>()
        pages.forEach { page ->
            (page as? JsonArray ?: return null).forEach { item ->
                val node = item as? JsonObject ?: return null
                val name = node.string("filename")?.takeIf(::isSafePath) ?: return null
                if (!paths.add(name)) return null
                val status = node.string("status") ?: return null
                val file = when (status) {
                    "added" -> ReviewFile(null, name, status)
                    "removed" -> ReviewFile(name, null, status)
                    "renamed", "copied" -> ReviewFile(node.string("previous_filename")?.takeIf(::isSafePath) ?: return null, name, status)
                    "modified", "changed", "unchanged" -> ReviewFile(name, name, status)
                    else -> return null
                }
                files += file
            }
        }
        return files.takeIf { it.size == expected }
    }

    fun isCommitId(value: String): Boolean = COMMIT.matches(value)
    fun isSafePath(value: String): Boolean = value.isNotBlank() && value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Outdated, file-level or out-of-range threads remain visible but are never attached to an incorrect line. */
    fun positionThreads(file: ReviewFile, threads: List<ReviewThread>, leftLines: Int, rightLines: Int): ReviewThreadPositions {
        val anchored = mutableListOf<PositionedReviewThread>()
        val unplaced = mutableListOf<ReviewThread>()
        for (thread in threads) {
            if (thread.path != file.oldPath && thread.path != file.newPath) continue
            val limit = if (thread.side == ReviewSide.LEFT) leftLines else rightLines
            val sideExists = if (thread.side == ReviewSide.LEFT) file.oldPath != null else file.newPath != null
            val line = thread.line
            if (!thread.outdated && sideExists && line != null && line in 1..limit) anchored += PositionedReviewThread(thread, line - 1)
            else unplaced += thread
        }
        return ReviewThreadPositions(anchored, unplaced)
    }

    private fun parse(text: String) = try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { null }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private val COMMIT = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    private const val MAX_FILES = 3000
}

data class ReviewSnapshot(
    val revisions: ReviewRevisions,
    val mergeBase: String,
    val files: List<ReviewFile>,
    val threads: List<ReviewThread>,
)

sealed interface ReviewDiffRead {
    data class Success(val snapshot: ReviewSnapshot) : ReviewDiffRead
    data class Failure(val message: String) : ReviewDiffRead
}

sealed interface ReviewFileRead {
    data class Text(val content: String) : ReviewFileRead
    data class Failure(val message: String) : ReviewFileRead
}

/** Loads one consistent PR snapshot, rejecting partial results and changing revisions. */
object ReviewDiffReader {
    suspend fun read(
        repo: GitHubRepo,
        number: Int,
        url: String,
        run: suspend (List<String>) -> GhResult,
    ): ReviewDiffRead {
        val urlRepo = GitHubRepo.fromPullRequestUrl(url)
        if (urlRepo == null || !urlRepo.host.equals(repo.host, ignoreCase = true) || !urlRepo.sameAs(repo) || GitHubRepo.pullRequestNumber(url) != number) {
            return ReviewDiffRead.Failure("Pull request does not match its repository or number")
        }
        val first = run(GhCommands.prRevisions(url))
        if (!first.ok) return ReviewDiffRead.Failure(first.errorText)
        val revisions = ReviewDiff.parseRevisions(first.stdout) ?: return ReviewDiffRead.Failure("Invalid pull request revisions")

        val compare = run(GhCommands.prMergeBase(repo, revisions.base, revisions.head))
        if (!compare.ok) return ReviewDiffRead.Failure(compare.errorText)
        val mergeBase = ReviewDiff.parseMergeBase(compare.stdout) ?: return ReviewDiffRead.Failure("Invalid merge base")

        val fileResult = run(GhCommands.prFiles(repo, number))
        if (!fileResult.ok) return ReviewDiffRead.Failure(fileResult.errorText)
        val files = ReviewDiff.parseFiles(fileResult.stdout, revisions.changedFiles)
            ?: return ReviewDiffRead.Failure("Incomplete or unsupported pull request file list")

        val threads = when (val result = ReviewThreadReader.read(repo, number, run)) {
            is ReviewThreadRead.Failure -> return ReviewDiffRead.Failure(result.message)
            is ReviewThreadRead.Success -> result.threads
        }

        val last = run(GhCommands.prRevisions(url))
        if (!last.ok) return ReviewDiffRead.Failure(last.errorText)
        if (ReviewDiff.parseRevisions(last.stdout) != revisions) {
            return ReviewDiffRead.Failure("Pull request changed while loading; refresh and try again")
        }
        return ReviewDiffRead.Success(ReviewSnapshot(revisions, mergeBase, files, threads))
    }

    suspend fun readContent(
        repo: GitHubRepo,
        path: String?,
        revision: String,
        run: suspend (List<String>) -> GhResult,
    ): ReviewFileRead {
        if (path == null) return ReviewFileRead.Text("")
        val result = run(GhCommands.prFileContent(repo, path, revision))
        if (!result.ok) return ReviewFileRead.Failure(result.errorText)
        val content = ReviewDiff.parseBlob(result.stdout)
            ?: return ReviewFileRead.Failure("File is binary, truncated, or unavailable as a text blob")
        return ReviewFileRead.Text(content)
    }
}
