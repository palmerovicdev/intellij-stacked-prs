package com.upstack.stack

import com.upstack.gh.GhCommands
import com.upstack.gh.GhResult
import com.upstack.gh.MergeMethod
import com.upstack.settings.FinalLabel

/** Una capa que `gh stack merge` puede fusionar: su PR esta abierto y no es draft. */
data class MergeCandidate(val layer: StackLayer, val number: Int, val details: PrDetails)

/** Por que una capa no se puede fusionar, ni ninguna de las de encima. */
enum class MergeBlock { DRAFT, CLOSED, UNPUBLISHED, UNKNOWN }

data class MergeBlocker(val layer: StackLayer, val reason: MergeBlock)

/**
 * Lo que se puede fusionar de una pila, como lo calcula gh-stack (`mergeCandidates` en
 * cmd/merge.go de la v0.1.1): de abajo arriba, saltando las capas fusionadas, hasta la primera
 * que no se puede. Esa para todo lo de encima: GitHub fusiona una capa con todas las de debajo.
 */
data class MergeTargets(
    val trunk: String,
    /** De abajo arriba. */
    val candidates: List<MergeCandidate>,
    /** La primera capa que no se puede fusionar, si la hay. */
    val blocker: MergeBlocker?,
) {
    fun candidateOf(branch: String): MergeCandidate? = candidates.firstOrNull { it.layer.branch == branch }

    /** Lo que entra al fusionar hasta [target], de abajo arriba. */
    fun upTo(target: MergeCandidate): List<MergeCandidate> = candidates.subList(0, candidates.indexOf(target) + 1)
}

/**
 * Como fusiona el repositorio: los metodos que admite, el que prefiere el usuario y si la base
 * usa cola de merge. Con cola, la cola elige el metodo y gh-stack no manda ninguno.
 */
data class MergeSettings(val allowed: Set<MergeMethod>, val viewerDefault: MergeMethod?, val usesMergeQueue: Boolean)

/** Que se eligio en el dialogo. [method]: null si la base usa cola de merge. */
data class MergeChoice(val target: MergeCandidate, val method: MergeMethod?)

/** Lo que dijo gh-stack al terminar bien. */
enum class MergeOutcome { MERGED, QUEUED, ALREADY_MERGED }

/**
 * Lo que haria gh-stack con `gh stack merge N`: prueba N primero como numero de pila y despues
 * como numero de PR (`resolveMergeStack` en cmd/merge.go de la v0.1.1). Con `--yes`, si hay una
 * pila con ese numero la fusiona entera, aunque N sea un PR de otra.
 */
enum class NumberCheck {
    /** No hay pila con ese numero: gh-stack lo toma como el PR. */
    PULL_REQUEST,

    /** Hay una pila con ese numero y N es su PR de arriba: fusionarla entera es lo mismo. */
    SAME_MERGE,

    /** Hay una pila con ese numero y fusionaria otros PRs: no se lanza. */
    OTHER_STACK,
}

/**
 * *Merge Up to Here…* y *Merge Stack…*: un unico `gh stack merge` (antes, si Settings lo pide, la
 * label de capa final: ver [calls]), nunca sin el dialogo.
 */
object MergePlans {

    /** Ver [MergeTargets]. Sin datos de GitHub de un PR no se sabe si es draft: para ahi. */
    fun targets(snapshot: StackSnapshot, details: Map<Int, PrDetails>): MergeTargets {
        val candidates = mutableListOf<MergeCandidate>()
        fun stop(layer: StackLayer, reason: MergeBlock) = MergeTargets(snapshot.trunk, candidates, MergeBlocker(layer, reason))
        for (layer in snapshot.layers) {
            if (layer.isMerged) continue
            val pr = layer.pr ?: return stop(layer, MergeBlock.UNPUBLISHED)
            val info = details[pr.number] ?: return stop(layer, MergeBlock.UNKNOWN)
            when {
                info.state == PrState.MERGED -> continue
                info.state == PrState.CLOSED -> return stop(layer, MergeBlock.CLOSED)
                info.isDraft -> return stop(layer, MergeBlock.DRAFT)
            }
            candidates += MergeCandidate(layer, pr.number, info)
        }
        return MergeTargets(snapshot.trunk, candidates, null)
    }

