package com.stacklane.stack

import com.stacklane.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestackTest {

    private fun layer(branch: String, current: Boolean = false, merged: Boolean = false, needsRebase: Boolean = false) =
        StackLayer(branch, isCurrent = current, isMerged = merged, isQueued = false, needsRebase = needsRebase, pr = null)

    private fun stack(vararg layers: StackLayer) =
        StackSnapshot("main", layers.firstOrNull { it.isCurrent }?.branch ?: "main", layers.toList())

    private fun branches(layers: List<StackLayer>) = layers.map { it.branch }

    @Test
    fun `a commit on a middle layer leaves the next one behind`() {
        // Lo que ensena gh stack view justo despues de commitear en b: c ya no contiene a b.
        val snapshot = stack(layer("a"), layer("b", current = true), layer("c", needsRebase = true), layer("d"))
        assertEquals(listOf("c"), branches(snapshot.outdatedLayers))
        assertEquals(listOf("c"), branches(snapshot.outdatedAbove(snapshot.current!!)))
        // gh-stack solo marca c, pero d tampoco tiene el commit: el aviso cuenta las dos.
        assertEquals(listOf("c", "d"), branches(snapshot.activeAbove(snapshot.current!!)))
        assertFalse(snapshot.behindTrunk)
        // Desde la capa actual, sin checkout: b se queda igual y c y d reciben el commit.
        assertEquals("b", snapshot.upstackStart?.branch)
    }

    @Test
    fun `nothing is behind on the top layer`() {
        val snapshot = stack(layer("a"), layer("b", current = true))
        assertTrue(snapshot.outdatedLayers.isEmpty())
        assertTrue(snapshot.outdatedAbove(snapshot.current!!).isEmpty())
        assertNull(snapshot.upstackStart)
    }

    @Test
    fun `above the outdated layer, the upstack starts at the outdated layer`() {
        val snapshot = stack(layer("a"), layer("b", needsRebase = true), layer("c"), layer("d", current = true))
        assertEquals("b", snapshot.upstackStart?.branch)
        assertTrue(snapshot.outdatedAbove(snapshot.current!!).isEmpty())
    }

    @Test
    fun `the bottom layer depends on the trunk, not on another layer`() {
        // main avanzo: a no lo contiene. Eso no lo arregla un rebase upstack sin trunk.
        val snapshot = stack(layer("a", current = true, needsRebase = true), layer("b"))
        assertTrue(snapshot.behindTrunk)
        assertTrue(snapshot.outdatedLayers.isEmpty())
        assertNull(snapshot.upstackStart)
    }

    @Test
    fun `behind the trunk and a layer behind at the same time`() {
        val snapshot = stack(layer("a", needsRebase = true), layer("b", current = true), layer("c", needsRebase = true))
        assertTrue(snapshot.behindTrunk)
        assertEquals(listOf("c"), branches(snapshot.outdatedLayers))
        assertEquals("b", snapshot.upstackStart?.branch)
    }

    @Test
    fun `merged layers are skipped, and the first active one is the bottom`() {
        val snapshot = stack(layer("a", merged = true), layer("b", needsRebase = true), layer("c", current = true, needsRebase = true))
        assertEquals("b", snapshot.bottom?.branch)
        assertTrue(snapshot.behindTrunk)
        assertEquals(listOf("c"), branches(snapshot.outdatedLayers))
        assertEquals("c", snapshot.upstackStart?.branch)
    }

    @Test
    fun `on the trunk there is no current layer to start from`() {
        val snapshot = stack(layer("a"), layer("b", needsRebase = true))
        assertEquals("b", snapshot.upstackStart?.branch)
    }

    // ------------------------------------------------------------------ rerere

    @Test
    fun `rerere answers as gh-stack stores them`() {
        assertEquals(Rerere.Answer.UNANSWERED, Rerere.parse(""))
        assertEquals(Rerere.Answer.ENABLED, Rerere.parse("rerere.enabled true\n"))
        assertEquals(Rerere.Answer.DECLINED, Rerere.parse("gh-stack.rerere-declined true\n"))
        assertEquals(Rerere.Answer.UNANSWERED, Rerere.parse("gh-stack.rerere-declined false\n"))
    }

    @Test
    fun `the repository config wins over the global one`() {
        // --get-regexp lista de la global a la del repositorio: la ultima linea manda.
        assertEquals(Rerere.Answer.ENABLED, Rerere.parse("rerere.enabled false\nrerere.enabled true\n"))
        assertEquals(Rerere.Answer.DECLINED, Rerere.parse("rerere.enabled true\nrerere.enabled false\n"))
    }

    @Test
    fun `enabling and declining write what gh-stack writes`() {
        assertEquals(
            listOf("git config rerere.enabled true", "git config rerere.autoupdate true"),
            Rerere.enable().map { GhCommands.display(it.args, it.tool) },
        )
        assertEquals(listOf("git config gh-stack.rerere-declined true"), Rerere.decline().map { GhCommands.display(it.args, it.tool) })
    }
}
