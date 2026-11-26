package com.upstack.stack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class OpenPullRequest(
    val number: Int, val title: String, val url: String, val head: String, val base: String, val draft: Boolean,
    val author: String? = null, val createdAt: String? = null, val details: PrDetails? = null,
    val state: PullRequestState = PullRequestState.OPEN,
)

/** The states GitHub gives a pull request; the names are the literals its `states:` argument takes. */
enum class PullRequestState { OPEN, CLOSED, MERGED }

/** A null result means the response is invalid or incomplete, not an empty repository. */
object OpenPullRequests {
    fun parse(text: String): List<OpenPullRequest>? = read(text, partial = false)?.pullRequests

    /** One page of a cursor-paged list: `text` is a single GraphQL response wrapped in an array. */
    fun parsePage(text: String): Page? = read(text, partial = true)

    data class Page(val pullRequests: List<OpenPullRequest>, val nextCursor: String?)

    private fun read(text: String, partial: Boolean): Page? {
        val pages = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: IllegalArgumentException) { null } ?: return null
        if (pages.isEmpty() || (partial && pages.size != 1)) return null
        var nextCursor: String? = null
        val result = mutableListOf<OpenPullRequest>()
        pages.forEachIndexed { index, page ->
            val root = page as? JsonObject ?: return null
            if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) return null
            val data = root["data"] as? JsonObject ?: return null
            val repo = data["repository"] as? JsonObject ?: return null
            val prs = repo["pullRequests"] as? JsonObject ?: return null
            val info = prs["pageInfo"] as? JsonObject ?: return null
            val hasNext = (info["hasNextPage"] as? JsonPrimitive)?.booleanOrNull ?: return null
            if (partial) {
                if (hasNext) nextCursor = (info["endCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: return null
            } else if (hasNext != (index < pages.lastIndex)) return null
            val nodes = prs["nodes"] as? JsonArray ?: return null
            nodes.forEach { node ->
                val pr = node as? JsonObject ?: return null
                fun string(key: String) = (pr[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                result += OpenPullRequest(
                    (pr["number"] as? JsonPrimitive)?.intOrNull ?: return null,
                    string("title") ?: return null,
                    string("url") ?: return null,
                    string("headRefName") ?: return null,
                    string("baseRefName") ?: return null,
                    (pr["isDraft"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                    (pr["author"] as? JsonObject)?.get("login")?.let { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content },
                    string("createdAt"),
                    StackJson.parsePrDetailsNode(pr),
                    string("state")?.let { name -> PullRequestState.entries.firstOrNull { it.name == name } ?: return null }
                        ?: PullRequestState.OPEN,
                )
            }
        }
        return Page(result, nextCursor)
    }
}
