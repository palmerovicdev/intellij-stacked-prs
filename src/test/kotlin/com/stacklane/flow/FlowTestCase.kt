package com.stacklane.flow

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.UiInterceptors
import com.stacklane.StacklaneBundle.message
import com.stacklane.settings.StacklaneProjectSettings
import com.stacklane.settings.StacklaneSettings
import com.stacklane.stack.StackService
import com.stacklane.stack.StackState
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Job
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Base de los tests de flujo: un proyecto con un repositorio git de verdad (en disco, con sus
 * remotos) y `gh` sustituido por [FakeGh]. git es el real: las preguntas de rerere y los planes
 * de las pilas sin ramas lo ejecutan.
 *
 * El test corre fuera del EDT: [StackService] salta al EDT para guardar documentos y para los
 * dialogos, y un test en el EDT esperando a la operacion se bloquearia.
 */
abstract class FlowTestCase : HeavyPlatformTestCase() {

    protected lateinit var gh: FakeGh
    protected lateinit var repoDir: Path
    protected lateinit var repository: GitRepository
    protected lateinit var service: StackService

    /** Cada notificacion de Stacklane, en el orden en que salio. */
    protected val notifications = CopyOnWriteArrayList<Notification>()

    private var previousGhPath = ""

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        // La IDEA instalada es la unificada (IU): al abrir el proyecto, una actividad de inicio
        // del modulo ultimate (la licencia) no se puede crear en tests y lo registra como error.
        // Solo se ignoran los errores de ese modulo; cualquier otro sigue fallando el test.
        LoggedErrorProcessor.executeWith<Exception>(IgnoreUltimateStartup) { super.setUp() }
        gh = FakeGh(createTempDirectoryWithSuffix("gh"))
        // Sin pila hasta que el test diga otra cosa: el primer refresco no debe fallar.
        gh.fallback(VIEW, exit = 2, stderr = "current branch is not part of a stack")

