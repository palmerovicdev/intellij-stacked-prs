package com.stacklane.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.gh.RebaseScope
import com.stacklane.settings.StacklaneConfigurable
import com.stacklane.settings.StacklaneSettings
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.Position
import com.stacklane.stack.PrState
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackPlans
import com.stacklane.stack.StackService
import com.stacklane.stack.StackSnapshot
import com.stacklane.stack.StackState
import com.stacklane.stack.repo
import com.stacklane.ui.Help
import com.stacklane.ui.Helps
import com.stacklane.ui.showHelp
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

/**
 * Base de todas las acciones. [TooltipDescriptionProvider]: sin el, el tooltip de la barra
 * de herramientas ensena solo el nombre, no la descripcion.
 */
internal abstract class StacklaneAction(icon: Icon? = null) : DumbAwareAction(), TooltipDescriptionProvider {

    init {
        if (icon != null) templatePresentation.icon = icon
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** El estado de la accion y, si se ve, su ayuda: que hace y que comandos ejecuta. */
    final override fun update(e: AnActionEvent) {
        updateState(e)
        if (e.presentation.isVisible) e.presentation.showHelp(help(e))
    }

    protected open fun updateState(e: AnActionEvent) = Unit

    /** Por defecto, la descripcion del bundle y los [commands]. */
    protected open fun help(e: AnActionEvent): Help = Help(templatePresentation.description.orEmpty(), commands(e))

    /** Lo que se ejecuta, como en la terminal, con los datos del sitio desde donde se abre. */
    protected open fun commands(e: AnActionEvent): List<String> = emptyList()

    /** Hay una escritura en curso. No crea el servicio si nadie lo ha usado todavia. */
    protected fun busy(project: Project?): Boolean =
        project?.getServiceIfCreated(StackService::class.java)?.running?.value != null
}

// ---------------------------------------------------------------------- barra de Stacks

/** Accion de la barra: depende del estado de la pila y espera a que acabe la escritura en curso. */
internal abstract class ToolbarAction(icon: Icon) : StacklaneAction(icon) {

    override fun updateState(e: AnActionEvent) {
        val project = e.project
        val state = project?.getServiceIfCreated(StackService::class.java)?.state?.value
        e.presentation.isEnabled = state != null && !busy(project) && isEnabled(state)
    }

    protected abstract fun isEnabled(state: StackState): Boolean

    protected fun loaded(e: AnActionEvent): StackState.Loaded? =
        e.project?.getServiceIfCreated(StackService::class.java)?.state?.value as? StackState.Loaded

    protected fun hasActiveLayers(state: StackState): Boolean =
        state is StackState.Loaded && state.snapshot.layers.any { !it.isMerged }
}

internal class RefreshAction : ToolbarAction(AllIcons.Actions.Refresh) {
    override fun isEnabled(state: StackState): Boolean = true
    override fun help(e: AnActionEvent): Help = Helps.refresh()
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackService.getInstance(it).requestRefresh() }
    }
}

internal class InitStackAction : ToolbarAction(AllIcons.General.Add) {
    // Siempre que haya repositorio: una pila nueva puede partir de cualquier rama, tambien
    // estando ya dentro de otra pila.
    override fun isEnabled(state: StackState): Boolean = state.repo != null
    override fun help(e: AnActionEvent): Help = Helps.initStack()
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::initStack)
    }
}

/** Siempre encima de la capa superior: si no es la rama actual, antes `gh stack top`. */
internal class AddLayerAction : ToolbarAction(AllIcons.Vcs.Branch) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun commands(e: AnActionEvent): List<String> {
        val snapshot = loaded(e)?.snapshot
        val needsTop = snapshot?.top?.let { it.branch != snapshot.currentBranch } == true
        return listOfNotNull(Help.gh(GhCommands.top()).takeIf { needsTop }, "gh stack add [-A | -u] [-m MESSAGE] BRANCH")
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::addLayer)
    }
}

