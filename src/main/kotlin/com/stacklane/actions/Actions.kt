package com.stacklane.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.RebaseScope
import com.stacklane.settings.StacklaneConfigurable
import com.stacklane.settings.StacklaneSettings
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.PrState
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackService
import com.stacklane.stack.StackSnapshot
import com.stacklane.stack.StackState
import com.stacklane.stack.repo
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

internal abstract class StacklaneAction(icon: Icon? = null) : DumbAwareAction() {

    init {
        if (icon != null) templatePresentation.icon = icon
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Hay una escritura en curso. No crea el servicio si nadie lo ha usado todavia. */
    protected fun busy(project: Project?): Boolean =
        project?.getServiceIfCreated(StackService::class.java)?.running?.value != null
}

// ---------------------------------------------------------------------- barra de Stacks

/** Accion de la barra: depende del estado de la pila y espera a que acabe la escritura en curso. */
internal abstract class ToolbarAction(icon: Icon) : StacklaneAction(icon) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.getServiceIfCreated(StackService::class.java)?.state?.value
        e.presentation.isEnabled = state != null && !busy(project) && isEnabled(state)
    }

    protected abstract fun isEnabled(state: StackState): Boolean

    protected fun hasActiveLayers(state: StackState): Boolean =
        state is StackState.Loaded && state.snapshot.layers.any { !it.isMerged }
}

internal class RefreshAction : ToolbarAction(AllIcons.Actions.Refresh) {
    override fun isEnabled(state: StackState): Boolean = true
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackService.getInstance(it).requestRefresh() }
    }
}

internal class InitStackAction : ToolbarAction(AllIcons.General.Add) {
    // Siempre que haya repositorio: una pila nueva puede partir de cualquier rama, tambien
    // estando ya dentro de otra pila.
    override fun isEnabled(state: StackState): Boolean = state.repo != null
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::initStack)
    }
}

internal class AddLayerAction : ToolbarAction(AllIcons.Vcs.Branch) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::addLayer)
    }
}

/** Desde el menu de una capa, el dialogo pone el foco en ella. */
internal class PublishStackAction : ToolbarAction(AllIcons.Actions.Upload) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StackFlows.publishStack(project, focus = e.getData(StackDataKeys.LAYER)?.layer?.branch)
    }
}

internal class PublishDraftAction : ToolbarAction(AllIcons.Vcs.Push) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackFlows.publish(it, ready = false) }
    }
}

internal class PublishReadyAction : ToolbarAction(AllIcons.General.InspectionsOK) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { StackFlows.publish(it, ready = true) }
    }
}

internal class SyncAction : ToolbarAction(AllIcons.Actions.CheckOut) {
    override fun isEnabled(state: StackState): Boolean = hasActiveLayers(state)
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(StackFlows::sync)
    }
}

/** El desplegable *Rebase* de la barra. Cada alcance es una accion (ver [RebaseScopeAction]). */
internal class RebaseGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.getServiceIfCreated(StackService::class.java)
        val state = service?.state?.value as? StackState.Loaded
        e.presentation.isEnabled = state != null && service.running.value == null &&
            !state.repo.stackRebaseInProgress && state.snapshot.layers.any { !it.isMerged }
    }
}

/**
 * Un alcance de `gh stack rebase`. Con un rebase parado a medias no se ofrece: gh-stack solo
 * acepta `--continue` o `--abort`. El texto nombra las ramas a las que afecta.
 */
internal abstract class RebaseScopeAction(icon: Icon, private val scope: RebaseScope) : ToolbarAction(icon) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val state = e.project?.getServiceIfCreated(StackService::class.java)?.state?.value as? StackState.Loaded
        e.presentation.text = state?.snapshot?.let(::text) ?: templateText
    }

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

    override fun update(e: AnActionEvent) {
        val selection = e.getData(StackDataKeys.LAYER)
        e.presentation.isVisible = selection != null
        e.presentation.isEnabled = selection != null && !selection.layer.isCurrent && !busy(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(StackDataKeys.LAYER) ?: return
        val service = StackService.getInstance(project)
        val repository = service.repositoryFor(selection.state.repo) ?: return
        service.checkout(repository, selection.layer.branch)
    }
}

