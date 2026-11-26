package com.upstack.flow

import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.upstack.UpstackBundle.message
import com.upstack.actions.PullRequestDataKeys
import com.upstack.gh.GhCommands
import com.upstack.stack.GitHubRepo
import com.upstack.settings.UpstackProjectSettings
import com.upstack.ui.PullRequestList
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class PullRequestsAuthorFilterEdgeFlowTest : FlowTestCase() {
    private fun descendants(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::descendants) ?: emptyList())
    private fun response(vararg authors: String?) = """[{"data":{"repository":{"pullRequests":{"nodes":[${authors.mapIndexed { i, author -> """{"number":${12+i},"title":"Editor ${12+i}","url":"https://github.com/acme/shop/pull/${12+i}","headRefName":"editor","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}},"author":${author?.let { """{"login":"$it"}""" } ?: "null"}}""" }.joinToString(",")}
        ],"pageInfo":{"hasNextPage":false}}}}}]"""

    fun `test author choices unknown authors disappearance failure and selection targets`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        gh.respond(args, stdout = response("bob", "Alice", null, "alice"), times = 1)
        val failed = gh.respond(args, exit = 1, stderr = "probe-denied", hold = true, times = 1)
        val missing = gh.respond(args, stdout = response("carol"), hold = true, times = 1)
        gh.respond(args, stdout = response("bob", "ALICE"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val authors = onEdt { chips(panel)[1] }
        fun options() = (0 until authors.itemCount).map { authors.getItemAt(it) }
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        try {
            onEdt { refresh.doClick() }
            waitUntil("loaded") { onEdt { list.model.size == 4 } }
            onEdt {
                assertEquals(listOf(message("prs.author.all"), "Alice", "bob"), options())
                list.selectedIndex = 3
                authors.selectedItem = "Alice"
                assertEquals(listOf(13, 15), numbers())
                assertEquals(15, PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
                authors.selectedItem = "bob"
                assertNull(list.selectedValue)
                assertNull(PullRequestDataKeys.TARGET.getData(panel.selectionContext()))
                list.selectedIndex = 0
                refresh.doClick()
            }
            waitUntil("failure pending") { gh.calls().count { it == args } >= 2 }
            onEdt { authors.selectedItem = "Alice"; search.text = "15" }
            failed.release()
            waitUntil("failure shown") { onEdt { descendants(panel).filterIsInstance<JBLabel>().any { it.text.contains("probe-denied") } } }
            onEdt { assertEquals(listOf(15), numbers()); assertEquals("Alice", authors.selectedItem); search.text = ""; refresh.doClick() }
            waitUntil("missing pending") { gh.calls().count { it == args } >= 3 }
            missing.release()
            waitUntil("absent author's refresh done") { onEdt { options().contains("carol") } }
            onEdt {
                assertEquals("Alice", authors.selectedItem)
                assertEquals(emptyList<Int>(), numbers())
                assertNull(PullRequestDataKeys.TARGET.getData(panel.selectionContext()))
                authors.selectedIndex = 0
                assertEquals(listOf(12), numbers())
                refresh.doClick()
            }
            waitUntil("replacement loaded") { onEdt { list.model.size == 2 } }
            onEdt { assertEquals(listOf(message("prs.author.all"), "ALICE", "bob"), options()) }
        } finally { failed.release(); missing.release() }
    }

    fun `test remote change resets author and rejects cancelled old author results`() {
        val origin = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val upstream = pullRequestsPage(GitHubRepo("github.com", "acme-upstream", "shop"))
        gh.respond(origin, stdout = response("alice"), times = 1)
        val stale = gh.respond(origin, stdout = response("stale"), hold = true)
        val fresh = gh.respond(upstream, stdout = response("bob"), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val authors = onEdt { chips(panel)[1] }
        val settings = UpstackProjectSettings.getInstance(project)
        val previous = settings.remote
        try {
            onEdt { settings.remote = "origin"; refresh.doClick() }
            waitUntil("loaded") { onEdt { list.model.size == 1 } }
            onEdt { authors.selectedItem = "alice"; refresh.doClick() }
            waitUntil("old pending") { gh.calls().count { it == origin } >= 2 }
            onEdt { settings.remote = "upstream"; refresh.doClick(); assertEquals(0, list.model.size); assertEquals(1, authors.itemCount); assertEquals(0, authors.selectedIndex) }
            waitUntil("new pending") { upstream in gh.calls() }
            fresh.release()
            waitUntil("new loaded") { onEdt { authors.itemCount == 2 && list.model.size == 1 } }
            stale.release()
            Thread.sleep(500)
            onEdt { assertEquals("bob", authors.getItemAt(1)); assertEquals("bob", list.model.getElementAt(0).author) }
        } finally { stale.release(); fresh.release(); onEdt { settings.remote = previous } }
    }
}
