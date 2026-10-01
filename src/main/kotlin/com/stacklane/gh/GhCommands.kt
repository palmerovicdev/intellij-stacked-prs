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

/** Que capas toca `gh stack rebase`. */
enum class RebaseScope(val flags: List<String>) {
    /** Trae el trunk y rebasa todas las capas, la de abajo sobre el trunk. */
    STACK(emptyList()),

    /**
     * Desde la capa actual hasta la cima, sin fetch ni trunk: lleva a las capas de encima lo
     * que cambio en una de abajo, y los unicos conflictos posibles son los de ese cambio. Sin
     * `--no-trunk`, desde la capa de abajo seria un rebase completo sobre el trunk.
     */
    UPSTACK(listOf("--upstack", "--no-trunk")),

    /** Desde el trunk hasta la capa actual. */
    DOWNSTACK(listOf("--downstack")),

    /** Todas las capas, cada una sobre la de debajo, sin fetch ni trunk. */
    LAYERS(listOf("--no-trunk")),
}

/** Como fusiona GitHub los PRs de `gh stack merge`. [graphql]: como lo nombra la API. */
enum class MergeMethod(val flag: String, val graphql: String) {
    SQUASH("--squash", "SQUASH"),
    MERGE("--merge", "MERGE"),
    REBASE("--rebase", "REBASE");

    companion object {
        fun parse(value: String?): MergeMethod? = entries.firstOrNull { it.name == value || it.graphql == value }
    }
}

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

    /** [prune]: borra tambien las ramas locales de las capas fusionadas. */
    fun sync(prune: Boolean = false): List<String> = if (prune) listOf("stack", "sync", "--prune") else listOf("stack", "sync")

    /**
     * Fusiona en GitHub, de una vez, el PR [pr] y todos los de debajo: o entran todos o ninguno.
     * Si la base usa cola de merge, entran en la cola. Sin [method] (cola de merge), gh-stack no
     * manda ninguno: la cola usa el suyo.
     *
     * Un numero suelto es para gh-stack primero un numero de pila y despues uno de PR: antes de
     * lanzarlo hay que comprobar que no haya una pila con ese numero (ver MergePlans.numberCheck).
     */
    fun merge(pr: Int, method: MergeMethod?): List<String> =
        listOfNotNull("stack", "merge", pr.toString(), "--yes", method?.flag)

    fun rebase(scope: RebaseScope = RebaseScope.STACK): List<String> = listOf("stack", "rebase") + scope.flags

    fun rebaseContinue(): List<String> = listOf("stack", "rebase", "--continue")

    fun rebaseAbort(): List<String> = listOf("stack", "rebase", "--abort")

    /** Las capas activas al remoto, cada una con `--force-with-lease`. No crea PRs. */
    fun push(): List<String> = listOf("stack", "push")

    fun top(): List<String> = listOf("stack", "top")

    /**
     * La pantalla interactiva de gh-stack para reestructurar la pila. El plugin no la ejecuta: no
     * tiene version sin terminal. Solo se copia y se ensena (ver StackFlows.removeFromStack).
     */
    fun modify(): List<String> = listOf("stack", "modify")

    /** Sube las ramas de la pila al remoto: tras uno de estos no queda nada rebasado sin subir. */
    fun pushesStack(args: List<String>): Boolean = args.take(2).let { it == push() || it == sync() || it == listOf("stack", "submit") }

    /** Deja de seguir en local la pila de la rama actual; GitHub no se toca. */
    fun unstackLocal(): List<String> = listOf("stack", "unstack", "--local")

    /**
     * Deshace en GitHub la pila de la rama actual y deja de seguirla en local. No cierra PRs
     * ni borra ramas. Si GitHub deja apilados PRs en cola o con auto-merge, sale bien pero
     * sigue registrandola en local.
     */
    fun unstack(): List<String> = listOf("stack", "unstack")

    /** Acepta numero de pila, numero o URL de PR, o nombre de rama. */
    fun checkout(target: String): List<String> = listOf("stack", "checkout", target)

    fun installExtension(): List<String> = listOf("extension", "install", "github/gh-stack")

    /** [pr]: numero, URL o rama, como lo acepta `gh pr`. Ver [prSelector]. */
    fun markReady(pr: String, repo: GitHubRepo? = null): List<String> = listOf("pr", "ready") + prSelector(pr, repo)

    fun markDraft(pr: String, repo: GitHubRepo? = null): List<String> = listOf("pr", "ready") + prSelector(pr, repo) + "--undo"

    /** Titulo y descripcion de un PR. [pr] como en [markReady]. */
    fun editPr(pr: String, repo: GitHubRepo?, title: String, body: String): List<String> =
        listOf("pr", "edit") + prSelector(pr, repo) + listOf("--title", title, "--body", body)

    /**
     * Cierra el PR. Sin `--delete-branch`, que borra a la vez la rama local y la remota: al
     * cerrar una pila cada borrado es una casilla aparte (ver ClosePlans).
     */
    fun closePr(prUrl: String, comment: String?): List<String> =
        listOf("pr", "close", prUrl) + if (comment.isNullOrBlank()) emptyList() else listOf("--comment", comment)

    /** La rama sobre la que se fusionaria el PR. */
    fun editBase(prUrl: String, base: String): List<String> = listOf("pr", "edit", prUrl, "--base", base)

    /**
     * Un PR por rama es el que acaba de crear `gh stack submit`, que aun no tiene URL conocida.
     * Con `--repo` gh no tiene que adivinar el repositorio cuando hay varios remotos.
     */
    private fun prSelector(pr: String, repo: GitHubRepo?): List<String> =
        if (repo == null) listOf(pr) else listOf(pr, "--repo", repo.cliName)

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
                "additions deletions changedFiles mergeable mergeStateStatus " +
                "labels(first: 30) { nodes { name color } } " +
                "commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } }"
        )
    }

    /**
     * Los metodos de merge que admite el repositorio, el que prefiere el usuario y si [base] usa
     * cola de merge: lo mismo que pregunta `gh stack merge` antes de fusionar. Solo lee.
     */
    fun mergeSettings(repo: GitHubRepo, base: String): List<String> = listOf(
        "api", "graphql",
        "--hostname", repo.host,
        "-f", "owner=${repo.owner}",
        "-f", "name=${repo.name}",
        "-f", "base=$base",
        "-f", "qualified=refs/heads/$base",
        "-f", "query=$MERGE_SETTINGS_QUERY",
    )

    /** La pila de GitHub con numero [number], si existe (si no, `HTTP 404`). Solo lee. */
    fun remoteStack(repo: GitHubRepo, number: Int): List<String> =
        listOf("api", "repos/${repo.owner}/${repo.name}/stacks/$number", "--hostname", repo.host)

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

    // La de RepoMergeConfig y BaseBranchUsesMergeQueue de gh-stack (merge_async.go de la v0.1.1), en una.
    private const val MERGE_SETTINGS_QUERY =
        "query(\$owner: String!, \$name: String!, \$base: String!, \$qualified: String!) { " +
            "repository(owner: \$owner, name: \$name) { " +
            "mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed viewerDefaultMergeMethod " +
            "mergeQueue(branch: \$base) { id } " +
            "ref(qualifiedName: \$qualified) { rules(first: 50) { nodes { type } } } } }"

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
