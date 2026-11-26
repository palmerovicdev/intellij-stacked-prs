package com.upstack.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.upstack.stack.*
import javax.swing.JComponent
import javax.swing.JPanel
import java.util.concurrent.atomic.AtomicInteger

class ReviewInlaysTest : BasePlatformTestCase() {
    fun `test registered extension annotates a real diff panel`() {
        val owner = Disposer.newDisposable()
        try {
            val thread = ReviewThread("T1", "f.kt", ReviewSide.RIGHT, 2, null, 2, null, null, false, false,
                listOf(ReviewComment("C1", "Fix", "https://example.com", "alice")), 1)
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest("f.kt", factory.create(project, "one\nold\n"), factory.create(project, "one\nnew\n"), "Base", "Head")
            val callback = AtomicInteger(-2)
            request.putUserData(ReviewDiffExtension.REVIEW, ReviewDecoration(ReviewFile("f.kt", "f.kt", "modified"), listOf(thread)) {
                callback.set(it ?: -1)
            })
            val panel = DiffManager.getInstance().createRequestPanel(project, owner, null)
            panel.setRequest(request)
            repeat(10) { UIUtil.dispatchAllInvocationEvents() }
            assertEquals("The review extension did not annotate the two-sided diff", 0, callback.get())
        } finally { Disposer.dispose(owner) }
    }

    fun `test unsupported viewer reports fallback and unmarked diffs are untouched`() {
        val context = object : DiffContext() {
            override fun getProject(): Project = project
            override fun isWindowFocused() = false
            override fun isFocusedInWindow() = false
            override fun requestFocusInWindow() = Unit
        }
        val viewer = object : FrameDiffTool.DiffViewer {
            private val panel = JPanel()
            override fun getComponent(): JComponent = panel
            override fun getPreferredFocusedComponent(): JComponent = panel
            override fun init() = FrameDiffTool.ToolbarComponents()
            override fun dispose() = Unit
        }
        var called = false
        val request = MessageDiffRequest("No diff")
        val extension = ReviewDiffExtension()
        extension.onViewerCreated(viewer, context, request)
        assertFalse(called)
        request.putUserData(ReviewDiffExtension.REVIEW, ReviewDecoration(ReviewFile("f.kt", "f.kt", "modified"), emptyList()) {
            called = true
            assertNull(it)
        })
        extension.onViewerCreated(viewer, context, request)
        assertTrue(called)
    }

    fun `test review comments attach after the exact line and are disposed with the viewer`() {
        val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument("first\nsecond\nthird\n"), project)
        val owner = Disposer.newDisposable()
        try {
            val thread = ReviewThread("T1", "f.kt", ReviewSide.RIGHT, 2, null, 2, null, null, true, false,
                listOf(ReviewComment("C1", "Please fix\nthis part", "https://example.com", "alice"),
                    ReviewComment("C2", "Fixed", "https://example.com", "bob")), 2)
            ReviewInlays.install(editor, listOf(PositionedReviewThread(thread, 1)), owner)
            val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            assertEquals(1, inlays.size)
            assertEquals(editor.document.getLineEndOffset(1), inlays.single().offset)
            val renderer = inlays.single().renderer as ReviewInlayRenderer
            val lines = renderer.plainText()
            assertTrue(lines.contains("alice"))
            assertTrue(lines.contains("Please fix\nthis part"))
            assertTrue(lines.contains("bob"))
            assertTrue(lines.contains("Fixed"))
            Disposer.dispose(owner)
            assertFalse(inlays.single().isValid)
        } finally {
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}