/** `gh stack rebase --upstack --no-trunk` desde esta capa, con checkout antes si no es la actual. */
internal class RebaseUpstackFromLayerAction : StacklaneAction(AllIcons.Actions.MoveUp) {

    override fun update(e: AnActionEvent) {
        val selection = e.getData(StackDataKeys.LAYER)
        e.presentation.isVisible = selection != null && !selection.layer.isMerged
        e.presentation.isEnabled = e.presentation.isVisible && !busy(e.project) &&
            selection?.state?.repo?.stackRebaseInProgress == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(StackDataKeys.LAYER) ?: return
        StackFlows.rebaseUpstack(project, selection.layer.branch)
    }
}

// ---------------------------------------------------------------------- una pila local sin ramas

/** Acciones sobre una pila local cuyas ramas ya no existen (ver StackFlows.cleanUpStaleStack). */
internal abstract class StaleStackAction(icon: Icon? = null) : StacklaneAction(icon) {

    override fun update(e: AnActionEvent) {
        val entry = e.getData(StackDataKeys.LOCAL_STACK)
        e.presentation.isVisible = entry != null && entry.isStale
        e.presentation.isEnabled = e.presentation.isVisible && !busy(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, e.getData(StackDataKeys.LOCAL_STACK) ?: return)
    }

    protected abstract fun perform(project: Project, entry: LocalStackEntry)
}

internal class ForgetStackAction : StaleStackAction(AllIcons.Actions.GC) {
    override fun perform(project: Project, entry: LocalStackEntry) = StackFlows.forgetStack(project, entry)
}

internal class RecreateStackAction : StaleStackAction(AllIcons.Actions.Restart) {
    override fun perform(project: Project, entry: LocalStackEntry) = StackFlows.initStack(project, prefill = entry.stack)
}

// ---------------------------------------------------------------------- un PR (Stacks y Pull Requests)

/**
 * Accion sobre un PR. La misma clase sirve en el menu de una capa de Stacks y en los menus
 * de la ventana Pull Requests: [PrTarget.from] resuelve el PR en los dos contextos, y si no
 * hay PR la accion no se ve.
 */
internal abstract class PrAction(icon: Icon? = null) : StacklaneAction(icon) {

    final override fun update(e: AnActionEvent) {
        val target = PrTarget.from(e)
        e.presentation.isVisible = target != null && isVisible(target)
        e.presentation.isEnabled = e.presentation.isVisible && (!needsIdle || !busy(e.project))
        if (target != null) updateText(e, target)
    }

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
    override fun perform(project: Project, target: PrTarget) = StackFlows.setDraft(project, target, draft = false)
}

internal class MarkDraftAction : PrAction(AllIcons.Actions.Undo) {
    override fun isVisible(target: PrTarget): Boolean = isOpen(target) && target.details?.isDraft != true
    override fun perform(project: Project, target: PrTarget) = StackFlows.setDraft(project, target, draft = true)
}

internal class EditLabelsAction : PrAction(AllIcons.Nodes.Tag) {
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

    override fun perform(project: Project, target: PrTarget) = StackFlows.toggleFinal(project, target)
}

/** Solo en Pull Requests: traer la pila de ese PR al checkout local (`gh stack checkout <url>`). */
internal class CheckoutStackFromPrAction : PrAction(AllIcons.Actions.CheckOut) {
    override fun isVisible(target: PrTarget): Boolean = target.pullRequestsComponent != null
    override fun perform(project: Project, target: PrTarget) = StackFlows.checkoutFromPullRequest(project, target)
}
