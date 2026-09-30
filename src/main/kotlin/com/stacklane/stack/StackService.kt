package com.stacklane.stack

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.stacklane.Notifier
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCli
import com.stacklane.gh.GhCommands
import com.stacklane.gh.GhExit
import com.stacklane.gh.GhNotFoundException
import com.stacklane.gh.GhResult
import com.stacklane.gh.Tool
import com.stacklane.settings.StacklaneProjectSettings
import com.stacklane.ui.RemoteChooser
import com.stacklane.ui.StackToolWindow
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Una invocacion de `gh` (o de `git`, ver [Tool]). [acceptsRemote]: el comando admite
 * `--remote` (submit, sync, rebase).
 */
data class GhCall(val args: List<String>, val acceptsRemote: Boolean = false, val tool: Tool = Tool.GH)

/** Una operacion de escritura: uno o varios comandos en orden; el primero que falla corta. */
class Operation(
    val title: @Nls String,
    val calls: List<GhCall>,
    /** null: el repositorio que muestra la ventana. */
    val repository: GitRepository? = null,
    /** Para PRs de un repositorio que no esta abierto en el proyecto. */
    val workDir: Path? = null,
    val successMessage: @Nls String? = null,
    val onSuccess: (suspend () -> Unit)? = null,
    /** Trata un fallo concreto; true si ya se aviso al usuario y sobra el aviso generico. */
    val onFailure: ((GhResult) -> Boolean)? = null,
    /**
     * Lo que hay que ejecutar si la secuencia se corta, segun cuantas llamadas terminaron bien
     * (ver [Plan]). Corre siempre, tambien al cancelar.
     */
    val cleanup: (completed: Int) -> List<GhCall> = { emptyList() },
)

/**
 * El estado de la pila del proyecto y todas las escrituras.
 *
 * La lectura es `gh stack view --json` (mas una consulta GraphQL para draft, labels, review
 * y CI) y se repite cuando git cambia de verdad: rama, commits o refs. Las escrituras son
 * los mismos comandos `gh` que se usarian en la terminal, de una en una, con progreso,
 * registro en la pestana Log y refresco del IDE al terminar.
 */
@Service(Service.Level.PROJECT)
class StackService(private val project: Project, private val cs: CoroutineScope) {

    private val _state = MutableStateFlow<StackState>(StackState.Loading)
    val state: StateFlow<StackState> = _state.asStateFlow()

    private val _running = MutableStateFlow<String?>(null)

    /** Titulo de la escritura en curso, o null. Nunca hay dos a la vez. */
    val running: StateFlow<String?> = _running.asStateFlow()

    val log = StackLog()

    // CONFLATED: cien eventos seguidos de git se quedan en un refresco.
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    @Volatile
    private var lastFingerprint: String? = null