/** Con una capa seleccionada en la ventana, el dialogo pone el foco en ella. */
internal class PublishStackAction : ToolbarAction(AllIcons.Actions.Upload) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun commands(e: AnActionEvent): List<String> = listOf(
        Help.gh(GhCommands.submit(ready = false)),
        "gh pr edit BRANCH --title TITLE --body BODY",
        "gh pr ready PR",
        "gh pr ready PR --undo",
    )
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StackFlows.publishStack(project, focus = e.getData(StackDataKeys.LAYER)?.layer?.branch)
    }
}

internal class PublishDraftAction : ToolbarAction(AllIcons.Vcs.Push) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun commands(e: AnActionEvent): List<String> = listOf(Help.gh(GhCommands.submit(ready = false)))
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackFlows.publish(it, ready = false) }
    }
}

internal class PublishReadyAction : ToolbarAction(AllIcons.General.InspectionsOK) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun commands(e: AnActionEvent): List<String> = listOf(Help.gh(GhCommands.submit(ready = true)))
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackFlows.publish(it, ready = true) }
    }
}

internal class SyncAction : ToolbarAction(AllIcons.Actions.CheckOut) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun commands(e: AnActionEvent): List<String> = listOf(Help.gh(GhCommands.sync()))
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::sync)
    }
}

/** El desplegable *Rebase* de la barra. Cada alcance es una accion (ver [RebaseScopeAction]). */
internal class RebaseGroup : DefaultActionGroup(), TooltipDescriptionProvider {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.getServiceIfCreated(StackService::class.java)
        val state = service?.state?.value as? StackState.Loaded
        e.presentation.isEnabled = state != null && service.running.value == null &&
            !state.repo.stackRebaseInProgress && state.snapshot.layers.any { !it.isMerged }
        e.presentation.showHelp(Help(templatePresentation.description.orEmpty(), listOf("gh stack rebase [--upstack | --downstack] [--no-trunk]")))
    }
}

/**
 * Un alcance de `gh stack rebase`. Con un rebase parado a medias no se ofrece: gh-stack solo
 * acepta `--continue` o `--abort`. El texto nombra las ramas a las que afecta.
 */
internal abstract class RebaseScopeAction(icon: Icon, private val scope: RebaseScope) : ToolbarAction(icon) {

    override fun updateState(e: AnActionEvent) {
        super.updateState(e)
        e.presentation.text = loaded(e)?.snapshot?.let(::text) ?: templateText
    }

    override fun commands(e: AnActionEvent): List<String> = listOf(Help.gh(GhCommands.rebase(scope)))

    override fun isEnabled(state: StackState): Boolean =
        state is StackState.Loaded && !state.repo.stackRebaseInProgress && hasActiveLayers(state) && appliesTo(state.snapshot)

    protected open fun appliesTo(snapshot: StackSnapshot): Boolean = true

    protected open fun text(snapshot: StackSnapshot): String? = null

    /** La capa actual, si es una capa activa: `--upstack` y `--downstack` parten de ella. */
    protected fun currentLayer(snapshot: StackSnapshot): StackLayer? = snapshot.current?.takeIf { !it.isMerged }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = StackService.getInstance(project)
        val repository = (service.state.value as? StackState.Loaded)?.repo?.let(service::repositoryFor) ?: return
        service.rebase(scope, repository)
    }
}

internal class RebaseUpstackAction : RebaseScopeAction(AllIcons.Actions.MoveUp, RebaseScope.UPSTACK) {
    override fun appliesTo(snapshot: StackSnapshot): Boolean = currentLayer(snapshot) != null
    override fun text(snapshot: StackSnapshot): String? =
        currentLayer(snapshot)?.let { message("action.rebase.upstack.from", it.branch) }
}

internal class RebaseAction : RebaseScopeAction(AllIcons.Vcs.Merge, RebaseScope.STACK) {
    override fun text(snapshot: StackSnapshot): String = message("action.rebase.stack.onto", snapshot.trunk)
}

