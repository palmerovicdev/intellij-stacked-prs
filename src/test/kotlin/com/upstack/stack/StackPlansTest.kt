package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.Tool
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
    fun `init suggests local and remote branches, and greys out the layers of active stacks`() {
        val active = LocalStackEntry.of(LocalStack("main", listOf("feat/a")), local = setOf("feat/a"), remote = emptySet())
        val gone = LocalStackEntry.of(LocalStack("main", listOf("feat/old")), local = emptySet(), remote = emptySet())
        val suggestions = StackPlans.initSuggestions(
            local = listOf("main", "feat/a", "feat/b"),
            remote = mapOf("feat/b" to "origin", "feat/c" to "origin", "feat/old" to "origin"),
            tracked = listOf(active, gone),
        )
        assertEquals(
            listOf(
                AddBranch("feat/b", AddBranchKind.LOCAL),
                AddBranch("feat/c", AddBranchKind.REMOTE, "origin"),
                // Su pila ya no tiene ramas: se olvida antes del init, asi que el nombre vale.
                AddBranch("feat/old", AddBranchKind.REMOTE, "origin"),
                AddBranch("main", AddBranchKind.LOCAL),
                AddBranch("feat/a", AddBranchKind.OTHER_STACK),
            ),
            suggestions,
        )
    }

    @Test
    fun `init tracks the layers only on a remote first, and deletes them if it stops`() {
        val plan = StackPlans.init(listOf("feat/a", "feat/c", "feat/d"), "main", mapOf("feat/c" to "origin/feat/c", "feat/d" to "origin/feat/d"))
        assertEquals(
            listOf(
                "git branch --track feat/c origin/feat/c",
                "git branch --track feat/d origin/feat/d",
                "gh stack init --base main feat/a feat/c feat/d",
            ),
            lines(plan.calls),
        )
        assertEquals(emptyList<String>(), lines(plan.cleanup(0)))
        assertEquals(listOf("git branch -D feat/c"), lines(plan.cleanup(1)))
        assertEquals(listOf("git branch -D feat/c feat/d"), lines(plan.cleanup(2)))
        // Sin ramas remotas, el init de siempre.
        assertEquals(listOf("gh stack init feat/a"), lines(StackPlans.init(listOf("feat/a"), null, emptyMap()).calls))
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
