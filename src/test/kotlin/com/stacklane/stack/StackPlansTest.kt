package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StackPlansTest {

    private val stale = LocalStack("main", listOf("feat/website-editor"), mapOf("feat/website-editor" to "488d11fae3e7"))

    private fun lines(calls: List<GhCall>) = calls.map { GhCommands.display(it.args, it.tool) }

    @Test
    fun `forget recreates a branch at HEAD, unstacks from it and comes back`() {
        val plan = StackPlans.forget(stale, Position("feat/diagnostics", "abc"))!!
        assertEquals(
            listOf(
                "git branch feat/website-editor HEAD",
                "git switch feat/website-editor",
                "gh stack unstack --local",
                "git switch feat/diagnostics",
                "git branch -D feat/website-editor",
            ),
            lines(plan.calls),
        )
    }

    @Test
    fun `forget undoes exactly what it did when it stops halfway`() {
        val plan = StackPlans.forget(stale, Position("feat/diagnostics", "abc"))!!
        // Nada se hizo: nada que deshacer. Tampoco borrar una rama que no se creo aqui.
        assertEquals(emptyList<String>(), lines(plan.cleanup(0)))
        // Rama creada, sin cambiar a ella.
        assertEquals(listOf("git branch -D feat/website-editor"), lines(plan.cleanup(1)))
        // En la rama temporal (fallo el unstack o la vuelta): volver y borrarla.
        assertEquals(listOf("git switch feat/diagnostics", "git branch -D feat/website-editor"), lines(plan.cleanup(2)))
        assertEquals(listOf("git switch feat/diagnostics", "git branch -D feat/website-editor"), lines(plan.cleanup(3)))
        // Ya de vuelta: solo borrar. Todo hecho: nada.
        assertEquals(listOf("git branch -D feat/website-editor"), lines(plan.cleanup(4)))
        assertEquals(emptyList<String>(), lines(plan.cleanup(5)))
    }

    @Test
    fun `forget from a detached head goes back to the commit, and refuses without one`() {
        val plan = StackPlans.forget(stale, Position(null, "abc123"))!!
        assertEquals("git switch --detach abc123", lines(plan.calls)[3])
        assertNull(StackPlans.forget(stale, Position(null, null)))
    }

    @Test
    fun `chained plans hand the cleanup to the part that was running`() {
        val forget = StackPlans.forget(stale, Position("dev", null))!!
        val plan = forget.then(StackPlans.restore(stale.heads)).then(listOf(GhCall(GhCommands.init(listOf("feat/website-editor"), "dev"))))
        assertEquals(
            listOf("git branch feat/website-editor 488d11fae3e7", "gh stack init --base dev feat/website-editor"),
            lines(plan.calls).drop(5),
        )
        assertEquals(lines(forget.cleanup(2)), lines(plan.cleanup(2)))
        // Olvido terminado; fallo el restore o el init: no hay nada del olvido que deshacer.
        assertEquals(emptyList<String>(), lines(plan.cleanup(5)))
        assertEquals(emptyList<String>(), lines(plan.cleanup(6)))
        assertTrue(plan.calls.take(2).all { it.tool == Tool.GIT })
    }

    @Test
    fun `local stack entries know what is left of their branches`() {
        val stack = LocalStack("main", listOf("a", "b", "c"), mapOf("a" to "1", "c" to "3"))
        val partial = LocalStackEntry.of(stack, local = setOf("a"), remote = setOf("b"))
        assertFalse(partial.isStale)
        assertEquals("a", partial.checkoutTarget)
        assertEquals(listOf("b"), partial.remoteOnlyBranches)
        assertEquals(mapOf("c" to "3"), partial.restorable)

        val remoteOnly = LocalStackEntry.of(stack, local = emptySet(), remote = setOf("b", "c"))
        assertEquals("c", remoteOnly.checkoutTarget)

        val gone = LocalStackEntry.of(stack, local = setOf("x"), remote = emptySet())
        assertTrue(gone.isStale)
        assertNull(gone.checkoutTarget)
        assertEquals(stack.heads, gone.restorable)
    }
}
