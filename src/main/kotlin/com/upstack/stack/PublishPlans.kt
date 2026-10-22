package com.upstack.stack

import com.upstack.gh.GhCommands

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
     * Las capas activas sin PR por encima de [layer]. `gh stack submit` publica todas las que no
     * tienen PR: para dejarlas sin el, [layerPlan] rehace la pila solo hasta [layer].
     */
    fun unpublishedAbove(snapshot: StackSnapshot, layer: StackLayer): List<StackLayer> =
        snapshot.activeAbove(layer).filter { it.pr == null }

    /**
     * [layer] no se puede publicar desde su menu: hay capas sin PR encima y, con capas fusionadas
     * o en cola, la pila no se puede rehacer solo hasta ella (ver [LayerPlans.upTo]).
     */
    fun layerBlocked(snapshot: StackSnapshot, layer: StackLayer): Boolean =
        unpublishedAbove(snapshot, layer).isNotEmpty() && snapshot.layers.any { it.isMerged || it.isQueued }

    /**
     * Las capas activas sin PR por debajo de [layer]: su PR hace falta como base del suyo, y
     * `gh stack submit` los crea a la vez, como draft y con el titulo que genera.
     */
    fun unpublishedBelow(snapshot: StackSnapshot, layer: StackLayer): List<StackLayer> =
        snapshot.layers.take(snapshot.layers.indexOf(layer)).filter { !it.isMerged && it.pr == null }

    /**
     * Publicar [layer] desde su menu: lo de [calls] solo con las capas que crea `submit`, las de
     * debajo como draft y esta con [text] y, si [ready], lista para review. Si hay capas sin PR
     * encima, `submit` corre con la pila seguida solo hasta [layer] y ellas se quedan sin PR (ver
     * [LayerPlans.upTo]); [current], la rama actual, es adonde se vuelve.
     */
    fun layerPlan(snapshot: StackSnapshot, layer: StackLayer, text: PrText, ready: Boolean, repo: GitHubRepo?, current: String?): Plan {
        val calls = calls(
            unpublishedBelow(snapshot, layer).map { PublishLayer(it.branch, null, null, ready = false) } +
                PublishLayer(layer.branch, null, null, ready = ready, text = text),
            repo,
        )
        return if (unpublishedAbove(snapshot, layer).isEmpty()) Plan(calls) else LayerPlans.upTo(snapshot, layer, current, calls)
    }

    /**
     * Titulo y descripcion propuestos para el PR de [branch]. El titulo es siempre el nombre de la
     * rama, tal cual. La descripcion sale de los commits: con uno, su cuerpo; con varios, la lista
     * de sus asuntos; sin commits propios, vacia.
     */
    fun proposal(branch: String, commits: List<CommitMessage>): PrText = when (commits.size) {
        0 -> PrText(branch, "")
        1 -> PrText(branch, commits[0].body)
        else -> PrText(branch, commits.joinToString("\n") { "- ${it.subject}" })
    }

    /** La salida de [com.upstack.gh.GitCommands.log]. */
    fun parseLog(output: String): List<CommitMessage> =
        output.split(RECORD).filter { it.isNotBlank() }.map { record ->
            CommitMessage(record.substringBefore(UNIT).trim(), record.substringAfter(UNIT, "").trim())
        }

    private const val RECORD = '\u001e'
    private const val UNIT = '\u001f'
}
