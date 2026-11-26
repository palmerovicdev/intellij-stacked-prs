package com.upstack.flow

import com.upstack.gh.GhCommands
import com.upstack.settings.UpstackSettings
import com.upstack.stack.GitHubRepo
import com.upstack.stack.PullRequestState

/** The request the Pull Requests tab sends for a page of [states]: the first one unless [after] is a previous cursor. */
internal fun pullRequestsPage(
    repo: GitHubRepo, states: Set<PullRequestState> = setOf(PullRequestState.OPEN), after: String? = null,
    size: Int = UpstackSettings.DEFAULT_PAGE_SIZE,
): List<String> = GhCommands.pullRequestsPage(repo, states, after, size)

/** The filter chips of the Pull Requests tab, in the order they are on screen: status, author, label, assignee, sort, then the repository. */
@Suppress("UNCHECKED_CAST")
internal fun chips(root: java.awt.Component): List<com.upstack.ui.FilterChip<Any>> {
    fun walk(component: java.awt.Component): List<java.awt.Component> =
        listOf(component) + ((component as? java.awt.Container)?.components?.flatMap(::walk) ?: emptyList())
    return walk(root).filterIsInstance<com.upstack.ui.FilterChip<*>>() as List<com.upstack.ui.FilterChip<Any>>
}
