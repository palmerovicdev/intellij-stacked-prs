package com.upstack.flow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.UiInterceptors
import com.upstack.actions.LayerSelection
import com.upstack.actions.StackDataKeys
import com.upstack.stack.LocalStack
import com.upstack.stack.LocalStackEntry
import com.upstack.stack.StackState
import com.upstack.ui.CloseStackDialog
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * *Close Stack…* desde la fila de una pila en la lista, de la accion al ultimo comando, con git
 * de verdad. Dos pilas que salen de main: desde main gh-stack no sabe cual ensenar (`view` sale
 * con 6) y ninguna se podia cerrar. Las ramas se borran en un repositorio local que hace de
 * remoto (`fork`), para no salir a la red.
 */
class CloseFromListFlowTest : FlowTestCase() {

    private lateinit var fork: String

    /** Las ramas por las que paso HEAD al preparar el repositorio: no son del test. */
    private var switchesInSetUp = 0

    private val unstack = listOf("stack", "unstack")

    override fun setUp() {
        super.setUp()
        fork = createTempDirectoryWithSuffix("fork").toString()
        git("init", "--bare", "-b", "main", fork)
        git("remote", "add", "fork", fork)
        git("switch", "-c", "feat/a")
        commit("a.txt")
        git("switch", "-c", "feat/b", "main")
        commit("b.txt")
        git("switch", "main")
        git("push", "fork", "main", "feat/a", "feat/b")
        preferredRemote = "fork"
        writeStacks(listOf("feat/a"), listOf("feat/b"))
        switchesInSetUp = allSwitches().size
        repository.update()
    }

    private fun commit(file: String) {
        Files.writeString(repoDir.resolve(file), "$file\n")
        git("add", file)
        git("commit", "-m", file)
    }

    /** Las pilas de `.git/gh-stack`, todas sobre main, como las deja gh-stack. */
    private fun writeStacks(vararg stacks: List<String>) {
        val json = stacks.joinToString(",") { branches ->
            val layers = branches.joinToString(",") { """{"branch":"$it"}""" }
            """{"trunk":{"branch":"main"},"branches":[$layers]}"""
        }
        Files.writeString(repoDir.resolve(".git/gh-stack"), """{"schemaVersion":1,"stacks":[$json]}""")
    }

    /** La del menu de la fila, como la registra plugin.xml: con su texto y su descripcion. */
    private fun close(): AnAction {
        val action = ActionManager.getInstance().getAction(ACTION_ID)
        assertNotNull("$ACTION_ID (Close Stack… on a local stack row) is not registered", action)
        return action!!
    }

    /** El evento del menu con [selected] elegidas, como lo da la lista de pilas. */
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

    /** Las dos pilas desde main: gh-stack sale con 6 y la ventana las lista. */
    private fun onSharedBase(): Pair<FakeGh.Rule, List<LocalStackEntry>> {
        val several = gh.respond(VIEW, exit = 6, stderr = "branch \"main\" belongs to multiple stacks\n")
        val state = awaitState<StackState.InSeveralStacks> { it.localStacks.size == 2 }
        awaitQuiet()
        return several to state.localStacks
    }

    private fun current() = git("branch", "--show-current").trim()

    private fun branches() = git("branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }.sorted()

    private fun forkBranches() =
        git("ls-remote", "--heads", fork).lines().filter { it.isNotBlank() }.map { it.substringAfter("refs/heads/") }.sorted()

    /** Los cambios de rama de HEAD desde que acabo [setUp], del primero al ultimo. */
    private fun switches() = allSwitches().drop(switchesInSetUp)

    private fun allSwitches() = git("reflog", "--format=%gs").lines().filter { it.startsWith("checkout:") }.reversed()

    /** El dialogo de cerrar se acepta tal cual; devuelve la rama en la que estaba HEAD al abrirse. */
    private fun answerCloseDialog(accept: Boolean): AtomicReference<String?> {
        val headAtDialog = AtomicReference<String?>()
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<CloseStackDialog>(CloseStackDialog::class.java) {
            override fun doIntercept(component: CloseStackDialog) {
                headAtDialog.set(current())
                component.close(if (accept) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE)
            }
        })
        return headAtDialog
    }

    fun `test closes one of two stacks on the same base from its row`() {
        val (several, stacks) = onSharedBase()
        val stackA = stacks.single { it.stack.branches == listOf("feat/a") }
        val action = close()

        val event = update(action, listOf(stackA))
        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)
        assertEquals(msg("action.Upstack.CloseLocalStack.text"), event.presentation.text)
        val commands = listOf(
            "git checkout feat/a",
            "gh stack unstack",
            "gh pr close PR",
            "git push REMOTE --delete feat/a",
            "git switch main",
            "git branch -D feat/a",
        )
        assertEquals(msg("help.close.local", "feat/a") + " (" + commands.joinToString(", ") + ")", event.presentation.description)

        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        // Retenido: mientras corre, gh-stack deja de seguir la pila (aqui lo hace el test).
        val unstackRule = gh.respond(unstack, hold = true)
        gh.respond(listOf("pr", "close", Samples.URL_A))
        val headAtDialog = answerCloseDialog(accept = true)

