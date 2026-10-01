package com.stacklane.actions

import com.intellij.CommonBundle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.stacklane.Notifier
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCli
import com.stacklane.gh.GhCommands
import com.stacklane.gh.GhNotFoundException
import com.stacklane.gh.GhResult
import com.stacklane.gh.GitCommands
import com.stacklane.gh.Tool
import com.stacklane.settings.StacklaneSettings
import com.stacklane.stack.ClosePlans
import com.stacklane.stack.CloseTargets
import com.stacklane.stack.GhCall
import com.stacklane.stack.GitHubRepo
import com.stacklane.stack.LocalStack
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.Operation
import com.stacklane.stack.Position
import com.stacklane.stack.PublishPlans
import com.stacklane.stack.StackJson
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackPlans
import com.stacklane.stack.StackService
import com.stacklane.stack.StackSnapshot
import com.stacklane.stack.StackState
import com.stacklane.stack.repo
import com.stacklane.ui.AddLayerDialog
import com.stacklane.ui.CloseStackDialog
import com.stacklane.ui.InitStackDialog
import com.stacklane.ui.LabelsDialog
import com.stacklane.ui.PublishDialog
import com.stacklane.ui.StackToolWindow
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Lo que hace cada accion, separado de las clases AnAction para poder lanzarlo tambien
 * desde los enlaces de la ventana. Todo acaba en [StackService.execute].
 */
internal object StackFlows {

    // Ramas que casi nunca se quieren adoptar como primera capa de una pila.
    private val TRUNK_NAMES = setOf("main", "master", "develop", "development", "trunk")

    // Lo que se ensena del ultimo commit comun en el titulo del diff de una capa.
    private const val SHORT_HASH = 10

    // ------------------------------------------------------------------ pila

    /**
     * Pila nueva. [prefill]: capas y base ya elegidas, al recrear una pila cuyas ramas se
     * borraron. Si alguna capa esta registrada en una pila sin ramas, el plan la olvida antes.
     */
    fun initStack(project: Project, prefill: LocalStack? = null) {
        val service = StackService.getInstance(project)
        val state = service.state.value
        val repository = state.repo?.let(service::repositoryFor) ?: service.repository() ?: return
        val current = repository.currentBranchName

        // gh-stack no inicia una pila desde una capa de otra, aunque tambien sea la base de
        // alguna: antes se vuelve a su trunk.
        val leaveLayerFor = when (state) {
            is StackState.Loaded -> state.snapshot.trunk.takeIf { current != null && current != it && state.snapshot.layers.any { l -> l.branch == current } }
            is StackState.InSeveralStacks -> state.layerOf?.stack?.trunk?.takeIf { state.branch == current }
            else -> null
        }
        // En una rama suelta con trabajo, lo normal es convertirla en la primera capa (base: la
        // rama por defecto). En cualquier otro caso, la base propuesta es la rama actual.
        val adopt = current?.takeIf { state is StackState.NotInStack && it !in TRUNK_NAMES }
        val layers = prefill?.branches?.joinToString(" ") ?: adopt
        val base = when {
            prefill != null -> prefill.trunk
            adopt != null -> null
            else -> current
        }
        // Tras salir de la capa, HEAD esta en su trunk: desde ahi se olvida y se vuelve.
        val from = if (leaveLayerFor != null) Position(leaveLayerFor, null) else Position(current, repository.currentRevision)

        val dialog = InitStackDialog(project, repository, layers, base, leaveLayerFor, service.localStacks(repository), from)
        if (!dialog.showAndGet()) return
        val plan = dialog.plan() ?: return
        val init = Operation(
            title = message("op.init"),
            calls = plan.calls,
            repository = repository,
            successMessage = message("op.init.done", dialog.branches.last(), dialog.base ?: message("init.base.default.short")),
            cleanup = plan.cleanup,
        )
        if (leaveLayerFor == null) {
            service.execute(init)
        } else {
            service.checkout(repository, leaveLayerFor) {
                if (repository.currentBranchName == leaveLayerFor) service.execute(init)
                else Notifier.warning(project, message("op.init"), message("init.leave.layer.failed", leaveLayerFor))
            }
        }
    }

