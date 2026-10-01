package com.stacklane.stack

import com.stacklane.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingBranchTest {

    // Salida real de `gh stack view --json` (gh-stack v0.1.1) tras `gh stack push` y borrar
    // feat/b a mano: gh-stack la sigue listando, sin needsRebase ni en ella ni en la de encima.
    private val view = StackJson.parseView(
        """
        {
          "trunk": "main",
          "currentBranch": "feat/c",
          "branches": [
            { "name": "feat/a", "head": "9e40a811", "base": "d648e817", "isCurrent": false, "isMerged": false, "isQueued": false, "needsRebase": false },
            { "name": "feat/b", "head": "9e95aaa6", "base": "9e40a811", "isCurrent": false, "isMerged": false, "isQueued": false, "needsRebase": false },
            { "name": "feat/c", "head": "4321fb51", "base": "9e95aaa6", "isCurrent": true, "isMerged": false, "isQueued": false, "needsRebase": false }
          ]
        }
        """.trimIndent()
    )

    private fun lines(missing: List<MissingBranch>) =
        StackPlans.restoreLayers(missing).calls.map { GhCommands.display(it.args, it.tool) }

    @Test
    fun `a deleted layer is found with its last pushed commit`() {
        val missing = view.missingBranches(local = setOf("main", "feat/a", "feat/c"), remote = emptyMap())
        assertEquals(listOf(MissingBranch("feat/b", null, "9e95aaa6", "feat/a")), missing)
        assertEquals(listOf("git branch feat/b 9e95aaa6"), lines(missing))
    }

    @Test
    fun `a layer still on a remote is recreated from it`() {
        val missing = view.missingBranches(local = setOf("main", "feat/a", "feat/c"), remote = mapOf("feat/b" to "origin/feat/b"))
        assertEquals("origin/feat/b", missing.single().remoteBranch)
        assertEquals(listOf("git branch --track feat/b origin/feat/b"), lines(missing))
    }

    @Test
    fun `a layer never pushed takes the base gh-stack saved for the layer above`() {
        val unpublished = view.copy(layers = view.layers.map { it.copy(head = null) })
        val missing = unpublished.missingBranches(local = setOf("main", "feat/a", "feat/c"), remote = emptyMap())
        assertEquals("9e95aaa6", missing.single().lastCommit)
    }

    @Test
    fun `the top layer never pushed is recreated empty on the layer below`() {
        val unpublished = view.copy(layers = view.layers.map { it.copy(head = null) })
        val missing = unpublished.missingBranches(local = setOf("main", "feat/a", "feat/b"), remote = emptyMap())
        assertNull(missing.single().lastCommit)
        assertEquals(listOf("git branch feat/c feat/b"), lines(missing))
    }

    @Test
    fun `merged layers without a branch are what sync --prune leaves, not missing`() {
        val merged = view.copy(layers = view.layers.mapIndexed { i, layer -> if (i == 0) layer.copy(isMerged = true) else layer })
        val missing = merged.missingBranches(local = setOf("main", "feat/b", "feat/c"), remote = emptyMap())
        assertEquals(emptyList<MissingBranch>(), missing)
    }

    @Test
    fun `several are listed bottom up, so a parent is restored before the layer on it`() {
        val missing = view.missingBranches(local = setOf("main", "feat/a"), remote = emptyMap())
        assertEquals(listOf("feat/b", "feat/c"), missing.map { it.branch })
        // La de encima no tiene a nadie encima: su ultimo commit es el que guardo al subirla.
        assertEquals("4321fb51", missing[1].lastCommit)
    }
}
