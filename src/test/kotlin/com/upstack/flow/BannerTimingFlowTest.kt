package com.upstack.flow

import com.upstack.gh.RebaseScope
import com.upstack.stack.GhCall
import com.upstack.stack.Operation
import com.upstack.stack.StackState
import com.upstack.stack.rebaseStopShown
import com.upstack.stack.repo
import com.upstack.stack.stackBannersWait
import kotlinx.coroutines.flow.combine
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Las bandas de encima de la lista cambian a la vez que lo que cuentan: tras una escritura, la que
 * se va y la que llega salen del mismo momento, nunca dos que se contradicen. El refresco que pide
 * cada escritura al terminar se retiene (`hold`) para mirar lo que hay justo antes de que llegue.
 */
class BannerTimingFlowTest : FlowTestCase() {

    private val rebase = listOf("stack", "rebase")
    private val details = listOf("api", "graphql")

    private fun views(): Int = gh.calls().count { it == VIEW }

    /** Lo que mira la ventana en disco: el fichero que deja gh-stack mientras el rebase espera. */
    private fun stillStopped(): Boolean = service.stackRebaseInProgress(repository)

    /** Carga [view] y deja la siguiente lectura retenida: la que pide la escritura del test al terminar. */
    private fun loadThenHoldNextView(view: String, next: String, matches: (StackState.Loaded) -> Boolean): Pair<StackState.Loaded, FakeGh.Rule> {
        val first = gh.respond(VIEW, stdout = view)
        gh.respond(details, prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        val loaded = awaitState<StackState.Loaded> { !it.detailsLoading && matches(it) }
        awaitQuiet()
        val held = gh.respond(VIEW, stdout = next, hold = true)
        first.remove()
        return loaded to held
    }

    fun `test after a rebase the push banner comes with the rebased stack, never next to the rebase one`() {
        git("config", "gh-stack.rerere-declined", "true")
        // Las ramas en local y sin subir: lo que hace salir la banda de subir.
        git("branch", "feat/a")
        git("branch", "feat/b")
        repository.update()
        val (loaded, held) = loadThenHoldNextView(VIEW_OUTDATED, Samples.VIEW_TWO_LAYERS) { it.snapshot.outdatedLayers.isNotEmpty() }
        val root = loaded.repo.root
        gh.respond(rebase)

        // Lo que pinta la ventana: el estado y lo pendiente de subir, cada vez que cambia uno de los dos.
        val both = CopyOnWriteArrayList<String>()
        val watcher = service.launch {
            combine(service.state, service.pushPending) { state, pending -> state to pending[root] }.collect { (state, pending) ->
                val outdated = (state as? StackState.Loaded)?.snapshot?.outdatedLayers.orEmpty()
                if (outdated.isNotEmpty() && !pending.isNullOrEmpty()) both += "needs rebase ${outdated.map { it.branch }} + push $pending"
            }
        }
        try {
            val before = views()
            service.rebase(RebaseScope.STACK, repository)
            awaitNotificationWith(msg("op.rebase.done"))
            awaitIdle()
            waitUntil("the rebased stack is being read") { views() > before }

            // Mientras se lee, la ventana sigue con la pila de antes y aun no ofrece subirla.
            val stale = service.state.value as StackState.Loaded
            assertEquals(listOf("feat/b"), stale.snapshot.outdatedLayers.map { it.branch })
            assertNull("push pending before the rebased stack is read", service.pushPending.value[root])

            held.release()
            waitUntil({ "push pending after the read, got ${service.pushPending.value}" }) {
                service.pushPending.value[root] == setOf("feat/a", "feat/b")
            }
            val fresh = service.state.value as StackState.Loaded
            assertEquals(emptyList<String>(), fresh.snapshot.outdatedLayers.map { it.branch })
            assertEquals(listOf("feat/a", "feat/b"), service.unpushedLayers(fresh))
            assertEquals(emptyList<String>(), both)
        } finally {
            watcher.cancel()
            held.release()
        }
        awaitQuiet()
    }

    fun `test after continue the stop banner goes before the refresh lands`() {
        leaveStackRebaseInProgress()
        val (stopped, held) = loadThenHoldNextView(Samples.VIEW_TWO_LAYERS, Samples.VIEW_TWO_LAYERS) { it.repo.stackRebaseInProgress }
        // Mientras sigue parado, su banda sale y lo demas de la pila espera.
        assertNotNull(stopped.rebaseStopShown(stillStopped()))
        assertTrue(stopped.stackBannersWait(stillStopped()))
        val continueCall = listOf("stack", "rebase", "--continue")
        val cont = gh.respond(continueCall, hold = true)

        try {
            val before = views()
            val job = service.continueRebase()
            waitUntil("continue reaches gh") { continueCall in gh.writes() }
            // gh-stack borra su fichero al terminar el `--continue`.
            Files.delete(repoDir.resolve(".git/gh-stack-rebase-state"))
            cont.release()
            awaitIdle(job)
            waitUntil("the stack is being read again") { views() > before }

            val stale = service.state.value
            assertTrue("the refresh has not landed yet: $stale", stale.repo?.stackRebaseInProgress == true)
            assertNull("stop banner after a finished continue", stale.rebaseStopShown(stillStopped()))
            // Lo que dice esa lectura es de antes del rebase: lo demas espera al refresco.
            assertTrue(stale.stackBannersWait(stillStopped()))

            held.release()
            val fresh = awaitState<StackState.Loaded> { !it.repo.stackRebaseInProgress && !it.detailsLoading }
            assertNull(fresh.rebaseStopShown(stillStopped()))
            assertFalse(fresh.stackBannersWait(stillStopped()))
        } finally {
            cont.release()
            held.release()
        }
        awaitQuiet()
    }

    fun `test a rebase that stops sets the other banners aside before the refresh lands`() {
        git("config", "gh-stack.rerere-declined", "true")
        val (_, held) = loadThenHoldNextView(VIEW_OUTDATED, VIEW_OUTDATED) { it.snapshot.outdatedLayers.isNotEmpty() }
        val stop = gh.respond(rebase, exit = 3, stderr = "CONFLICT (content): Merge conflict in README.md\n", hold = true)

        try {
            val before = views()
            service.rebase(RebaseScope.STACK, repository)
            waitUntil("rebase reaches gh") { rebase in gh.writes() }
            leaveStackRebaseInProgress()
            stop.release()
            awaitNotificationWith(msg("conflict.rebase"))
            awaitIdle()
            waitUntil("the stopped stack is being read") { views() > before }

            val stale = service.state.value
            assertFalse("the refresh has not landed yet: $stale", stale.repo?.stackRebaseInProgress == true)
            // La banda de rebasar es de antes del rebase: no se queda junto a la del rebase parado.
            assertTrue("other banners wait while the stack rebase is stopped", stale.stackBannersWait(stillStopped()))
            assertNull(stale.rebaseStopShown(stillStopped()))

            held.release()
            val fresh = awaitState<StackState.Loaded> { it.repo.stackRebaseInProgress && !it.detailsLoading }
            assertNotNull(fresh.rebaseStopShown(stillStopped()))
            assertTrue(fresh.stackBannersWait(stillStopped()))
        } finally {
            stop.release()
            held.release()
        }
        awaitQuiet()
    }

    fun `test git events during a write do not read the stack, the end of the write does`() {
        awaitQuiet()
        val ready = listOf("pr", "ready", "1")
        val write = gh.respond(ready, hold = true)

        try {
            val job = service.execute(Operation("Held", listOf(GhCall(ready)), repository))
            waitUntil("the write reaches gh") { ready in gh.writes() }
            val before = views()
            git("branch", "feat/x")
            repository.update()
            awaitQuiet()
            assertEquals("gh stack view during the write: ${gh.calls()}", before, views())

            write.release()
            awaitIdle(job)
            waitUntil("the end of the write reads the stack") { views() > before }
        } finally {
            write.release()
        }

        // Sin escritura en marcha, el mismo aviso de git si lee la pila.
        awaitQuiet()
        val idle = views()
        git("branch", "feat/y")
        repository.update()
        waitUntil("a git event with no write running reads the stack") { views() > idle }
        awaitQuiet()
    }

    private companion object {
        /** [Samples.VIEW_TWO_LAYERS] con `feat/b` atras de `feat/a`: la banda de rebasar. */
        val VIEW_OUTDATED = Samples.VIEW_TWO_LAYERS.replace(
            """"isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false""",
            """"isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":true""",
        )
    }
}