internal class RebaseDownstackAction : RebaseScopeAction(AllIcons.Actions.MoveDown, RebaseScope.DOWNSTACK) {
    override fun appliesTo(snapshot: StackSnapshot): Boolean = currentLayer(snapshot) != null
    override fun text(snapshot: StackSnapshot): String? =
        currentLayer(snapshot)?.let { message("action.rebase.downstack.to", snapshot.trunk, it.branch) }
}

internal class RebaseLayersAction : RebaseScopeAction(AllIcons.Vcs.Branch, RebaseScope.LAYERS)

internal class OpenSettingsAction : StacklaneAction(AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, StacklaneConfigurable::class.java)
    }
}

// ---------------------------------------------------------------------- una capa

internal class CheckoutLayerAction : StacklaneAction(AllIcons.Actions.CheckOut) {

    override fun updateState(e: AnActionEvent) {
        val selection = e.getData(StackDataKeys.LAYER)
        e.presentation.isVisible = selection != null
        e.presentation.isEnabled = selection != null && !selection.layer.isCurrent && !busy(e.project)
    }

    override fun commands(e: AnActionEvent): List<String> = listOfNotNull(e.getData(StackDataKeys.LAYER)?.layer?.branch?.let(Help::checkout))

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(StackDataKeys.LAYER) ?: return
        val service = StackService.getInstance(project)
        val repository = service.repositoryFor(selection.state.repo) ?: return
        service.checkout(repository, selection.layer.branch)
    }
}

/** Lo que anade esta capa sobre la de debajo, en el diff del IDE (ver StackFlows.showLayerChanges). */
internal class ShowLayerChangesAction : StacklaneAction(AllIcons.Actions.Diff) {

    override fun updateState(e: AnActionEvent) {
        val selection = e.getData(StackDataKeys.LAYER)
        e.presentation.isEnabledAndVisible = selection != null && !selection.layer.isMerged
    }

    override fun help(e: AnActionEvent): Help =
        e.getData(StackDataKeys.LAYER)?.let { Helps.layerChanges(it.state.snapshot, it.layer) } ?: super.help(e)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StackFlows.showLayerChanges(project, e.getData(StackDataKeys.LAYER) ?: return)
    }
}

/** `gh stack rebase --upstack --no-trunk` desde esta capa, con checkout antes si no es la actual. */
internal class RebaseUpstackFromLayerAction : StacklaneAction(AllIcons.Actions.MoveUp) {

    override fun updateState(e: AnActionEvent) {
        val selection = e.getData(StackDataKeys.LAYER)
        e.presentation.isVisible = selection != null && !selection.layer.isMerged
        e.presentation.isEnabled = e.presentation.isVisible && !busy(e.project) &&
            selection?.state?.repo?.stackRebaseInProgress == false
    }

    override fun help(e: AnActionEvent): Help =
        e.getData(StackDataKeys.LAYER)?.let { Helps.rebaseUpstackFrom(it.state.snapshot, it.layer.branch) } ?: super.help(e)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(StackDataKeys.LAYER) ?: return
        StackFlows.rebaseUpstack(project, selection.layer.branch)
    }
}

// ---------------------------------------------------------------------- una pila local sin ramas

/** Acciones sobre una pila local cuyas ramas ya no existen (ver StackFlows.cleanUpStaleStack). */
internal abstract class StaleStackAction(icon: Icon? = null) : StacklaneAction(icon) {

    override fun updateState(e: AnActionEvent) {
        val entry = e.getData(StackDataKeys.LOCAL_STACK)
        e.presentation.isVisible = entry != null && entry.isStale
        e.presentation.isEnabled = e.presentation.isVisible && !busy(e.project)
    }

    override fun commands(e: AnActionEvent): List<String> {
        val entry = e.getData(StackDataKeys.LOCAL_STACK) ?: return emptyList()
        val repository = e.project?.getServiceIfCreated(StackService::class.java)?.repository() ?: return emptyList()
        return commands(entry, Position(repository.currentBranchName, repository.currentRevision))
    }