        val settings = StacklaneSettings.getInstance()
        previousGhPath = settings.ghPath
        settings.ghPath = gh.executable.toString()

        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Stacklane") notifications += notification
                }
            },
        )

        repoDir = Path.of(requireNotNull(project.basePath))
        Files.createDirectories(repoDir)
        git("init", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
        git("remote", "add", "origin", "git@github.com:acme/shop.git")
        // Dos remotos sin remote.pushDefault: lo que hace preguntar a gh-stack.
        git("remote", "add", "upstream", "git@github.com:acme-upstream/shop.git")
        Files.writeString(repoDir.resolve("README.md"), "shop\n")
        git("add", "README.md")
        git("commit", "-m", "init")

        repository = registerRepository()
        service = StackService.getInstance(project)
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            UiInterceptors.clear()
            StacklaneSettings.getInstance().ghPath = previousGhPath
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Git4Idea descubre el repositorio por los mapeos de VCS, en segundo plano. */
    private fun registerRepository(): GitRepository {
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoDir))
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        ProjectLevelVcsManager.getInstance(project).directoryMappings = listOf(VcsDirectoryMapping(root.path, "Git"))
        val manager = GitRepositoryManager.getInstance(project)
        var found: GitRepository? = null
        waitUntil("Git4Idea registers $repoDir") {
            found = manager.getRepositoryForRoot(root)
            found != null && manager.repositories.isNotEmpty()
        }
        return requireNotNull(found)
    }

    // ---------------------------------------------------------------- git

    protected fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repoDir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")}: $output" }
        return output
    }

    /** La configuracion del repositorio, o null si la clave no esta. */
    protected fun gitConfig(key: String): String? = try {
        git("config", "--get", key).trim()
    } catch (_: IllegalStateException) {
        null
    }

    /** Lo que deja gh-stack mientras un rebase espera `--continue` o `--abort`. */
    protected fun leaveStackRebaseInProgress() {
        Files.writeString(repoDir.resolve(".git/gh-stack-rebase-state"), "{}")
    }

    // ---------------------------------------------------------------- esperas

    protected fun waitUntil(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) =
        waitUntil({ what }, timeoutMs, condition)

    /** [what] se calcula al agotar la espera: lo que habia entonces, no al empezar. */
    protected fun waitUntil(what: () -> String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out: ${what()}")
            Thread.sleep(POLL_MS)
        }
    }

    /** Pide un refresco y espera a que la ventana ensene un estado que cumpla [matches]. */
    protected inline fun <reified T : StackState> awaitState(crossinline matches: (T) -> Boolean = { true }): T {
        service.requestRefresh()
        var state: StackState? = null
        waitUntil({ "state ${T::class.simpleName}, last: ${service.state.value}" }) {
            state = service.state.value
            (state as? T)?.let(matches) == true
        }
        return state as T
    }

    /** La primera notificacion que cumple [matches], esperandola si aun no ha salido. */
    protected fun awaitNotification(what: String, matches: (Notification) -> Boolean): Notification {
        var found: Notification? = null
        waitUntil({ "notification: $what; got: ${describeNotifications()}; gh: ${gh.writes()}; running: ${service.running.value}" }) {
            found = notifications.firstOrNull(matches)
            found != null
        }
        return requireNotNull(found)
    }

    protected fun awaitNotificationWith(content: String): Notification =
        awaitNotification(content) { it.content == content || it.title == content }

    /** Espera a que [job] termine y no quede ninguna escritura en marcha. */
    protected fun awaitIdle(job: Job? = null) {
        if (job != null) waitUntil("operation finishes") { job.isCompleted }
        waitUntil({ "no write running, still: ${service.running.value}" }) { service.running.value == null }
    }

    /**
     * Espera a que [gh] lleve un rato sin llamadas: el refresco que pide cada escritura al
     * terminar sale tras una pausa, y si su `gh` sigue vivo al cerrar el test, falla por hilo
     * perdido.
     */
    protected fun awaitQuiet() {
        var last = -1
        var since = System.currentTimeMillis()
        waitUntil("gh stays quiet") {
            val count = gh.calls().size
            if (count != last) {
                last = count
                since = System.currentTimeMillis()
            }
            System.currentTimeMillis() - since > QUIET_MS
        }
    }

    protected fun describeNotifications(): String =
        notifications.joinToString(" | ") { "[${it.type}] ${it.title}: ${it.content}" }.ifEmpty { "<none>" }

    /** Ejecuta [block] en el EDT, donde corren las acciones. */
    protected fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        EdtTestUtil.runInEdtAndWait<Throwable> { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Pulsa el boton [text] de [notification]. */
    protected fun press(notification: Notification, text: String) {
        val action = notification.actions.firstOrNull { it.templateText == text }
        assertNotNull("no action “$text” in ${notification.actions.map { it.templateText }}", action)
        onEdt { Notification.fire(notification, action!!, null) }
    }

    // ---------------------------------------------------------------- dialogos

    /** Las preguntas sí/no/cancelar (rerere, publicar listo) contestan [answer]. */
    protected fun answerMessages(answer: Int) {
        TestDialogManager.setTestDialog(TestDialog { answer }, testRootDisposable)
    }

    /** El siguiente dialogo (el que elige remoto, por ejemplo) se acepta tal cual, o se cancela. */
    protected fun answerNextDialog(accept: Boolean) {
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
            override fun doIntercept(component: DialogWrapper) {
                component.close(if (accept) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE)
            }
        })
    }

    protected var preferredRemote: String?
        get() = StacklaneProjectSettings.getInstance(project).remote
        set(value) {
            StacklaneProjectSettings.getInstance(project).remote = value
        }

    protected fun msg(key: String, vararg params: Any): String = message(key, *params)

    private object IgnoreUltimateStartup : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> =
            if (generateSequence(t) { it.cause }.any { "[Plugin: com.intellij.modules.ultimate]" in it.message.orEmpty() }) Action.NONE
            else super.processError(category, message, details, t)
    }

    companion object {
        val VIEW = listOf("stack", "view", "--json")
        const val TIMEOUT_MS = 20_000L
        private const val POLL_MS = 20L

        // Mas que la pausa antes de cada refresco (250 ms en StackService).
        private const val QUIET_MS = 600L
    }
}
