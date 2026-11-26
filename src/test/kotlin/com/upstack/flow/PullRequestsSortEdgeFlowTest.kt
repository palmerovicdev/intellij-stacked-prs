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

class PullRequestsSortEdgeFlowTest : FlowTestCase() {
    private fun descendants(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::descendants) ?: emptyList())
    private fun response() = """[{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":12,"title":"Editor one","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-02-01T12:00:00+02:00","author":{"login":"alice"},"labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
        {"number":13,"title":"Editor bad","url":"https://github.com/acme/shop/pull/13","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"broken","author":{"login":"alice"},"labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
        {"number":14,"title":"Editor UTC","url":"https://github.com/acme/shop/pull/14","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-02-01T10:00:00Z","author":{"login":"alice"},"labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
        {"number":15,"title":"Editor older","url":"https://github.com/acme/shop/pull/15","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-02-01T11:00:00+02:00","author":{"login":"alice"},"labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}},
        {"number":16,"title":"Other missing","url":"https://github.com/acme/shop/pull/16","headRefName":"other","baseRefName":"main","isDraft":false,"author":{"login":"bob"},"labels":{"nodes":[{"name":"feature","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
    ],"pageInfo":{"hasNextPage":false}}}}}]"""

    fun `test sort malformed dates stable instants conjunction and action target`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        gh.respond(args, stdout = response())
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val combos = onEdt { chips(panel) }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        onEdt { refresh.doClick() }
        waitUntil("loaded") { onEdt { list.model.size == 5 } }
        onEdt {
            assertEquals(listOf(12,13,14,15,16), numbers())
            list.selectedIndex = 2
            combos[4].selectedIndex = 1
            assertEquals(listOf(12,14,15,13,16), numbers())
            assertEquals(14, PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
            combos[4].selectedIndex = 2
            assertEquals(listOf(15,12,14,13,16), numbers())
            assertEquals(14, PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
            combos[1].selectedItem = "alice"
            combos[2].selectedIndex = 1
            search.text = "UTC"
            assertEquals(listOf(14), numbers())
            assertEquals(14, PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
            search.text = "missing"
            assertEquals(0,list.model.size)
            assertNull(PullRequestDataKeys.TARGET.getData(panel.selectionContext()))
            combos[1].selectedIndex = 0
            combos[2].selectedIndex = 0
            search.text = ""
            combos[4].selectedIndex = 0
            assertEquals(listOf(12,13,14,15,16), numbers())
        }
    }

    fun `test sort changes during failed refresh and remote replacement`() {
        val origin = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        val upstream = pullRequestsPage(GitHubRepo("github.com", "acme-upstream", "shop"))
        gh.respond(origin, stdout = response(), times = 1)
        val failed = gh.respond(origin, exit = 1, stderr = "sort-probe-denied", hold = true, times = 1)
        val stale = gh.respond(origin, stdout = response(), hold = true)
        val fresh = gh.respond(upstream, stdout = response().replace("Editor one", "Replacement"), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val sort = onEdt { chips(panel)[4] }
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        val settings = UpstackProjectSettings.getInstance(project)
        val previous = settings.remote
        try {
            onEdt { settings.remote = "origin"; refresh.doClick() }
            waitUntil("loaded") { onEdt { list.model.size == 5 } }
            onEdt { refresh.doClick() }
            waitUntil("failed load pending") { gh.calls().count { it == origin } >= 2 }
            onEdt { sort.selectedIndex = 1; assertEquals(listOf(12,14,15,13,16), numbers()) }
            failed.release()
            waitUntil("failure visible") { onEdt { descendants(panel).filterIsInstance<JBLabel>().any { it.text.contains("sort-probe-denied") } } }
            onEdt { assertEquals(listOf(12,14,15,13,16), numbers()); refresh.doClick() }
            waitUntil("stale pending") { gh.calls().count { it == origin } >= 3 }
            onEdt { settings.remote = "upstream"; refresh.doClick(); assertEquals(0,list.model.size); sort.selectedIndex = 2 }
            waitUntil("replacement pending") { upstream in gh.calls() }
            fresh.release()
            waitUntil("replacement loaded") { onEdt { list.model.size == 5 } }
            stale.release()
            Thread.sleep(500)
            onEdt {
                assertEquals(listOf(15,12,14,13,16), numbers())
                assertEquals("Replacement", list.model.getElementAt(1).title)
            }
        } finally { failed.release(); stale.release(); fresh.release(); onEdt { settings.remote = previous } }
    }
}
