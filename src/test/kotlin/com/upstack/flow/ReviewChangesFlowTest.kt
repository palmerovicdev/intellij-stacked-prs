package com.upstack.flow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.UiInterceptors
import com.upstack.actions.PrTarget
import com.upstack.actions.PullRequestDataKeys
import com.upstack.gh.GhCommands
import com.upstack.stack.GitHubRepo
import com.upstack.stack.OpenPullRequest
import com.upstack.ui.ReviewChangesDialog
import java.util.concurrent.atomic.AtomicReference
import java.awt.event.ActionEvent

class ReviewChangesFlowTest : FlowTestCase() {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private val mergeBase = "c".repeat(40)
    private val github = GitHubRepo("github.com", "acme", "shop")
    private var dialog: ReviewChangesDialog? = null

    override fun tearDown() {
        try { dialog?.let { shown -> onEdt { if (!shown.isDisposed) shown.close(DialogWrapper.CANCEL_EXIT_CODE) } } }
        finally { super.tearDown() }
    }

    fun `test opens PR changes with all review comments without modifying the stack`() {
        val revisions = """{"baseRefOid":"$base","headRefOid":"$head","changedFiles":1}"""
        gh.respond(GhCommands.prRevisions(Samples.URL_B), stdout = revisions)
        gh.respond(GhCommands.prMergeBase(github, base, head), stdout = """{"merge_base_commit":{"sha":"$mergeBase"}}""")
        gh.respond(GhCommands.prFiles(github, 12), stdout = """[[{"filename":"a.kt","status":"modified"}]]""")
        gh.respond(GhCommands.reviewThreads(github, 12), stdout = """[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[
          {"id":"T1","path":"a.kt","line":2,"diffSide":"RIGHT","isOutdated":false,"isResolved":false,
           "comments":{"totalCount":1,"nodes":[{"id":"C1","body":"Please fix","url":"https://github.com/acme/shop/pull/12#discussion_r1","author":{"login":"alice"}}]}}
        ],"pageInfo":{"hasNextPage":false}}}}}}]""")
        gh.respond(GhCommands.prFileContent(github, "a.kt", mergeBase), stdout = blob("first\\nold\\n"))
        gh.respond(GhCommands.prFileContent(github, "a.kt", head), stdout = blob("first\\nnew\\n"))
        val shown = AtomicReference<ReviewChangesDialog>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<ReviewChangesDialog>(ReviewChangesDialog::class.java) {
            override fun doIntercept(component: ReviewChangesDialog) { shown.set(component) }
        })
        val pr = OpenPullRequest(12, "API", Samples.URL_B, "feat/b", "feat/a", false)
        val target = requireNotNull(PrTarget.fromPullRequest(pr) {})
        val action = ActionManager.getInstance().getAction("Upstack.ReviewChanges")
        assertNotNull(action)
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PullRequestDataKeys.TARGET, target).build()
        val event = TestActionEvent.createTestEvent(action, context)
        onEdt {
            action.update(event)
            assertTrue(event.presentation.description.contains("gh pr view"))
            action.actionPerformed(event)
        }
        waitUntil("review dialog opens") { shown.get() != null }
        val opened = shown.get()
        dialog = opened
        waitUntil("files and comments load") { onEdt { opened.loadedFile == "a.kt" } }
        assertTrue(onEdt { opened.commentsText.contains("Please fix") })
        assertTrue(onEdt { opened.commentsText.contains("alice") })
        assertTrue(gh.calls().contains(GhCommands.prFileContent(github, "a.kt", mergeBase)))
        assertTrue(gh.calls().contains(GhCommands.prFileContent(github, "a.kt", head)))
        assertFalse(gh.calls().any { it.take(2) == listOf("stack", "merge") })
    }

    fun `test failed read can be retried without losing the requested pull request`() {
        val revisionsArgs = GhCommands.prRevisions(Samples.URL_B)
        gh.respond(revisionsArgs, exit = 1, stderr = "Access denied", times = 1)
        gh.respond(revisionsArgs, stdout = """{"baseRefOid":"$base","headRefOid":"$head","changedFiles":1}""")
        gh.respond(GhCommands.prMergeBase(github, base, head), stdout = """{"merge_base_commit":{"sha":"$mergeBase"}}""")
        gh.respond(GhCommands.prFiles(github, 12), stdout = """[[{"filename":"a.kt","status":"modified"}]]""")
        gh.respond(GhCommands.reviewThreads(github, 12), stdout = """[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}}]""")
        gh.respond(GhCommands.prFileContent(github, "a.kt", mergeBase), stdout = blob("old\\n"))
        gh.respond(GhCommands.prFileContent(github, "a.kt", head), stdout = blob("new\\n"))
        val shown = AtomicReference<ReviewChangesDialog>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<ReviewChangesDialog>(ReviewChangesDialog::class.java) {
            override fun doIntercept(component: ReviewChangesDialog) { shown.set(component) }
        })
        val target = requireNotNull(PrTarget.fromPullRequest(OpenPullRequest(12, "API", Samples.URL_B, "feat/b", "feat/a", false)) {})
        val action = ActionManager.getInstance().getAction("Upstack.ReviewChanges")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PullRequestDataKeys.TARGET, target).build()
        onEdt { action.actionPerformed(TestActionEvent.createTestEvent(action, context)) }
        waitUntil("review dialog opens") { shown.get() != null }
        val opened = shown.get()
        dialog = opened
        waitUntil("error is shown") { onEdt { opened.statusText.contains("Access denied") } }
        onEdt { opened.refreshAction.actionPerformed(ActionEvent(opened, ActionEvent.ACTION_PERFORMED, "refresh")) }
        waitUntil("retry loads the file") { onEdt { opened.loadedFile == "a.kt" } }
        assertTrue(gh.calls().count { it == revisionsArgs } >= 3)
    }

    fun `test refresh cancels old file request without replacing the newer error`() {
        val revisionsArgs = GhCommands.prRevisions(Samples.URL_B)
        gh.respond(revisionsArgs, stdout = """{"baseRefOid":"$base","headRefOid":"$head","changedFiles":1}""", times = 2)
        gh.respond(revisionsArgs, exit = 1, stderr = "New refresh failure")
        gh.respond(GhCommands.prMergeBase(github, base, head), stdout = """{"merge_base_commit":{"sha":"$mergeBase"}}""")
        gh.respond(GhCommands.prFiles(github, 12), stdout = """[[{"filename":"a.kt","status":"modified"}]]""")
        gh.respond(GhCommands.reviewThreads(github, 12), stdout = """[{"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}}]""")
        val left = gh.respond(GhCommands.prFileContent(github, "a.kt", mergeBase), stdout = blob("old\\n"), hold = true)
        val right = gh.respond(GhCommands.prFileContent(github, "a.kt", head), stdout = blob("new\\n"), hold = true)
        val shown = AtomicReference<ReviewChangesDialog>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<ReviewChangesDialog>(ReviewChangesDialog::class.java) {
            override fun doIntercept(component: ReviewChangesDialog) { shown.set(component) }
        })
        val target = requireNotNull(PrTarget.fromPullRequest(OpenPullRequest(12, "API", Samples.URL_B, "feat/b", "feat/a", false)) {})
        val action = ActionManager.getInstance().getAction("Upstack.ReviewChanges")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PullRequestDataKeys.TARGET, target).build()
        onEdt { action.actionPerformed(TestActionEvent.createTestEvent(action, context)) }
        waitUntil("dialog opens") { shown.get() != null }
        val opened = shown.get()
        dialog = opened
        try {
            waitUntil("both file reads start") {
                val calls = gh.calls()
                calls.contains(GhCommands.prFileContent(github, "a.kt", mergeBase)) && calls.contains(GhCommands.prFileContent(github, "a.kt", head))
            }
            onEdt { opened.refreshAction.actionPerformed(ActionEvent(opened, ActionEvent.ACTION_PERFORMED, "refresh")) }
            waitUntil("new refresh error") { onEdt { opened.statusText.contains("New refresh failure") } }
            waitUntil("previous file read cancels") { onEdt { opened.fileLoadCompleted } }
            left.release()
            right.release()
            assertNull(onEdt { opened.loadedFile })
            assertTrue(onEdt { opened.statusText.contains("New refresh failure") })
        } finally { left.release(); right.release() }
    }

    private fun blob(text: String): String = """{"data":{"repository":{"object":{"__typename":"Blob","isBinary":false,"isTruncated":false,"text":"$text"}}}}"""
}
