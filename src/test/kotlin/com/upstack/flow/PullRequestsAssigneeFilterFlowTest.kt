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

class PullRequestsAssigneeFilterFlowTest : FlowTestCase() {
    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test assignee filter includes later pages and combines with other controls`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        gh.respond(pullRequestsPage(repo), stdout = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,"author":{"login":"alice"},
             "labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[{"login":"bob"}],"assigneePageInfo":{"hasNextPage":true,"endCursor":"next"}}},
            {"number":13,"title":"Checkout","url":"https://github.com/acme/shop/pull/13","headRefName":"checkout","baseRefName":"main","isDraft":false,"author":{"login":"bob"},
             "labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[{"login":"alice"}],"assigneePageInfo":{"hasNextPage":false}}},
            {"number":14,"title":"Unassigned","url":"https://github.com/acme/shop/pull/14","headRefName":"other","baseRefName":"main","isDraft":false,
             "labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]""")
        val more = GhCommands.pullRequestAssignees(repo, 12)
        val held = gh.respond(more, hold = true, stdout = """[
            {"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[{"login":"bob"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}},
            {"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[{"login":"carol"}],"pageInfo":{"hasNextPage":false}}}}}}
            ]""")
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val combos = onEdt { chips(panel).also { assertEquals(6, it.size) } }
        val authors = combos[1]
        val labels = combos[2]
        val assigned = combos[3]
        try {
            onEdt { refresh.doClick() }
            waitUntil("extra assignees requested") { more in gh.calls() }
            assertEquals(0, onEdt { list.model.size })
            held.release()
            waitUntil("all assignees loaded") { onEdt { list.model.size == 3 && assigned.itemCount == 5 } }
            onEdt {
                assigned.selectedIndex = (0 until assigned.itemCount).first { assigned.getItemAt(it).toString() == "carol" }
                assertEquals(12, list.model.getElementAt(0).number)
                authors.selectedItem = "bob"
                assertEquals(0, list.model.size)
                authors.selectedIndex = 0
                labels.selectedIndex = 1
                search.text = "editor"
                assertEquals(12, list.model.getElementAt(0).number)
                labels.selectedIndex = 0
                search.text = ""
                assigned.selectedIndex = 1
                assertEquals(14, list.model.getElementAt(0).number)
                assigned.selectedIndex = 0
                assertEquals(3, list.model.size)
            }
        } finally { held.release() }
    }

    fun `test failed extra assignee page retains previous filtered data`() {
        val repo = GitHubRepo("github.com", "acme", "shop")
        val listing = pullRequestsPage(repo)
        val first = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
             "labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[{"login":"alice"}],"assigneePageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(listing, stdout = first, times = 1)
        gh.respond(listing, stdout = first.replace("\"assigneePageInfo\":{\"hasNextPage\":false}", "\"assigneePageInfo\":{\"hasNextPage\":true,\"endCursor\":\"next\"}"))
        gh.respond(GhCommands.pullRequestAssignees(repo, 12), exit = 1, stderr = "Assignees denied")
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val assignees = onEdt { chips(panel)[3] }
        onEdt { refresh.doClick() }
        waitUntil("initial assignee loaded") { onEdt { list.model.size == 1 && assignees.itemCount == 3 } }
        onEdt { assignees.selectedIndex = 2; list.selectedIndex = 0; refresh.doClick() }
        waitUntil("assignee error shown") {
            onEdt { descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text.contains("Assignees denied") } }
        }
        onEdt {
            assertEquals(1, list.model.size)
            assertEquals(12, list.selectedValue?.number)
            assertEquals("alice", assignees.selectedItem.toString())
        }
    }
    fun `test secondary cancellation assignee disappearance and remote reset`() {
        val origin = GitHubRepo("github.com", "acme", "shop")
        val upstream = GitHubRepo("github.com", "acme-upstream", "shop")
        val listing = pullRequestsPage(origin)
        fun response(assignee: String, more: Boolean = false) = """[{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Editor","url":"https://github.com/acme/shop/pull/12","headRefName":"editor","baseRefName":"main","isDraft":false,
             "assignees":{"nodes":[{"login":"$assignee"}],"assigneePageInfo":{"hasNextPage":$more,"endCursor":"next"}},"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false}}}
            ],"pageInfo":{"hasNextPage":false}}}}}]"""
        gh.respond(listing, stdout = response("bug"), times = 1)
        gh.respond(listing, stdout = response("old", true), times = 1)
        gh.respond(listing, stdout = response("feature"), times = 1)
        gh.respond(listing, stdout = response("BUG"))
        val extraArgs = GhCommands.pullRequestAssignees(origin, 12)
        val stale = gh.respond(extraArgs, hold = true, stdout = """[{"data":{"repository":{"pullRequest":{"number":12,"assignees":{"nodes":[{"login":"stale"}],"pageInfo":{"hasNextPage":false}}}}}}]""")
        gh.respond(pullRequestsPage(upstream), stdout = response("remote-assignee"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val assignees = onEdt { chips(panel)[3] }
        fun choices() = (0 until assignees.itemCount).map { assignees.getItemAt(it).toString() }
        val settings = com.upstack.settings.UpstackProjectSettings.getInstance(project)
        val previous = settings.remote
        try {
            onEdt { refresh.doClick() }
            waitUntil("bug loaded") { onEdt { choices().contains("bug") } }
            onEdt { assignees.selectedIndex = 2; list.selectedIndex = 0; refresh.doClick() }
            waitUntil("secondary assignees in flight") { extraArgs in gh.calls() }
            onEdt { assertEquals(12, list.selectedValue?.number); refresh.doClick() }
            waitUntil("feature replaces assignees") { onEdt { choices().contains("feature") } }
            onEdt {
                assertEquals("bug", assignees.selectedItem.toString())
                assertEquals(0, list.model.size)
                assertNull(list.selectedValue)
            }
            stale.release()
            Thread.sleep(500)
            onEdt { assertFalse(choices().contains("stale")); assertEquals(0, list.model.size); refresh.doClick() }
            waitUntil("case change matches retained filter") { onEdt { list.model.size == 1 && choices().contains("BUG") } }
            onEdt { assertEquals("BUG", assignees.selectedItem.toString()); settings.remote = "upstream"; refresh.doClick(); assertEquals(0, assignees.selectedIndex) }
            waitUntil("remote assignees loaded") { onEdt { choices().contains("remote-assignee") && list.model.size == 1 } }
            onEdt { assertFalse(choices().contains("bug")); assertFalse(choices().contains("BUG")); assertEquals(0, assignees.selectedIndex) }
        } finally { stale.release(); onEdt { settings.remote = previous } }
    }

    fun `test assignment conjunction sort and action target preserve identity`() {
        val args = pullRequestsPage(GitHubRepo("github.com", "acme", "shop"))
        fun pr(n: Int, user: String, day: Int) = """{"number":$n,"title":"Editor $n","url":"https://github.com/acme/shop/pull/$n","headRefName":"editor","baseRefName":"main","isDraft":false,"createdAt":"2026-02-0${day}T00:00:00Z","author":{"login":"author"},"labels":{"nodes":[{"name":"bug","color":"abcdef"}],"labelPageInfo":{"hasNextPage":false}},"assignees":{"nodes":[{"login":"$user"}],"assigneePageInfo":{"hasNextPage":false}}}"""
        gh.respond(args, stdout = """[{"data":{"repository":{"pullRequests":{"nodes":[${pr(12,"alice",1)},${pr(13,"alice",2)},${pr(14,"bob",3)}],"pageInfo":{"hasNextPage":false}}}}}]""")
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val combos = onEdt { chips(panel) }
        val search = onEdt { descendants(panel).filterIsInstance<SearchTextField>().single() }
        fun numbers() = (0 until list.model.size).map { list.model.getElementAt(it).number }
        onEdt { refresh.doClick() }
        waitUntil("assignment conjunction loaded") { onEdt { list.model.size == 3 } }
        onEdt {
            list.selectedIndex = 0
            combos[3].selectedIndex = 2
            combos[1].selectedIndex = 1
            combos[2].selectedIndex = 1
            search.text = "Editor"
            combos[4].selectedIndex = 1
            assertEquals(listOf(13,12), numbers())
            assertEquals(12, com.upstack.actions.PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
            combos[4].selectedIndex = 2
            assertEquals(listOf(12,13), numbers())
            assertEquals(12, com.upstack.actions.PullRequestDataKeys.TARGET.getData(panel.selectionContext())?.number)
            search.text = "no match"
            assertEquals(0, list.model.size)
            assertNull(com.upstack.actions.PullRequestDataKeys.TARGET.getData(panel.selectionContext()))
        }
    }
}