    /**
     * El metodo con el que abre el dialogo: el ultimo que se uso aqui, si el repositorio lo
     * admite; si no, el que prefiere el usuario en GitHub; si no, el primero que se admita.
     */
    fun initialMethod(settings: MergeSettings?, last: MergeMethod?): MergeMethod {
        val allowed = settings?.allowed?.takeIf { it.isNotEmpty() } ?: MergeMethod.entries.toSet()
        return listOfNotNull(last, settings?.viewerDefault).firstOrNull { it in allowed }
            ?: MergeMethod.entries.first { it in allowed }
    }

    /** `gh stack merge N`: siempre la ultima de [calls], y la que dice como fue ([outcome]). */
    fun call(choice: MergeChoice): GhCall = GhCall(GhCommands.merge(choice.target.number, choice.method))

    /**
     * Lo que se ejecuta al fusionar, en orden; lo mismo que ensena el dialogo. Con [label] (en
     * Settings, ponerla antes de Merge Stack; null si no) y el PR de arriba de lo que se fusiona
     * sin ella: antes se crea la label (`--force`: si ya existe, se actualiza), se le pone a ese PR
     * y se quita de las demas capas que la tengan, como *Mark as Final Layer*. Si una de esas
     * falla, la operacion para ahi y no se fusiona nada.
     */
    fun calls(choice: MergeChoice, snapshot: StackSnapshot, details: Map<Int, PrDetails>, github: GitHubRepo, label: FinalLabel?): List<GhCall> {
        val target = choice.target
        if (label == null || target.details.hasLabel(label.name)) return listOf(call(choice))
        val others = FinalLabelPlans.othersWith(snapshot, details, target.number, label.name)
        val url = FinalLabelPlans.prUrl(target.layer, github)
        return listOf(GhCall(label.create(github))) + FinalLabelPlans.mark(url, others, label.name, github) + call(choice)
    }

    /**
     * La respuesta de [GhCommands.remoteStack] para el numero del PR [target]. null si no se pudo
     * saber: entonces no se lanza el merge.
     */
    fun numberCheck(target: Int, result: GhResult): NumberCheck? {
        if (!result.ok) return if (result.errorText.contains("HTTP 404")) NumberCheck.PULL_REQUEST else null
        val prs = StackJson.parseRemoteStackPrs(result.stdout) ?: return null
        return if (prs.lastOrNull() == target) NumberCheck.SAME_MERGE else NumberCheck.OTHER_STACK
    }

    /** Lo que dice gh-stack al terminar bien (cmd/merge.go de la v0.1.1), en stdout o stderr. */
    fun outcome(result: GhResult): MergeOutcome? {
        val output = result.stdout + "\n" + result.stderr
        return when {
            "to the merge queue" in output -> MergeOutcome.QUEUED
            "already merged" in output || "already fully merged" in output -> MergeOutcome.ALREADY_MERGED
            "Merged #" in output -> MergeOutcome.MERGED
            else -> null
        }
    }
}

/**
 * La label de capa final en una pila: una pila tiene un solo final. La comparten *Mark as Final
 * Layer* (StackFlows.toggleFinal) y Merge Stack con la opcion de Settings ([MergePlans.calls]).
 */
object FinalLabelPlans {

    /** Las capas de la pila con la label [label], menos la del PR [number]. GitHub no distingue mayusculas. */
    fun othersWith(snapshot: StackSnapshot, details: Map<Int, PrDetails>, number: Int, label: String): List<StackLayer> =
        snapshot.layers.filter { layer ->
            val pr = layer.pr
            pr != null && pr.number != number && details[pr.number]?.hasLabel(label) == true
        }

    /** Ponerla en el PR [url] y quitarla de [others], cada uno con su `gh pr edit`. */
    fun mark(url: String, others: List<StackLayer>, label: String, github: GitHubRepo): List<GhCall> =
        listOf(GhCall(GhCommands.editLabels(url, listOf(label), emptyList()))) +
            others.map { GhCall(GhCommands.editLabels(prUrl(it, github), emptyList(), listOf(label))) }

    /** La URL del PR de [layer]; si gh-stack aun no la tiene, la de siempre en [github]. */
    fun prUrl(layer: StackLayer, github: GitHubRepo): String {
        val pr = requireNotNull(layer.pr)
        return pr.url.ifEmpty { "https://${github.host}/${github.owner}/${github.name}/pull/${pr.number}" }
    }
}
