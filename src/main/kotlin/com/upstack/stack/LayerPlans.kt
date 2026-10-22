package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GitCommands
import com.upstack.gh.LayerCommit
import com.upstack.gh.Tool

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

/** Por que la rama de una capa no se puede renombrar desde el plugin. Ver [LayerPlans.renameBlock]. */
enum class RenameBlock {
    /** GitHub cierra el PR cuya rama de origen se renombra, y no se puede cambiar la de un PR. */
    HAS_PR,

    /** Como en [DropBlock.MERGED_LAYERS]: la pila de GitHub no se podria volver a enlazar. */
    MERGED_LAYERS,

    /** La rama esta en el remoto, pero no se sabe en que repositorio de GitHub renombrarla. */
    NO_GITHUB,
}

/** Que pasa con una rama del proyecto si se elige en *Add Layer…*. Ver [LayerPlans.addSuggestions]. */
enum class AddBranchKind {
    /** Local y fuera de toda pila: `gh stack add` la adopta tal cual. */
    LOCAL,

    /** Solo en un remoto: antes se crea la local que la sigue, y `add` la adopta (ver [LayerPlans.add]). */
    REMOTE,

    /** Capa o trunk de esta pila: ya esta. */
    THIS_STACK,

    /** Capa de otra pila que gh-stack sigue: `add` falla con «already exists in the stack». */
    OTHER_STACK,
}

/** Una rama que sugiere *Add Layer…*. [remote]: donde esta, solo en [AddBranchKind.REMOTE]. */
data class AddBranch(val name: String, val kind: AddBranchKind, val remote: String? = null) {
    /** `gh stack add` la acepta. */
    val usable: Boolean get() = kind == AddBranchKind.LOCAL || kind == AddBranchKind.REMOTE

    /** `origin/feat/a`, la rama remota que seguira la local nueva. */
    val remoteBranch: String? get() = remote?.let { "$it/$name" }
}

