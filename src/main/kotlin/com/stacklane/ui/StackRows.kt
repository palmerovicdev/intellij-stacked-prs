package com.stacklane.ui

import com.intellij.openapi.util.text.StringUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.ChecksState
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.PrDetails
import com.stacklane.stack.ReviewDecision
import com.stacklane.stack.StackLayer

/** Una fila de la lista. De arriba abajo: capas (la superior primero) y el trunk. */
internal sealed interface StackRow {

    /** Para conservar la seleccion entre refrescos. */
    val key: String

    val searchText: String

    fun tooltip(): String?

    data class Layer(
        val layer: StackLayer,
        val details: PrDetails?,
        val parent: String,
        /** 1 = la mas cercana al trunk, como numera gh-stack. */
        val position: Int,
        val isTop: Boolean,
        val detailsLoading: Boolean,
    ) : StackRow {
        val status: LayerStatus get() = LayerStatus.of(layer, details)

        override val key: String get() = "layer:${layer.branch}"

        override val searchText: String get() = listOfNotNull(layer.branch, details?.title, layer.pr?.number?.let { "#$it" }).joinToString(" ")

        override fun tooltip(): String = buildString {
            append("<html>")
            val pr = layer.pr
            if (pr != null) {
                append("<b>#").append(pr.number).append("</b> ")
                details?.let { append(escape(it.title)) }
                append("<br>")
            }
            append(escape(layer.branch)).append(" &rarr; ").append(escape(parent)).append("<br>")
            append(escape(listOfNotNull(status.text, details?.review?.let(::reviewText), details?.checks?.let(::checksText)).joinToString(" · ")))
            if (layer.needsRebase) append("<br>").append(escape(message("layer.needs.rebase")))
            details?.labels?.takeIf { it.isNotEmpty() }?.let { labels ->
                append("<br>").append(escape(message("tooltip.labels", labels.joinToString(", ") { it.name })))
            }
            if (pr != null && pr.url.isNotEmpty()) append("<br><small>").append(escape(pr.url)).append("</small>")
            append("</html>")
        }
    }

    data class Trunk(val name: String, val isCurrent: Boolean) : StackRow {
        override val key: String get() = "trunk:$name"
        override val searchText: String get() = name
        override fun tooltip(): String = message("tooltip.trunk", name)
    }

    /** Una pila guardada en local, cuando la rama actual no esta en ninguna. */
    data class Local(val entry: LocalStackEntry) : StackRow {
        override val key: String get() = "local:${entry.stack.branches.last()}"
        override val searchText: String get() = entry.stack.branches.joinToString(" ")
        override fun tooltip(): String = when {
            entry.isStale -> message("tooltip.local.stale")
            entry.localBranches.isEmpty() -> message("tooltip.local.remote", entry.checkoutTarget.orEmpty())
            else -> message("tooltip.local.stack", entry.checkoutTarget.orEmpty())
        }
    }
}

internal fun reviewText(review: ReviewDecision): String = when (review) {
    ReviewDecision.APPROVED -> message("review.approved")
    ReviewDecision.CHANGES_REQUESTED -> message("review.changes")
    ReviewDecision.REVIEW_REQUIRED -> message("review.required")
}

internal fun checksText(checks: ChecksState): String = when (checks) {
    ChecksState.SUCCESS -> message("checks.success")
    ChecksState.FAILURE -> message("checks.failure")
    ChecksState.PENDING -> message("checks.pending")
}

private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
