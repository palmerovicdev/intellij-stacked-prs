package com.stacklane.stack

import com.stacklane.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosePlansTest {

    private val urlA = "https://github.com/acme/app/pull/11"
    private val urlB = "https://github.com/acme/app/pull/12"
    private val urlC = "https://github.com/acme/app/pull/13"

    private fun layer(branch: String, pr: Int? = null, url: String = "", merged: Boolean = false, current: Boolean = false) =
        StackLayer(branch, current, merged, isQueued = false, needsRebase = false, pr = pr?.let { PrRef(it, url, if (merged) "MERGED" else "OPEN") })

    /** main ← feat/a (#11) ← feat/b (#12) ← feat/c (#13), en feat/c. */
    private val snapshot = StackSnapshot(
        trunk = "main",
        currentBranch = "feat/c",
        layers = listOf(layer("feat/a", 11, urlA), layer("feat/b", 12, urlB), layer("feat/c", 13, urlC, current = true)),
    )

    private val everywhere = mapOf("main" to "m0", "feat/a" to "a1", "feat/b" to "b1", "feat/c" to "c1")

    private fun targets(
        snapshot: StackSnapshot = this.snapshot,
        details: Map<Int, PrDetails> = emptyMap(),
        remote: String? = "origin",
        local: Map<String, String> = everywhere,
        remoteHeads: Map<String, String> = everywhere,
        otherTrunks: Set<String> = emptySet(),
    ) = ClosePlans.targets(snapshot, details, remote, local, remoteHeads, otherTrunks)

    private fun lines(calls: List<GhCall>) = calls.map { GhCommands.display(it.args, it.tool) }

    @Test
    fun `unstacks first, then closes from the top down, deletes the remote branches and the local ones last`() {
        val targets = targets()
        val choice = CloseChoice.defaults(targets).copy(comment = "Superseded by #20")
        assertEquals(
            listOf(
                "gh stack unstack",
                "gh pr close $urlC --comment 'Superseded by #20'",
                "gh pr close $urlB --comment 'Superseded by #20'",
                "gh pr close $urlA --comment 'Superseded by #20'",
                "git push origin --delete feat/c feat/b feat/a",
                // HEAD esta en feat/c: antes de borrarla se sale al trunk.
                "git switch main",
                "git branch -D feat/c feat/b feat/a",
            ),
            lines(ClosePlans.calls(choice, targets)),
        )
    }

    @Test
    fun `the unstack goes alone, before anything that cannot be undone`() {
        val targets = targets()
        val choice = CloseChoice.defaults(targets)
        assertEquals(listOf("stack", "unstack"), ClosePlans.unstack(choice, targets).args)
        assertTrue(ClosePlans.afterUnstack(choice, targets).none { it.args.take(2) == listOf("stack", "unstack") })
    }

    @Test
    fun `without the GitHub part, or without any pull request, it only stops tracking locally`() {
        val targets = targets()
        val localOnly = CloseChoice.defaults(targets).copy(unstackOnGitHub = false)
        assertEquals(GhCommands.unstackLocal(), ClosePlans.unstack(localOnly, targets).args)

        val unpublished = targets(snapshot.copy(layers = snapshot.layers.map { it.copy(pr = null) }))
        assertFalse(unpublished.onGitHub)
        assertEquals(GhCommands.unstackLocal(), ClosePlans.unstack(CloseChoice.defaults(unpublished), unpublished).args)
    }

    @Test
    fun `an empty comment adds no flag`() {
        val targets = targets()
        val choice = CloseChoice.defaults(targets).copy(comment = "   ", deleteRemote = false, deleteLocal = false)
        assertEquals(
            listOf("gh pr close $urlC", "gh pr close $urlB", "gh pr close $urlA"),
            lines(ClosePlans.afterUnstack(choice, targets)),
        )
    }

    @Test
    fun `only open pull requests are closed`() {
        val merged = snapshot.copy(layers = listOf(layer("feat/a", 11, urlA, merged = true)) + snapshot.layers.drop(1))
        val closedOnGitHub = mapOf(12 to details(12, PrState.CLOSED))
        val targets = targets(merged, details = closedOnGitHub)
        assertEquals(listOf(13), targets.openPrs.map { it.number })
        // Las ramas se borran igual, tambien la de la capa fusionada.
        assertEquals(listOf("feat/c", "feat/b", "feat/a"), targets.remoteBranches)
    }

    @Test
    fun `remote branches are only deleted if the open pull requests are closed first`() {
        val targets = targets()
        val keepPrs = CloseChoice.defaults(targets).copy(closePrs = false)
        assertFalse(ClosePlans.canDeleteRemote(closePrs = false, targets))
        assertTrue(lines(ClosePlans.afterUnstack(keepPrs, targets)).none { it.startsWith("git push") })

        // Sin PRs abiertos no hay nada que cerrar antes.
        val allMerged = targets(details = (11..13).associateWith { details(it, PrState.MERGED) })
        assertTrue(ClosePlans.canDeleteRemote(closePrs = false, allMerged))
    }

    @Test
    fun `only branches that exist are deleted`() {
        val targets = targets(local = everywhere - "feat/a", remoteHeads = everywhere - "feat/b")
        assertEquals(listOf("feat/c", "feat/a"), targets.remoteBranches)
        assertEquals(listOf("feat/c", "feat/b"), targets.localBranches)
        assertNull(targets(remote = null).remote)
        assertEquals(emptyList<String>(), targets(remote = null).remoteBranches)
    }

    @Test
    fun `a branch that is the base of another stack is never deleted`() {
        val targets = targets(otherTrunks = setOf("feat/b"))
        assertEquals(listOf("feat/b"), targets.shared)
        assertEquals(listOf("feat/c", "feat/a"), targets.remoteBranches)
        assertEquals(listOf("feat/c", "feat/a"), targets.localBranches)
        // Su PR si se cierra: cerrarlo no borra la rama.
        assertEquals(listOf(13, 12, 11), targets.openPrs.map { it.number })
    }

    @Test
    fun `a trunk that is only on the remote is checked out from there`() {
        val targets = targets(local = everywhere - "main")
        assertEquals(listOf("switch", "--track", "origin/main"), targets.leave)
    }

    @Test
    fun `with no trunk to go to, the current branch is kept`() {
        val targets = targets(local = everywhere - "main", remoteHeads = everywhere - "main")
        assertNull(targets.leave)
        assertEquals("feat/c", targets.keptCheckedOut)
        assertEquals(listOf("feat/b", "feat/a"), targets.localBranches)
    }

    @Test
    fun `on the trunk there is nowhere to switch`() {
        val onTrunk = snapshot.copy(currentBranch = "main", layers = snapshot.layers.map { it.copy(isCurrent = false) })
        assertNull(targets(onTrunk).leave)
        assertEquals(listOf("git branch -D feat/c feat/b feat/a"), lines(ClosePlans.afterUnstack(CloseChoice.defaults(targets(onTrunk)), targets(onTrunk))).takeLast(1))
    }

    @Test
    fun `local branches that differ from the remote are not deleted by default`() {
        val targets = targets(remoteHeads = everywhere + ("feat/b" to "old") - "feat/a")
        assertEquals(listOf("feat/b", "feat/a"), targets.unpushed)
        assertFalse(CloseChoice.defaults(targets).deleteLocal)
        assertTrue(CloseChoice.defaults(targets()).deleteLocal)
    }

    @Test
    fun `a merged layer is not reported as unpushed`() {
        val merged = snapshot.copy(layers = listOf(layer("feat/a", 11, urlA, merged = true)) + snapshot.layers.drop(1))
        assertEquals(emptyList<String>(), targets(merged, remoteHeads = everywhere - "feat/a").unpushed)
    }

    @Test
    fun `reads the branches from ls-remote`() {
        val output = "a1b2\trefs/heads/feat/a\nc3d4\trefs/heads/feat/c\nbogus line\n"
        assertEquals(mapOf("feat/a" to "a1b2", "feat/c" to "c3d4"), ClosePlans.parseLsRemote(output))
    }

    private fun details(number: Int, state: PrState) =
        PrDetails(number, "", "", state, isDraft = false, review = null, checks = null, labels = emptyList(), baseRef = "")
}
