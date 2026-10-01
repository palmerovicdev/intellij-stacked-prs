package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.GhResult
import com.stacklane.gh.MergeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergePlansTest {

    private fun layer(branch: String, pr: Int? = null, merged: Boolean = false) =
        StackLayer(branch, isCurrent = false, isMerged = merged, isQueued = false, needsRebase = false,
            pr = pr?.let { PrRef(it, "https://github.com/acme/app/pull/$it", if (merged) "MERGED" else "OPEN") })

    private fun details(number: Int, draft: Boolean = false, state: PrState = PrState.OPEN) = PrDetails(
        number = number, title = "PR $number", url = "", state = state, isDraft = draft,
        review = null, checks = null, labels = emptyList(), baseRef = "",
    )

    /** main ← feat/a (#11) ← feat/b (#12) ← feat/c (#13). */
    private val snapshot = StackSnapshot("main", "feat/c", listOf(layer("feat/a", 11), layer("feat/b", 12), layer("feat/c", 13)))

    private val ready = mapOf(11 to details(11), 12 to details(12), 13 to details(13))

    private fun numbers(targets: MergeTargets) = targets.candidates.map { it.number }

    @Test
    fun `every open layer that is ready can be merged, bottom to top`() {
        val targets = MergePlans.targets(snapshot, ready)
        assertEquals(listOf(11, 12, 13), numbers(targets))
        assertNull(targets.blocker)
        assertEquals(listOf(11, 12), targets.upTo(targets.candidateOf("feat/b")!!).map { it.number })
    }

    @Test
    fun `a draft stops everything above it, as in gh stack`() {
        val targets = MergePlans.targets(snapshot, ready + (12 to details(12, draft = true)))
        assertEquals(listOf(11), numbers(targets))
        assertEquals(MergeBlocker(snapshot.layers[1], MergeBlock.DRAFT), targets.blocker)
        assertNull(targets.candidateOf("feat/c"))
    }

    @Test
    fun `merged layers are skipped, closed or unpublished ones stop`() {
        val merged = StackSnapshot("main", "feat/c", listOf(layer("feat/a", 11, merged = true), layer("feat/b", 12), layer("feat/c")))
        val targets = MergePlans.targets(merged, mapOf(12 to details(12)))
        assertEquals(listOf(12), numbers(targets))
        assertEquals(MergeBlock.UNPUBLISHED, targets.blocker?.reason)

        val closed = MergePlans.targets(snapshot, ready + (11 to details(11, state = PrState.CLOSED)))
        assertEquals(emptyList<Int>(), numbers(closed))
        assertEquals(MergeBlock.CLOSED, closed.blocker?.reason)

        // GitHub ya lo fusiono aunque gh-stack aun no lo sepa: se salta.
        assertEquals(listOf(12, 13), numbers(MergePlans.targets(snapshot, ready + (11 to details(11, state = PrState.MERGED)))))
    }

    @Test
    fun `without GitHub data a layer cannot be checked, so it stops`() {
        val targets = MergePlans.targets(snapshot, mapOf(11 to details(11)))
        assertEquals(listOf(11), numbers(targets))
        assertEquals(MergeBlock.UNKNOWN, targets.blocker?.reason)
    }

    @Test
    fun `the method is the last one if allowed, then the default of the user, then the first allowed`() {
        val all = MergeSettings(MergeMethod.entries.toSet(), MergeMethod.MERGE, usesMergeQueue = false)
        assertEquals(MergeMethod.REBASE, MergePlans.initialMethod(all, MergeMethod.REBASE))
        assertEquals(MergeMethod.MERGE, MergePlans.initialMethod(all, null))
        val squashOnly = MergeSettings(setOf(MergeMethod.SQUASH), MergeMethod.MERGE, usesMergeQueue = false)
        assertEquals(MergeMethod.SQUASH, MergePlans.initialMethod(squashOnly, MergeMethod.REBASE))
        assertEquals(MergeMethod.SQUASH, MergePlans.initialMethod(null, null))
        assertEquals(MergeMethod.REBASE, MergePlans.initialMethod(null, MergeMethod.REBASE))
    }

    @Test
    fun `the command, with the method or, with a merge queue, without one`() {
        val target = MergePlans.targets(snapshot, ready).candidateOf("feat/b")!!
        assertEquals("gh stack merge 12 --yes --squash", GhCommands.display(MergePlans.call(MergeChoice(target, MergeMethod.SQUASH)).args))
        assertEquals("gh stack merge 12 --yes", GhCommands.display(MergePlans.call(MergeChoice(target, null)).args))
    }

    private fun result(exit: Int, stdout: String = "", stderr: String = "") = GhResult(emptyList(), exit, stdout, stderr)

    @Test
    fun `a number that is also a stack number is only merged if it means the same`() {
        assertEquals(NumberCheck.PULL_REQUEST, MergePlans.numberCheck(12, result(1, stderr = "gh: Not Found (HTTP 404)")))
        val ours = """{"number":12,"pull_requests":[{"number":11,"state":"open"},{"number":12,"state":"open"}]}"""
        assertEquals(NumberCheck.SAME_MERGE, MergePlans.numberCheck(12, result(0, ours)))
        val other = """{"number":12,"pull_requests":[{"number":3},{"number":4}]}"""
        assertEquals(NumberCheck.OTHER_STACK, MergePlans.numberCheck(12, result(0, other)))
        // PR #12 en medio de la pila 12: gh-stack fusionaria tambien los de encima.
        val above = """{"number":12,"pull_requests":[{"number":12},{"number":13}]}"""
        assertEquals(NumberCheck.OTHER_STACK, MergePlans.numberCheck(12, result(0, above)))
        assertNull(MergePlans.numberCheck(12, result(1, stderr = "HTTP 500: boom")))
        assertNull(MergePlans.numberCheck(12, result(0, "not json")))
    }

    @Test
    fun `what gh stack said when it finished`() {
        assertEquals(MergeOutcome.MERGED, MergePlans.outcome(result(0, stderr = "✓ Merged #11, #12 into main (abc1234)\n")))
        assertEquals(
            MergeOutcome.QUEUED,
            MergePlans.outcome(result(0, stderr = "✓ Added #11, #12 to the merge queue for main\nThey will merge once the queue processes them.\n")),
        )
        assertEquals(MergeOutcome.ALREADY_MERGED, MergePlans.outcome(result(0, stderr = "✓ This stack is already fully merged.\n")))
        assertEquals(MergeOutcome.ALREADY_MERGED, MergePlans.outcome(result(0, stderr = "✓ pull request #12 is already merged\n")))
        assertNull(MergePlans.outcome(result(0)))
    }

    @Test
    fun `merge settings with allowed methods, default and merge queue`() {
        val settings = StackJson.parseMergeSettings(
            """{"data":{"repository":{"mergeCommitAllowed":false,"squashMergeAllowed":true,"rebaseMergeAllowed":true,
               "viewerDefaultMergeMethod":"REBASE","mergeQueue":null,"ref":{"rules":{"nodes":[{"type":"PULL_REQUEST"}]}}}}}""",
        )
        assertEquals(MergeSettings(setOf(MergeMethod.SQUASH, MergeMethod.REBASE), MergeMethod.REBASE, usesMergeQueue = false), settings)

        val queue = """{"data":{"repository":{"squashMergeAllowed":true,"mergeQueue":{"id":"MQ_1"},"ref":null}}}"""
        assertEquals(true, StackJson.parseMergeSettings(queue)?.usesMergeQueue)
        val rule = """{"data":{"repository":{"squashMergeAllowed":true,"mergeQueue":null,"ref":{"rules":{"nodes":[{"type":"MERGE_QUEUE"}]}}}}}"""
        assertEquals(true, StackJson.parseMergeSettings(rule)?.usesMergeQueue)

        assertNull(StackJson.parseMergeSettings("""{"data":{"repository":{}}}"""))
        assertNull(StackJson.parseMergeSettings("""{"errors":[{"message":"Could not resolve to a Repository"}]}"""))
    }
}
