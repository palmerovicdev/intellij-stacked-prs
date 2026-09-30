package com.stacklane.gh

import com.stacklane.stack.GitHubRepo

/** Como se prepara el commit opcional de `gh stack add`. */
enum class Staging(val flag: String?) {
    /** `-A`: todo, incluidos los ficheros sin seguimiento. */
    ALL("-A"),

    /** `-u`: solo los cambios de ficheros con seguimiento. */
    TRACKED("-u"),

    /** Sin flag: lo que ya este en el indice. */
    STAGED(null),
}

data class LayerCommit(val message: String, val staging: Staging)

/**
 * Los comandos que ejecuta el plugin, uno por operacion. Son exactamente los que se
 * escribirian en la terminal, y la pestana Log los muestra asi.
 */
object GhCommands {

    fun view(): List<String> = listOf("stack", "view", "--json")

    fun init(branches: List<String>, trunk: String?): List<String> = buildList {
        add("stack")
        add("init")
        if (!trunk.isNullOrBlank()) {
            add("--base")
            add(trunk)
        }
        addAll(branches)
    }

    fun add(branch: String, commit: LayerCommit?): List<String> = buildList {
        add("stack")
        add("add")
        if (commit != null) {
            commit.staging.flag?.let(::add)
            add("-m")
            add(commit.message)
        }
        add(branch)
    }

    /** `--auto` crea los PRs nuevos como draft; con `--open` todos quedan listos, tambien los que ya existian. */
    fun submit(ready: Boolean): List<String> =
        if (ready) listOf("stack", "submit", "--auto", "--open") else listOf("stack", "submit", "--auto")

    fun sync(): List<String> = listOf("stack", "sync")

    fun rebase(): List<String> = listOf("stack", "rebase")

    fun rebaseContinue(): List<String> = listOf("stack", "rebase", "--continue")

    fun rebaseAbort(): List<String> = listOf("stack", "rebase", "--abort")

    fun top(): List<String> = listOf("stack", "top")

    /** Deja de seguir en local la pila de la rama actual; GitHub no se toca. */
    fun unstackLocal(): List<String> = listOf("stack", "unstack", "--local")

    /** Acepta numero de pila, numero o URL de PR, o nombre de rama. */
    fun checkout(target: String): List<String> = listOf("stack", "checkout", target)

    fun installExtension(): List<String> = listOf("extension", "install", "github/gh-stack")

    fun markReady(prUrl: String): List<String> = listOf("pr", "ready", prUrl)

    fun markDraft(prUrl: String): List<String> = listOf("pr", "ready", prUrl, "--undo")

    fun editLabels(prUrl: String, toAdd: Collection<String>, toRemove: Collection<String>): List<String> = buildList {
        add("pr")
        add("edit")
        add(prUrl)
        for (label in toAdd) {
            add("--add-label")
            add(label)
        }
        for (label in toRemove) {
            add("--remove-label")
            add(label)
        }
    }

    /** `--force`: si ya existe se actualiza en vez de fallar, y el reintento es idempotente. */
    fun createLabel(repo: GitHubRepo, name: String): List<String> = listOf(
        "label", "create", name,
        "--repo", repo.cliName,
        "--color", FINAL_LABEL_COLOR,
        "--description", "Top layer of a stack of pull requests",
        "--force",
    )

    fun repoLabels(repo: GitHubRepo): List<String> =
        listOf("label", "list", "--repo", repo.cliName, "--json", "name,color,description", "--limit", "1000")

    fun prLabels(prUrl: String): List<String> = listOf("pr", "view", prUrl, "--json", "labels")

    /** Una sola llamada GraphQL para todos los PRs de la pila, en vez de un `gh pr view` por capa. */
    fun prDetails(repo: GitHubRepo, numbers: Collection<Int>): List<String> = listOf(
        "api", "graphql",
        "--hostname", repo.host,
        "-f", "owner=${repo.owner}",
        "-f", "name=${repo.name}",
        "-f", "query=${prDetailsQuery(numbers)}",
    )

    fun prDetailsQuery(numbers: Collection<Int>): String = buildString {
        append("query(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) {")
        for (number in numbers.distinct()) {
            append(" pr").append(number).append(": pullRequest(number: ").append(number).append(") { ...Layer }")
        }
        append(" } } ")
        append(
            "fragment Layer on PullRequest { number title url state isDraft reviewDecision baseRefName " +
                "labels(first: 30) { nodes { name color } } " +
                "commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } }"
        )
    }

    /** La linea que se pegaria en la terminal. */
    fun display(args: List<String>, tool: Tool = Tool.GH): String = buildString {
        append(tool.command)
        for (arg in args) {
            append(' ')
            // La consulta GraphQL es ruido en el log: se abrevia.
            if (arg.startsWith("query=") && arg.length > 60) append("query='…'") else append(quote(arg))
        }
    }

    private const val FINAL_LABEL_COLOR = "8250DF"

    private val SAFE = Regex("[A-Za-z0-9_./:=@%+,-]+")

    private fun quote(arg: String): String =
        if (arg.matches(SAFE)) arg else "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Motivo por el que git rechazaria [name] como rama (`git check-ref-format`), o null si
     * es valido. Se comprueba en los dialogos para no lanzar un comando que va a fallar.
     */
    fun branchNameProblem(name: String): BranchNameProblem? = when {
        name.isBlank() -> BranchNameProblem.EMPTY
        name.any { it.isWhitespace() } -> BranchNameProblem.WHITESPACE
        name.any { it < ' ' || it == '\u007f' || it in "~^:?*[\\" } -> BranchNameProblem.CHARACTERS
        name.startsWith("-") || name.startsWith("/") || name.endsWith("/") ||
            name.endsWith(".") || name.endsWith(".lock") || name == "@" -> BranchNameProblem.BOUNDARY
        name.contains("..") || name.contains("@{") || name.contains("//") ||
            name.split('/').any { it.startsWith(".") } -> BranchNameProblem.SEQUENCE
        else -> null
    }
}

enum class BranchNameProblem { EMPTY, WHITESPACE, CHARACTERS, BOUNDARY, SEQUENCE }
