package com.upstack.flow

import com.intellij.openapi.util.Disposer
import com.upstack.UpstackBundle.message
import com.upstack.stack.GitHubRepo
import com.upstack.stack.PullRequestState
import com.upstack.ui.PullRequestList
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class PullRequestsStatusFilterFlowTest : FlowTestCase() {
    private val repo = GitHubRepo("github.com", "acme", "shop")

    /** One GraphQL response, as `gh api graphql` prints it without --paginate. */
    private fun response(number: Int, state: String, next: String? = null) = """{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":$number,"title":"PR $number","url":"https://github.com/acme/shop/pull/$number","state":"$state",
         "headRefName":"feature","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
        ],"pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" }}}}}}}"""

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test open is the default and another status reloads with its own states`() {
        val open = pullRequestsPage(repo)
        val merged = pullRequestsPage(repo, setOf(PullRequestState.MERGED))
        val all = pullRequestsPage(repo, PullRequestState.entries.toSet())
        gh.respond(open, stdout = response(12, "OPEN"))
        val heldMerged = gh.respond(merged, stdout = response(13, "MERGED"), hold = true)
        gh.respond(all, stdout = response(14, "CLOSED"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val status = onEdt { chips(panel)[0] }
        try {
            assertEquals(listOf("Open", "Closed", "Merged", "All"), onEdt { (0 until status.itemCount).map { status.getItemAt(it).toString() } })
            assertEquals("Open", onEdt { status.selectedItem.toString() })
            onEdt { refresh.doClick() }
            waitUntil("open PR loaded") { onEdt { list.model.size == 1 } }
            assertEquals(12, onEdt { list.model.getElementAt(0).number })
            onEdt { status.selectedIndex = 2 }
            waitUntil("merged request starts") { gh.calls().count { it == merged } == 1 }
            assertEquals(0, onEdt { list.model.size })
            heldMerged.release()
            waitUntil("merged PR loaded") { onEdt { list.model.size == 1 } }
            onEdt {
                assertEquals(13, list.model.getElementAt(0).number)
                assertEquals(PullRequestState.MERGED, list.model.getElementAt(0).state)
                status.selectedIndex = 3
            }
            waitUntil("all PRs loaded") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 14 } }
            assertEquals(1, gh.calls().count { it == open })
        } finally { heldMerged.release() }
    }

    fun `test load more appends the next page with the cursor and disappears on the last page`() {
        val first = pullRequestsPage(repo, setOf(PullRequestState.CLOSED))
        val second = pullRequestsPage(repo, setOf(PullRequestState.CLOSED), after = "C1")
        gh.respond(pullRequestsPage(repo), stdout = response(1, "OPEN"))
        gh.respond(first, stdout = response(20, "CLOSED", next = "C1"))
        gh.respond(second, stdout = response(19, "CLOSED"))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val refresh = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        val more = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.text == message("prs.load.more") } }
        val status = onEdt { chips(panel)[0] }
        onEdt { refresh.doClick() }
        waitUntil("open PR loaded") { onEdt { list.model.size == 1 } }
        assertFalse(onEdt { more.isVisible })
        onEdt { status.selectedIndex = 1 }
        waitUntil("first closed page") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 20 } }
        waitUntil("load more offered") { onEdt { more.isVisible && more.isEnabled } }
        onEdt { more.doClick() }
        waitUntil("second page appended") { onEdt { list.model.size == 2 } }
        assertEquals(listOf(20, 19), onEdt { (0 until list.model.size).map { list.model.getElementAt(it).number } })
        assertFalse(onEdt { more.isVisible })
        assertEquals(1, gh.calls().count { it == second })
    }
}
