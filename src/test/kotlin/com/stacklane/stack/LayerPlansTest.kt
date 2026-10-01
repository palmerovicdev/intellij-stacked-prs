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
    fun `from a lower layer, it goes back there after init leaves HEAD on the top`() {
        val plan = LayerPlans.drop(stack, stack.layers[2], "feat/a", deleteLocal = true, deleteOn = null)
        assertEquals(
            listOf("gh stack unstack --local", "gh stack init --base main feat/a feat/b", "git switch feat/a", "git branch -D feat/c"),
            lines(plan.calls),
        )
    }

    @Test
    fun `rename without a pull request is only allowed with nothing merged and a known GitHub repository`() {
        val github = GitHubRepo("github.com", "acme", "shop")
        assertNull(LayerPlans.renameBlock(stack, stack.layers[2], onRemote = true, github = github))
        assertNull(LayerPlans.renameBlock(stack, stack.layers[2], onRemote = false, github = null))
        assertEquals(RenameBlock.HAS_PR, LayerPlans.renameBlock(stack, stack.layers[1], onRemote = true, github = github))
        assertEquals(RenameBlock.NO_GITHUB, LayerPlans.renameBlock(stack, stack.layers[2], onRemote = true, github = null))
        val merged = stack.copy(layers = listOf(layer("feat/a", 11, merged = true)) + stack.layers.drop(1))
        assertEquals(RenameBlock.MERGED_LAYERS, LayerPlans.renameBlock(merged, merged.layers[2], onRemote = false, github = null))
    }

    @Test
    fun `rename a local branch unstacks first, renames and starts the stack again where HEAD was`() {
        val plan = LayerPlans.rename(stack, stack.layers[2], "feat/c2", remote = null, current = "feat/a")
        assertEquals(
            listOf(
                "gh stack unstack --local",
                "git branch -m feat/c feat/c2",
                "gh stack init --base main feat/a feat/b feat/c2",
                "git switch feat/a",
            ),
            lines(plan.calls),
        )
        assertEquals(emptyList<String>(), lines(plan.cleanup(0)))
        assertEquals(listOf("gh stack init --base main feat/a feat/b feat/c", "git switch feat/a"), lines(plan.cleanup(1)))
        assertEquals(
            listOf("git branch -m feat/c2 feat/c", "gh stack init --base main feat/a feat/b feat/c", "git switch feat/a"),
            lines(plan.cleanup(2)),
        )
        // Tras init la pila ya esta bien: si falla la vuelta, no se deshace nada.
        assertEquals(emptyList<String>(), lines(plan.cleanup(3)))
    }

    @Test
    fun `rename a pushed branch renames it on GitHub first and points the local one at the new name`() {
        val remote = RemoteRename("origin", GitHubRepo("github.com", "acme", "shop"))
        val plan = LayerPlans.rename(stack, stack.layers[2], "feat/c2", remote, current = "feat/c")
        assertEquals(
            listOf(
                "gh stack unstack --local",
                "gh api --method POST repos/acme/shop/branches/feat/c/rename -f new_name=feat/c2",
                "git branch -m feat/c feat/c2",
                "git config branch.feat/c2.remote origin",
                "git config branch.feat/c2.merge refs/heads/feat/c2",
                "git branch -r -d origin/feat/c",
                "gh stack init --base main feat/a feat/b feat/c2",
            ),
            lines(plan.calls),
        )
        // Fallo al renombrar la local: GitHub ya cambio, se devuelve.
        assertEquals(
            listOf("gh api --method POST repos/acme/shop/branches/feat/c2/rename -f new_name=feat/c", "gh stack init --base main feat/a feat/b feat/c"),
            lines(plan.cleanup(2)),
        )
        assertEquals(
            listOf(
                "git branch -m feat/c2 feat/c",
                "git config branch.feat/c.merge refs/heads/feat/c",
                "gh api --method POST repos/acme/shop/branches/feat/c2/rename -f new_name=feat/c",
                "gh stack init --base main feat/a feat/b feat/c",
            ),
            lines(plan.cleanup(6)),
        )
    }

    @Test
    fun `a top layer without its branch is only taken out`() {
        val plan = LayerPlans.drop(stack, stack.layers[2], "feat/b", deleteLocal = true, deleteOn = null, layerExists = false)
        assertEquals(listOf("gh stack unstack --local", "gh stack init --base main feat/a feat/b"), lines(plan.calls))
        assertEquals(listOf("gh stack init --base main feat/a feat/b"), lines(plan.cleanup(1)))
    }
}
