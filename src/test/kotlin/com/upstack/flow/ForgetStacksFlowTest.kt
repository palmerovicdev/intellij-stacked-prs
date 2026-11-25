package com.upstack.flow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.TestActionEvent
import com.upstack.actions.StackDataKeys
import com.upstack.stack.LocalStack
import com.upstack.stack.LocalStackEntry
import com.upstack.stack.StackState
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * *Forget Stack* sobre varias pilas sin ramas elegidas a la vez en la lista, de la accion al
 * ultimo comando, con git de verdad: cada pila se olvida desde una rama temporal y se vuelve.
 */
class ForgetStacksFlowTest : FlowTestCase() {

    private val unstack = listOf("stack", "unstack", "--local")

    /** Las del menu de la lista, como las registra plugin.xml: con su texto y su descripcion. */
    private fun forget(): AnAction = ActionManager.getInstance().getAction("Upstack.ForgetStack")

    private fun recreate(): AnAction = ActionManager.getInstance().getAction("Upstack.RecreateStack")

    /** Las pilas de `.git/gh-stack`, como las deja gh-stack, y como las lee la ventana. */
    private fun localStacks(vararg stacks: List<String>): List<LocalStackEntry> {
        val json = stacks.joinToString(",") { branches ->
            val layers = branches.joinToString(",") { """{"branch":"$it"}""" }
            """{"trunk":{"branch":"main"},"branches":[$layers]}"""
        }
        Files.writeString(repoDir.resolve(".git/gh-stack"), """{"schemaVersion":1,"stacks":[$json]}""")
        return awaitState<StackState.NotInStack> { it.localStacks.size == stacks.size }.localStacks
    }

    /** El evento de una accion del menu con [selected] elegidas, como lo da la lista de pilas. */
    private fun event(action: AnAction, selected: List<LocalStackEntry>): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(StackDataKeys.LOCAL_STACKS, selected)
            .apply { selected.singleOrNull()?.let { add(StackDataKeys.LOCAL_STACK, it) } }
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    private fun update(action: AnAction, selected: List<LocalStackEntry>): AnActionEvent =
        event(action, selected).also { e -> onEdt { action.update(e) } }

    private fun branches() = git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

    /** Los cambios de rama de HEAD, del primero al ultimo. */
    private fun switches() = git("reflog", "--format=%gs").lines().filter { it.startsWith("checkout:") }.reversed()

    private fun forgetCommands(temp: String) = listOf(
        "git branch $temp HEAD",
        "git switch $temp",
        "gh stack unstack --local",
        "git switch main",
        "git branch -D $temp",
    )

    fun `test several stale stacks are forgotten in one operation`() {
        val stacks = localStacks(listOf("feat/old-a"), listOf("feat/old-b", "feat/old-c"))
        assertTrue(stacks.all { it.isStale })
        gh.respond(unstack)
        val asked = CopyOnWriteArrayList<String>()
        TestDialogManager.setTestDialog(TestDialog { message -> asked += message; Messages.OK }, testRootDisposable)

        val action = forget()
        val event = update(action, stacks)
        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)
        assertEquals(msg("action.forget.several", 2), event.presentation.text)
        val description = event.presentation.description
        assertTrue(description, description.startsWith(msg("action.forget.several.description", 2)))
        assertTrue(description, description.contains((forgetCommands("feat/old-a") + forgetCommands("feat/old-b")).joinToString(", ")))

        onEdt { action.actionPerformed(event) }
        val done = msg("op.forget.several.done", 2, "main ← feat/old-a, main ← feat/old-b ← feat/old-c")
        awaitNotificationWith(done)
        awaitIdle()
        awaitQuiet()

        // Una sola confirmacion, con las dos pilas.
        assertEquals(1, asked.size)
        assertTrue(asked.single(), asked.single().contains("main ← feat/old-a") && asked.single().contains("main ← feat/old-b ← feat/old-c"))
        // Una sola operacion: un unstack por pila, en orden, y nada mas.
        assertEquals(listOf(unstack, unstack), gh.writes())
        assertEquals(listOf(done), notifications.map { it.content })
        // Cada unstack desde una rama de su pila, y de vuelta a main.
        assertEquals(
            listOf(
                "checkout: moving from main to feat/old-a",
                "checkout: moving from feat/old-a to main",
                "checkout: moving from main to feat/old-b",
                "checkout: moving from feat/old-b to main",
            ),
            switches(),
        )
        assertEquals("main", git("branch", "--show-current").trim())
        assertEquals(listOf("main"), branches())
    }

    fun `test one stale stack is forgotten as before, without asking`() {
        val stacks = localStacks(listOf("feat/old-a"))
        gh.respond(unstack)
        // Sin respuesta preparada: si se abriera una confirmacion, el test fallaria.

        val action = forget()
        val event = update(action, stacks)
        assertTrue(event.presentation.isVisible)
        assertEquals(msg("action.Upstack.ForgetStack.text"), event.presentation.text)
        assertEquals(
            msg("action.Upstack.ForgetStack.description") + " (" + forgetCommands("feat/old-a").joinToString(", ") + ")",
            event.presentation.description,
        )

        onEdt { action.actionPerformed(event) }
        val done = msg("op.forget.done", "main ← feat/old-a")
        awaitNotificationWith(done)
        awaitIdle()
        awaitQuiet()

        assertEquals(listOf(unstack), gh.writes())
        assertEquals(listOf(done), notifications.map { it.content })
        assertEquals(listOf("checkout: moving from main to feat/old-a", "checkout: moving from feat/old-a to main"), switches())
        assertEquals("main", git("branch", "--show-current").trim())
        assertEquals(listOf("main"), branches())
    }

    fun `test cancelling the confirmation forgets nothing`() {
        val stacks = localStacks(listOf("feat/old-a"), listOf("feat/old-b"))
        gh.respond(unstack)
        answerMessages(Messages.CANCEL)

        val action = forget()
        val event = update(action, stacks)
        onEdt { action.actionPerformed(event) }
        awaitIdle()
        awaitQuiet()

        assertEquals(emptyList<List<String>>(), gh.writes())
        assertEquals(emptyList<String>(), notifications.map { it.content })
        assertEquals(emptyList<String>(), switches())
        assertEquals(listOf("main"), branches())
    }

    fun `test forget is offered only when every selected stack lost its branches, and recreate only for one`() {
        val staleA = LocalStackEntry(LocalStack("main", listOf("feat/old-a")), emptyList(), emptyList())
        val staleB = LocalStackEntry(LocalStack("main", listOf("feat/old-b")), emptyList(), emptyList())
        val live = LocalStackEntry(LocalStack("main", listOf("feat/live")), listOf("feat/live"), emptyList())

        // Una pila que sigue en uso no se olvida desde aqui, ni sola ni con otras.
        assertFalse(update(forget(), listOf(staleA, live)).presentation.isVisible)
        assertFalse(update(forget(), listOf(live)).presentation.isVisible)
        assertTrue(update(forget(), listOf(staleA, staleB)).presentation.isVisible)

        // Recrear es de una sola pila.
        assertTrue(update(recreate(), listOf(staleA)).presentation.isVisible)
        assertFalse(update(recreate(), listOf(staleA, staleB)).presentation.isVisible)
        assertFalse(update(recreate(), listOf(live)).presentation.isVisible)
    }
}