    /** [from]: donde esta HEAD, adonde se vuelve tras olvidar la pila. */
    protected abstract fun commands(entry: LocalStackEntry, from: Position): List<String>

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, e.getData(StackDataKeys.LOCAL_STACK) ?: return)
    }

    protected abstract fun perform(project: Project, entry: LocalStackEntry)

    protected fun forget(entry: LocalStackEntry, from: Position): List<String> =
        StackPlans.forget(entry.stack, from)?.calls?.let(Help::of).orEmpty()
}

internal class ForgetStackAction : StaleStackAction(AllIcons.Actions.GC) {
    override fun commands(entry: LocalStackEntry, from: Position): List<String> = forget(entry, from)
    override fun perform(project: Project, entry: LocalStackEntry) = StackFlows.forgetStack(project, entry)
}

/** Lo que propone el dialogo de pila nueva: olvidarla, recuperar sus ramas y `gh stack init`. */
internal class RecreateStackAction : StaleStackAction(AllIcons.Actions.Restart) {
    override fun commands(entry: LocalStackEntry, from: Position): List<String> =
        forget(entry, from) + Help.of(StackPlans.restore(entry.restorable).calls) +
            Help.gh(GhCommands.init(entry.stack.branches, "BASE"))

    override fun perform(project: Project, entry: LocalStackEntry) = StackFlows.initStack(project, prefill = entry.stack)
}

// ---------------------------------------------------------------------- un PR (Stacks y Pull Requests)

/**
 * Accion sobre un PR. La misma clase sirve en el menu de una capa de Stacks y en los menus
 * de la ventana Pull Requests: [PrTarget.from] resuelve el PR en los dos contextos, y si no
 * hay PR la accion no se ve.
 */
internal abstract class PrAction(icon: Icon? = null) : StacklaneAction(icon) {

    final override fun updateState(e: AnActionEvent) {
        val target = PrTarget.from(e)
        e.presentation.isVisible = target != null && isVisible(target)
        e.presentation.isEnabled = e.presentation.isVisible && (!needsIdle || !busy(e.project))
        if (target != null) updateText(e, target)
    }

    final override fun help(e: AnActionEvent): Help = PrTarget.from(e)?.let(::help) ?: super.help(e)

    protected open fun help(target: PrTarget): Help = Help(templatePresentation.description.orEmpty(), commands(target))

    protected open fun commands(target: PrTarget): List<String> = emptyList()

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, PrTarget.from(e) ?: return)
    }

    /** Las que escriben esperan a que acabe la escritura en curso; abrir o copiar, no. */
    protected open val needsIdle: Boolean get() = true

    protected open fun isVisible(target: PrTarget): Boolean = true

    protected open fun updateText(e: AnActionEvent, target: PrTarget) = Unit

    protected abstract fun perform(project: Project, target: PrTarget)

    /** El PR sigue abierto. Sin datos de GitHub (menu de Pull Requests) se supone que si. */
    protected fun isOpen(target: PrTarget): Boolean =
        target.details?.let { it.state == PrState.OPEN } ?: (target.selection?.layer?.isMerged != true)
}

internal class OpenPrAction : PrAction(AllIcons.General.Web) {
    override val needsIdle: Boolean get() = false
    override fun perform(project: Project, target: PrTarget) = BrowserUtil.browse(target.url)
}

internal class CopyPrLinkAction : PrAction(AllIcons.Actions.Copy) {
    override val needsIdle: Boolean get() = false
    override fun perform(project: Project, target: PrTarget) = CopyPasteManager.getInstance().setContents(StringSelection(target.url))
}

internal class MarkReadyAction : PrAction(AllIcons.Actions.Checked) {
    override fun isVisible(target: PrTarget): Boolean = isOpen(target) && target.details?.isDraft != false
    override fun commands(target: PrTarget): List<String> = listOf(Help.gh(GhCommands.markReady(target.url)))
    override fun perform(project: Project, target: PrTarget) = StackFlows.setDraft(project, target, draft = false)
}

