package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.GitCommands
import com.stacklane.gh.Tool

/** Por que una capa no se puede borrar sin `gh stack modify`. Ver [LayerPlans.dropBlock]. */
enum class DropBlock {
    /** Solo la de arriba: si no, las de encima se quedarian con sus commits. */
    NOT_TOP,

    /** Su PR esta en la pila de GitHub, y `submit` no volveria a enlazarla sin el. */
    HAS_PR,

    /** La pila de GitHub guarda los PRs fusionados o en cola, y la pila nueva no los tendria. */
    MERGED_LAYERS,

    /** Es la unica capa: eso es cerrar la pila. */
    ONLY_LAYER,
}

/**
 * Borrar la capa de arriba de una pila. gh-stack v0.1.1 solo saca capas con `gh stack modify`,
 * que es interactivo. Para la de arriba sin PR hay rodeo con comandos normales: dejar de seguir
 * la pila en local y volver a crearla con las demas capas, que gh-stack adopta tal cual estan.
 * Sus PRs se vuelven a encontrar al leer la pila, y el siguiente `submit` o `sync` adopta la pila
 * de GitHub, porque sigue teniendo los mismos PRs (`reconcileUntrackedStack` de la v0.1.1).
 */
object LayerPlans {

    /** null si [layer] se puede borrar con [drop]. */
    fun dropBlock(snapshot: StackSnapshot, layer: StackLayer): DropBlock? = when {
        layer.isMerged || layer != snapshot.top -> DropBlock.NOT_TOP
        layer.pr != null -> DropBlock.HAS_PR
        snapshot.layers.any { it.isMerged || it.isQueued } -> DropBlock.MERGED_LAYERS
        snapshot.layers.size < 2 -> DropBlock.ONLY_LAYER
        else -> null
    }

    /**
     * Borrar [layer], la de arriba. Si HEAD esta en ella, antes se pasa a la de debajo: alli se
     * queda. [deleteLocal]: borrar tambien su rama; [deleteOn]: el remoto donde borrarla, o null.
     * [layerExists]: su rama esta en local (una capa sin rama, P44, no se puede volver a adoptar).
     *
     * Si se corta tras `unstack` y antes de que `init` termine, la pila queda sin seguir: se
     * vuelve a crear como estaba. Si se corta antes, solo hay que volver a la capa.
     */
    fun drop(
        snapshot: StackSnapshot,
        layer: StackLayer,
        current: String?,
        deleteLocal: Boolean,
        deleteOn: String?,
        layerExists: Boolean = true,
    ): Plan {
        val branches = snapshot.layers.map { it.branch }
        val remaining = branches - layer.branch
        val leave = current == layer.branch
        val calls = buildList {
            if (leave) add(git(GitCommands.switch(snapshot.parentOf(layer))))
            add(GhCall(GhCommands.unstackLocal()))
            add(GhCall(GhCommands.init(remaining, snapshot.trunk)))
            if (deleteLocal && layerExists) add(git(GitCommands.deleteBranch(layer.branch)))
            if (deleteOn != null) add(git(GitCommands.deleteRemoteBranches(deleteOn, listOf(layer.branch))))
        }
        val unstacked = if (leave) 2 else 1
        return Plan(calls) { completed ->
            buildList {
                if (completed == unstacked) add(GhCall(GhCommands.init(if (layerExists) branches else remaining, snapshot.trunk)))
                if (leave && completed in 1..unstacked) add(git(GitCommands.switch(layer.branch)))
            }
        }
    }

    private fun git(args: List<String>) = GhCall(args, tool = Tool.GIT)
}
