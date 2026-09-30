package com.stacklane.stack

import com.stacklane.gh.GitCommands
import com.stacklane.gh.Tool

/**
 * git rerere recuerda como se resolvio cada conflicto y repite la resolucion cuando el mismo
 * conflicto vuelve a salir, algo habitual al rebasar una pila varias veces.
 *
 * gh-stack pregunta si activarlo antes de rebasar, pero solo en una terminal interactiva: desde
 * el plugin nunca llegaria a preguntarlo. Aqui se hace la misma pregunta y se guarda la
 * respuesta en las mismas claves (cmd/utils.go e internal/git/gitops.go de la v0.1.1), asi
 * que ni la terminal ni el IDE vuelven a preguntar lo que se contesto en el otro.
 */
object Rerere {

    enum class Answer { ENABLED, DECLINED, UNANSWERED }

    /** La salida de [GitCommands.rerereConfig]. Si no hay ninguna de las dos claves, esta vacia. */
    fun parse(output: String): Answer {
        // Una linea por valor, de la configuracion global a la del repositorio: gana la ultima.
        val values = output.lineSequence()
            .map { it.trim().split(' ', limit = 2) }
            .filter { it.size == 2 }
            .associate { (key, value) -> key.lowercase() to value.trim() }
        return when (values["rerere.enabled"]) {
            "true" -> Answer.ENABLED
            // gh-stack preguntaria igual; un `false` escrito a mano ya es una respuesta.
            "false" -> Answer.DECLINED
            else -> if (values["gh-stack.rerere-declined"] == "true") Answer.DECLINED else Answer.UNANSWERED
        }
    }

    /** Lo que hace gh-stack al contestar que si. */
    fun enable(): List<GhCall> = listOf(
        git(GitCommands.configSet("rerere.enabled", "true")),
        git(GitCommands.configSet("rerere.autoupdate", "true")),
    )

    /** Lo que hace gh-stack al contestar que no: no vuelve a preguntar en este repositorio. */
    fun decline(): List<GhCall> = listOf(git(GitCommands.configSet("gh-stack.rerere-declined", "true")))

    private fun git(args: List<String>) = GhCall(args, tool = Tool.GIT)
}
