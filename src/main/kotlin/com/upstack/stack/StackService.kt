package com.upstack.stack

import com.intellij.CommonBundle
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.upstack.Notifier
import com.upstack.UpstackBundle.message
import com.upstack.gh.Environment
import com.upstack.gh.EnvironmentCheck
import com.upstack.gh.EnvironmentReport
import com.upstack.gh.GhCli
import com.upstack.gh.GhCommands
import com.upstack.gh.GhExit
import com.upstack.gh.GhNotFoundException
import com.upstack.gh.GhOutputSink
import com.upstack.gh.GhResult
import com.upstack.gh.GitCommands
import com.upstack.gh.LoginPrompt
import com.upstack.gh.RebaseScope
import com.upstack.gh.RemoteInfo
import com.upstack.gh.RepositoryEnvironment
import com.upstack.gh.StackExtension
import com.upstack.gh.Tool
import com.upstack.gh.VersionStatus
import com.upstack.settings.RestackMode
import com.upstack.settings.UpstackConfigurable
import com.upstack.settings.UpstackProjectSettings
import com.upstack.settings.UpstackSettings
import com.upstack.ui.LoginDialog
import com.upstack.ui.RemoteChooser
import com.upstack.ui.StackToolWindow
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
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Tras [onSuccess], con lo que devolvio la ultima llamada: para avisar segun lo que dijo gh. */
    val onOutput: (suspend (GhResult) -> Unit)? = null,
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

    private val _pushPending = MutableStateFlow<Map<VirtualFile, Set<String>>>(emptyMap())

    /**
     * Por raiz de repositorio, las capas que se rebasaron desde el plugin y aun no se subieron.
     * La ventana ofrece *Push Stack* mientras alguna siga distinta de su rama remota.
     */
    val pushPending: StateFlow<Map<VirtualFile, Set<String>>> = _pushPending.asStateFlow()

    private val _conflicts = MutableStateFlow<Int?>(null)

    /**
     * Mientras un `gh stack rebase` esta parado en el repositorio que se muestra, cuantos
     * ficheros siguen en conflicto segun [ChangeListManager]; null si no hay rebase parado o
     * si aun no se sabe (el IDE no ha vuelto a mirar los cambios desde la ultima escritura).
     */
    val conflicts: StateFlow<Int?> = _conflicts.asStateFlow()

    private val _removals = MutableStateFlow<Map<VirtualFile, Set<String>>>(emptyMap())

    /**
     * Por raiz de repositorio, las capas cuya rama se recupero para sacarlas de la pila con
     * `gh stack modify`, que solo se puede hacer en la terminal. La ventana lo recuerda mientras
     * sigan en la pila.
     */
    val removals: StateFlow<Map<VirtualFile, Set<String>>> = _removals.asStateFlow()

    val log = StackLog()

    // CONFLATED: cien eventos seguidos de git se quedan en un refresco.
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    @Volatile
    private var lastFingerprint: String? = null

    private val stackVersionChecked = AtomicBoolean()

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
        val chosen = UpstackProjectSettings.getInstance(project).repositoryRoot
        return all.firstOrNull { it.root.path == chosen }
            ?: all.firstOrNull { it.root.path == project.basePath }
            ?: all.firstOrNull()
    }

    fun selectRepository(repository: GitRepository) {
        UpstackProjectSettings.getInstance(project).repositoryRoot = repository.root.path
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
        val preferred = UpstackProjectSettings.getInstance(project).remote
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
            if (!result.isMissingExtension) checkStackVersionOnce(ref.root.toNioPath())
            val branch = repository.currentBranchName
            val previous = (_state.value as? StackState.Loaded)?.takeIf { it.repo.root == ref.root }
            when {
                result.ok -> StackJson.parseView(result.stdout).let {
                    showSnapshot(ref, it, missingBranches(repository, it), localStacks(repository))
                }
                // Con el rebase parado HEAD esta suelto y gh-stack no sabe en que rama esta (lee
                // `git symbolic-ref HEAD`): `view` sale con 2. La pila de antes sigue siendo esa.
                result.exitCode == GhExit.NOT_IN_STACK && ref.stackRebaseInProgress && previous != null ->
                    _state.value = previous.copy(repo = ref)
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

    private suspend fun showSnapshot(ref: RepoRef, snapshot: StackSnapshot, missing: List<MissingBranch>, localStacks: List<LocalStackEntry>) {
        val numbers = snapshot.layers.mapNotNull { it.pr?.number }.toSet()
        // Mientras llegan los datos nuevos se ensenan los anteriores: sin parpadeo.
        val previous = (_state.value as? StackState.Loaded)?.takeIf { it.repo.root == ref.root }
        val cached = previous?.details.orEmpty().filterKeys { it in numbers }
        // La que ya salio de la pila deja de estar pendiente de sacar.
        val layers = snapshot.layers.mapTo(HashSet()) { it.branch }
        _removals.update { all -> all[ref.root]?.let { all + (ref.root to it.filterTo(HashSet(), layers::contains)) } ?: all }
        // Lo elegido (lista o capas) vale mientras no cambie la rama; en otra, se empieza de nuevo.
        val listShown = previous?.takeIf { it.snapshot.currentBranch == snapshot.currentBranch }?.listShown
            ?: (snapshot.currentBranch == snapshot.trunk)
        val loaded = StackState.Loaded(
            ref, snapshot, cached, detailsLoading = numbers.isNotEmpty(), detailsError = null, missing = missing,
            localStacks = localStacks,
        ).let { it.copy(listShown = listShown && it.hasStackList) }
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
        finishDetails(snapshot, details, error, if (error != null && result.needsLogin) Environment.loginHost(github) else null)
    }

    /** La lista de pilas en vez de las capas de la que se muestra. No cambia de rama. */
    fun showStackList() = setListShown(true)

    /** Desde la lista, otra vez las capas de la pila que se muestra. */
    fun openStack() = setListShown(false)

    private fun setListShown(shown: Boolean) {
        _state.update { current ->
            if (current is StackState.Loaded && (!shown || current.hasStackList)) current.copy(listShown = shown) else current
        }
    }

    private fun finishDetails(snapshot: StackSnapshot, details: Map<Int, PrDetails>, error: String?, loginHost: String? = null) {
        _state.update { current ->
            if (current is StackState.Loaded && current.snapshot == snapshot) {
                current.copy(
                    details = details.ifEmpty { current.details },
                    detailsLoading = false,
                    detailsError = error,
                    loginHost = loginHost,
                )
            } else {
                current
            }
        }
    }

    /**
     * Las capas activas de [snapshot] sin rama local. Si una sigue en un remoto, se toma la del
     * remoto elegido, despues la de `origin` y despues cualquiera.
     */
    private fun missingBranches(repository: GitRepository, snapshot: StackSnapshot): List<MissingBranch> {
        val branches = repository.branches
        val local = branches.localBranches.mapTo(HashSet()) { it.name }
        val preferred = preferredRemote(repository) ?: "origin"
        val remote = branches.remoteBranches
            .sortedBy { if (it.remote.name == preferred) 0 else 1 }
            .groupBy { it.nameForRemoteOperations }
            .mapValues { (_, candidates) -> candidates.first().name }
        return snapshot.missingBranches(local, remote)
    }

    private suspend fun repoRef(repository: GitRepository) = RepoRef(
        root = repository.root,
        name = repository.root.name,
        github = githubOf(repository),
        rebase = rebaseStop(repository),
    )

    /** gh-stack deja este fichero mientras un `gh stack rebase` espera `--continue`/`--abort`. */
    fun stackRebaseInProgress(repository: GitRepository): Boolean =
        gitDir(repository)?.resolve(REBASE_STATE_FILE)?.let(Files::exists) == true

    /**
     * Donde esta parado el `gh stack rebase`, o null si no lo esta. Solo con lo que dice git: la
     * rama del rebase de git en curso y su `REBASE_HEAD`; de gh-stack, solo que el rebase sigue
     * abierto y sus pilas locales, para saber que capa es esa rama.
     */
    suspend fun rebaseStop(repository: GitRepository): RebaseStop? {
        if (!stackRebaseInProgress(repository)) return null
        val gitDir = gitDir(repository)
        val branch = REBASE_DIRS.firstNotNullOfOrNull { dir ->
            gitDir?.resolve(dir)?.resolve("head-name")?.takeIf(Files::isRegularFile)
                ?.let { runCatching { Files.readString(it).trim() }.getOrNull() }
        }?.takeIf { it.startsWith(HEADS) }?.removePrefix(HEADS)
        val stack = branch?.let { name -> localStacks(repository).map { it.stack }.firstOrNull { name in it.branches } }
        val head = try {
            GhCli.run(repository.root.toNioPath(), GitCommands.rebaseHead(), tool = Tool.GIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val commit = head?.takeIf { it.ok }?.stdout?.trim()?.split(UNIT_SEPARATOR, limit = 2)?.takeIf { it.size == 2 }
        return RebaseStop(
            branch = branch,
            position = branch?.let { stack?.positionOf(it) },
            layers = stack?.branches?.size,
            commit = commit?.get(0),
            subject = commit?.get(1),
        )
    }

    /** Los ficheros de [root] que el IDE ve en conflicto, en el ultimo repaso de los cambios. */
    private fun conflictedFiles(root: VirtualFile): List<VirtualFile> =
        ChangeListManager.getInstance(project).allChanges
            .filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            .mapNotNull { it.virtualFile }
            .filter { VfsUtilCore.isAncestor(root, it, false) }

    /** El IDE termino de repasar los cambios: cuantos conflictos quedan, si hay rebase parado. */
    internal fun onChangesUpdated() {
        val repository = repository()
        _conflicts.value = repository?.takeIf(::stackRebaseInProgress)?.let { conflictedFiles(it.root).size }
    }

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
        var stoppedOnConflicts = false
        try {
            _running.value = operation.title
            val workDir = operation.workDir ?: repository?.root?.toNioPath() ?: return@launch
            var last: GhResult? = null
            // Lo mismo que hace el IDE antes de un checkout o un rebase: git ve lo que hay en disco.
            withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
            val failure = withBackgroundProgress(project, operation.title, cancellable = true) {
                var completed = 0
                try {
                    for (call in operation.calls) {
                        val result = run(repository, workDir, call)
                        if (!result.ok) return@withBackgroundProgress result
                        last = result
                        completed++
                    }
                    null
                } finally {
                    val undo = operation.cleanup(completed)
                    if (undo.isNotEmpty()) withContext(NonCancellable) { undo.forEach { run(repository, workDir, it) } }
                }
            }
            if (failure == null) {
                if (repository != null && operation.calls.any { it.tool == Tool.GH && GhCommands.pushesStack(it.args) }) {
                    _pushPending.update { it - repository.root }
                }
                operation.successMessage?.let { Notifier.info(project, it) }
                operation.onSuccess?.invoke()
                last?.let { operation.onOutput?.invoke(it) }
            } else {
                stoppedOnConflicts = failure.exitCode == GhExit.CONFLICT && repository != null && stackRebaseInProgress(repository)
                if (operation.onFailure?.invoke(failure) != true) reportFailure(operation, failure, repository)
            }
        } catch (e: GhNotFoundException) {
            if (e.tool == Tool.GH) _state.value = StackState.GhMissing
            Notifier.error(project, operation.title, message(if (e.tool == Tool.GH) "state.gh.missing" else "state.git.missing"))
        } finally {
            // Lo que se conto antes ya no vale: hasta el repaso que pide syncIde, no se sabe.
            _conflicts.value = null
            withContext(NonCancellable) {
                repository?.let(::syncIde)
            }
            _running.value = null
            writeLock.unlock()
            requestRefresh()
        }
        // Despues de syncIde: el dialogo espera al repaso de cambios que ve los conflictos nuevos.
        if (stoppedOnConflicts) withContext(Dispatchers.EDT) { resolveConflicts(opened = true) }
    }

    private suspend fun run(repository: GitRepository?, workDir: Path, call: GhCall): GhResult {
        if (call.tool != Tool.GH) return GhCli.run(workDir, call.args, log, call.tool)
        val remote = if (call.acceptsRemote && repository != null) preferredRemote(repository) else null
        val result = GhCli.run(workDir, call.args + remoteArgs(remote), log)
        if (remote != null || repository == null || !call.acceptsRemote || !result.isRemoteAmbiguous) return result

        // Varios remotos y ninguno por defecto: gh-stack solo lo resuelve en una terminal
        // interactiva. Se pregunta aqui, se recuerda y se repite el comando.
        val chosen = withContext(Dispatchers.EDT) { RemoteChooser.choose(project, repository) } ?: return result
        UpstackProjectSettings.getInstance(project).remote = chosen
        return GhCli.run(workDir, call.args + remoteArgs(chosen), log)
    }

    private fun preferredRemote(repository: GitRepository): String? =
        UpstackProjectSettings.getInstance(project).remote?.takeIf { name -> repository.remotes.any { it.name == name } }

    /**
     * El remoto donde estan las ramas de [snapshot]: el elegido, el que siguen sus capas,
     * `origin` o el primero. null si el repositorio no tiene remotos.
     */
    fun stackRemote(repository: GitRepository, snapshot: StackSnapshot): String? =
        preferredRemote(repository)
            ?: snapshot.layers.asReversed().firstNotNullOfOrNull { repository.getBranchTrackInfo(it.branch)?.remote?.name }
            ?: repository.remotes.firstOrNull { it.name == "origin" }?.name
            ?: repository.remotes.firstOrNull()?.name

    private fun remoteArgs(remote: String?): List<String> = if (remote == null) emptyList() else listOf("--remote", remote)

    /** Git cambio por fuera del IDE: ramas, ficheros del arbol de trabajo, cambios locales. */
    private fun syncIde(repository: GitRepository) {
        VfsUtil.markDirtyAndRefresh(true, true, true, repository.root)
        repository.update()
        VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(repository.root)
    }

    private suspend fun reportFailure(operation: Operation, result: GhResult, repository: GitRepository?) {
        val showLog = Notifier.action(message("notification.show.log")) { StackToolWindow.showLog(project) }
        val stop = repository?.let { rebaseStop(it) }
        // Solo de gh: un `git branch` que falla al recuperar una rama tambien la nombra.
        val missing = if (operation.calls.any { it.tool == Tool.GH }) missingIn(result, repository) else emptyList()
        when {
            (result.exitCode == GhExit.CONFLICT || result.exitCode == GhExit.REBASE_ACTIVE) && stop != null ->
                Notifier.warning(
                    project, message("conflict.title"),
                    stop.branch?.let { message("conflict.rebase.layer", it) } ?: message("conflict.rebase"),
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
            // Sin sesion, lo que arregla el fallo es entrar, no leer el error de gh.
            result.needsLogin -> Environment.loginHost(repository?.let(::githubOf)).let { host ->
                Notifier.error(
                    project, message("operation.failed", operation.title), message("error.signed.out", host),
                    Notifier.action(message("action.log.in")) { LoginDialog.logIn(project, host) },
                    showLog,
                )
            }
            // gh-stack no salta una capa sin rama: falla al buscarla, con un error de git que no lo dice.
            missing.isNotEmpty() ->
                Notifier.error(
                    project, message("operation.failed", operation.title),
                    message("error.missing.branch", missing.joinToString(", ") { it.branch }),
                    Notifier.action(message(if (missing.size == 1) "action.restore.branch" else "action.restore.branches")) {
                        restoreBranches(missing, repository)
                    },
                    showLog,
                )
            else ->
                Notifier.error(project, message("operation.failed", operation.title), Notifier.html(result.errorText), showLog)
        }
    }

    /** Las capas sin rama del estado en pantalla que nombra el error de [result]. */
    private fun missingIn(result: GhResult, repository: GitRepository?): List<MissingBranch> {
        val state = _state.value as? StackState.Loaded ?: return emptyList()
        if (repository == null || state.repo.root != repository.root || state.missing.isEmpty()) return emptyList()
        // git las nombra entre comillas, sueltas o como `refs/heads/rama`.
        val words = result.errorText.split(ERROR_SEPARATORS).flatMapTo(HashSet()) { listOf(it, it.removePrefix(HEADS)) }
        return state.missing.filter { it.branch in words }
    }

    // ---------------------------------------------------------------- entorno

    /**
     * Que `gh` hay, con que sesion, que gh-stack y si el repositorio que se muestra admite pilas
     * (ver [EnvironmentCheck]). [ghPath]: la ruta escrita en los ajustes, aplicada o no. Solo
     * lee, y deja cada comando en el Log.
     */
    suspend fun checkEnvironment(ghPath: String = UpstackSettings.getInstance().ghPath): EnvironmentReport {
        val repository = repository()
        val remotes = repository?.let { repo ->
            RepositoryEnvironment(
                name = repo.root.name,
                remotes = repo.remotes.sortedBy { it.name }.map { remote ->
                    RemoteInfo(remote.name, remote.firstUrl.orEmpty(), remote.urls.firstNotNullOfOrNull(GitHubRepo::fromRemoteUrl))
                },
                github = githubOf(repo),
            )
        }
        return EnvironmentCheck.run(ghPath, repository?.root?.toNioPath() ?: HOME, remotes, log)
    }

    /**
     * `gh auth login --web` en [host], sin terminal: gh imprime el codigo de un solo uso y espera
     * a que se autorice en el navegador. [onPrompt] lo recibe en cuanto sale, desde el hilo que
     * lee el proceso. Cancelar la corrutina lo mata y no cambia la sesion.
     *
     * @throws GhNotFoundException si no hay `gh`.
     */
    suspend fun logIn(host: String, onPrompt: (LoginPrompt) -> Unit): GhResult {
        val printed = StringBuffer()
        var shown: LoginPrompt? = null
        val sink = object : GhOutputSink {
            override fun started(workDir: Path, command: String) = log.started(workDir, command)

            override fun output(text: String) {
                log.output(text)
                val prompt = Environment.parseLoginPrompt(printed.append(text).toString(), host)
                if (prompt != null && prompt != shown) {
                    shown = prompt
                    onPrompt(prompt)
                }
            }

            override fun finished(exitCode: Int) = log.finished(exitCode)
        }
        return GhCli.run(HOME, GhCommands.login(host), sink)
    }

    fun upgradeExtension(): Job = execute(
        Operation(
            title = message("op.upgrade"),
            calls = listOf(GhCall(GhCommands.upgradeExtension())),
            workDir = HOME,
            // `[stack]: upgraded from v0.1.0 to v0.1.1`, o que ya estaba al dia: lo dice gh.
            onOutput = { result -> Notifier.info(project, message("op.upgrade.done"), Notifier.html(result.stdout + result.stderr)) },
        )
    )

    /**
     * Una vez por sesion: si gh-stack no es la version de la que se leyeron el JSON y los codigos
     * de salida, se avisa, una vez por version, en vez de fallar mas tarde sin explicacion. Es
     * un aviso y no un bloqueo: todo sigue funcionando igual.
     */
    private fun checkStackVersionOnce(workDir: Path) {
        if (!stackVersionChecked.compareAndSet(false, true)) return
        cs.launch {
            val result = try {
                GhCli.run(workDir, GhCommands.stackVersion())
            } catch (_: GhNotFoundException) {
                return@launch
            }
            if (!result.ok) return@launch
            val extension = StackExtension(true, Environment.parseStackVersion(result.stdout))
            val status = extension.status
            val version = extension.version ?: UNREADABLE_VERSION
            val settings = UpstackSettings.getInstance()
            if (status == VersionStatus.KNOWN || settings.warnedStackVersion == version) return@launch
            settings.warnedStackVersion = version
            val text = when (status) {
                VersionStatus.OLDER -> message("version.older", version, StackExtension.REFERENCE)
                VersionStatus.NEWER -> message("version.newer", version, StackExtension.REFERENCE)
                else -> message("version.unreadable", StackExtension.REFERENCE)
            }
            val openSettings = Notifier.action(message("action.open.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, UpstackConfigurable::class.java)
            }
            // Una mas nueva no se arregla actualizando.
            if (status == VersionStatus.NEWER) Notifier.warning(project, message("version.title"), text, openSettings)
            else Notifier.warning(
                project, message("version.title"), text,
                Notifier.action(message("action.upgrade.extension")) { upgradeExtension() },
                openSettings,
            )
        }
    }

    // ---------------------------------------------------------------- capas sin rama

    /**
     * Recrea las ramas de [missing] (ver [StackPlans.restoreLayers]). [forRemoval]: se recuperan
     * para sacarlas de la pila con `gh stack modify`; la ventana lo recuerda hasta que salgan.
     */
    fun restoreBranches(missing: List<MissingBranch>, repository: GitRepository? = repository(), forRemoval: Boolean = false): Job {
        val names = missing.joinToString(", ") { it.branch }
        return execute(
            Operation(
                title = message("op.restore", names),
                calls = StackPlans.restoreLayers(missing).calls,
                repository = repository,
                successMessage = if (forRemoval) null else message("op.restore.done", names),
                onSuccess = {
                    if (forRemoval && repository != null) {
                        _removals.update { it + (repository.root to it[repository.root].orEmpty() + missing.map(MissingBranch::branch)) }
                    }
                },
            )
        )
    }

    /** Ya no hace falta recordar que [branch] se va a sacar de la pila. */
    fun forgetRemoval(root: VirtualFile, branch: String) {
        _removals.update { all -> all[root]?.let { all + (root to it - branch) } ?: all }
    }

    // ---------------------------------------------------------------- rebase

    /**
     * `gh stack rebase` con su alcance. Antes, la pregunta de rerere que gh-stack solo hace en
     * una terminal; al terminar, se ofrece subir las ramas, que solo cambiaron en local.
     * [returnTo]: la rama a la que volver si todo va bien.
     */
    fun rebase(
        scope: RebaseScope = RebaseScope.STACK,
        repository: GitRepository? = repository(),
        returnTo: String? = null,
    ): Job = cs.launch {
        val target = repository ?: return@launch
        val rerere = askRerere(target) ?: return@launch
        execute(
            Operation(
                title = rebaseTitle(scope),
                calls = rerere + GhCall(GhCommands.rebase(scope), acceptsRemote = true),
                repository = target,
                onSuccess = {
                    rebased(target)
                    if (returnTo != null && returnTo != target.currentBranchName) {
                        withContext(Dispatchers.EDT) { checkout(target, returnTo) }
                    }
                },
            )
        )
    }

    /**
     * `--upstack` empieza en la rama actual: si [branch] no lo es, antes se hace checkout, y al
     * terminar se vuelve a la rama de partida. Si el rebase se para en un conflicto, se queda
     * donde gh-stack lo dejo.
     */
    fun rebaseUpstackFrom(repository: GitRepository, branch: String) {
        val original = repository.currentBranchName
        if (original == branch) {
            rebase(RebaseScope.UPSTACK, repository)
            return
        }
        checkout(repository, branch) {
            if (repository.currentBranchName == branch) rebase(RebaseScope.UPSTACK, repository, returnTo = original)
            else Notifier.warning(project, message("op.rebase.upstack"), message("rebase.checkout.failed", branch))
        }
    }

    fun continueRebase(): Job {
        val repository = repository()
        return execute(
            Operation(
                message("op.rebase.continue"),
                listOf(GhCall(GhCommands.rebaseContinue())),
                repository,
                onSuccess = { repository?.let(::rebased) },
            )
        )
    }

    fun push(repository: GitRepository? = repository()): Job = execute(
        Operation(
            message("op.push"),
            listOf(GhCall(GhCommands.push(), acceptsRemote = true)),
            repository,
            successMessage = message("op.push.done"),
        )
    )

    /** Tras un rebase, las ramas solo cambiaron en local: lo siguiente es subirlas. */
    private fun rebased(repository: GitRepository) {
        val layers = (_state.value as? StackState.Loaded)?.takeIf { it.repo.root == repository.root }
            ?.snapshot?.layers?.filter { !it.isMerged }?.map { it.branch }.orEmpty()
        if (layers.isNotEmpty()) _pushPending.update { it + (repository.root to layers.toSet()) }
        Notifier.info(project, message("op.rebase.done"), Notifier.action(message("action.push.stack")) { push(repository) })
    }

    /**
     * Las capas de [state] que se rebasaron aqui y siguen sin subir. Si ya coinciden con su rama
     * remota (un `gh stack push` en la terminal, por ejemplo) no cuentan.
     */
    fun unpushedLayers(state: StackState.Loaded): List<String> {
        val pending = _pushPending.value[state.repo.root] ?: return emptyList()
        val repository = repositoryFor(state.repo) ?: return emptyList()
        return state.snapshot.layers
            .filter { !it.isMerged && it.branch in pending && differsFromRemote(repository, it.branch) }
            .map { it.branch }
    }

    /**
     * La rama remota es la de seguimiento o, si no la hay, la del mismo nombre (primero en el
     * remoto elegido). Sin ninguna, no se subio nunca: tambien hay que subirla.
     */
    private fun differsFromRemote(repository: GitRepository, branch: String): Boolean {
        val branches = repository.branches
        val local = branches.findLocalBranch(branch) ?: return false
        val preferred = preferredRemote(repository) ?: "origin"
        val remote = repository.getBranchTrackInfo(branch)?.remoteBranch
            ?: branches.remoteBranches.filter { it.nameForRemoteOperations == branch }.minByOrNull { if (it.remote.name == preferred) 0 else 1 }
            ?: return true
        return branches.getHash(local) != branches.getHash(remote)
    }

    private fun rebaseTitle(scope: RebaseScope): @Nls String = when (scope) {
        RebaseScope.STACK -> message("op.rebase")
        RebaseScope.UPSTACK -> message("op.rebase.upstack")
        RebaseScope.DOWNSTACK -> message("op.rebase.downstack")
        RebaseScope.LAYERS -> message("op.rebase.layers")
    }

    /**
     * La pregunta de rerere, hasta que se conteste en este repositorio (ver [Rerere]). Devuelve
     * los comandos que guardan la respuesta, nada si ya estaba contestada, o null si se cancela:
     * entonces no se rebasa, como al interrumpir la pregunta en la terminal.
     */
    private suspend fun askRerere(repository: GitRepository): List<GhCall>? {
        val config = try {
            GhCli.run(repository.root.toNioPath(), GitCommands.rerereConfig(), tool = Tool.GIT)
        } catch (_: GhNotFoundException) {
            return emptyList()
        }
        // 1: ninguna de las dos claves. Cualquier otro fallo es de git, y lo contara el rebase.
        if (config.exitCode !in 0..1 || Rerere.parse(config.stdout) != Rerere.Answer.UNANSWERED) return emptyList()
        val choice = withContext(Dispatchers.EDT) {
            MessageDialogBuilder.yesNoCancel(message("rerere.title"), message("rerere.text"))
                .yesText(message("rerere.enable"))
                .noText(message("rerere.decline"))
                .cancelText(CommonBundle.getCancelButtonText())
                .icon(Messages.getQuestionIcon())
                .show(project)
        }
        return when (choice) {
            Messages.YES -> Rerere.enable()
            Messages.NO -> Rerere.decline()
            else -> null
        }
    }

    // ---------------------------------------------------------------- tras un commit

    /**
     * Un commit del IDE en [roots]. Si cayo en una capa con capas encima, esas se quedaron
     * atras: se ofrece llevarlo hasta ellas con un rebase upstack, o se hace sin preguntar,
     * segun los ajustes.
     */
    fun afterCommit(roots: Collection<VirtualFile>) {
        val mode = UpstackSettings.getInstance().restackAfterCommit
        if (mode == RestackMode.NEVER) return
        cs.launch {
            repositories()
                .filter { it.root in roots && localStacks(it).isNotEmpty() && !stackRebaseInProgress(it) }
                .forEach { afterCommit(it, mode) }
        }
    }

    private suspend fun afterCommit(repository: GitRepository, mode: RestackMode) {
        val root = repository.root.toNioPath()
        // Se lee otra vez: la pila en pantalla es de antes del commit.
        val view = try {
            GhCli.run(root, GhCommands.view())
        } catch (_: GhNotFoundException) {
            return
        }
        if (!view.ok) return
        val snapshot = runCatching { StackJson.parseView(view.stdout) }.getOrNull() ?: return
        val layer = snapshot.current?.takeIf { !it.isMerged } ?: return
        if (snapshot.outdatedAbove(layer).isEmpty()) return
        // Ninguna de las de encima tiene el commit, aunque gh-stack solo marque la siguiente.
        val behind = snapshot.activeAbove(layer)

        val clean = isClean(root)
        if (mode == RestackMode.ALWAYS && clean) {
            rebase(RebaseScope.UPSTACK, repository)
            return
        }
        val text = buildString {
            append(message("restack.text", layer.branch, behind.joinToString(", ") { it.branch }, snapshot.trunk))
            if (!clean) append("<br>").append(message("restack.dirty"))
        }
        Notifier.info(
            project, message("restack.title", behind.size), text,
            Notifier.action(message("action.rebase.upstack")) { rebaseUpstackFrom(repository, layer.branch) },
            Notifier.action(message("action.restack.always")) {
                UpstackSettings.getInstance().restackAfterCommit = RestackMode.ALWAYS
                rebaseUpstackFrom(repository, layer.branch)
            },
        )
    }

    /** Sin cambios en ficheros con seguimiento: git puede empezar el rebase. */
    private suspend fun isClean(root: Path): Boolean = try {
        val status = GhCli.run(root, GitCommands.statusTracked(), tool = Tool.GIT)
        status.ok && status.stdout.isBlank()
    } catch (_: GhNotFoundException) {
        false
    }

    // ---------------------------------------------------------------- operaciones compartidas

    /**
     * Abortar deshace tambien las capas que ya se rebasaron y lo resuelto hasta ahora: se pregunta
     * siempre. En el EDT.
     */
    fun abortRebase() {
        val confirmed = MessageDialogBuilder.yesNo(message("abort.title"), message("abort.text"))
            .yesText(message("action.rebase.abort"))
            .noText(CommonBundle.getCancelButtonText())
            .icon(Messages.getWarningIcon())
            .ask(project)
        if (!confirmed) return
        execute(
            Operation(message("op.rebase.abort"), listOf(GhCall(GhCommands.rebaseAbort())), successMessage = message("op.rebase.aborted"))
        )
    }

    /**
     * El dialogo de conflictos de siempre, con los ficheros que git marco en conflicto. [opened]:
     * se abre solo porque el rebase se acaba de parar; si no queda ninguno, ya lo dice la banda.
     */
    fun resolveConflicts(opened: Boolean = false) {
        val repository = repository() ?: return
        val changes = ChangeListManager.getInstance(project)
        changes.invokeAfterUpdate(true) {
            val files = conflictedFiles(repository.root)
            when {
                files.isNotEmpty() -> mergeDialog(files)
                // Con rerere.autoupdate, git ya aplico y anadio una resolucion recordada.
                !opened -> Notifier.info(project, message("conflict.none"), Notifier.action(message("action.rebase.continue")) { continueRebase() })
            }
        }
    }

    /** El dialogo de conflictos del IDE. Los tests lo cambian: sin pantalla no se puede crear. */
    internal var mergeDialog: (List<VirtualFile>) -> Unit = { files ->
        AbstractVcsHelper.getInstance(project).showMergeDialogWithResult(files)
    }

    /** Checkout con el de git4idea: smart checkout, dialogo de cambios locales, etc. */
    fun checkout(repository: GitRepository, branch: String, then: Runnable? = null) {
        GitBrancher.getInstance(project).checkout(branch, false, listOf(repository), then)
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 250L
        private const val STACK_FILE = "gh-stack"
        private const val REBASE_STATE_FILE = "gh-stack-rebase-state"

        /** Donde deja git el rebase en curso: `rebase-merge` el de siempre, `rebase-apply` el de `--apply`. */
        private val REBASE_DIRS = listOf("rebase-merge", "rebase-apply")
        private const val HEADS = "refs/heads/"
        private const val UNIT_SEPARATOR = '\u001F'
        private const val UNREADABLE_VERSION = "?"

        /** Donde corre lo que no es de ningun repositorio: instalar la extension, iniciar sesion. */
        private val HOME: Path get() = Path.of(System.getProperty("user.home"))
        private val ERROR_SEPARATORS = Regex("""[\s'"`:]+""")

        fun getInstance(project: Project): StackService = project.service()
    }
}

/**
 * Los avisos de git4idea y del repaso de cambios del IDE, registrados en plugin.xml. No crean el servicio: si la ventana
 * no se abrio nunca, no hay nada que refrescar.
 */
internal class GitEvents(private val project: Project) : GitRepositoryChangeListener, VcsRepositoryMappingListener, ChangeListListener {

    override fun repositoryChanged(repository: GitRepository) {
        project.getServiceIfCreated(StackService::class.java)?.onRepositoryChanged(repository)
    }

    override fun mappingChanged() {
        project.getServiceIfCreated(StackService::class.java)?.requestRefresh()
    }

    override fun changeListUpdateDone() {
        project.getServiceIfCreated(StackService::class.java)?.onChangesUpdated()
    }
}
