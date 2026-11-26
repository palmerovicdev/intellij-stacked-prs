package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

sealed interface OpenPullRequestRead {
    /** [nextCursor] is only set for a cursor-paged read that has more pull requests after this page. */
    data class Success(
        val pullRequests: List<OpenPullRequest>, val assignees: Map<Int, List<String>>, val nextCursor: String? = null,
    ) : OpenPullRequestRead
    data class Failure(val message: String, val invalid: Boolean = false) : OpenPullRequestRead
}

/** Asks for one page of pull requests instead of all of them; [after] is the cursor a previous page returned. */
data class PullRequestPage(val after: String? = null, val size: Int = 20)

/** Reads every PR, label and assignee before exposing a complete list to the UI (or one page of it when [PullRequestPage] is given). */
object OpenPullRequestReader {
    suspend fun read(
        repo: GitHubRepo, states: Set<PullRequestState> = setOf(PullRequestState.OPEN), page: PullRequestPage? = null,
        run: suspend (List<String>) -> GhResult,
    ): OpenPullRequestRead {
        val result = run(if (page == null) GhCommands.openPullRequests(repo, states) else GhCommands.pullRequestsPage(repo, states, page.after, page.size))
        if (!result.ok) return OpenPullRequestRead.Failure(result.errorText)
        // Without --paginate/--slurp gh prints the single response object; the parsers expect the slurped array.
        val text = if (page == null || result.stdout.trimStart().startsWith("[")) result.stdout else "[${result.stdout.trim()}]"
        val parsed = if (page == null) OpenPullRequests.parse(text)?.let { OpenPullRequests.Page(it, null) } else OpenPullRequests.parsePage(text)
        val prs = parsed?.pullRequests
            ?: return OpenPullRequestRead.Failure("Invalid or incomplete pull request response", invalid = true)
        val states = initialStates(text)
            ?: return OpenPullRequestRead.Failure("Invalid or incomplete pull request metadata", invalid = true)
        if (states.size != prs.size || prs.map { it.number }.toSet().size != prs.size) {
            return OpenPullRequestRead.Failure("Invalid or incomplete pull request metadata", invalid = true)
        }
        val completed = mutableListOf<OpenPullRequest>()
        val allAssignees = mutableMapOf<Int, List<String>>()
        for ((pr, state) in prs.zip(states)) {
            var complete = pr
            if (state.labelsMore) {
                val labels = run(GhCommands.pullRequestLabels(repo, pr.number))
                if (!labels.ok) return OpenPullRequestRead.Failure(labels.errorText)
                val allLabels = parseLabelPages(labels.stdout, pr.number)
                    ?: return OpenPullRequestRead.Failure("Invalid or incomplete pull request labels", invalid = true)
                val details = pr.details ?: return OpenPullRequestRead.Failure("Invalid pull request details", invalid = true)
                complete = pr.copy(details = details.copy(labels = allLabels))
            }
            val assigned = if (state.assigneesMore) {
                val response = run(GhCommands.pullRequestAssignees(repo, pr.number))
                if (!response.ok) return OpenPullRequestRead.Failure(response.errorText)
                parseAssigneePages(response.stdout, pr.number)
                    ?: return OpenPullRequestRead.Failure("Invalid or incomplete pull request assignees", invalid = true)
            } else state.assignees
            allAssignees[pr.number] = assigned
            completed += complete
        }
        return OpenPullRequestRead.Success(completed, allAssignees, parsed.nextCursor)
    }

    private data class InitialState(val labelsMore: Boolean, val assignees: List<String>, val assigneesMore: Boolean)

    private fun initialStates(text: String): List<InitialState>? {
        val pages = pages(text) ?: return null
        val states = mutableListOf<InitialState>()
        for (page in pages) {
            val nodes = page.obj("data")?.obj("repository")?.obj("pullRequests")?.get("nodes") as? JsonArray ?: return null
            for (node in nodes) {
                val connection = (node as? JsonObject)?.obj("labels") ?: return null
                labels(connection) ?: return null
                val info = connection.obj("labelPageInfo") ?: return null
                val hasNext = info.bool("hasNextPage") ?: return null
                if (hasNext && info.string("endCursor").isNullOrBlank()) return null
                val assigned = node.obj("assignees") ?: return null
                val users = assignees(assigned) ?: return null
                val userInfo = assigned.obj("assigneePageInfo") ?: return null
                val usersMore = userInfo.bool("hasNextPage") ?: return null
                if (usersMore && userInfo.string("endCursor").isNullOrBlank()) return null
                states += InitialState(hasNext, users, usersMore)
            }
        }
        return states
    }

    private fun parseAssigneePages(text: String, number: Int): List<String>? {
        val pages = pages(text) ?: return null
        val result = mutableListOf<String>()
        val names = mutableSetOf<String>()
        for ((index, page) in pages.withIndex()) {
            val pr = page.obj("data")?.obj("repository")?.obj("pullRequest") ?: return null
            if ((pr["number"] as? JsonPrimitive)?.intOrNull != number) return null
            val connection = pr.obj("assignees") ?: return null
            val info = connection.obj("pageInfo") ?: return null
            val hasNext = info.bool("hasNextPage") ?: return null
            if (hasNext != (index < pages.lastIndex)) return null
            if (hasNext && info.string("endCursor").isNullOrBlank()) return null
            for (login in assignees(connection) ?: return null) {
                if (!names.add(login.lowercase())) return null
                result += login
            }
        }
        return result
    }

    private fun assignees(connection: JsonObject): List<String>? {
        val nodes = connection["nodes"] as? JsonArray ?: return null
        val names = mutableSetOf<String>()
        return nodes.map { element ->
            val login = (element as? JsonObject)?.string("login")?.takeIf(String::isNotBlank) ?: return null
            if (!names.add(login.lowercase())) return null
            login
        }
    }

    private fun parseLabelPages(text: String, number: Int): List<PrLabel>? {
        val pages = pages(text) ?: return null
        val result = mutableListOf<PrLabel>()
        val names = mutableSetOf<String>()
        for ((index, page) in pages.withIndex()) {
            val pr = page.obj("data")?.obj("repository")?.obj("pullRequest") ?: return null
            if ((pr["number"] as? JsonPrimitive)?.intOrNull != number) return null
            val connection = pr.obj("labels") ?: return null
            val info = connection.obj("pageInfo") ?: return null
            val hasNext = info.bool("hasNextPage") ?: return null
            if (hasNext != (index < pages.lastIndex)) return null
            if (hasNext && info.string("endCursor").isNullOrBlank()) return null
            for (label in labels(connection) ?: return null) {
                if (!names.add(label.name.lowercase())) return null
                result += label
            }
        }
        return result
    }

    private fun labels(connection: JsonObject): List<PrLabel>? {
        val nodes = connection["nodes"] as? JsonArray ?: return null
        val names = mutableSetOf<String>()
        return nodes.map { element ->
            val node = element as? JsonObject ?: return null
            val name = node.string("name")?.takeIf(String::isNotBlank) ?: return null
            val color = node.string("color") ?: return null
            if (!names.add(name.lowercase())) return null
            PrLabel(name, color)
        }
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

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
