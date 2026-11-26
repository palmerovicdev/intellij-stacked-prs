package com.upstack.flow

import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCommands
import com.upstack.stack.GitHubRepo
import com.upstack.ui.PullRequestList
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class PullRequestsSortFlowTest : FlowTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test creation order combines with filters and survives refresh`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val response = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor old","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-01-01T10:00:00Z","author":{"login":"alice"},"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":13,"title":"Editor unknown","url":"https://github.com/acme/shop/pull/13","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":null,"author":{"login":"alice"},"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":14,"title":"Editor new","url":"https://github.com/acme/shop/pull/14","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-02-01T10:00:00Z","author":{"login":"alice"},"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":15,"title":"Other","url":"https://github.com/acme/shop/pull/15","headRefName":"other","baseRefName":"main","isDraft":false,"createdAt":"2026-02-01T12:00:00+02:00","author":{"login":"bob"},"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(args, stdout = response, times = 1)
        val held = gh.respond(args, stdout = response.replace("Editor new", "Editor new updated"), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val combos = onEdt { chips(panel) }
        assertEquals(6, combos.size)
        val authors = combos[1]
        val sort = combos[4]
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        try {
            onEdt { refresh.doClick() }
            waitUntil("PRs loaded") { onEdt { list.model.size == 4 } }
            onEdt {
                assertEquals(listOf(12, 13, 14, 15), numbers())
                list.selectedIndex = 0
                sort.selectedIndex = 1
                assertEquals(listOf(14, 15, 12, 13), numbers())
                assertEquals(12, list.selectedValue?.number)
                sort.selectedIndex = 2
                assertEquals(listOf(12, 14, 15, 13), numbers())
                authors.selectedItem = "alice"
                search.text = "editor"
                assertEquals(listOf(12, 14, 13), numbers())
                refresh.doClick()
            }
            waitUntil("refresh starts") { gh.calls().count { it == args } >= 2 }
            assertEquals(listOf(12, 14, 13), onEdt { numbers() })
            held.release()
            waitUntil("new data sorted") { onEdt { list.model.size == 3 && list.model.getElementAt(1).title == "Editor new updated" } }
            onEdt {
                assertEquals(2, sort.selectedIndex)
                assertEquals(listOf(12, 14, 13), numbers())
                authors.selectedIndex = 0
                search.text = ""
                sort.selectedIndex = 0
                assertEquals(listOf(12, 13, 14, 15), numbers())
            }
        } finally { held.release() }
    }
}
