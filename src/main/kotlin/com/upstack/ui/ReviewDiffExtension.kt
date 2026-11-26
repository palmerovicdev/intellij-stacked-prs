package com.upstack.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.util.Key
import com.upstack.stack.ReviewDiff
import com.upstack.stack.ReviewFile
import com.upstack.stack.ReviewSide
import com.upstack.stack.ReviewThread

internal data class ReviewDecoration(
    val file: ReviewFile,
    val threads: List<ReviewThread>,
    /** null means the current diff tool does not expose distinct left/right editors. */
    val onUnplacedCount: (Int?) -> Unit,
)

/** Only requests explicitly marked by Upstack receive annotations. */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val review = request.getUserData(REVIEW) ?: return
        val sideBySide = viewer as? TwosideTextDiffViewer
        if (sideBySide == null) {
            review.onUnplacedCount(null)
            return
        }
        val left = sideBySide.getEditor(Side.LEFT)
        val right = sideBySide.getEditor(Side.RIGHT)
        val positions = ReviewDiff.positionThreads(review.file, review.threads, left.document.lineCount, right.document.lineCount)
        ReviewInlays.install(left, positions.anchored.filter { it.thread.side == ReviewSide.LEFT }, viewer)
        ReviewInlays.install(right, positions.anchored.filter { it.thread.side == ReviewSide.RIGHT }, viewer)
        review.onUnplacedCount(positions.unplaced.size)
    }

    internal companion object {
        val REVIEW: Key<ReviewDecoration> = Key.create("upstack.review.diff")
    }
}
