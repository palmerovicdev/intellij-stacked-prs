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

class PullRequestsSearchFlowTest : FlowTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test search filters loaded pull requests and clear restores them`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val response = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Improve editor","url":"https://github.com/acme/shop/pull/12","headRefName":"feature/editor","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":{"login":"alice"}},
            {"number":13,"title":"Fix checkout","url":"https://github.com/acme/shop/pull/13","headRefName":"fix/payment","baseRefName":"develop","isDraft":true,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":{"login":"bob"}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(args, stdout = response, times = 1)
        gh.respond(args, stdout = response.replace("Fix checkout", "Fix checkout updated"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        onEdt { refresh.doClick() }
        waitUntil("initial PRs loaded") { onEdt { list.model.size == 2 } }
        fun checkSearch(query: String, expected: List<Int>) = onEdt {
            search.text = query
            assertEquals(expected, (0 until list.model.size).map { list.model.getElementAt(it).number })
        }
        checkSearch("EDITOR", listOf(12))
        checkSearch("#13", listOf(13))
        checkSearch("alice", listOf(12))
        checkSearch("payment", listOf(13))
        checkSearch("develop", listOf(13))
        checkSearch("missing", emptyList())
        checkSearch("", listOf(12, 13))
        onEdt { search.text = "checkout"; refresh.doClick() }
        waitUntil("refreshed filtered PR") { onEdt { list.model.size == 1 && list.model.getElementAt(0).title == "Fix checkout updated" } }
        checkSearch("", listOf(12, 13))
    }
    fun `test active search survives failed refresh and uses latest query on success`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val response = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Alpha","url":"https://github.com/acme/shop/pull/12","headRefName":"alpha","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":13,"title":"Beta","url":"https://github.com/acme/shop/pull/13","headRefName":"beta","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(args, stdout = response, times = 1)
        val failed = gh.respond(args, exit = 1, stderr = "denied", hold = true, times = 1)
        val success = gh.respond(args, stdout = response.replace("Alpha", "Alpha updated"), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { refresh.doClick() }
            waitUntil("loaded") { onEdt { list.model.size == 2 } }
            onEdt { list.selectedIndex = 1; search.text = "Beta" }
            assertEquals(13, onEdt { list.selectedValue?.number })
            assertEquals(13, onEdt { com.upstack.actions.PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number })
            onEdt { refresh.doClick() }
            waitUntil("held failure") { gh.calls().count { it == args } >= 2 }
            onEdt { search.text = "Alpha" }
            assertEquals(12, onEdt { list.model.getElementAt(0).number })
            failed.release()
            waitUntil("failed") { onEdt { descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text.contains("denied") } } }
            onEdt { search.text = "" }
            assertEquals(2, onEdt { list.model.size })
            onEdt { search.text = "Beta"; refresh.doClick() }
            waitUntil("held success") { gh.calls().count { it == args } >= 3 }
            onEdt { search.text = "Alpha" }
            success.release()
            waitUntil("latest query applied") { onEdt { list.model.size == 1 && list.model.getElementAt(0).title == "Alpha updated" } }
        } finally { failed.release(); success.release() }
    }

}