    init {
        cs.launch {
            refreshRequests.receiveAsFlow().collectLatest {
                delay(REFRESH_DEBOUNCE_MS)
                refreshNow()
            }
        }
        requestRefresh()
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(block = block)

    // ---------------------------------------------------------------- repositorios

    fun repositories(): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    /** El repositorio que se muestra: el elegido, el de la raiz del proyecto o el primero. */
    fun repository(): GitRepository? {
        val all = repositories()
        val chosen = StacklaneProjectSettings.getInstance(project).repositoryRoot
        return all.firstOrNull { it.root.path == chosen }
            ?: all.firstOrNull { it.root.path == project.basePath }
            ?: all.firstOrNull()
    }

    fun selectRepository(repository: GitRepository) {
        StacklaneProjectSettings.getInstance(project).repositoryRoot = repository.root.path
        _state.value = StackState.Loading
        requestRefresh()
    }

    // Entre los ya conocidos, sin getRepositoryForRoot: ese actualiza el repositorio de forma
    // sincrona y la plataforma lo prohibe en el EDT, que es desde donde se llama esto.
    fun repositoryFor(ref: RepoRef): GitRepository? = repositories().firstOrNull { it.root == ref.root }

    /** El repositorio abierto cuyo remoto apunta a [github], si lo hay. */
    fun repositoryFor(github: GitHubRepo): GitRepository? =
        repositories().firstOrNull { repository -> remotesOf(repository).any { it.sameAs(github) } }

    fun githubOf(repository: GitRepository): GitHubRepo? {
        val preferred = StacklaneProjectSettings.getInstance(project).remote
        return repository.remotes
            .sortedBy { remote ->
                when (remote.name) {
                    preferred -> 0
                    "origin" -> 1
                    "upstream" -> 2
                    else -> 3
                }
            }
            .firstNotNullOfOrNull { remote -> remote.urls.firstNotNullOfOrNull(GitHubRepo::fromRemoteUrl) }
    }

    private fun remotesOf(repository: GitRepository): List<GitHubRepo> =
        repository.remotes.flatMap { it.urls }.mapNotNull(GitHubRepo::fromRemoteUrl)

    internal fun onRepositoryChanged(repository: GitRepository) {
        if (repository.root != repository()?.root) return
        // Git4Idea avisa tambien de cambios que no mueven la pila (config, indice...): solo
        // se refresca si cambio la rama, un commit o alguna ref.
        if (fingerprint(repository) != lastFingerprint) requestRefresh()
    }

    private fun fingerprint(repository: GitRepository): String = buildString {
        append(repository.root.path).append('|')
        append(repository.currentBranchName).append('|')
        append(repository.currentRevision).append('|')
        append(repository.state)
        val branches = repository.branches
        (branches.localBranches + branches.remoteBranches).sortedBy { it.fullName }.forEach { branch ->
            append('|').append(branch.fullName).append('=').append(branches.getHash(branch)?.asString())
        }
    }

    // ---------------------------------------------------------------- lectura

    private suspend fun refreshNow() {
        val repository = repository()
        if (repository == null) {
            _state.value = StackState.NoRepository
            return
        }
        lastFingerprint = fingerprint(repository)
        val ref = repoRef(repository)
        try {
            val result = GhCli.run(ref.root.toNioPath(), GhCommands.view())
            val branch = repository.currentBranchName
            when {
                result.ok -> showSnapshot(ref, StackJson.parseView(result.stdout))
                // En `view --json` el 6 solo significa «la rama esta en varias pilas» (view.go de la v0.1.1).
                result.exitCode == GhExit.DISAMBIGUATE && branch != null ->
                    _state.value = StackState.InSeveralStacks(ref, branch, localStacks(repository))
                result.exitCode == GhExit.NOT_IN_STACK || result.exitCode == GhExit.DISAMBIGUATE ->
                    _state.value = StackState.NotInStack(ref, branch, localStacks(repository))
                result.isMissingExtension -> _state.value = StackState.ExtensionMissing
                else -> _state.value = StackState.Failed(ref, result.errorText.ifEmpty { "exit code ${result.exitCode}" })
            }
        } catch (_: GhNotFoundException) {
            _state.value = StackState.GhMissing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = StackState.Failed(ref, e.message ?: e.toString())
        }
    }

    private suspend fun showSnapshot(ref: RepoRef, snapshot: StackSnapshot) {
        val numbers = snapshot.layers.mapNotNull { it.pr?.number }.toSet()
        // Mientras llegan los datos nuevos se ensenan los anteriores: sin parpadeo.
        val previous = (_state.value as? StackState.Loaded)?.takeIf { it.repo.root == ref.root }
        val cached = previous?.details.orEmpty().filterKeys { it in numbers }
        val loaded = StackState.Loaded(ref, snapshot, cached, detailsLoading = numbers.isNotEmpty(), detailsError = null)
        _state.value = loaded
        if (numbers.isEmpty()) return

        val github = snapshot.layers.firstNotNullOfOrNull { layer -> layer.pr?.url?.let(GitHubRepo::fromPullRequestUrl) }
            ?: ref.github
        if (github == null) {
            finishDetails(snapshot, emptyMap(), message("details.no.github"))
            return
        }
        val result = GhCli.run(ref.root.toNioPath(), GhCommands.prDetails(github, numbers))
        val details = StackJson.parsePrDetails(result.stdout)
        val error = if (details.isEmpty()) result.errorText.lineSequence().firstOrNull { it.isNotBlank() }
            ?: message("details.failed") else null
        finishDetails(snapshot, details, error)
    }

    private fun finishDetails(snapshot: StackSnapshot, details: Map<Int, PrDetails>, error: String?) {
        _state.update { current ->
            if (current is StackState.Loaded && current.snapshot == snapshot) {
                current.copy(
                    details = details.ifEmpty { current.details },
                    detailsLoading = false,
                    detailsError = error,
                )
            } else {
                current
            }
        }
    }

    private fun repoRef(repository: GitRepository) = RepoRef(
        root = repository.root,
        name = repository.root.name,
        github = githubOf(repository),
        stackRebaseInProgress = stackRebaseInProgress(repository),
    )

    /** gh-stack deja este fichero mientras un `gh stack rebase` espera `--continue`/`--abort`. */
    fun stackRebaseInProgress(repository: GitRepository): Boolean =
        gitDir(repository)?.resolve(REBASE_STATE_FILE)?.let(Files::exists) == true

    /** Las pilas que gh-stack sigue en local, con lo que queda de sus ramas. */
    fun localStacks(repository: GitRepository): List<LocalStackEntry> {
        val file = gitDir(repository)?.resolve(STACK_FILE) ?: return emptyList()
        val stacks = try {
            if (Files.isRegularFile(file)) StackJson.parseLocalStacks(Files.readString(file)) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val branches = repository.branches
        val local = branches.localBranches.mapTo(HashSet()) { it.name }
        val remote = branches.remoteBranches.mapTo(HashSet()) { it.nameForRemoteOperations }
        return LocalStackEntry.all(stacks, local, remote)
    }

    // En un worktree `.git` es un fichero que apunta al directorio real.
    private fun gitDir(repository: GitRepository): Path? {
        val dotGit = repository.root.toNioPath().resolve(".git")
        return when {
            Files.isDirectory(dotGit) -> dotGit
            Files.isRegularFile(dotGit) -> runCatching {
                Files.readString(dotGit).lineSequence()
                    .firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()
                    ?.let { dotGit.parent.resolve(it).normalize() }
            }.getOrNull()
            else -> null
        }
    }

    // ---------------------------------------------------------------- escritura

    fun execute(operation: Operation): Job = cs.launch {
        if (!writeLock.tryLock()) {
            Notifier.warning(project, operation.title, message("operation.busy", _running.value.orEmpty()))
            return@launch
        }
        val repository = operation.repository ?: repository()
        try {
            _running.value = operation.title
            val workDir = operation.workDir ?: repository?.root?.toNioPath() ?: return@launch
            // Lo mismo que hace el IDE antes de un checkout o un rebase: git ve lo que hay en disco.
            withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
            val failure = withBackgroundProgress(project, operation.title, cancellable = true) {
                var completed = 0
                try {
                    for (call in operation.calls) {
                        val result = run(repository, workDir, call)
                        if (!result.ok) return@withBackgroundProgress result
                        completed++
                    }
                    null
                } finally {
                    val undo = operation.cleanup(completed)
                    if (undo.isNotEmpty()) withContext(NonCancellable) { undo.forEach { run(repository, workDir, it) } }
                }
            }
            if (failure == null) {
                operation.successMessage?.let { Notifier.info(project, it) }
                operation.onSuccess?.invoke()
            } else if (operation.onFailure?.invoke(failure) != true) {
                reportFailure(operation, failure, repository)
            }
        } catch (e: GhNotFoundException) {
            if (e.tool == Tool.GH) _state.value = StackState.GhMissing
            Notifier.error(project, operation.title, message(if (e.tool == Tool.GH) "state.gh.missing" else "state.git.missing"))
        } finally {
            withContext(NonCancellable) {
                repository?.let(::syncIde)
            }
            _running.value = null
            writeLock.unlock()
            requestRefresh()
        }
    }

    private suspend fun run(repository: GitRepository?, workDir: Path, call: GhCall): GhResult {
        if (call.tool != Tool.GH) return GhCli.run(workDir, call.args, log, call.tool)
        val remote = if (call.acceptsRemote && repository != null) preferredRemote(repository) else null
        val result = GhCli.run(workDir, call.args + remoteArgs(remote), log)
        if (remote != null || repository == null || !call.acceptsRemote || !result.isRemoteAmbiguous) return result

        // Varios remotos y ninguno por defecto: gh-stack solo lo resuelve en una terminal
        // interactiva. Se pregunta aqui, se recuerda y se repite el comando.
        val chosen = withContext(Dispatchers.EDT) { RemoteChooser.choose(project, repository) } ?: return result
        StacklaneProjectSettings.getInstance(project).remote = chosen
        return GhCli.run(workDir, call.args + remoteArgs(chosen), log)
    }

    private fun preferredRemote(repository: GitRepository): String? =
        StacklaneProjectSettings.getInstance(project).remote?.takeIf { name -> repository.remotes.any { it.name == name } }

    private fun remoteArgs(remote: String?): List<String> = if (remote == null) emptyList() else listOf("--remote", remote)

    /** Git cambio por fuera del IDE: ramas, ficheros del arbol de trabajo, cambios locales. */
    private fun syncIde(repository: GitRepository) {
        VfsUtil.markDirtyAndRefresh(true, true, true, repository.root)
        repository.update()
        VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(repository.root)
    }

    private fun reportFailure(operation: Operation, result: GhResult, repository: GitRepository?) {
        val showLog = Notifier.action(message("notification.show.log")) { StackToolWindow.showLog(project) }
        val stackRebase = repository != null && stackRebaseInProgress(repository)
        when {
            (result.exitCode == GhExit.CONFLICT || result.exitCode == GhExit.REBASE_ACTIVE) && stackRebase ->
                Notifier.warning(
                    project, message("conflict.title"), message("conflict.rebase"),
                    Notifier.action(message("action.resolve.conflicts")) { resolveConflicts() },
                    Notifier.action(message("action.rebase.continue")) { continueRebase() },
                    Notifier.action(message("action.rebase.abort")) { abortRebase() },
                    showLog,
                )
            result.exitCode == GhExit.CONFLICT ->
                Notifier.warning(
                    project, message("conflict.title"), message("conflict.sync"),
                    Notifier.action(message("action.rebase.stack")) { rebase() },
                    showLog,
                )
            result.exitCode == GhExit.LOCK_FAILED ->
                Notifier.warning(project, operation.title, message("error.locked"), showLog)
            result.exitCode == GhExit.STACKS_UNAVAILABLE ->
                Notifier.error(project, operation.title, message("error.unavailable"), showLog)
            result.exitCode == GhExit.MODIFY_RECOVERY ->
                Notifier.error(project, operation.title, message("error.modify"), showLog)
            else ->
                Notifier.error(project, message("operation.failed", operation.title), Notifier.html(result.errorText), showLog)
        }
    }

    // ---------------------------------------------------------------- operaciones compartidas

    fun rebase(): Job = execute(
        Operation(
            message("op.rebase"),
            listOf(GhCall(GhCommands.rebase(), acceptsRemote = true)),
            successMessage = message("op.rebase.done"),
        )
    )

    fun continueRebase(): Job = execute(
        Operation(message("op.rebase.continue"), listOf(GhCall(GhCommands.rebaseContinue())), successMessage = message("op.rebase.done"))
    )

    fun abortRebase(): Job = execute(
        Operation(message("op.rebase.abort"), listOf(GhCall(GhCommands.rebaseAbort())), successMessage = message("op.rebase.aborted"))
    )

    /** El dialogo de conflictos de siempre, con los ficheros que git marco en conflicto. */
    fun resolveConflicts() {
        val changes = ChangeListManager.getInstance(project)
        changes.invokeAfterUpdate(true) {
            val files = changes.allChanges
                .filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
                .mapNotNull { it.virtualFile }
            if (files.isEmpty()) {
                Notifier.info(project, message("conflict.none"))
            } else {
                AbstractVcsHelper.getInstance(project).showMergeDialogWithResult(files)
            }
        }
    }

    /** Checkout con el de git4idea: smart checkout, dialogo de cambios locales, etc. */
    fun checkout(repository: GitRepository, branch: String, then: Runnable? = null) {
        GitBrancher.getInstance(project).checkout(branch, false, listOf(repository), then)
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 250L
        private const val STACK_FILE = "gh-stack"
        private const val REBASE_STATE_FILE = "gh-stack-rebase-state"

        fun getInstance(project: Project): StackService = project.service()
    }
}

/**
 * Los avisos de git4idea, registrados en plugin.xml. No crean el servicio: si la ventana
 * no se abrio nunca, no hay nada que refrescar.
 */
internal class GitEvents(private val project: Project) : GitRepositoryChangeListener, VcsRepositoryMappingListener {

    override fun repositoryChanged(repository: GitRepository) {
        project.getServiceIfCreated(StackService::class.java)?.onRepositoryChanged(repository)
    }

    override fun mappingChanged() {
        project.getServiceIfCreated(StackService::class.java)?.requestRefresh()
    }
}
