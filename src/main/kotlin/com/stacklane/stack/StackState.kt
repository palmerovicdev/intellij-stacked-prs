package com.stacklane.stack

import com.intellij.openapi.vfs.VirtualFile

/** El repositorio que se esta mostrando. */
data class RepoRef(
    val root: VirtualFile,
    val name: String,
    /** Deducido de los remotos; null si ninguno apunta a GitHub. */
    val github: GitHubRepo?,
    /** Hay un `gh stack rebase` parado a la espera de `--continue` o `--abort`. */
    val stackRebaseInProgress: Boolean,
)

/** Lo que pinta la ventana Stacks. Un unico estado, publicado por [StackService]. */
sealed interface StackState {

    data object Loading : StackState

    data object NoRepository : StackState

    data object GhMissing : StackState

    data object ExtensionMissing : StackState

    /** La rama actual no esta en ninguna pila. */
    data class NotInStack(
        val repo: RepoRef,
        val branch: String?,
        val localStacks: List<LocalStackEntry>,
    ) : StackState

    /**
     * La rama actual esta en varias pilas: es capa de una y base de otra, o la base de varias
     * (como `main`). gh-stack no elige (`view` sale con 6); hay que abrir una desde otra rama.
     */
    data class InSeveralStacks(
        val repo: RepoRef,
        val branch: String,
        /** Todas las pilas locales, no solo las de [branch]: desde el trunk se quiere ver el resto. */
        val localStacks: List<LocalStackEntry>,
    ) : StackState {
        /** Las pilas en las que esta [branch], segun `.git/gh-stack`. */
        val stacksOfBranch: List<LocalStackEntry> get() = localStacks.filter { it.stack.contains(branch) }

        /** La pila en la que [branch] es una capa y no la base, si la hay: gh-stack no deja empezar otra desde ahi. */
        val layerOf: LocalStackEntry? get() = localStacks.firstOrNull { branch in it.stack.branches }
    }

    data class Loaded(
        val repo: RepoRef,
        val snapshot: StackSnapshot,
        /** Por numero de PR. Puede estar incompleto mientras [detailsLoading]. */
        val details: Map<Int, PrDetails>,
        val detailsLoading: Boolean,
        val detailsError: String?,
    ) : StackState {
        fun detailsOf(layer: StackLayer): PrDetails? = layer.pr?.let { details[it.number] }
    }

    data class Failed(val repo: RepoRef?, val message: String) : StackState
}

val StackState.repo: RepoRef?
    get() = when (this) {
        is StackState.NotInStack -> repo
        is StackState.InSeveralStacks -> repo
        is StackState.Loaded -> repo
        is StackState.Failed -> repo
        else -> null
    }
