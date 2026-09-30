package com.stacklane.stack

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una rama en dos pilas (P3): la capa de arriba de A es la base de B. */
class LocalStackTest {

    private val a = LocalStack("main", listOf("a1", "a2"))
    private val b = LocalStack("a2", listOf("b1", "b2"))
    private val c = LocalStack("main", listOf("c1"))
    private val all = setOf("main", "a1", "a2", "b1", "b2", "c1")

    private fun entries(vararg stacks: LocalStack, local: Set<String> = all, remote: Set<String> = emptySet()) =
        LocalStackEntry.all(stacks.toList(), local, remote)

    private fun state(branch: String, vararg stacks: LocalStack) =
        StackState.InSeveralStacks(RepoRef(LightVirtualFile("repo"), "repo", null, false), branch, entries(*stacks))

    @Test
    fun `a stack contains its trunk, like gh-stack`() {
        assertTrue(a.contains("main"))
        assertTrue(a.contains("a2"))
        assertFalse(a.contains("b1"))
        assertEquals(0, a.positionOf("main"))
        assertEquals(2, a.positionOf("a2"))
        assertNull(a.positionOf("b1"))
    }

    @Test
    fun `a layer that is the base of another stack is not used to open it`() {
        val (stackA, stackB) = entries(a, b)
        assertEquals(setOf("a2"), stackA.sharedBranches)
        assertEquals("a1", stackA.checkoutTarget)
        assertTrue(stackA.targetSkipsShared)
        // La base de B es de A, no de B: B se abre desde su cima.
        assertEquals(emptySet<String>(), stackB.sharedBranches)
        assertEquals("b2", stackB.checkoutTarget)
        assertFalse(stackB.targetSkipsShared)
    }

    @Test
    fun `a layer only on the remote beats a local one that is shared`() {
        val (stackA) = entries(a, b, local = setOf("main", "a2"), remote = setOf("a1"))
        assertEquals("a1", stackA.checkoutTarget)
    }

    @Test
    fun `with every branch shared, the target is still the top one`() {
        val single = LocalStack("main", listOf("a1"))
        val onTop = LocalStack("a1", listOf("b1"))
        val (stackA) = entries(single, onTop)
        assertEquals("a1", stackA.checkoutTarget)
        assertFalse(stackA.targetSkipsShared)
    }

    @Test
    fun `on a layer that is also a base, both stacks are listed and the layer is left before a new stack`() {
        val onA2 = state("a2", a, b, c)
        assertEquals(listOf(a, b), onA2.stacksOfBranch.map { it.stack })
        assertEquals(a, onA2.layerOf?.stack)
    }

    @Test
    fun `on a trunk of several stacks there is no layer to leave`() {
        val onMain = state("main", a, b, c)
        assertEquals(listOf(a, c), onMain.stacksOfBranch.map { it.stack })
        assertNull(onMain.layerOf)
    }
}
