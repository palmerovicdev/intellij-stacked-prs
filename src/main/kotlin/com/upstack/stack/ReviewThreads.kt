package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

enum class ReviewSide { LEFT, RIGHT }

data class ReviewComment(val id: String, val body: String, val url: String, val author: String?)

data class ReviewThread(
    val id: String,
    val path: String,
    val side: ReviewSide,
    val line: Int?,
    val startLine: Int?,
    val originalLine: Int?,
    val originalStartLine: Int?,
    val startSide: ReviewSide?,
    val resolved: Boolean,
    val outdated: Boolean,
    val comments: List<ReviewComment>,
    val totalComments: Int,
) {
    val hasMissingComments: Boolean get() = comments.size < totalComments
}

/** Strictly distinguishes a complete empty response from an incomplete or failed GraphQL read. */
object ReviewThreads {
    fun parse(text: String): List<ReviewThread>? {
        val pages = pages(text) ?: return null
        val threads = mutableListOf<ReviewThread>()
        val ids = mutableSetOf<String>()
        pages.forEachIndexed { index, page ->
            val connection = page.obj("data")?.obj("repository")?.obj("pullRequest")?.obj("reviewThreads") ?: return null
            if (!completePage(connection, index, pages.lastIndex)) return null
            val nodes = connection["nodes"] as? JsonArray ?: return null
            nodes.forEach { element ->
                val node = element as? JsonObject ?: return null
                val id = node.string("id") ?: return null
                if (!ids.add(id)) return null
                val side = node.string("diffSide")?.let { value -> ReviewSide.entries.firstOrNull { it.name == value } } ?: return null
                val comments = node.obj("comments") ?: return null
                val total = comments.int("totalCount") ?: return null
                val parsed = parseCommentNodes(comments) ?: return null
                if (total < parsed.size) return null
                threads += ReviewThread(
                    id, node.string("path") ?: return null, side, node.int("line"), node.int("startLine"),
                    node.int("originalLine"), node.int("originalStartLine"),
                    node.string("startDiffSide")?.let { value -> ReviewSide.entries.firstOrNull { it.name == value } },
                    node.bool("isResolved") ?: return null, node.bool("isOutdated") ?: return null,
                    parsed, total,
                )
            }
        }
        return threads
    }

    fun parseComments(text: String, threadId: String): List<ReviewComment>? {
        val pages = pages(text) ?: return null
        val comments = mutableListOf<ReviewComment>()
        val ids = mutableSetOf<String>()
        pages.forEachIndexed { index, page ->
            val node = page.obj("data")?.obj("node") ?: return null
            if (node.string("id") != threadId) return null
            val connection = node.obj("comments") ?: return null
            if (!completePage(connection, index, pages.lastIndex)) return null
            val parsed = parseCommentNodes(connection) ?: return null
            if (parsed.any { !ids.add(it.id) }) return null
            comments += parsed
        }
        return comments
    }

    private fun pages(text: String): List<JsonObject>? {
        val array = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: IllegalArgumentException) { null } ?: return null
        if (array.isEmpty()) return null
        return array.map { element ->
            val page = element as? JsonObject ?: return null
            if ((page["errors"] as? JsonArray)?.isNotEmpty() == true) return null
            page
        }
    }

    private fun completePage(connection: JsonObject, index: Int, last: Int): Boolean =
        connection.obj("pageInfo")?.bool("hasNextPage") == (index < last)

    private fun parseCommentNodes(connection: JsonObject): List<ReviewComment>? {
        val nodes = connection["nodes"] as? JsonArray ?: return null
        val ids = mutableSetOf<String>()
        return nodes.map { element ->
            val comment = element as? JsonObject ?: return null
            val id = comment.string("id") ?: return null
            if (!ids.add(id)) return null
            ReviewComment(id, comment.string("body") ?: return null, comment.string("url") ?: return null, comment.obj("author")?.string("login"))
        }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}

sealed interface ReviewThreadRead {
    data class Success(val threads: List<ReviewThread>) : ReviewThreadRead
    data class Failure(val message: String) : ReviewThreadRead
}

/** Fetches all thread pages and completes long conversations before exposing any result. */
object ReviewThreadReader {
    suspend fun read(
        repo: GitHubRepo,
        number: Int,
        run: suspend (List<String>) -> GhResult,
    ): ReviewThreadRead {
        val result = run(GhCommands.reviewThreads(repo, number))
        if (!result.ok) return ReviewThreadRead.Failure(result.errorText)
        val threads = ReviewThreads.parse(result.stdout) ?: return ReviewThreadRead.Failure("Invalid or incomplete review threads response")
        val completed = mutableListOf<ReviewThread>()
        for (thread in threads) {
            if (!thread.hasMissingComments) {
                completed += thread
                continue
            }
            val replies = run(GhCommands.reviewThreadComments(repo, thread.id))
            if (!replies.ok) return ReviewThreadRead.Failure(replies.errorText)
            val comments = ReviewThreads.parseComments(replies.stdout, thread.id)
                ?: return ReviewThreadRead.Failure("Invalid or incomplete review comments response")
            completed += thread.copy(comments = comments, totalComments = comments.size)
        }
        return ReviewThreadRead.Success(completed)
    }
}
