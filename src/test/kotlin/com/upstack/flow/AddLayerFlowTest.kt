package com.upstack.flow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.upstack.actions.StackFlows
import com.upstack.stack.StackState
import com.upstack.ui.AddLayerDialog
import java.nio.file.Files

/** *Add Layer…*: una capa encima con una rama nueva, o con una del proyecto que gh-stack adopta. */
class AddLayerFlowTest : FlowTestCase() {

    /** main ← feat/a ← feat/b, sin publicar, en feat/b: la de arriba. */
    private fun loadStack() {
        for (name in listOf("feat/a", "feat/b")) {
            git("switch", "-c", name)
            Files.writeString(repoDir.resolve(name.replace('/', '-') + ".txt"), "$name\n")
            git("add", ".")
            git("commit", "-m", name)
        }
        gh.respond(VIEW, stdout = Samples.VIEW_UNPUBLISHED)
        awaitState<StackState.Loaded>()
    }

    /** [name] solo en origin, como tras un fetch de la rama de otro. */
    private fun onlyOnOrigin(name: String) {
        git("update-ref", "refs/remotes/origin/$name", "main")
        repository.update()
        waitUntil("Git4Idea sees origin/$name") { repository.branches.remoteBranches.any { it.name == "origin/$name" } }
    }

    /** El dialogo se acepta con [name] en su campo. */
    private fun addAs(name: String) {
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<AddLayerDialog>(AddLayerDialog::class.java) {
            override fun doIntercept(component: AddLayerDialog) {
                component.enterName(name)
                assertNull(component.doValidate())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })
    }

    private fun branches() = git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    fun `test a new name is created by gh stack add alone`() {
        loadStack()
        val add = listOf("stack", "add", "feat/c")
        gh.respond(add)
        addAs("feat/c")

        onEdt { StackFlows.addLayer(project) }
        awaitNotificationWith(msg("op.add.done", "feat/c"))

        assertEquals(listOf(add), gh.writes())
        awaitQuiet()
    }

    fun `test a branch only on origin is tracked locally first, and gh stack adopts it`() {
        loadStack()
        onlyOnOrigin("feat/old")
        val add = listOf("stack", "add", "feat/old")
        gh.respond(add)
        addAs("feat/old")

        onEdt { StackFlows.addLayer(project) }
        awaitNotificationWith(msg("op.add.done", "feat/old"))

        assertEquals(listOf(add), gh.writes())
        // Sin ella, gh stack add habria creado otra feat/old desde feat/b y el push pisaria la de origin.
        assertEquals("origin/feat/old", git("rev-parse", "--abbrev-ref", "feat/old@{upstream}").trim())
        awaitQuiet()
    }

    fun `test if gh stack add fails the local branch created for it is deleted`() {
        loadStack()
        onlyOnOrigin("feat/old")
        gh.respond(listOf("stack", "add", "feat/old"), exit = 1, stderr = "boom\n")
        addAs("feat/old")

        onEdt { StackFlows.addLayer(project) }
        awaitNotificationWith(msg("operation.failed", msg("op.add")))
        awaitIdle()

        assertFalse("feat/old" in branches())
        assertEquals("feat/b", git("branch", "--show-current").trim())
        awaitQuiet()
    }
}