/** La rama de la capa esta en [remote], que es [github]: tambien se renombra alli. */
data class RemoteRename(val remote: String, val github: GitHubRepo)

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
     * `init` deja HEAD en la capa de arriba: si se estaba en otra (o en el trunk), se vuelve.
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
            addAll(backTo(if (leave) null else current, remaining))
            if (deleteLocal && layerExists) add(git(GitCommands.deleteBranch(layer.branch)))
            if (deleteOn != null) add(git(GitCommands.deleteRemoteBranches(deleteOn, listOf(layer.branch))))
        }
        val unstacked = if (leave) 2 else 1
        return Plan(calls) { completed ->
            buildList {
                if (completed == unstacked) {
                    val restored = if (layerExists) branches else remaining
                    add(GhCall(GhCommands.init(restored, snapshot.trunk)))
                    if (!leave) addAll(backTo(current, restored))
                }
                if (leave && completed in 1..unstacked) add(git(GitCommands.switch(layer.branch)))
            }
        }
    }

    /**
     * null si la rama de [layer] se puede renombrar con [rename]. [onRemote]: la rama esta en el
     * remoto de la pila; [github]: el repositorio de ese remoto, si se sabe.
     */
    fun renameBlock(snapshot: StackSnapshot, layer: StackLayer, onRemote: Boolean, github: GitHubRepo?): RenameBlock? = when {
        layer.pr != null -> RenameBlock.HAS_PR
        snapshot.layers.any { it.isMerged || it.isQueued } -> RenameBlock.MERGED_LAYERS
        onRemote && github == null -> RenameBlock.NO_GITHUB
        else -> null
    }

    /**
     * Renombrar la rama de [layer] a [new]. gh-stack guarda los nombres y solo los cambia en
     * `gh stack modify`, que es interactivo: como al borrar la de arriba, se deja de seguir la pila
     * en local y se vuelve a crear con el nombre nuevo. El `unstack` va primero, con los nombres
     * aun buenos: gh-stack lo resuelve desde la rama actual, que puede ser la que se renombra.
     *
     * Con [remote], antes que la local se renombra la de GitHub, que reapunta los PRs de encima, y
     * despues la rama local sigue a la nueva: su configuracion la heredo de la vieja.
     *
     * `init` deja HEAD en la capa de arriba: si se estaba en otra (o en el trunk), se vuelve.
     *
     * Si se corta a medias, se deshace lo hecho (los dos renombres) y la pila se vuelve a crear
     * como estaba.
     */
    fun rename(snapshot: StackSnapshot, layer: StackLayer, new: String, remote: RemoteRename?, current: String?): Plan {
        val old = layer.branch
        val branches = snapshot.layers.map { it.branch }
        val renamed = branches.map { if (it == old) new else it }
        val calls = buildList {
            add(GhCall(GhCommands.unstackLocal()))
            if (remote != null) add(GhCall(GhCommands.renameRemoteBranch(remote.github, old, new)))
            add(git(GitCommands.renameBranch(old, new)))
            if (remote != null) {
                add(git(GitCommands.configSet("branch.$new.remote", remote.remote)))
                add(git(GitCommands.configSet("branch.$new.merge", "refs/heads/$new")))
                add(git(GitCommands.deleteRemoteTracking("${remote.remote}/$old")))
            }
            add(GhCall(GhCommands.init(renamed, snapshot.trunk)))
            addAll(backTo(if (current == old) new else current, renamed))
        }
        val initialized = calls.indexOfFirst { it.args.take(2) == listOf("stack", "init") } + 1
        // Cuantas llamadas tienen que haber terminado para que cada renombre este hecho.
        val remoteRenamed = 2
        val localRenamed = if (remote != null) 3 else 2
        return Plan(calls) { completed ->
            // Antes de `unstack` no cambio nada; tras `init`, la pila ya esta bien.
            if (completed == 0 || completed >= initialized) return@Plan emptyList()
            buildList {
                if (completed >= localRenamed) {
                    add(git(GitCommands.renameBranch(new, old)))
                    if (remote != null) add(git(GitCommands.configSet("branch.$old.merge", "refs/heads/$old")))
                }
                if (remote != null && completed >= remoteRenamed) add(GhCall(GhCommands.renameRemoteBranch(remote.github, new, old)))
                add(GhCall(GhCommands.init(branches, snapshot.trunk)))
                addAll(backTo(current, branches))
            }
        }
    }

    /**
     * [calls] con gh-stack siguiendo en local la pila solo hasta [layer], y despues entera otra vez:
     * asi `submit`, que publica todas las capas sin PR que sigue, deja fuera las de encima. La pila
     * parcial y la entera adoptan las ramas y sus PRs, y `submit` adopta la pila de GitHub. Con
     * capas fusionadas o en cola no vale (como [DropBlock.MERGED_LAYERS]).
     *
     * `init` deja HEAD en la ultima capa que adopta: [calls] corren en [layer], y al final se
     * vuelve a [current].
     *
     * Si se corta a medias, la pila se vuelve a crear entera y HEAD vuelve a [current].
     */
    fun upTo(snapshot: StackSnapshot, layer: StackLayer, current: String?, calls: List<GhCall>): Plan {
        val branches = snapshot.layers.map { it.branch }
        val partial = branches.take(branches.indexOf(layer.branch) + 1)
        val all = buildList {
            add(GhCall(GhCommands.unstackLocal()))
            add(GhCall(GhCommands.init(partial, snapshot.trunk)))
            addAll(calls)
            add(GhCall(GhCommands.unstackLocal()))
            add(GhCall(GhCommands.init(branches, snapshot.trunk)))
            addAll(backTo(current, branches))
        }
        // Cuantas llamadas tienen que haber terminado para que cada `init` este hecho.
        val partialInitialized = 2
        val initialized = calls.size + 4
        return Plan(all) { completed ->
            // Antes del primer `unstack` no cambio nada; tras el ultimo `init`, la pila ya esta bien.
            if (completed == 0 || completed >= initialized) return@Plan emptyList()
            buildList {
                // La pila parcial sigue en local: antes de volver a crearla entera, se deja de seguir.
                if (completed in partialInitialized until initialized - 1) add(GhCall(GhCommands.unstackLocal()))
                add(GhCall(GhCommands.init(branches, snapshot.trunk)))
                addAll(backTo(current, branches))
            }
        }
    }

    /**
     * Las ramas del proyecto que sugiere *Add Layer…*, locales y remotas, cada una con lo que pasa
     * si se elige (ver [AddBranchKind]): primero las que `gh stack add` acepta, despues las que no,
     * y por nombre. [remote]: por nombre, el remoto que la tiene; una que tambien esta en local
     * cuenta como local. El trunk de otra pila si vale: gh-stack solo mira sus capas.
     */
    fun addSuggestions(local: Collection<String>, remote: Map<String, String>, snapshot: StackSnapshot, tracked: List<LocalStack>): List<AddBranch> {
        val ours = buildSet {
            add(snapshot.trunk)
            snapshot.layers.mapTo(this) { it.branch }
        }
        val others = tracked.flatMapTo(HashSet()) { it.branches }
        fun kind(name: String, isLocal: Boolean) = when (name) {
            in ours -> AddBranchKind.THIS_STACK
            in others -> AddBranchKind.OTHER_STACK
            else -> if (isLocal) AddBranchKind.LOCAL else AddBranchKind.REMOTE
        }
        val locals = local.toSet()
        val all = locals.map { AddBranch(it, kind(it, isLocal = true)) } +
            remote.filterKeys { it !in locals }.map { (name, where) ->
                val kind = kind(name, isLocal = false)
                AddBranch(name, kind, where.takeIf { kind == AddBranchKind.REMOTE })
            }
        return all.sortedWith(compareBy({ !it.usable }, { it.name }))
    }

    /**
     * `gh stack add` de [branch] encima de la pila, con `gh stack top` antes si [needsTop]. Si solo
     * esta en un remoto ([remoteBranch], `origin/rama`), antes se crea la local que la sigue, y
     * gh-stack la adopta tal cual: sin ella, `add` crearia otra rama con ese nombre desde la capa de
     * arriba, y el siguiente push pisaria la del remoto.
     *
     * Si se corta despues de crear esa rama local y antes de que `add` termine, se borra.
     */
    fun add(branch: String, commit: LayerCommit?, needsTop: Boolean, remoteBranch: String?): Plan {
        val calls = buildList {
            if (remoteBranch != null) add(git(GitCommands.branchTracking(branch, remoteBranch)))
            if (needsTop) add(GhCall(GhCommands.top()))
            add(GhCall(GhCommands.add(branch, commit)))
        }
        return Plan(calls) { completed ->
            if (remoteBranch != null && completed in 1 until calls.size) listOf(git(GitCommands.deleteBranch(branch))) else emptyList()
        }
    }

    /** `init` termina en la ultima de [branches]: volver a [branch] si es otra. */
    private fun backTo(branch: String?, branches: List<String>): List<GhCall> =
        if (branch == null || branch == branches.lastOrNull()) emptyList() else listOf(git(GitCommands.switch(branch)))

    private fun git(args: List<String>) = GhCall(args, tool = Tool.GIT)
}
