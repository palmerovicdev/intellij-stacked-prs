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
)

/** A null result means the response is invalid or incomplete, not an empty repository. */
object OpenPullRequests {
    fun parse(text: String): List<OpenPullRequest>? {
        val pages = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: IllegalArgumentException) { null } ?: return null
        if (pages.isEmpty()) return null
        val result = mutableListOf<OpenPullRequest>()
        pages.forEachIndexed { index, page ->
            val root = page as? JsonObject ?: return null
            if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) return null
            val data = root["data"] as? JsonObject ?: return null
            val repo = data["repository"] as? JsonObject ?: return null
            val prs = repo["pullRequests"] as? JsonObject ?: return null
            val info = prs["pageInfo"] as? JsonObject ?: return null
            val hasNext = (info["hasNextPage"] as? JsonPrimitive)?.booleanOrNull ?: return null
            if (hasNext != (index < pages.lastIndex)) return null
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
                )
            }
        }
        return result
    }
}
