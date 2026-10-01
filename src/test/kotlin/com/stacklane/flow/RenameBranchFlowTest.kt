package com.stacklane.flow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.stacklane.actions.StackFlows
import com.stacklane.stack.StackState
import com.stacklane.ui.RenameBranchDialog
import java.nio.file.Files

/** *Rename Branch…*: la rama de una capa sin PR cambia de nombre, aqui y en GitHub si se subio. */
class RenameBranchFlowTest : FlowTestCase() {

    private val unstack = listOf("stack", "unstack", "--local")
    private val initRenamed = listOf("stack", "init", "--base", "main", "feat/a2", "feat/b")

    /** main ← feat/a ← feat/b, sin publicar, en feat/b. */
    private fun loadStack(): StackState.Loaded {
        for (name in listOf("feat/a", "feat/b")) {
            git("switch", "-c", name)
            Files.writeString(repoDir.resolve(name.replace('/', '-') + ".txt"), "$name\n")
            git("add", ".")
            git("commit", "-m", name)
        }
        gh.respond(VIEW, stdout = Samples.VIEW_UNPUBLISHED)
        return awaitState()
    }

    /** El dialogo de renombrar se acepta con [name] en su campo. */
    private fun renameTo(name: String) {
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<RenameBranchDialog>(RenameBranchDialog::class.java) {
            override fun doIntercept(component: RenameBranchDialog) {
                component.enterName(name)
                assertNull(component.doValidate())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })
    }

    /** feat/a, subida a origin: Git4Idea tiene que ver su rama remota para que se renombre alli. */
    private fun pushFeatA() {
        git("update-ref", "refs/remotes/origin/feat/a", "feat/a")
        preferredRemote = "origin"
        repository.update()
        waitUntil("Git4Idea sees origin/feat/a") { repository.branches.remoteBranches.any { it.name == "origin/feat/a" } }
    }

    /** Las escrituras, con las de `gh api`, que [FakeGh.writes] cuenta como lecturas. */
    private fun writes() = gh.calls().filter { it != VIEW && it.take(2) != listOf("api", "graphql") }

    private fun branches() = git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    fun `test a local branch is renamed and HEAD stays where it was`() {
        val state = loadStack()
        gh.respond(unstack)
        gh.respond(initRenamed)
        renameTo("feat/a2")

        onEdt { StackFlows.renameBranch(project, state.snapshot.layers.first()) }
        awaitNotificationWith(msg("op.rename.done", "feat/a", "feat/a2"))

        assertEquals(listOf(unstack, initRenamed), gh.writes())
        assertEquals(listOf("feat/a2", "feat/b", "main"), branches())
        assertEquals("feat/b", git("branch", "--show-current").trim())
        awaitQuiet()
    }

    fun `test a pushed branch is renamed on GitHub and the local one follows the new name`() {
        val state = loadStack()
        pushFeatA()
        git("branch", "--set-upstream-to=origin/feat/a", "feat/a")
        val api = listOf("api", "--method", "POST", "repos/acme/shop/branches/feat/a/rename", "-f", "new_name=feat/a2")
        gh.respond(unstack)
        gh.respond(api)
        gh.respond(initRenamed)
        renameTo("feat/a2")

        onEdt { StackFlows.renameBranch(project, state.snapshot.layers.first()) }
        awaitNotificationWith(msg("op.rename.done", "feat/a", "feat/a2"))

        assertEquals(listOf(unstack, api, initRenamed), writes())
        assertEquals("origin", gitConfig("branch.feat/a2.remote"))
        assertEquals("refs/heads/feat/a2", gitConfig("branch.feat/a2.merge"))
        assertEquals(1, gitExit("rev-parse", "--verify", "--quiet", "refs/remotes/origin/feat/a"))
        awaitQuiet()
    }

    fun `test if GitHub refuses, the stack is started again as it was`() {
        val state = loadStack()
        pushFeatA()
        val api = listOf("api", "--method", "POST", "repos/acme/shop/branches/feat/a/rename", "-f", "new_name=feat/a2")
        val initOriginal = listOf("stack", "init", "--base", "main", "feat/a", "feat/b")
        gh.respond(unstack)
        gh.respond(api, exit = 1, stderr = "HTTP 403: Resource not accessible by integration\n")
        gh.respond(initOriginal)
        renameTo("feat/a2")

        onEdt { StackFlows.renameBranch(project, state.snapshot.layers.first()) }
        awaitNotificationWith(msg("operation.failed", msg("op.rename", "feat/a")))
        awaitIdle()

        assertEquals(listOf(unstack, api, initOriginal), writes())
        assertEquals(listOf("feat/a", "feat/b", "main"), branches())
        awaitQuiet()
    }
}
