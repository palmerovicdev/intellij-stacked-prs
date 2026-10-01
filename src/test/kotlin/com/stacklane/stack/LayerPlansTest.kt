package com.stacklane.stack

import com.stacklane.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerPlansTest {

    private fun layer(name: String, pr: Int? = null, merged: Boolean = false) =
        StackLayer(name, isCurrent = false, isMerged = merged, isQueued = false, needsRebase = false, pr = pr?.let { PrRef(it, "", "OPEN") })

    private val stack = StackSnapshot("main", "feat/c", listOf(layer("feat/a", 11), layer("feat/b", 12), layer("feat/c")))

    private fun lines(calls: List<GhCall>) = calls.map { GhCommands.display(it.args, it.tool) }

    @Test
    fun `only the top layer without a pull request, in a stack with nothing merged, can be deleted`() {
        assertNull(LayerPlans.dropBlock(stack, stack.layers[2]))
        assertEquals(DropBlock.NOT_TOP, LayerPlans.dropBlock(stack, stack.layers[1]))
        val published = stack.copy(layers = stack.layers.dropLast(1) + layer("feat/c", 13))
        assertEquals(DropBlock.HAS_PR, LayerPlans.dropBlock(published, published.layers[2]))
        val merged = stack.copy(layers = listOf(layer("feat/a", 11, merged = true)) + stack.layers.drop(1))
        assertEquals(DropBlock.MERGED_LAYERS, LayerPlans.dropBlock(merged, merged.layers[2]))
        val single = StackSnapshot("main", "feat/a", listOf(layer("feat/a")))
        assertEquals(DropBlock.ONLY_LAYER, LayerPlans.dropBlock(single, single.layers[0]))
    }

    @Test
    fun `from the layer itself, it leaves to the one below, starts the stack again and deletes the branches`() {
        val plan = LayerPlans.drop(stack, stack.layers[2], "feat/c", deleteLocal = true, deleteOn = "origin")
        assertEquals(
            listOf(
                "git switch feat/b",
                "gh stack unstack --local",
                "gh stack init --base main feat/a feat/b",
                "git branch -D feat/c",
                "git push origin --delete feat/c",
            ),
            lines(plan.calls),
        )
    }

    @Test
    fun `cut after unstack, the stack is started again as it was and HEAD goes back`() {
        val plan = LayerPlans.drop(stack, stack.layers[2], "feat/c", deleteLocal = true, deleteOn = null)
        assertEquals(emptyList<String>(), lines(plan.cleanup(0)))
        assertEquals(listOf("git switch feat/c"), lines(plan.cleanup(1)))
        assertEquals(listOf("gh stack init --base main feat/a feat/b feat/c", "git switch feat/c"), lines(plan.cleanup(2)))
        assertEquals(emptyList<String>(), lines(plan.cleanup(3)))
    }

    @Test
    fun `a top layer without its branch is only taken out`() {
        val plan = LayerPlans.drop(stack, stack.layers[2], "feat/b", deleteLocal = true, deleteOn = null, layerExists = false)
        assertEquals(listOf("gh stack unstack --local", "gh stack init --base main feat/a feat/b"), lines(plan.calls))
        assertEquals(listOf("gh stack init --base main feat/a feat/b"), lines(plan.cleanup(1)))
    }
}
