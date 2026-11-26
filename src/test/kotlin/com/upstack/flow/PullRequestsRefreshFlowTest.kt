package com.upstack.flow

import com.intellij.openapi.util.Disposer
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCommands
import com.upstack.settings.UpstackProjectSettings
import com.upstack.stack.GitHubRepo
import com.upstack.ui.PullRequestList
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class PullRequestsRefreshFlowTest : FlowTestCase() {
    private val origin = GitHubRepo("github.com", "acme", "shop")
    private val upstream = GitHubRepo("github.com", "acme-upstream", "shop")

    private fun response(number: Int) = """[{"data":{"repository":{"pullRequests":{"nodes":[
        {"number":$number,"title":"PR $number","url":"https://github.com/acme/shop/pull/$number",
         "headRefName":"feature","baseRefName":"main","isDraft":false,"labels":{"nodes":[],"labelPageInfo":{"hasNextPage":false,"endCursor":null}},"assignees":{"nodes":[],"assigneePageInfo":{"hasNextPage":false}}}
        ],"pageInfo":{"hasNextPage":false}}}}}]"""

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test refresh keeps rows and selection while loading and on failure`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12), times = 1)
        val held = gh.respond(args, exit = 1, stderr = "Access denied", hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { button.doClick() }
            waitUntil("initial PR loaded") { onEdt { list.model.size == 1 } }
            onEdt { list.selectedIndex = 0; button.doClick() }
            waitUntil("refresh starts") { gh.calls().count { it == args } >= 2 }
            assertEquals(12, onEdt { list.model.getElementAt(0).number })
            assertEquals(12, onEdt { list.selectedValue?.number })
            held.release()
            waitUntil("refresh error shown") { onEdt { descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text.contains("Access denied") } } }
            assertEquals(12, onEdt { list.model.getElementAt(0).number })
        } finally { held.release() }
    }

    fun `test changing remote clears previous rows while new remote loads`() {
        val oldArgs = pullRequestsPage(origin)
        val newArgs = pullRequestsPage(upstream)
        gh.respond(oldArgs, stdout = response(12))
        val held = gh.respond(newArgs, stdout = response(13), hold = true)
        val settings = UpstackProjectSettings.getInstance(project)
        val previous = settings.remote
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { settings.remote = "origin"; button.doClick() }
            waitUntil("initial PR loaded") { onEdt { list.model.size == 1 } }
            onEdt { settings.remote = "upstream"; button.doClick() }
            waitUntil("new remote starts") { newArgs in gh.calls() }
            assertEquals(0, onEdt { list.model.size })
            held.release()
            waitUntil("new remote PR loaded") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 13 } }
        } finally { held.release(); onEdt { settings.remote = previous } }
    }

    fun `test overlapping refresh ignores a cancelled older response`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12), times = 1)
        val older = gh.respond(args, stdout = response(13), hold = true, times = 1)
        gh.respond(args, stdout = response(14))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { button.doClick() }
            waitUntil("initial row") { onEdt { list.model.size == 1 } }
            onEdt { button.doClick() }
            waitUntil("older call started") { gh.calls().count { it == args } >= 2 }
            onEdt { button.doClick() }
            waitUntil("newer row") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 14 } }
            older.release()
            Thread.sleep(500)
            assertEquals(14, onEdt { list.model.getElementAt(0).number })
        } finally { older.release() }
    }

    fun `test disposal cancels outstanding load without replacing retained row`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12), times = 1)
        val held = gh.respond(args, stdout = response(13), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { button.doClick() }
            waitUntil("initial row") { onEdt { list.model.size == 1 } }
            onEdt { button.doClick() }
            waitUntil("refresh started") { gh.calls().count { it == args } >= 2 }
            onEdt { Disposer.dispose(panel) }
            held.release()
            Thread.sleep(500)
            assertEquals(12, onEdt { list.model.getElementAt(0).number })
        } finally { held.release() }
    }

    fun `test invalid and ssh alias remotes clear retained rows`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        onEdt { button.doClick() }
        waitUntil("initial row") { onEdt { list.model.size == 1 } }
        git("remote", "remove", "upstream")
        git("remote", "set-url", "origin", "git@github-personal:acme/shop.git")
        repository.update()
        onEdt { button.doClick() }
        assertEquals(0, onEdt { list.model.size })
        git("remote", "set-url", "origin", "git@github.com:acme/shop.git")
        repository.update()
        onEdt { button.doClick() }
        waitUntil("reloaded row") { onEdt { list.model.size == 1 } }
        git("remote", "remove", "origin")
        repository.update()
        onEdt { button.doClick() }
        assertEquals(0, onEdt { list.model.size })
    }

    fun `test switching local root clears rows even for same GitHub remote`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12), times = 1)
        val held = gh.respond(args, stdout = response(13), hold = true)
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        try {
            onEdt { button.doClick() }
            waitUntil("initial row") { onEdt { list.model.size == 1 } }
            val secondDir = repoDir.resolve("second")
            git("clone", repoDir.toString(), secondDir.toString())
            git("-C", secondDir.toString(), "remote", "set-url", "origin", "git@github.com:acme/shop.git")
            val secondRoot = requireNotNull(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondDir))
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, secondRoot)
            com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(
                com.intellij.openapi.vcs.VcsDirectoryMapping(repository.root.path, "Git"),
                com.intellij.openapi.vcs.VcsDirectoryMapping(secondRoot.path, "Git"),
            )
            val manager = git4idea.repo.GitRepositoryManager.getInstance(project)
            waitUntil("second root registered") { manager.getRepositoryForRoot(secondRoot) != null }
            val second = requireNotNull(manager.getRepositoryForRoot(secondRoot))
            onEdt { service.selectRepository(second); button.doClick() }
            waitUntil("second call started") { gh.calls().count { it == args } >= 2 }
            assertEquals(0, onEdt { list.model.size })
            held.release()
            waitUntil("second root row loaded") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 13 } }
        } finally { held.release() }
    }

    fun `test successful refresh keeps selection only while PR remains open`() {
        val args = pullRequestsPage(origin)
        gh.respond(args, stdout = response(12), times = 2)
        gh.respond(args, stdout = response(13))
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        val list = onEdt { descendants(panel).filterIsInstance<PullRequestList>().single() }
        val button = onEdt { descendants(panel).filterIsInstance<JButton>().single { it.toolTipText == message("prs.refresh") } }
        onEdt { button.doClick() }
        waitUntil("initial row") { onEdt { list.model.size == 1 } }
        onEdt { list.selectedIndex = 0; button.doClick() }
        waitUntil("second response complete") { onEdt { descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text == message("prs.count", 1) } } }
        assertEquals(12, onEdt { list.selectedValue?.number })
        onEdt { button.doClick() }
        waitUntil("replacement row loaded") { onEdt { list.model.size == 1 && list.model.getElementAt(0).number == 13 } }
        assertNull(onEdt { list.selectedValue })
    }
}