    /**
     * Una pila cuyas ramas ya no existen: gh-stack la sigue registrando, no se puede sacar y
     * sus nombres no se pueden reutilizar. Se ofrece olvidarla o recrearla sobre otra base.
     */
    fun cleanUpStaleStack(project: Project, entry: LocalStackEntry) {
        val choice = MessageDialogBuilder.yesNoCancel(
            message("stale.title"),
            message("stale.text", describe(entry)),
        )
            .yesText(message("stale.forget"))
            .noText(message("stale.recreate"))
            .cancelText(CommonBundle.getCancelButtonText())
            .icon(Messages.getWarningIcon())
            .show(project)
        when (choice) {
            Messages.YES -> forgetStack(project, entry)
            Messages.NO -> initStack(project, prefill = entry.stack)
        }
    }

    /** Deja de seguir en local una pila sin ramas. Ni GitHub ni el arbol de trabajo se tocan. */
    fun forgetStack(project: Project, entry: LocalStackEntry) {
        val service = StackService.getInstance(project)
        val repository = service.repository() ?: return
        val plan = StackPlans.forget(entry.stack, Position(repository.currentBranchName, repository.currentRevision))
            ?: return Notifier.warning(project, message("op.forget"), message("init.detached"))
        service.execute(
            Operation(
                title = message("op.forget"),
                calls = plan.calls,
                repository = repository,
                successMessage = message("op.forget.done", describe(entry)),
                cleanup = plan.cleanup,
            )
        )
    }

    private fun describe(entry: LocalStackEntry): String = (listOf(entry.stack.trunk) + entry.stack.branches).joinToString(" ← ")

    private fun describe(snapshot: StackSnapshot): String = (listOf(snapshot.trunk) + snapshot.layers.map { it.branch }).joinToString(" ← ")