        onEdt {
            // Desde feat/a gh-stack ya sabe que pila es.
            several.remove()
            gh.respond(VIEW, stdout = Samples.VIEW_ONE_LAYER)
            action.actionPerformed(event)
        }
        waitUntil({ "unstack runs; gh: ${gh.calls()}; ${describeNotifications()}" }) { unstack in gh.calls() }
        // Deshace la pila desde su capa, no desde main.
        assertEquals("feat/a", current())
        writeStacks(listOf("feat/b"))
        unstackRule.release()

        awaitNotificationWith(msg("op.close.done", "main ← feat/a"))
        awaitIdle()
        awaitQuiet()

        assertEquals("feat/a", headAtDialog.get())
        assertEquals(listOf(unstack, listOf("pr", "close", Samples.URL_A)), gh.writes())
        // Primero a feat/a para abrirla; al borrarla, de vuelta a main.
        assertEquals(listOf("checkout: moving from main to feat/a", "checkout: moving from feat/a to main"), switches())
        assertEquals("main", current())
        // feat/b y su pila, intactas.
        assertEquals(listOf("feat/b", "main"), branches())
        assertEquals(listOf("feat/b", "main"), forkBranches())
        assertEquals(listOf(listOf("feat/b")), service.localStacks(repository).map { it.stack.branches })
    }

    fun `test cancelling the dialog closes nothing and stays on the layer`() {
        val (several, stacks) = onSharedBase()
        val stackA = stacks.single { it.stack.branches == listOf("feat/a") }
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        val headAtDialog = answerCloseDialog(accept = false)

        val action = close()
        val event = update(action, listOf(stackA))
        onEdt {
            several.remove()
            gh.respond(VIEW, stdout = Samples.VIEW_ONE_LAYER)
            action.actionPerformed(event)
        }
        waitUntil("the dialog is shown") { headAtDialog.get() != null }
        awaitIdle()
        awaitQuiet()

        assertEquals("feat/a", headAtDialog.get())
        assertEquals(emptyList<List<String>>(), gh.writes())
        assertEquals(listOf("checkout: moving from main to feat/a"), switches())
        assertEquals("feat/a", current())
        assertEquals(listOf("feat/a", "feat/b", "main"), branches())
        assertEquals(listOf("feat/a", "feat/b", "main"), forkBranches())
    }

    fun `test close is offered on one stack with branches, not on a stale one, several or a layer`() {
        val live = LocalStackEntry(LocalStack("main", listOf("feat/a")), listOf("feat/a"), emptyList())
        val other = LocalStackEntry(LocalStack("main", listOf("feat/b")), listOf("feat/b"), emptyList())
        val stale = LocalStackEntry(LocalStack("main", listOf("feat/old")), emptyList(), emptyList())
        val action = close()

        assertTrue(update(action, listOf(live)).presentation.isVisible)
        // Lo de las pilas sin ramas es Forget / Recreate.
        assertFalse(update(action, listOf(stale)).presentation.isVisible)
        // Con varias elegidas solo se ofrece lo que sirve para todas.
        assertFalse(update(action, listOf(live, other)).presentation.isVisible)

        // En el menu de una capa, nada de la pila entera.
        gh.respond(VIEW, stdout = Samples.VIEW_TWO_LAYERS)
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        val loaded = awaitState<StackState.Loaded> { !it.detailsLoading }
        val onLayer = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(StackDataKeys.LAYER, LayerSelection(loaded, loaded.snapshot.layers.first()))
                .build(),
        )
        onEdt { action.update(onLayer) }
        assertFalse(onLayer.presentation.isVisible)
        awaitQuiet()
    }

    fun `test a stack without a branch of its own cannot be closed from its row and says why`() {
        // Su unica capa es la base de otra pila: desde ella gh-stack tampoco sabe cual ensenar.
        val blocked = LocalStackEntry(LocalStack("main", listOf("feat/a")), listOf("feat/a"), emptyList(), sharedBranches = setOf("feat/a"))
        // Solo en el remoto: no hay capa a la que cambiar.
        val remote = LocalStackEntry(LocalStack("main", listOf("feat/r")), emptyList(), listOf("feat/r"))
        val action = close()

        val onBlocked = update(action, listOf(blocked))
        assertTrue(onBlocked.presentation.isVisible)
        assertFalse(onBlocked.presentation.isEnabled)
        assertEquals(msg("help.close.local.blocked"), onBlocked.presentation.description)

        val onRemote = update(action, listOf(remote))
        assertTrue(onRemote.presentation.isVisible)
        assertFalse(onRemote.presentation.isEnabled)
        assertEquals(msg("help.close.local.remote"), onRemote.presentation.description)
    }

    private companion object {
        const val ACTION_ID = "Upstack.CloseLocalStack"
    }
}
