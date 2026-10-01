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
        /** Capas de la pila, fusionadas incluidas: la de arriba es la [position] `total`. */
        val total: Int,
        val isTop: Boolean,
        val detailsLoading: Boolean,
        /** La base del PR en GitHub, si no es [parent]. Ver `StackSnapshot.wrongBase`. */
        val wrongBase: String? = null,
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
            append(escape(layer.branch)).append(" &rarr; ").append(escape(parent))
            append(" · ").append(escape(message("tooltip.position", position, total))).append("<br>")
            append(escape(listOfNotNull(status.text, details?.review?.let(::reviewText), details?.checks?.let(::checksText)).joinToString(" · ")))
            details?.size?.let { append("<br>").append(escape(message("tooltip.size", it.additions, it.deletions, it.files))) }
            if (layer.needsRebase) append("<br>").append(escape(message("layer.needs.rebase")))
            wrongBase?.let { append("<br>").append(escape(message("tooltip.wrong.base", it, parent))) }
            if (details?.hasConflicts == true) append("<br>").append(escape(message("tooltip.conflicts", details.baseRef)))
            if (details?.isBehind == true) append("<br>").append(escape(message("tooltip.behind", details.baseRef)))
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

    /**
     * Una pila guardada en local, cuando la rama actual no esta en ninguna o esta en varias.
     * [head]: la rama actual, si es de esta pila.
     */
    data class Local(val entry: LocalStackEntry, val head: String? = null) : StackRow {
        override val key: String get() = "local:${entry.stack.branches.last()}"
        override val searchText: String get() = entry.stack.branches.joinToString(" ")

        override fun tooltip(): String {
            val action = action()
            val position = head?.let(entry.stack::positionOf) ?: return action
            val where = if (position == 0) message("tooltip.local.base", head)
            else message("tooltip.local.layer", head, position, entry.stack.branches.size)
            return "<html>${escape(where)}<br>${escape(action)}</html>"
        }

        private fun action(): String {
            val target = entry.checkoutTarget.orEmpty()
            return when {
                entry.isStale -> message("tooltip.local.stale")
                target == head -> message("tooltip.local.blocked")
                target !in entry.localBranches -> message("tooltip.local.remote", target)
                entry.targetSkipsShared -> message("tooltip.local.shared", target)
                else -> message("tooltip.local.stack", target)
            }
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