internal class MarkDraftAction : PrAction(AllIcons.Actions.Undo) {
    override fun isVisible(target: PrTarget): Boolean = isOpen(target) && target.details?.isDraft != true
    override fun commands(target: PrTarget): List<String> = listOf(Help.gh(GhCommands.markDraft(target.url)))
    override fun perform(project: Project, target: PrTarget) = StackFlows.setDraft(project, target, draft = true)
}

/**
 * Solo en Stacks, y solo si el PR apunta en GitHub a otra rama que la capa de debajo: se le
 * pone esa (ver StackSnapshot.wrongBase).
 */
internal class ChangePrBaseAction : PrAction(AllIcons.Actions.Edit) {

    override fun isVisible(target: PrTarget): Boolean = wrongBase(target) != null

    override fun updateText(e: AnActionEvent, target: PrTarget) {
        parent(target)?.let { e.presentation.text = message("action.change.base", it) }
    }

    override fun help(target: PrTarget): Help {
        val parent = parent(target) ?: return super.help(target)
        return Help(message("help.change.base", wrongBase(target).orEmpty(), parent), listOf(Help.gh(GhCommands.editBase(target.url, parent))))
    }

    override fun perform(project: Project, target: PrTarget) {
        StackFlows.changeBase(project, target, parent(target) ?: return)
    }

    private fun wrongBase(target: PrTarget): String? = target.selection?.let { it.state.snapshot.wrongBase(it.layer, it.details) }

    private fun parent(target: PrTarget): String? = target.selection?.let { it.state.snapshot.parentOf(it.layer) }
}

internal class EditLabelsAction : PrAction(AllIcons.Nodes.Tag) {
    override fun commands(target: PrTarget): List<String> =
        listOf(Help.gh(listOf("pr", "edit", target.url)) + " --add-label LABEL --remove-label LABEL")
    override fun perform(project: Project, target: PrTarget) = StackFlows.editLabels(project, target)
}

internal class ToggleFinalAction : PrAction(AllIcons.Nodes.Target) {

    override fun updateText(e: AnActionEvent, target: PrTarget) {
        val label = StacklaneSettings.getInstance().finalLabel
        e.presentation.text = when (target.details?.hasLabel(label)) {
            true -> message("action.final.remove", label)
            false -> message("action.final.add", label)
            null -> message("action.final.toggle", label)
        }
    }

    /** Desde Pull Requests no se sabe si la tiene: se lee antes y se pone o se quita. */
    override fun help(target: PrTarget): Help {
        val label = StacklaneSettings.getInstance().finalLabel
        val add = Help.gh(GhCommands.editLabels(target.url, listOf(label), emptyList()))
        val remove = Help.gh(GhCommands.editLabels(target.url, emptyList(), listOf(label)))
        return when (target.details?.hasLabel(label)) {
            true -> Help(message("help.final.remove", label), listOf(remove))
            false -> Help(
                message("help.final.add", label),
                listOf(add) + StackFlows.otherFinalLayers(target, label).map {
                    Help.gh(GhCommands.editLabels(StackFlows.urlOf(it, target.github), emptyList(), listOf(label)))
                },
            )
            null -> Help(message("help.final.toggle", label), listOf(Help.gh(GhCommands.prLabels(target.url)), add, remove))
        }
    }

    override fun perform(project: Project, target: PrTarget) = StackFlows.toggleFinal(project, target)
}

/** Solo en Pull Requests: traer la pila de ese PR al checkout local (`gh stack checkout <url>`). */
internal class CheckoutStackFromPrAction : PrAction(AllIcons.Actions.CheckOut) {
    override fun isVisible(target: PrTarget): Boolean = target.pullRequestsComponent != null
    override fun commands(target: PrTarget): List<String> = listOf(Help.gh(GhCommands.checkout(target.url)))
    override fun perform(project: Project, target: PrTarget) = StackFlows.checkoutFromPullRequest(project, target)
}
