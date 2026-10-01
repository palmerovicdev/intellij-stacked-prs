package com.stacklane.stack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lectura de las salidas JSON de `gh`. Con el arbol de kotlinx.serialization que ya trae
 * el IDE: sin plugin de compilador y sin empaquetar ninguna libreria.
 *
 * Todo es tolerante: un campo que falta o cambia de tipo da un valor por defecto, no una
 * excepcion. Solo falla lo que no es JSON o no tiene la forma basica esperada.
 */
object StackJson {

    /** `gh stack view --json`. */
    fun parseView(text: String): StackSnapshot {
        val root = parse(text) as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")
        val layers = (root["branches"] as? JsonArray).orEmpty().mapNotNull { element ->
            val branch = element as? JsonObject ?: return@mapNotNull null
            val name = branch.string("name") ?: return@mapNotNull null
            val pr = (branch["pr"] as? JsonObject)?.let { pr ->
                val number = pr.int("number") ?: return@let null
                PrRef(number, pr.string("url").orEmpty(), pr.string("state") ?: "OPEN")
            }
            StackLayer(
                branch = name,
                isCurrent = branch.bool("isCurrent"),
                isMerged = branch.bool("isMerged"),
                isQueued = branch.bool("isQueued"),
                needsRebase = branch.bool("needsRebase"),
                pr = pr,
            )
        }
        return StackSnapshot(
            trunk = root.string("trunk").orEmpty(),
            currentBranch = root.string("currentBranch").orEmpty(),
            layers = layers,
        )
    }

    /**
     * La respuesta de [com.stacklane.gh.GhCommands.prDetails]. Si GitHub devolvio errores
     * parciales (un PR borrado, por ejemplo) se lee lo que haya llegado.
     */
    fun parsePrDetails(text: String): Map<Int, PrDetails> {
        val root = parse(text) as? JsonObject ?: return emptyMap()
        val repository = (root["data"] as? JsonObject)?.get("repository") as? JsonObject ?: return emptyMap()
        return repository.values.mapNotNull { element ->
            val pr = element as? JsonObject ?: return@mapNotNull null
            val number = pr.int("number") ?: return@mapNotNull null
            val labels = ((pr["labels"] as? JsonObject)?.get("nodes") as? JsonArray).orEmpty().mapNotNull { node ->
                val label = node as? JsonObject ?: return@mapNotNull null
                PrLabel(label.string("name") ?: return@mapNotNull null, label.string("color").orEmpty())
            }
            val rollup = (((pr["commits"] as? JsonObject)?.get("nodes") as? JsonArray)
                ?.firstOrNull() as? JsonObject)
                ?.obj("commit")?.obj("statusCheckRollup")?.string("state")
            val additions = pr.int("additions")
            val deletions = pr.int("deletions")
            PrDetails(
                number = number,
                title = pr.string("title").orEmpty(),
                url = pr.string("url").orEmpty(),
                state = PrState.parse(pr.string("state")),
                isDraft = pr.bool("isDraft"),
                review = ReviewDecision.parse(pr.string("reviewDecision")),
                checks = ChecksState.parse(rollup),
                labels = labels,
                baseRef = pr.string("baseRefName").orEmpty(),
                size = if (additions != null && deletions != null) PrSize(additions, deletions, pr.int("changedFiles") ?: 0) else null,
                // GitHub calcula las dos cosas en segundo plano: mientras tanto dice UNKNOWN.
                hasConflicts = pr.string("mergeable") == "CONFLICTING",
                isBehind = pr.string("mergeStateStatus") == "BEHIND",
            )
        }.associateBy { it.number }
    }

    /** `gh label list --json name,color,description`. */
    fun parseRepoLabels(text: String): List<RepoLabel> =
        (parse(text) as? JsonArray).orEmpty().mapNotNull { element ->
            val label = element as? JsonObject ?: return@mapNotNull null
            RepoLabel(
                name = label.string("name") ?: return@mapNotNull null,
                color = label.string("color").orEmpty(),
                description = label.string("description").orEmpty(),
            )
        }

    /** `gh pr view --json labels`: solo los nombres. */
    fun parsePrLabelNames(text: String): Set<String> {
        val root = parse(text) as? JsonObject ?: return emptySet()
        return (root["labels"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("name") }
            .toCollection(LinkedHashSet())
    }

    /**
     * El fichero `.git/gh-stack`. Es estado interno de gh-stack, asi que solo se lee el
     * esquema conocido (1) y solo para listar pilas; nunca se escribe.
     */
    fun parseLocalStacks(text: String): List<LocalStack> {
        val root = parse(text) as? JsonObject ?: return emptyList()
        if (root.int("schemaVersion") != 1) return emptyList()
        return (root["stacks"] as? JsonArray).orEmpty().mapNotNull { element ->
            val stack = element as? JsonObject ?: return@mapNotNull null
            val trunk = stack.obj("trunk")?.string("branch") ?: return@mapNotNull null
            val entries = (stack["branches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val branches = entries.mapNotNull { it.string("branch") }
            val heads = entries.mapNotNull { entry ->
                val branch = entry.string("branch") ?: return@mapNotNull null
                entry.string("head")?.takeIf { it.isNotEmpty() }?.let { branch to it }
            }.toMap()
            if (branches.isEmpty()) null else LocalStack(trunk, branches, heads)
        }
    }

    // gh escribe avisos por stderr, pero por si alguna version los mezcla con la salida,
    // se empieza en el primer caracter que abre JSON.
    private fun parse(text: String): JsonElement? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        return try {
            Json.parseToJsonElement(text.substring(start))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
