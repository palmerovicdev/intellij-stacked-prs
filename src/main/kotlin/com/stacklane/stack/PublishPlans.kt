package com.stacklane.stack

import com.stacklane.gh.GhCommands

/** Un commit de una capa: de ahi se propone el titulo y la descripcion de su PR. */
data class CommitMessage(val subject: String, val body: String)

/** Titulo y descripcion de un PR. */
data class PrText(val title: String, val body: String)

/** Una capa en *Publish Stack…*: lo que hay en GitHub y lo que se eligio. */
data class PublishLayer(
    val branch: String,
    /** null: la capa aun no tiene PR y `gh stack submit --auto` lo crea como draft. */
    val prUrl: String?,
    /** El PR es draft ahora; null si no hay PR o no se sabe. */
    val isDraft: Boolean?,
    /** Listo para review o draft; null: se deja como este (estado desconocido, cerrado, en cola). */
    val ready: Boolean?,
    /** Solo en las capas nuevas: sustituye al titulo que genera `--auto`. */
    val text: PrText? = null,
)

/**
 * Publicar decidiendo PR a PR. `gh stack submit --auto` solo sabe crear los PRs nuevos como
 * draft con titulos generados, y `--open` marca listos todos, tambien los que ya existian. El
 * editor interactivo de gh-stack deja elegir, pero el plugin no puede abrirlo: se hace lo
 * mismo con comandos sueltos despues del submit.
 */
object PublishPlans {

    /**
     * `gh stack submit --auto` y despues, capa a capa de abajo arriba: el titulo y la
     * descripcion de cada PR nuevo y cada cambio de draft a listo o al reves. Los PRs nuevos
     * se nombran por su rama: su URL no existe hasta que acaba el submit.
     */
    fun calls(layers: List<PublishLayer>, repo: GitHubRepo?): List<GhCall> = buildList {
        add(GhCall(GhCommands.submit(ready = false), acceptsRemote = true))
        for (layer in layers) {
            val url = layer.prUrl
            if (url == null) {
                layer.text?.let { add(GhCall(GhCommands.editPr(layer.branch, repo, it.title, it.body))) }
                if (layer.ready == true) add(GhCall(GhCommands.markReady(layer.branch, repo)))
                continue
            }
            val draft = layer.isDraft ?: continue
            when (layer.ready) {
                true -> if (draft) add(GhCall(GhCommands.markReady(url)))
                false -> if (!draft) add(GhCall(GhCommands.markDraft(url)))
                null -> Unit
            }
        }
    }

    /**
     * Titulo y descripcion propuestos para el PR de [branch]. Con un commit, su asunto y su
     * cuerpo. Con varios, el asunto del primero, que suele decir de que va la capa, y la lista
     * de todos en la descripcion. Sin commits propios, el nombre de la rama.
     */
    fun proposal(branch: String, commits: List<CommitMessage>): PrText = when (commits.size) {
        0 -> PrText(branch, "")
        1 -> PrText(commits[0].subject, commits[0].body)
        else -> PrText(commits[0].subject, commits.joinToString("\n") { "- ${it.subject}" })
    }

    /** La salida de [com.stacklane.gh.GitCommands.log]. */
    fun parseLog(output: String): List<CommitMessage> =
        output.split(RECORD).filter { it.isNotBlank() }.map { record ->
            CommitMessage(record.substringBefore(UNIT).trim(), record.substringAfter(UNIT, "").trim())
        }

    private const val RECORD = '\u001e'
    private const val UNIT = '\u001f'
}
