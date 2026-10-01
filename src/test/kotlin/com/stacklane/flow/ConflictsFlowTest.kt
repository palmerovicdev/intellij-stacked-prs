package com.stacklane.flow

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.stacklane.gh.RebaseScope
import com.stacklane.stack.StackState
import com.stacklane.stack.repo
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un `gh stack rebase` parado de verdad: git se para en un conflicto de `feat/a` y gh-stack deja
 * su fichero. La ventana dice en que capa y en que commit, cuenta los conflictos y, cuando no
 * queda ninguno, ofrece seguir.
 */
class ConflictsFlowTest : FlowTestCase() {

    private val rebaseUpstack = listOf("stack", "rebase", "--upstack", "--no-trunk")

    /** `feat/a` y `main` cambian la misma linea; git se para al rebasar `feat/a` sobre `main`. */
    private fun stopOnConflict() {
        Files.writeString(
            repoDir.resolve(".git/gh-stack"),
            """{"schemaVersion":1,"stacks":[{"trunk":{"branch":"main"},"branches":[{"branch":"feat/a"},{"branch":"feat/b"}]}]}""",
        )
        git("switch", "-c", "feat/a")
        Files.writeString(repoDir.resolve("README.md"), "shop, layer a\n")
        git("commit", "-am", "Layer change")
        git("branch", "feat/b")
        git("switch", "main")
        Files.writeString(repoDir.resolve("README.md"), "shop, main\n")
        git("commit", "-am", "Main change")
        assertEquals(1, gitExit("rebase", "main", "feat/a"))
        leaveStackRebaseInProgress()
    }

    private fun refreshChanges() {
        VfsUtil.markDirtyAndRefresh(false, true, true, repository.root)
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    }

    private fun awaitConflicts(count: Int) {
        refreshChanges()
        waitUntil({ "conflicts $count, got ${service.conflicts.value}" }) { service.conflicts.value == count }
    }

    fun `test a stopped rebase names the layer and the commit git could not apply`() {
        stopOnConflict()

        val stop = requireNotNull(awaitState<StackState> { it.repo?.rebase?.branch != null }.repo?.rebase)
        assertEquals("feat/a", stop.branch)
        assertEquals(1, stop.position)
        assertEquals(2, stop.layers)
        assertEquals("Layer change", stop.subject)
        assertEquals(git("rev-parse", "--short", "REBASE_HEAD").trim(), stop.commit)
    }

    fun `test the stack stays on screen while git leaves HEAD detached`() {
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        awaitState<StackState.Loaded> { !it.detailsLoading }

        // gh-stack no lee la rama con HEAD suelto: view sale con 2.
        gh.respond(VIEW, exit = 2, stderr = "failed to get current branch\n")
        stopOnConflict()

        val state = awaitState<StackState.Loaded> { it.repo.rebase?.branch == "feat/a" }
        assertEquals(listOf("feat/a", "feat/b"), state.snapshot.layers.map { it.branch })
    }

    fun `test conflicts are counted until they are resolved`() {
        stopOnConflict()
        awaitState<StackState> { it.repo?.stackRebaseInProgress == true }

        awaitConflicts(1)

        Files.writeString(repoDir.resolve("README.md"), "shop, main and layer a\n")
        git("add", "README.md")
        awaitConflicts(0)
    }

    fun `test the merge dialog opens by itself when the rebase stops`() {
        git("config", "gh-stack.rerere-declined", "true")
        gh.respond(rebaseUpstack, exit = 3, stderr = "CONFLICT (content): Merge conflict in README.md\n")
        stopOnConflict()
        val opened = CopyOnWriteArrayList<List<String>>()
        service.mergeDialog = { files -> opened += files.map { it.name } }

        service.rebase(RebaseScope.UPSTACK, repository)
        awaitNotificationWith(msg("conflict.rebase.layer", "feat/a"))
        waitUntil("the merge dialog opens") { opened.isNotEmpty() }
        assertEquals(listOf(listOf("README.md")), opened)
        awaitIdle()
        awaitQuiet()
    }

    fun `test abort asks first and does nothing when cancelled`() {
        stopOnConflict()
        awaitState<StackState> { it.repo?.stackRebaseInProgress == true }
        answerMessages(Messages.NO)

        onEdt { service.abortRebase() }
        awaitQuiet()
        assertEquals(emptyList<List<String>>(), gh.writes())

        gh.respond(listOf("stack", "rebase", "--abort"))
        answerMessages(Messages.YES)
        onEdt { service.abortRebase() }
        awaitNotificationWith(msg("op.rebase.aborted"))
        assertEquals(listOf(listOf("stack", "rebase", "--abort")), gh.writes())
        awaitIdle()
        awaitQuiet()
    }
}
