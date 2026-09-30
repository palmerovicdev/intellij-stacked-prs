package com.stacklane.stack

import com.stacklane.gh.GhCommands
import com.stacklane.gh.GitCommands
import com.stacklane.gh.Tool

/**
 * Una secuencia de comandos y como deshacerla si se corta a medias. [cleanup] recibe cuantas
 * llamadas terminaron bien y devuelve lo que hay que ejecutar para dejar el repositorio
 * como estaba.
 */
class Plan(val calls: List<GhCall>, val cleanup: (completed: Int) -> List<GhCall> = { emptyList() }) {

    /** Primero este plan y luego [next]; cada uno deshace lo suyo si se corta en su tramo. */
    fun then(next: Plan): Plan {
        val size = calls.size
        return Plan(calls + next.calls) { completed ->
            if (completed < size) cleanup(completed) else next.cleanup(completed - size)
        }
    }

    fun then(next: List<GhCall>): Plan = then(Plan(next))
}

/** Donde esta HEAD antes de empezar, para volver alli. */
data class Position(val branch: String?, val revision: String?) {
    /** null si no se sabe volver (HEAD suelto y sin commit conocido). */
    fun switchBack(): List<String>? = when {
        branch != null -> GitCommands.switch(branch)
        revision != null -> GitCommands.switchDetached(revision)
        else -> null
    }
}

/** Los rodeos que gh-stack v0.1.1 no ofrece como comando, escritos como planes. */
object StackPlans {

    /**
     * Olvidar una pila cuyas ramas ya no existen.
     *
     * gh-stack solo deja de seguir una pila desde una de sus ramas (`unstack --local` actua
     * sobre la pila actual) o por su numero en GitHub, que una pila de un solo PR no tiene. Se
     * recrea una de sus ramas **en el commit actual**, se cambia a ella —mismo commit: ni el
     * arbol de trabajo ni los cambios locales se tocan—, se deshace la pila en local y se
     * vuelve. GitHub no se toca. Devuelve null si no se sabe volver a donde se estaba.
     */
    fun forget(stack: LocalStack, from: Position): Plan? {
        val back = from.switchBack() ?: return null
        val temp = stack.branches.first()
        val calls = listOf(
            git(GitCommands.branch(temp, "HEAD")),
            git(GitCommands.switch(temp)),
            GhCall(GhCommands.unstackLocal()),
            git(back),
            git(GitCommands.deleteBranch(temp)),
        )
        return Plan(calls) { completed ->
            buildList {
                // Se quedo en la rama temporal: volver.
                if (completed in 2..3) add(git(back))
                // La rama temporal existe: borrarla. Solo si se creo aqui (completed >= 1).
                if (completed in 1..4) add(git(GitCommands.deleteBranch(temp)))
            }
        }
    }

    /**
     * Recrear ramas borradas en su ultimo commit conocido, para que `gh stack init` las adopte
     * con su trabajo en vez de crearlas vacias desde la base.
     */
    fun restore(heads: Map<String, String>): Plan = Plan(heads.map { (branch, sha) -> git(GitCommands.branch(branch, sha)) })

    private fun git(args: List<String>) = GhCall(args, tool = Tool.GIT)
}
