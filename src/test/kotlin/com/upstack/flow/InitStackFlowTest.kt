package com.upstack.flow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.upstack.actions.StackFlows
import com.upstack.stack.AddBranch
import com.upstack.stack.AddBranchKind
import com.upstack.stack.StackState
import com.upstack.ui.InitStackDialog
import java.util.concurrent.atomic.AtomicReference

/** *Create Stack…*: las capas se autocompletan con las ramas del proyecto, como en *Add Layer…*. */
class InitStackFlowTest : FlowTestCase() {

    /** En main, fuera de toda pila, con [name] solo en origin. */
    private fun onMainWithOnOrigin(name: String) {
        gh.respond(VIEW, exit = 2, stderr = "current branch \"main\" is not part of a stack")
        awaitState<StackState.NotInStack>()
        git("update-ref", "refs/remotes/origin/$name", "main")
        repository.update()
        waitUntil("Git4Idea sees origin/$name") { repository.branches.remoteBranches.any { it.name == "origin/$name" } }
    }

    fun `test the layers field suggests the branches of the project`() {
        onMainWithOnOrigin("feat/old")
        val suggested = AtomicReference<List<AddBranch>>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<InitStackDialog>(InitStackDialog::class.java) {
            override fun doIntercept(component: InitStackDialog) {
                suggested.set(component.suggestions)
                component.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        })

        onEdt { StackFlows.initStack(project) }
        waitUntil("the dialog opens") { suggested.get() != null }

        assertTrue(AddBranch("feat/old", AddBranchKind.REMOTE, "origin") in suggested.get())
        assertTrue(AddBranch("main", AddBranchKind.LOCAL) in suggested.get())
        assertEquals(emptyList<List<String>>(), gh.writes())
        awaitQuiet()
    }

    fun `test a branch only on origin is tracked locally first, and gh stack init adopts it`() {
        onMainWithOnOrigin("feat/old")
        val init = listOf("stack", "init", "--base", "main", "feat/old")
        gh.respond(init)
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<InitStackDialog>(InitStackDialog::class.java) {
            override fun doIntercept(component: InitStackDialog) {
                component.enterNames("feat/old")
                assertNull(component.doValidate())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })

        onEdt { StackFlows.initStack(project) }
        awaitNotificationWith(msg("op.init.done", "feat/old", "main"))

        assertEquals(listOf(init), gh.writes())
        // Sin ella, gh stack init habria creado otra feat/old desde main y el push pisaria la de origin.
        assertEquals("origin/feat/old", git("rev-parse", "--abbrev-ref", "feat/old@{upstream}").trim())
        awaitQuiet()
    }
}
