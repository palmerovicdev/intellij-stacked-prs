package com.upstack.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.upstack.gh.GhCommands
import com.upstack.gh.MergeMethod
import com.upstack.stack.GitHubRepo

/**
 * La label de capa final como la define Settings: nombre, color (hex de 6 cifras sin `#`, como
 * lo pide `gh label create --color`) y descripcion.
 */
data class FinalLabel(val name: String, val color: String, val description: String) {

    /** `gh label create … --force`: si ya existe, toma este color y esta descripcion. */
    fun create(repo: GitHubRepo): List<String> = GhCommands.createLabel(repo, name, color, description)
}

/** Que hacer cuando un commit del IDE deja atras las capas de encima. */
enum class RestackMode { ASK, ALWAYS, NEVER }

/**
 * Preferencias globales: donde esta `gh`, la label de la capa final (y si Merge Stack la pone
 * antes de fusionar), que hacer tras un commit en una capa que no es la cima, cada cuanto se
 * relee la pila y de que version de gh-stack ya se aviso.
 */
@Service(Service.Level.APP)
@State(name = "Upstack", storages = [Storage("upstack.xml")])
class UpstackSettings : SimplePersistentStateComponent<UpstackSettings.Options>(Options()) {

    class Options : BaseState() {
        var ghPath by string("")
        var finalLabel by string(DEFAULT_FINAL_LABEL)
        var finalLabelColor by string(DEFAULT_FINAL_LABEL_COLOR)
        var finalLabelDescription by string(DEFAULT_FINAL_LABEL_DESCRIPTION)
        var finalLabelBeforeMerge by property(false)
        // Por nombre: `enum()` de BaseState es inline y viene compilado para JVM 25, y el plugin sale en 21.
        var restackAfterCommit by string(RestackMode.ASK.name)
        var warnedStackVersion by string()
        var refreshIntervalSeconds by property(DEFAULT_REFRESH_INTERVAL)
    }

    /**
     * Cada cuantos segundos se vuelve a leer la pila mientras la ventana la ensena (ver
     * StackService.refreshPeriodically); 0 lo apaga. Encendido, entre [MIN_REFRESH_INTERVAL] y
     * [MAX_REFRESH_INTERVAL]: lo de fuera se guarda como el limite mas cercano.
     */
    var refreshIntervalSeconds: Int
        get() = refreshInterval(state.refreshIntervalSeconds)
        set(value) {
            state.refreshIntervalSeconds = refreshInterval(value)
        }

    var restackAfterCommit: RestackMode
        get() = RestackMode.entries.firstOrNull { it.name == state.restackAfterCommit } ?: RestackMode.ASK
        set(value) {
            state.restackAfterCommit = value.name
        }

    /** La version de gh-stack de la que ya se aviso que no es la de referencia: una vez por version. */
    var warnedStackVersion: String?
        get() = state.warnedStackVersion
        set(value) {
            state.warnedStackVersion = value
        }

    /** Ruta explicita a `gh`. Vacia: se busca en el PATH del shell. */
    var ghPath: String
        get() = state.ghPath.orEmpty().trim()
        set(value) {
            state.ghPath = value.trim()
        }

    var finalLabel: String
        get() = state.finalLabel?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FINAL_LABEL
        set(value) {
            state.finalLabel = value.trim().ifEmpty { DEFAULT_FINAL_LABEL }
        }

    /** Hex de 6 cifras, en mayusculas y sin `#`. Lo que no lo es no se guarda: queda el de por defecto. */
    var finalLabelColor: String
        get() = hexColor(state.finalLabelColor) ?: DEFAULT_FINAL_LABEL_COLOR
        set(value) {
            state.finalLabelColor = hexColor(value) ?: DEFAULT_FINAL_LABEL_COLOR
        }

    /** Vacia: la de por defecto, como el nombre. */
    var finalLabelDescription: String
        get() = state.finalLabelDescription?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FINAL_LABEL_DESCRIPTION
        set(value) {
            state.finalLabelDescription = value.trim().ifEmpty { DEFAULT_FINAL_LABEL_DESCRIPTION }
        }

    /**
     * Merge Stack pone la label al PR de arriba de lo que se fusiona si aun no la tiene (ver
     * MergePlans.calls). Apagado por defecto.
     */
    var finalLabelBeforeMerge: Boolean
        get() = state.finalLabelBeforeMerge
        set(value) {
            state.finalLabelBeforeMerge = value
        }

    val finalLabelSpec: FinalLabel get() = FinalLabel(finalLabel, finalLabelColor, finalLabelDescription)

    /** La label que Merge Stack pone antes de fusionar, o null si no se pidio en Settings. */
    val labelBeforeMerge: FinalLabel? get() = finalLabelSpec.takeIf { finalLabelBeforeMerge }

    companion object {
        const val DEFAULT_FINAL_LABEL = "stack-final"
        const val DEFAULT_FINAL_LABEL_COLOR = "8250DF"
        const val DEFAULT_FINAL_LABEL_DESCRIPTION = "Top layer of a stack of pull requests"

        /** GitHub no admite nombres de label mas largos, ni descripciones. */
        const val MAX_LABEL_NAME = 50
        const val MAX_LABEL_DESCRIPTION = 100

        const val DEFAULT_REFRESH_INTERVAL = 60
        const val MIN_REFRESH_INTERVAL = 10
        const val MAX_REFRESH_INTERVAL = 3600

        /** 0 (o menos): apagado. Si no, entre [MIN_REFRESH_INTERVAL] y [MAX_REFRESH_INTERVAL]. */
        fun refreshInterval(seconds: Int): Int = if (seconds <= 0) 0 else seconds.coerceIn(MIN_REFRESH_INTERVAL, MAX_REFRESH_INTERVAL)

        private val HEX_COLOR = Regex("[0-9A-F]{6}")

        /** [value] como lo pide `gh label create --color` (`0E8A16`), o null si no es un color hex de 6 cifras. */
        fun hexColor(value: String?): String? = value?.trim()?.removePrefix("#")?.uppercase()?.takeIf(HEX_COLOR::matches)

        fun getInstance(): UpstackSettings = service()
    }
}

/**
 * Preferencias de este proyecto, en el workspace (no se versionan): el repositorio elegido
 * cuando hay varios, el remoto al que publicar cuando gh-stack no sabe elegir y el ultimo
 * metodo de merge, que depende de lo que admita el repositorio.
 */
@Service(Service.Level.PROJECT)
@State(name = "UpstackProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class UpstackProjectSettings : SimplePersistentStateComponent<UpstackProjectSettings.Options>(Options()) {

    class Options : BaseState() {
        var repositoryRoot by string()
        var remote by string()
        var mergeMethod by string()
    }

    var repositoryRoot: String?
        get() = state.repositoryRoot
        set(value) {
            state.repositoryRoot = value
        }

    var remote: String?
        get() = state.remote
        set(value) {
            state.remote = value
        }

    /** El ultimo con el que se fusiono desde el plugin. */
    var mergeMethod: MergeMethod?
        get() = MergeMethod.parse(state.mergeMethod)
        set(value) {
            state.mergeMethod = value?.name
        }

    companion object {
        fun getInstance(project: Project): UpstackProjectSettings = project.service()
    }
}
