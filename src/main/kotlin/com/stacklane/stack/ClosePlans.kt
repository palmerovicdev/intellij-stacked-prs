package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.GitCommands
import com.stacklane.gh.Tool

/** Un PR abierto de la pila: al cerrarla, se cierra. */
data class ClosePr(val number: Int, val url: String, val branch: String)

/**
 * Lo que toca *Close Stack…*, de la cima hacia abajo. Sale de la ventana y del repositorio (ver
 * [ClosePlans.targets]); el dialogo lo ensena antes de elegir.
 */
data class CloseTargets(
    /** Alguna capa tiene PR: hay pila en GitHub que deshacer. */
    val onGitHub: Boolean,
    val openPrs: List<ClosePr>,
    /** El remoto donde se borran las ramas; null si el repositorio no tiene ninguno. */
    val remote: String?,
    /** Ramas de la pila que existen en [remote]. */
    val remoteBranches: List<String>,
    /** Ramas de la pila que existen en local y se pueden borrar. */
    val localBranches: List<String>,
    /** HEAD esta en una de [localBranches]: antes de borrarlas se cambia con este comando de git. */
    val leave: List<String>?,
    /** La rama actual, que no se borra porque no hay a donde ir: el trunk no existe ni en local ni en [remote]. */
    val keptCheckedOut: String?,
    /** Ramas de la pila que son la base de otra pila: no se borran, o esa pila se quedaria sin base. */
    val shared: List<String>,
    /** Capas activas cuya rama local no es igual que en [remote] (o no esta alli): borrarlas puede perder commits. */
    val unpushed: List<String>,
)

/** Que se eligio en el dialogo. Deshacer la pila en local se hace siempre. */
data class CloseChoice(
    val unstackOnGitHub: Boolean,
    val closePrs: Boolean,
    val comment: String,
    val deleteRemote: Boolean,
    val deleteLocal: Boolean,
) {
    companion object {
        /** Todo, salvo borrar ramas locales con commits que quiza solo esten aqui. */
        fun defaults(targets: CloseTargets) = CloseChoice(
            unstackOnGitHub = true,
            closePrs = true,
            comment = "",
            deleteRemote = true,
            deleteLocal = targets.unpushed.isEmpty(),
        )
    }
}

/**
 * Cerrar una pila entera: la pila, sus PRs y sus ramas, en el orden que no deja nada a medias.
 *
 * 1. `gh stack unstack`, con las ramas aun vivas. Si se borraran antes, gh-stack seguiria
 *    registrando la pila sin ramas (lo que limpia *Forget Stack*).
 * 2. `gh pr close` de la cima hacia abajo: un PR no se puede borrar, solo cerrar, y cerrando
 *    de abajo arriba los de encima se quedarian un momento con una base que desaparece.
 * 3. `git push REMOTO --delete`: borrar la rama de un PR abierto lo cerraria sin comentario,
 *    asi que solo se ofrece si los PRs abiertos se cierran antes.
 * 4. Salir de la rama actual si se va a borrar, y `git branch -D`.
 *
 * El paso 1 va solo y despues se comprueba que gh-stack ya no siga la pila (ver
 * StackFlows.closeStack): GitHub puede negarse a deshacerla y gh-stack sale bien igualmente.
 */
object ClosePlans {

    /**
     * [local]: rama local -> commit, de las ramas de la pila y su trunk. [remoteHeads]: lo
     * mismo en [remote]. [otherTrunks]: las bases de las demas pilas que gh-stack sigue.
     */
    fun targets(
        snapshot: StackSnapshot,
        details: Map<Int, PrDetails>,
        remote: String?,
        local: Map<String, String>,
        remoteHeads: Map<String, String>,
        otherTrunks: Set<String>,
    ): CloseTargets {
        val layers = snapshot.layers.asReversed()
        val openPrs = layers.mapNotNull { layer ->
            val pr = layer.pr ?: return@mapNotNull null
            val state = details[pr.number]?.state ?: if (layer.isMerged) PrState.MERGED else PrState.parse(pr.state)
            if (state == PrState.OPEN) ClosePr(pr.number, pr.url, layer.branch) else null
        }
        val (shared, deletable) = layers.map { it.branch }.partition { it in otherTrunks }
        val remoteBranches = if (remote == null) emptyList() else deletable.filter { it in remoteHeads }

        val current = snapshot.currentBranch
        val trunk = snapshot.trunk
        val leave = when {
            current !in deletable || current !in local -> null
            trunk in local -> GitCommands.switch(trunk)
            remote != null && trunk in remoteHeads -> GitCommands.switchTracking("$remote/$trunk")
            else -> null
        }
        val keptCheckedOut = current.takeIf { it in deletable && it in local && leave == null }
        val localBranches = deletable.filter { it in local && it != keptCheckedOut }

        val merged = snapshot.layers.filter { it.isMerged }.mapTo(HashSet()) { it.branch }
        val unpushed = localBranches.filter { it !in merged && local[it] != remoteHeads[it] }

        return CloseTargets(
            onGitHub = snapshot.layers.any { it.pr != null },
            openPrs = openPrs,
            remote = remote,
            remoteBranches = remoteBranches,
            localBranches = localBranches,
            leave = leave,
            keptCheckedOut = keptCheckedOut,
            shared = shared,
            unpushed = unpushed,
        )
    }

    /** Paso 1: `gh stack unstack`, o solo en local si no hay pila en GitHub o no se quiere tocar. */
    fun unstack(choice: CloseChoice, targets: CloseTargets): GhCall =
        GhCall(if (choice.unstackOnGitHub && targets.onGitHub) GhCommands.unstack() else GhCommands.unstackLocal())

    /** Borrar las ramas remotas cerraria los PRs abiertos sin comentario: solo si se cierran antes. */
    fun canDeleteRemote(closePrs: Boolean, targets: CloseTargets): Boolean =
        targets.remote != null && targets.remoteBranches.isNotEmpty() && (closePrs || targets.openPrs.isEmpty())

    /** Pasos 2 a 4, cuando gh-stack ya no sigue la pila. */
    fun afterUnstack(choice: CloseChoice, targets: CloseTargets): List<GhCall> = buildList {
        if (choice.closePrs) {
            val comment = choice.comment.trim().ifEmpty { null }
            targets.openPrs.forEach { add(GhCall(GhCommands.closePr(it.url, comment))) }
        }
        val remote = targets.remote
        if (choice.deleteRemote && remote != null && canDeleteRemote(choice.closePrs, targets)) {
            add(git(GitCommands.deleteRemoteBranches(remote, targets.remoteBranches)))
        }
        if (choice.deleteLocal && targets.localBranches.isNotEmpty()) {
            targets.leave?.let { add(git(it)) }
            add(git(GitCommands.deleteBranches(targets.localBranches)))
        }
    }

    /** Todo, en orden: lo que ensenan el dialogo y la ayuda de la accion. */
    fun calls(choice: CloseChoice, targets: CloseTargets): List<GhCall> =
        listOf(unstack(choice, targets)) + afterUnstack(choice, targets)

    /** La salida de [GitCommands.lsRemoteHeads]: rama -> commit. */
    fun parseLsRemote(output: String): Map<String, String> = output.lineSequence()
        .mapNotNull { line ->
            val (sha, ref) = line.trim().split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            ref.removePrefix(HEADS).takeIf { ref.startsWith(HEADS) && it.isNotEmpty() }?.let { it to sha }
        }
        .toMap()

    private const val HEADS = "refs/heads/"

    private fun git(args: List<String>) = GhCall(args, tool = Tool.GIT)
}
