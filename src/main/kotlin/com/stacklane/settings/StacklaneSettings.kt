package com.stacklane.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Preferencias globales: donde esta `gh` y como se llama la label de la capa final. */
@Service(Service.Level.APP)
@State(name = "Stacklane", storages = [Storage("stacklane.xml")])
class StacklaneSettings : SimplePersistentStateComponent<StacklaneSettings.Options>(Options()) {

    class Options : BaseState() {
        var ghPath by string("")
        var finalLabel by string(DEFAULT_FINAL_LABEL)
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

    companion object {
        const val DEFAULT_FINAL_LABEL = "stack-final"

        fun getInstance(): StacklaneSettings = service()
    }
}

/**
 * Preferencias de este proyecto, en el workspace (no se versionan): el repositorio elegido
 * cuando hay varios y el remoto al que publicar cuando gh-stack no sabe elegir.
 */
@Service(Service.Level.PROJECT)
@State(name = "StacklaneProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StacklaneProjectSettings : SimplePersistentStateComponent<StacklaneProjectSettings.Options>(Options()) {

    class Options : BaseState() {
        var repositoryRoot by string()
        var remote by string()
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

    companion object {
        fun getInstance(project: Project): StacklaneProjectSettings = project.service()
    }
}
