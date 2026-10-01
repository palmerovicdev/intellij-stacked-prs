package com.stacklane.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.stacklane.stack.GitHubRepo
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.MissingBranch
import com.stacklane.stack.PrDetails
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackState
import java.awt.Component

/** La capa seleccionada en la ventana Stacks, con la pila a la que pertenece. */
data class LayerSelection(val state: StackState.Loaded, val layer: StackLayer) {
    val details: PrDetails? get() = state.detailsOf(layer)

    /** Si la rama de la capa ya no esta en local, lo que se sabe para recuperarla. */
    val missing: MissingBranch? get() = state.missingOf(layer)
}

object StackDataKeys {
    val LAYER: DataKey<LayerSelection> = DataKey.create("stacklane.layer")

    /** Una pila local seleccionada, cuando la rama actual no esta en ninguna. */
    val LOCAL_STACK: DataKey<LocalStackEntry> = DataKey.create("stacklane.local.stack")
}

/**
 * El PR sobre el que actua una accion: el de la capa seleccionada en Stacks o el
 * seleccionado en la ventana Pull Requests de IntelliJ (lista o detalle).
 */
internal class PrTarget(
    val url: String,
    val number: Int,
    val github: GitHubRepo,
    /** Solo desde Stacks: la pila y lo que ya se sabe del PR. */
    val selection: LayerSelection?,
    /** Solo desde Pull Requests: su componente, para refrescar sus vistas al terminar. */
    val pullRequestsComponent: Component?,
) {
    val details: PrDetails? get() = selection?.details

    companion object {
        /**
         * La URL del PR seleccionado en Pull Requests.
         *
         * El plugin GitHub la publica con este nombre desde `GHPRActionKeys`, una clase
         * `@ApiStatus.Internal`. No se importa: se crea una clave propia con el mismo nombre,
         * que es lo que identifica una clave en la plataforma, y el valor es un `String`.
         * Ninguna clase del plugin GitHub entra en el classpath, asi que el Plugin Verifier
         * no ve ningun uso interno. El contrato es el nombre: si JetBrains lo cambia, esto
         * devuelve null y las acciones simplemente no aparecen en esos menus.
         */
        private val PULL_REQUEST_URL: DataKey<String> = DataKey.create("org.jetbrains.plugins.github.pullrequest.url")

        fun from(e: AnActionEvent): PrTarget? {
            e.getData(StackDataKeys.LAYER)?.let { selection ->
                val pr = selection.layer.pr ?: return null
                val github = GitHubRepo.fromPullRequestUrl(pr.url) ?: selection.state.repo.github ?: return null
                val url = pr.url.ifEmpty { "https://${github.host}/${github.owner}/${github.name}/pull/${pr.number}" }
                return PrTarget(url, pr.number, github, selection, null)
            }
            val url = e.getData(PULL_REQUEST_URL) ?: return null
            val github = GitHubRepo.fromPullRequestUrl(url) ?: return null
            val number = GitHubRepo.pullRequestNumber(url) ?: return null
            return PrTarget(url, number, github, null, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
        }
    }
}

/**
 * Refresca la lista y el detalle nativos de Pull Requests despues de escribir en un PR.
 * Se invocan sus propias acciones de recarga por id, con el componente de donde salio el
 * menu como contexto; si alguna no existe o no aplica a ese contexto, no hace nada.
 */
internal object PullRequestsViews {

    private val RELOAD_ACTIONS = listOf("Github.PullRequest.Details.Reload", "Github.PullRequest.List.Reload")

    fun reload(component: Component) {
        if (!component.isShowing) return
        val manager = ActionManager.getInstance()
        for (id in RELOAD_ACTIONS) {
            val action = manager.getAction(id) ?: continue
            manager.tryToExecute(action, null, component, ActionPlaces.UNKNOWN, true)
        }
    }
}
