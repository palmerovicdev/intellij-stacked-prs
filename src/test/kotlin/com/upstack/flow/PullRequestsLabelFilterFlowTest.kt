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

class PullRequestsLabelFilterFlowTest : FlowTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test label filter includes later pages and combines with author and search`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        val listing = pullRequestsPage(repo)
        val labels = GhCommands.pullRequestLabels(repo, 12)
        gh.respond(listing, stdout = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,"author":{"login":"alice"},
             "labels":{"nodes":[{"name":"first","color":"abcdef"}],"labelPageInfo":{"hasNextPage":true,"endCursor":"next"}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":13,"title":"Checkout","url":"https://github.com/acme/shop/pull/13","headRefName":"checkout","baseRefName":"main","isDraft":false,"author":{"login":"bob"},
             "labels":{"nodes":[{"name":"bug","color":"123456"},{"name":"All labels","color":"eeeeee"}],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]""")
        val held = gh.respond(labels, hold = true, stdout = """[
            {"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[{"name":"first","color":"abcdef"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}},
            {"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[{"name":"later","color":"123456"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
            ]""")
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val combos = onEdt { chips(panel).also { assertEquals(6, it.size) } }
        val authors = combos[1]
        val labelFilter = combos[2]
        try {
            onEdt { refresh.doClick() }
            waitUntil("additional labels requested") { labels in gh.calls() }
            assertEquals(0, onEdt { list.model.size })
            held.release()
            waitUntil("complete PR list loaded") { onEdt { list.model.size == 2 && labelFilter.itemCount == 5 } }
            onEdt {
                labelFilter.selectedIndex = (1 until labelFilter.itemCount).first { labelFilter.getItemAt(it).toString() == "later" }
                assertEquals(12, list.model.getElementAt(0).number)
                authors.selectedItem = "bob"
                assertEquals(0, list.model.size)
                authors.selectedIndex = 0
                search.text = "editor"
                assertEquals(12, list.model.getElementAt(0).number)
                labelFilter.selectedIndex = 0
                search.text = ""
                assertEquals(2, list.model.size)
                labelFilter.selectedIndex = (1 until labelFilter.itemCount).first { labelFilter.getItemAt(it).toString() == "All labels" }
                assertEquals(13, list.model.getElementAt(0).number)
                assertEquals(1, list.model.size)
            }
        } finally { held.release() }
    }

    fun `test failed extra label page keeps existing filtered PRs`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        val listing = pullRequestsPage(repo)
        val first = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
             "labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(listing, stdout = first, times = 1)
        gh.respond(listing, stdout = first.replace("\"hasNextPage\":false,\"endCursor\":null", "\"hasNextPage\":true,\"endCursor\":\"next\""))
        gh.respond(GhCommands.pullRequestLabels(repo, 12), exit = 1, stderr = "Labels denied")
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val labels = onEdt { chips(panel)[2] }
        onEdt { refresh.doClick() }
        waitUntil("initial labels loaded") { onEdt { list.model.size == 1 && labels.itemCount == 2 } }
        onEdt { labels.selectedIndex = 1; list.selectedIndex = 0; refresh.doClick() }
        waitUntil("labels error shown") {
            onEdt { descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text.contains("Labels denied") } }
        }
        onEdt {
            assertEquals(1, list.model.size)
            assertEquals(12, list.selectedValue?.number)
            assertEquals("bug", labels.selectedItem.toString())
        }
    }

    fun `test secondary cancellation label disappearance and remote reset`() {
        val origin = GitHubRepo("github.com", "acme", "shop")
        val upstream = GitHubRepo("github.com", "acme-upstream", "shop")
        val listing = pullRequestsPage(origin)
        fun response(label: String, more: Boolean = false) = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
             "labels":{"nodes":[{"name":"$label","color":"abcdef"}],"labelPageInfo":{"hasNextPage":$more,"endCursor":"next"}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(listing, stdout = response("bug"), times = 1)
        gh.respond(listing, stdout = response("old", true), times = 1)
        gh.respond(listing, stdout = response("feature"), times = 1)
        gh.respond(listing, stdout = response("BUG"))
        val extraArgs = GhCommands.pullRequestLabels(origin, 12)
        val stale = gh.respond(extraArgs, hold = true, stdout = """[{"data":{"repository":{"pullRequest":{"number":12,"labels":{"nodes":[{"name":"stale","color":"abcdef"}],"pageInfo":{"hasNextPage":false}}}}}}]""")
        gh.respond(pullRequestsPage(upstream), stdout = response("remote-label"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val labels = onEdt { chips(panel)[2] }
        fun choices() = (0 until labels.itemCount).map { labels.getItemAt(it).toString() }
        val settings = com.upstack.settings.UpstackProjectSettings.getInstance(project)
        val previous = settings.remote
        try {
            onEdt { refresh.doClick() }
            waitUntil("bug loaded") { onEdt { choices().contains("bug") } }
            onEdt { labels.selectedIndex = 1; list.selectedIndex = 0; refresh.doClick() }
            waitUntil("secondary labels in flight") { extraArgs in gh.calls() }
            onEdt { assertEquals(12, list.selectedValue?.number); refresh.doClick() }
            waitUntil("feature replaces labels") { onEdt { choices().contains("feature") } }
            onEdt {
                assertEquals("bug", labels.selectedItem.toString())
                assertEquals(0, list.model.size)
                assertNull(list.selectedValue)
            }
            stale.release()
            Thread.sleep(500)
            onEdt { assertFalse(choices().contains("stale")); assertEquals(0, list.model.size); refresh.doClick() }
            waitUntil("case change matches retained filter") { onEdt { list.model.size == 1 && choices().contains("BUG") } }
            onEdt { assertEquals("BUG", labels.selectedItem.toString()); settings.remote = "upstream"; refresh.doClick(); assertEquals(0, labels.selectedIndex) }
            waitUntil("remote labels loaded") { onEdt { choices().contains("remote-label") && list.model.size == 1 } }
            onEdt { assertFalse(choices().contains("bug")); assertFalse(choices().contains("BUG")); assertEquals(0, labels.selectedIndex) }
        } finally { stale.release(); onEdt { settings.remote = previous } }
    }
}
