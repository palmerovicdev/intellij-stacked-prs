package com.upstack.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.stack.PositionedReviewThread
import com.upstack.stack.ReviewThread
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/** Review annotations live only as long as their own diff viewer. */
internal object ReviewInlays {
    fun install(editor: Editor, threads: List<PositionedReviewThread>, owner: Disposable) {
        val inlays = threads.mapNotNull { positioned ->
            if (positioned.lineIndex !in 0 until editor.document.lineCount) return@mapNotNull null
            val offset = editor.document.getLineEndOffset(positioned.lineIndex)
            editor.inlayModel.addBlockElement(offset, true, false, 0, ReviewInlayRenderer(positioned.thread))?.also {
                Disposer.register(owner, it)
            }
        }
        if (inlays.isEmpty()) return
        val component = editor.contentComponent
        val listener = object : ComponentAdapter() {
            private var width = -1
            override fun componentResized(event: ComponentEvent) {
                if (component.width == width) return
                width = component.width
                inlays.filter { it.isValid }.forEach { it.update() }
            }
        }
        component.addComponentListener(listener)
        Disposer.register(owner, Disposable { component.removeComponentListener(listener) })
    }
}

/** Plain text is painted, never interpreted as HTML from an untrusted review comment. */
internal class ReviewInlayRenderer(private val thread: ReviewThread) : EditorCustomElementRenderer {
    fun plainText(): String = buildString {
        append(message(if (thread.resolved) "review.thread.resolved" else "review.thread.unresolved"))
        thread.comments.forEachIndexed { index, comment ->
            if (index > 0) append('\n')
            append('\n').append(comment.author ?: message("review.thread.deleted.author")).append(':')
            append('\n').append(comment.body)
        }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val visible = inlay.editor.scrollingModel.visibleArea.width.takeIf { it > 0 } ?: JBUI.scale(400)
        return (visible - JBUI.scale(24)).coerceIn(JBUI.scale(100), JBUI.scale(640))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(JBFont.label())
        return lines(inlay).size * metrics.height + JBUI.scale(12)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val graphics = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(graphics)
            graphics.color = ColorUtil.withAlpha(StackColors.QUEUED, 0.12)
            graphics.fillRoundRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height, JBUI.scale(8), JBUI.scale(8))
            graphics.font = JBFont.label()
            graphics.color = inlay.editor.colorsScheme.defaultForeground
            val metrics = graphics.fontMetrics
            var baseline = targetRegion.y + JBUI.scale(6) + metrics.ascent
            for (line in lines(inlay)) {
                graphics.drawString(line, targetRegion.x + JBUI.scale(6), baseline)
                baseline += metrics.height
            }
        } finally { graphics.dispose() }
    }

    private fun lines(inlay: Inlay<*>): List<String> {
        val metrics = inlay.editor.contentComponent.getFontMetrics(JBFont.label())
        val width = (calcWidthInPixels(inlay) - JBUI.scale(12)).coerceAtLeast(1)
        return plainText().split('\n').flatMap { line ->
            if (line.isBlank()) listOf("") else TextWrap.fit(line, width, Int.MAX_VALUE, metrics::stringWidth).lines
        }
    }
}
