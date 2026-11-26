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

class PullRequestsAuthorFilterFlowTest : FlowTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test author filter combines with search and survives refresh`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val response = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":{"login":"alice"}},
            {"number":13,"title":"Checkout","url":"https://github.com/acme/shop/pull/13","headRefName":"checkout","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":{"login":"bob"}},
            {"number":14,"title":"Editor fix","url":"https://github.com/acme/shop/pull/14","headRefName":"editor-fix","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":{"login":"bob"}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(args, stdout = response, times = 1)
        val held = gh.respond(args, stdout = response.replace("Editor fix", "Editor fix updated"), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val authors = onEdt { chips(panel)[1] }
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        try {
            onEdt { refresh.doClick() }
            waitUntil("authors loaded") { onEdt { authors.itemCount == 3 && list.model.size == 3 } }
            onEdt { authors.selectedItem = "bob"; assertEquals(listOf(13, 14), numbers()) }
            onEdt { search.text = "editor"; assertEquals(listOf(14), numbers()); refresh.doClick() }
            waitUntil("refresh begins") { gh.calls().count { it == args } >= 2 }
            assertEquals(listOf(14), onEdt { numbers() })
            held.release()
            waitUntil("filtered refresh completes") { onEdt { list.model.size == 1 && list.model.getElementAt(0).title == "Editor fix updated" } }
            onEdt { assertEquals("bob", authors.selectedItem); search.text = ""; assertEquals(listOf(13, 14), numbers()) }
            onEdt { authors.selectedIndex = 0; assertEquals(listOf(12, 13, 14), numbers()) }
        } finally { held.release() }
    }
}