    /**
     * *Close Stack…*: deshacer la pila, cerrar sus PRs y borrar sus ramas, en el orden de
     * [ClosePlans]. Antes del dialogo se pregunta al remoto que ramas tiene de verdad: lo que
     * git sabe en local puede estar atrasado, y borrar una rama que ya no existe haria fallar
     * el `git push --delete` entero.
     *
     * Primero `gh stack unstack`, solo. Si gh-stack sigue registrando la pila, GitHub se nego a
     * deshacerla (PRs en cola o con auto-merge) y no se cierra ni se borra nada: si no, quedaria
     * otra pila sin ramas.
     */
    fun closeStack(project: Project) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        val repository = service.repositoryFor(state.repo) ?: return
        val snapshot = state.snapshot
        val name = describe(snapshot)
        service.launch {
            val remoteHeads = withBackgroundProgress(project, message("close.reading")) { readRemoteHeads(service, repository, snapshot) }
            val targets = closeTargets(service, repository, state, remoteHeads)
            val choice = withContext(Dispatchers.EDT) {
                val dialog = CloseStackDialog(project, name, targets)
                // show() y no showAndGet(): lo mismo en un dialogo modal, y lo que interceptan los tests.
                dialog.show()
                if (dialog.isOK) dialog.choice else null
            } ?: return@launch

            var unstacked = false
            service.execute(
                Operation(
                    title = message("op.close"),
                    calls = listOf(ClosePlans.unstack(choice, targets)),
                    repository = repository,
                    onSuccess = { unstacked = true },
                )
            ).join()
            if (!unstacked) return@launch

            val layers = snapshot.layers.mapTo(HashSet()) { it.branch }
            if (service.localStacks(repository).any { entry -> entry.stack.branches.any(layers::contains) }) {
                Notifier.warning(project, message("close.kept.title"), message("close.kept", name), showLog(project))
                return@launch
            }
            val rest = ClosePlans.afterUnstack(choice, targets)
            if (rest.isEmpty()) return@launch Notifier.info(project, message("op.close.done.unstacked", name))
            service.execute(
                Operation(
                    title = message("op.close"),
                    calls = rest,
                    repository = repository,
                    successMessage = message("op.close.done", name),
                )
            )
        }
    }

    /**
     * Lo que tocaria cerrar la pila de [state]. [remoteHeads]: las ramas del remoto, leidas con
     * `git ls-remote`; sin ellas, lo que git sabe del remoto en local.
     */
    fun closeTargets(
        service: StackService,
        repository: GitRepository,
        state: StackState.Loaded,
        remoteHeads: Map<String, String>? = null,
    ): CloseTargets {
        val snapshot = state.snapshot
        val names = stackBranches(snapshot)
        val branches = repository.branches
        val local = names.mapNotNull { name ->
            branches.findLocalBranch(name)?.let(branches::getHash)?.let { name to it.asString() }
        }.toMap()
        val remote = service.stackRemote(repository, snapshot)
        val heads = remoteHeads ?: branches.remoteBranches
            .filter { it.remote.name == remote && it.nameForRemoteOperations in names }
            .mapNotNull { branch -> branches.getHash(branch)?.let { branch.nameForRemoteOperations to it.asString() } }
            .toMap()
        val otherTrunks = service.localStacks(repository).mapTo(HashSet()) { it.stack.trunk }
        return ClosePlans.targets(snapshot, state.details, remote, local, heads, otherTrunks)
    }

    private fun stackBranches(snapshot: StackSnapshot): Set<String> = snapshot.layers.mapTo(LinkedHashSet()) { it.branch } + snapshot.trunk

    /** null si no se pudo preguntar (sin red, sin remoto): entonces vale lo que git sabe en local. */
    private suspend fun readRemoteHeads(service: StackService, repository: GitRepository, snapshot: StackSnapshot): Map<String, String>? {
        val remote = service.stackRemote(repository, snapshot) ?: return null
        val result = try {
            GhCli.run(repository.root.toNioPath(), GitCommands.lsRemoteHeads(remote, stackBranches(snapshot)), tool = Tool.GIT)
        } catch (_: GhNotFoundException) {
            return null
        }
        return if (result.ok) ClosePlans.parseLsRemote(result.stdout) else null
    }

    private fun showLog(project: Project) = Notifier.action(message("notification.show.log")) { StackToolWindow.showLog(project) }

    fun addLayer(project: Project) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        val repository = service.repositoryFor(state.repo) ?: return
        val snapshot = state.snapshot
        val top = snapshot.top ?: return Notifier.info(project, message("add.all.merged"))
        val needsTop = snapshot.currentBranch != top.branch

        // gh-stack no crea capa si la superior aun no tiene commits y se pide commitear: el
        // commit cae en esa capa. El dialogo lo avisa.
        val branches = repository.branches
        val topHash = branches.findLocalBranch(top.branch)?.let(branches::getHash)
        val parentHash = branches.findLocalBranch(snapshot.parentOf(top))?.let(branches::getHash)

        val dialog = AddLayerDialog(project, state, top, needsTop, topIsEmpty = topHash != null && topHash == parentHash)
        if (!dialog.showAndGet()) return
        val calls = buildList {
            if (needsTop) add(GhCall(GhCommands.top()))
            add(GhCall(GhCommands.add(dialog.branch, dialog.commit)))
        }
        service.execute(Operation(message("op.add"), calls, repository, successMessage = message("op.add.done", dialog.branch)))
    }

    /**
     * *Publish Stack…*: capa a capa, cual queda lista para review y con que titulo y
     * descripcion salen las nuevas. Antes de abrir el dialogo se leen los commits de las capas
     * nuevas para proponerlos. [focus]: la capa desde cuyo menu se abrio.
     */
    fun publishStack(project: Project, focus: String? = null) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        val repository = service.repositoryFor(state.repo) ?: return
        val snapshot = state.snapshot
        // Donde gh-stack crea los PRs. Un alias de ~/.ssh/config no es un host que gh entienda.
        val github = snapshot.layers.firstNotNullOfOrNull { layer -> layer.pr?.url?.let(GitHubRepo::fromPullRequestUrl) }
            ?: state.repo.github?.takeUnless { it.isSshAlias }
        val fresh = snapshot.layers.filter { !it.isMerged && it.pr == null }
        service.launch {
            val proposals = try {
                withBackgroundProgress(project, message("publish.reading")) {
                    fresh.associate { layer ->
                        val log = GitCommands.log(gitRef(repository, snapshot.parentOf(layer)), layer.branch)
                        val result = GhCli.run(repository.root.toNioPath(), log, tool = Tool.GIT)
                        // Sin commits legibles se propone el nombre de la rama: el dialogo deja cambiarlo.
                        val commits = if (result.ok) PublishPlans.parseLog(result.stdout) else emptyList()
                        layer.branch to PublishPlans.proposal(layer.branch, commits)
                    }
                }
            } catch (_: GhNotFoundException) {
                emptyMap()
            }
            val layers = withContext(Dispatchers.EDT) {
                val dialog = PublishDialog(project, state, proposals, github, focus)
                if (dialog.showAndGet()) dialog.layers() else null
            } ?: return@launch
            service.execute(
                Operation(
                    title = message("op.publish"),
                    calls = PublishPlans.calls(layers, github),
                    repository = repository,
                    successMessage = message("op.publish.done"),
                )
            )
        }
    }

    /** Una rama como la entiende git: la local o, si solo esta en un remoto, `remoto/rama`. */
    private fun gitRef(repository: GitRepository, branch: String): String {
        val branches = repository.branches
        if (branches.findLocalBranch(branch) != null) return branch
        return branches.remoteBranches.firstOrNull { it.nameForRemoteOperations == branch }?.name ?: branch
    }

    fun publish(project: Project, ready: Boolean) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        val repository = service.repositoryFor(state.repo) ?: return
        val asReady = if (ready) confirmReady(project, state) ?: return else false
        service.execute(
            Operation(
                title = message(if (asReady) "op.publish.ready" else "op.publish.draft"),
                calls = listOf(GhCall(GhCommands.submit(asReady), acceptsRemote = true)),
                repository = repository,
                successMessage = message(if (asReady) "op.publish.ready.done" else "op.publish.draft.done"),
            )
        )
    }

    /**
     * `--open` marca listos TODOS los PRs de la pila, tambien los drafts que ya existian.
     * Antes de hacerlo se ensena exactamente cuales cambian. true: listos; false: publicar
     * como drafts; null: cancelado.
     */
    private fun confirmReady(project: Project, state: StackState.Loaded): Boolean? {
        val active = state.snapshot.layers.filter { !it.isMerged }
        val created = active.filter { it.pr == null }.map { it.branch }
        val drafts = active.filter { state.detailsOf(it)?.isDraft == true }.map(::describe)
        val unknown = active.filter { it.pr != null && state.detailsOf(it) == null }.map(::describe)
        val text = buildString {
            append(message("publish.ready.intro"))
            if (created.isNotEmpty()) append("\n\n").append(message("publish.ready.new", created.joinToString(", ")))
            if (drafts.isNotEmpty()) append("\n\n").append(message("publish.ready.drafts", drafts.joinToString(", ")))
            if (unknown.isNotEmpty()) append("\n\n").append(message("publish.ready.unknown", unknown.joinToString(", ")))
            if (created.isEmpty() && drafts.isEmpty() && unknown.isEmpty()) append("\n\n").append(message("publish.ready.nothing"))
        }
        val choice = MessageDialogBuilder.yesNoCancel(message("publish.ready.title"), text)
            .yesText(message("publish.ready.yes"))
            .noText(message("publish.ready.no"))
            .cancelText(CommonBundle.getCancelButtonText())
            .icon(Messages.getQuestionIcon())
            .show(project)
        return when (choice) {
            Messages.YES -> true
            Messages.NO -> false
            else -> null
        }
    }

    private fun describe(layer: StackLayer): String = "#${layer.pr?.number} ${layer.branch}"

    fun sync(project: Project) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        service.execute(
            Operation(
                title = message("op.sync"),
                calls = listOf(GhCall(GhCommands.sync(), acceptsRemote = true)),
                repository = service.repositoryFor(state.repo),
                successMessage = message("op.sync.done"),
            )
        )
    }

    /** Lleva a las capas de encima lo que cambio en [from] (`gh stack rebase --upstack --no-trunk`). */
    fun rebaseUpstack(project: Project, from: String) {
        val service = StackService.getInstance(project)
        val state = service.state.value as? StackState.Loaded ?: return
        val repository = service.repositoryFor(state.repo) ?: return
        service.rebaseUpstackFrom(repository, from)
    }

    /**
     * `gh stack checkout`: si la pila solo existe en GitHub, gh-stack la trae y la registra.
     * [label]: como se nombra en el progreso; un PR, por su numero y no por su URL.
     */
    fun checkoutStack(project: Project, target: String, repository: GitRepository? = null, label: String = target) {
        StackService.getInstance(project).execute(
            Operation(
                title = message("op.checkout.stack", label),
                calls = listOf(GhCall(GhCommands.checkout(target))),
                repository = repository,
                onSuccess = { withContext(Dispatchers.EDT) { StackToolWindow.show(project) } },
            )
        )
    }

    fun checkoutStackByInput(project: Project) {
        val input = Messages.showInputDialog(project, message("checkout.prompt"), message("checkout.title"), null)
            ?.trim().orEmpty()
        if (input.isNotEmpty()) checkoutStack(project, input)
    }

    fun installExtension(project: Project) {
        StackService.getInstance(project).execute(
            Operation(
                title = message("op.install"),
                calls = listOf(GhCall(GhCommands.installExtension())),
                workDir = Path.of(System.getProperty("user.home")),
                successMessage = message("op.install.done"),
            )
        )
    }

    // ------------------------------------------------------------------ un PR

    fun setDraft(project: Project, target: PrTarget, draft: Boolean) {
        val args = if (draft) GhCommands.markDraft(target.url) else GhCommands.markReady(target.url)
        executeOnPr(
            project, target,
            title = message(if (draft) "op.draft" else "op.ready", target.number),
            calls = listOf(GhCall(args)),
            success = message(if (draft) "op.draft.done" else "op.ready.done", target.number),
        )
    }

    fun editLabels(project: Project, target: PrTarget) {
        val service = StackService.getInstance(project)
        val workDir = workDirFor(service, target)
        service.launch {
            val (all, applied) = try {
                withBackgroundProgress(project, message("labels.loading", target.number)) {
                    coroutineScope {
                        val all = async { GhCli.run(workDir, GhCommands.repoLabels(target.github)) }
                        val applied = async { GhCli.run(workDir, GhCommands.prLabels(target.url)) }
                        all.await() to applied.await()
                    }
                }
            } catch (_: GhNotFoundException) {
                return@launch Notifier.error(project, message("labels.error"), message("state.gh.missing"))
            }
            listOf(all, applied).firstOrNull { !it.ok }?.let { failed ->
                return@launch Notifier.error(project, message("labels.error"), Notifier.html(failed.errorText))
            }
            val current = StackJson.parsePrLabelNames(applied.stdout)
            val selected = withContext(Dispatchers.EDT) {
                val dialog = LabelsDialog(project, target.url, target.number, StackJson.parseRepoLabels(all.stdout), current)
                if (dialog.showAndGet()) dialog.selectedLabels() else null
            } ?: return@launch
            val toAdd = selected - current
            val toRemove = current - selected
            if (toAdd.isEmpty() && toRemove.isEmpty()) return@launch
            executeOnPr(
                project, target,
                title = message("op.labels", target.number),
                calls = listOf(GhCall(GhCommands.editLabels(target.url, toAdd, toRemove))),
                success = message("op.labels.done", target.number),
            )
        }
    }

    /**
     * Pone o quita la label de capa final (por defecto `stack-final`). Al ponerla en una capa
     * de la pila se quita de las demas capas que la tuvieran: una pila tiene un solo final.
     */
    fun toggleFinal(project: Project, target: PrTarget) {
        val label = StacklaneSettings.getInstance().finalLabel
        val service = StackService.getInstance(project)
        service.launch {
            // Desde Stacks ya se sabe; desde Pull Requests hay que preguntarlo.
            val hasLabel = target.details?.hasLabel(label) ?: run {
                val result = try {
                    withBackgroundProgress(project, message("labels.loading", target.number)) {
                        GhCli.run(workDirFor(service, target), GhCommands.prLabels(target.url))
                    }
                } catch (_: GhNotFoundException) {
                    return@launch Notifier.error(project, message("labels.error"), message("state.gh.missing"))
                }
                if (!result.ok) return@launch Notifier.error(project, message("labels.error"), Notifier.html(result.errorText))
                StackJson.parsePrLabelNames(result.stdout).any { it.equals(label, ignoreCase = true) }
            }

            if (hasLabel) {
                executeOnPr(
                    project, target,
                    title = message("op.final.remove", label, target.number),
                    calls = listOf(GhCall(GhCommands.editLabels(target.url, emptyList(), listOf(label)))),
                    success = message("op.final.removed", label, target.number),
                )
                return@launch
            }

            val others = otherFinalLayers(target, label)
            val calls = listOf(GhCall(GhCommands.editLabels(target.url, listOf(label), emptyList()))) +
                others.map { GhCall(GhCommands.editLabels(urlOf(it, target.github), emptyList(), listOf(label))) }
            val success = if (others.isEmpty()) message("op.final.added", label, target.number)
            else message("op.final.moved", label, target.number, others.joinToString(", ") { "#${it.pr?.number}" })
            val title = message("op.final.add", label, target.number)

            executeOnPr(project, target, title, calls, success, onFailure = { result ->
                // La label no existe en el repositorio: se ofrece crearla y repetir.
                if (!result.errorText.contains("not found", ignoreCase = true)) return@executeOnPr false
                Notifier.warning(
                    project, message("final.missing.title", label), message("final.missing", label, target.github.cliName),
                    Notifier.action(message("final.create")) {
                        executeOnPr(project, target, title, listOf(GhCall(GhCommands.createLabel(target.github, label))) + calls, success)
                    },
                )
                true
            })
        }
    }

    /** Las otras capas de la pila con la label de capa final: al ponerla en [target], se les quita. */
    fun otherFinalLayers(target: PrTarget, label: String): List<StackLayer> = target.selection?.state?.let { stack ->
        stack.snapshot.layers.filter { layer ->
            val pr = layer.pr
            pr != null && pr.number != target.number && stack.detailsOf(layer)?.hasLabel(label) == true
        }
    }.orEmpty()

    fun checkoutFromPullRequest(project: Project, target: PrTarget) {
        val repository = StackService.getInstance(project).repositoryFor(target.github)
        if (repository == null) {
            Notifier.error(project, message("op.checkout.stack", "#${target.number}"), message("checkout.no.repository", target.github.cliName))
            return
        }
        checkoutStack(project, target.url, repository, label = "#${target.number}")
    }

    /**
     * El PR de la capa apunta a otra rama que la de debajo ([base]): se le pone esa. Solo
     * cambia este PR; el aviso dice cual tenia, por si era la buena.
     */
    fun changeBase(project: Project, target: PrTarget, base: String) {
        val previous = target.details?.baseRef.orEmpty()
        executeOnPr(
            project, target,
            title = message("op.base", target.number, base),
            calls = listOf(GhCall(GhCommands.editBase(target.url, base))),
            success = message("op.base.done", target.number, base, previous),
        )
    }

    /**
     * Lo que anade la capa sobre la de debajo, como lo ensena su PR, en el diff del IDE. Si la
     * de debajo avanzo despues (la capa necesita rebase), desde su ultimo commit comun: si no,
     * lo nuevo de abajo saldria como si esta capa lo quitara.
     */
    fun showLayerChanges(project: Project, selection: LayerSelection) {
        val service = StackService.getInstance(project)
        val repository = service.repositoryFor(selection.state.repo) ?: return
        val layer = selection.layer
        val branch = gitRef(repository, layer.branch)
        val parent = gitRef(repository, selection.state.snapshot.parentOf(layer))
        service.launch {
            val base = if (!layer.needsRebase) parent else try {
                GhCli.run(repository.root.toNioPath(), GitCommands.mergeBase(parent, branch), tool = Tool.GIT)
                    .takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }?.take(SHORT_HASH)
            } catch (_: GhNotFoundException) {
                null
            } ?: parent
            withContext(Dispatchers.EDT) { GitBrancher.getInstance(project).showDiff(base, branch, listOf(repository)) }
        }
    }

    private fun executeOnPr(
        project: Project,
        target: PrTarget,
        title: String,
        calls: List<GhCall>,
        success: String,
        onFailure: ((GhResult) -> Boolean)? = null,
    ) {
        val service = StackService.getInstance(project)
        val component = target.pullRequestsComponent
        service.execute(
            Operation(
                title = title,
                calls = calls,
                repository = target.selection?.state?.repo?.let(service::repositoryFor) ?: service.repositoryFor(target.github),
                successMessage = success,
                onSuccess = component?.let { { withContext(Dispatchers.EDT) { PullRequestsViews.reload(it) } } },
                onFailure = onFailure,
            )
        )
    }

    // `gh pr` y `gh label` con URL o --repo funcionan desde cualquier sitio; se prefiere el
    // repositorio del PR para que gh vea la misma configuracion que en la terminal.
    private fun workDirFor(service: StackService, target: PrTarget): Path =
        (target.selection?.state?.repo?.root ?: service.repositoryFor(target.github)?.root ?: service.repository()?.root)
            ?.toNioPath() ?: Path.of(System.getProperty("user.home"))

    fun urlOf(layer: StackLayer, github: GitHubRepo): String {
        val pr = requireNotNull(layer.pr)
        return pr.url.ifEmpty { "https://${github.host}/${github.owner}/${github.name}/pull/${pr.number}" }
    }
}
