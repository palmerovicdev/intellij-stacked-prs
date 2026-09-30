package com.stacklane.stack

/** Lo que devuelve `gh stack view --json`: la pila de la rama actual. */
data class StackSnapshot(
    val trunk: String,
    val currentBranch: String,
    /** De abajo (la mas cercana al trunk) arriba, en el orden de gh-stack. */
    val layers: List<StackLayer>,
) {
    /** La capa superior activa. gh-stack salta las fusionadas al navegar, y aqui tambien. */
    val top: StackLayer? get() = layers.lastOrNull { !it.isMerged }

    val current: StackLayer? get() = layers.firstOrNull { it.isCurrent }

    /** La rama sobre la que se apoya [layer]: la capa activa anterior, o el trunk. */
    fun parentOf(layer: StackLayer): String {
        val index = layers.indexOf(layer)
        return layers.subList(0, index.coerceAtLeast(0)).lastOrNull { !it.isMerged }?.branch ?: trunk
    }

    /** La capa activa de mas abajo: la unica cuya base es el trunk. */
    val bottom: StackLayer? get() = layers.firstOrNull { !it.isMerged }

    /**
     * La capa de abajo ya no contiene el trunk local (avanzo `main`, por ejemplo). Solo lo
     * arregla rebasar la pila entera sobre el trunk.
     */
    val behindTrunk: Boolean get() = bottom?.needsRebase == true

    /**
     * Las capas que ya no contienen a la de debajo, sin contar la de abajo, que depende del
     * trunk. Es lo que deja un commit en una capa intermedia, y lo arregla un rebase upstack.
     */
    val outdatedLayers: List<StackLayer>
        get() {
            val bottom = bottom
            return layers.filter { !it.isMerged && it.needsRebase && it != bottom }
        }

    /**
     * Las capas por encima de [layer] que recibirian un rebase upstack desde ella. gh-stack
     * salta las fusionadas y las que estan en la cola de merge.
     */
    fun activeAbove(layer: StackLayer): List<StackLayer> =
        layers.drop(layers.indexOf(layer) + 1).filter { !it.isMerged && !it.isQueued }

    /**
     * Las [outdatedLayers] por encima de [layer]. Tras un commit en [layer] solo sale la de
     * justo encima: las demas aun contienen a su padre, aunque tampoco tengan el commit.
     */
    fun outdatedAbove(layer: StackLayer): List<StackLayer> {
        val index = layers.indexOf(layer)
        return outdatedLayers.filter { layers.indexOf(it) > index }
    }

    /**
     * Desde que capa lanzar el rebase upstack que pone al dia las [outdatedLayers]. `--upstack`
     * empieza en la rama actual: si esta a la altura de la primera o por debajo, desde ahi, sin
     * checkout (las de en medio ya estan al dia y se quedan igual). Si no, desde la primera.
     */
    val upstackStart: StackLayer?
        get() {
            val first = outdatedLayers.firstOrNull() ?: return null
            val current = current?.takeIf { !it.isMerged }
            return if (current != null && layers.indexOf(current) <= layers.indexOf(first)) current else first
        }
}

data class StackLayer(
    val branch: String,
    val isCurrent: Boolean,
    val isMerged: Boolean,
    val isQueued: Boolean,
    val needsRebase: Boolean,
    val pr: PrRef?,
)

/** El PR tal y como lo guarda gh-stack: sin draft, labels ni CI. Eso lo trae [PrDetails]. */
data class PrRef(val number: Int, val url: String, val state: String)

/** Datos de un PR que gh-stack no guarda, pedidos a GitHub en una sola consulta. */
data class PrDetails(
    val number: Int,
    val title: String,
    val url: String,
    val state: PrState,
    val isDraft: Boolean,
    val review: ReviewDecision?,
    val checks: ChecksState?,
    val labels: List<PrLabel>,
    val baseRef: String,
) {
    fun hasLabel(name: String): Boolean = labels.any { it.name.equals(name, ignoreCase = true) }
}

enum class PrState { OPEN, CLOSED, MERGED;

    companion object {
        fun parse(value: String?): PrState = entries.firstOrNull { it.name == value } ?: OPEN
    }
}

enum class ReviewDecision { APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED;

    companion object {
        fun parse(value: String?): ReviewDecision? = entries.firstOrNull { it.name == value }
    }
}

/** El `statusCheckRollup` del ultimo commit, reducido a lo que se pinta. */
enum class ChecksState { SUCCESS, FAILURE, PENDING;

    companion object {
        fun parse(value: String?): ChecksState? = when (value) {
            "SUCCESS" -> SUCCESS
            "FAILURE", "ERROR" -> FAILURE
            "PENDING", "EXPECTED" -> PENDING
            else -> null
        }
    }
}

/** Color en hexadecimal sin `#`, como lo devuelve GitHub. */
data class PrLabel(val name: String, val color: String)

data class RepoLabel(val name: String, val color: String, val description: String)

/**
 * Una pila guardada en `.git/gh-stack`. [heads]: el ultimo commit conocido de cada rama; gh-stack
 * solo lo guarda de las capas publicadas.
 */
data class LocalStack(val trunk: String, val branches: List<String>, val heads: Map<String, String> = emptyMap()) {

    /** Como `Stack.Contains` de gh-stack: el trunk cuenta. */
    fun contains(branch: String): Boolean = branch == trunk || branch in branches

    /** 0 si [branch] es el trunk, 1 la capa de abajo, [branches].size la de arriba; null si no esta. */
    fun positionOf(branch: String): Int? = when (branch) {
        trunk -> 0
        in branches -> branches.indexOf(branch) + 1
        else -> null
    }
}

/**
 * Una pila local y lo que queda de sus ramas. Si ya no queda ninguna (se borraron a mano o al
 * cerrar el PR), gh-stack la sigue registrando: no se puede sacar ni reutilizar sus nombres
 * hasta olvidarla.
 */
data class LocalStackEntry(
    val stack: LocalStack,
    /** Ramas de la pila que existen en local, en orden. */
    val localBranches: List<String>,
    /** Ramas que solo existen en un remoto. */
    val remoteOnlyBranches: List<String>,
    /**
     * Ramas de la pila que tambien son la base de otra. Desde ellas gh-stack no sabe que pila
     * ensenar (`view` sale con 6), asi que no sirven para abrir esta.
     */
    val sharedBranches: Set<String> = emptySet(),
) {
    val isStale: Boolean get() = localBranches.isEmpty() && remoteOnlyBranches.isEmpty()

    /**
     * La capa mas alta desde la que gh-stack ensena esta pila: una local, si no la del remoto,
     * saltando las [sharedBranches]. Si todas lo son, la mas alta que quede: lleva a la lista
     * de pilas de esa rama, que al menos explica por que.
     */
    val checkoutTarget: String?
        get() = localBranches.lastOrNull { it !in sharedBranches }
            ?: remoteOnlyBranches.lastOrNull { it !in sharedBranches }
            ?: localBranches.lastOrNull()
            ?: remoteOnlyBranches.lastOrNull()

    /** [checkoutTarget] se queda por debajo de alguna capa porque esa es base de otra pila. */
    val targetSkipsShared: Boolean
        get() {
            val target = checkoutTarget ?: return false
            return stack.branches.drop(stack.branches.indexOf(target) + 1).any { it in sharedBranches }
        }

    /** Las ramas borradas cuyo ultimo commit se conoce: se pueden recuperar. */
    val restorable: Map<String, String>
        get() = stack.heads.filterKeys { it !in localBranches && it !in remoteOnlyBranches }

    companion object {
        /** [trunks]: las bases de todas las pilas locales. Una capa no puede ser la base de su propia pila. */
        fun of(stack: LocalStack, local: Set<String>, remote: Set<String>, trunks: Set<String> = emptySet()) = LocalStackEntry(
            stack = stack,
            localBranches = stack.branches.filter { it in local },
            remoteOnlyBranches = stack.branches.filter { it !in local && it in remote },
            sharedBranches = stack.branches.filterTo(LinkedHashSet()) { it in trunks },
        )

        /** Todas las pilas de `.git/gh-stack`, cada una sabiendo cuales de sus ramas son base de otra. */
        fun all(stacks: List<LocalStack>, local: Set<String>, remote: Set<String>): List<LocalStackEntry> {
            val trunks = stacks.mapTo(HashSet()) { it.trunk }
            return stacks.map { of(it, local, remote, trunks) }
        }
    }
}

/** Un repositorio de GitHub (o GitHub Enterprise) identificado por host, owner y nombre. */
data class GitHubRepo(val host: String, val owner: String, val name: String) {

    /** Como lo acepta `--repo` de gh: `HOST/OWNER/REPO` fuera de github.com. */
    val cliName: String get() = if (host == GITHUB_COM) "$owner/$name" else "$host/$owner/$name"

    /**
     * Mismo repositorio. Un host sin punto es un alias de `~/.ssh/config`
     * (`git@github-personal:org/repo`), que no se puede comparar con el host real: en ese
     * caso bastan owner y nombre.
     */
    fun sameAs(other: GitHubRepo): Boolean =
        owner.equals(other.owner, true) && name.equals(other.name, true) &&
            (isSshAlias || other.isSshAlias || host.equals(other.host, true))

    val isSshAlias: Boolean get() = '.' !in host && host != "localhost"

    companion object {
        private const val GITHUB_COM = "github.com"

        private val PULL_REQUEST_URL = Regex("""^https?://([^/]+)/([^/]+)/([^/]+)/pull/(\d+)(?:[/?#].*)?$""")

        // https://host/owner/name(.git), ssh://git@host(:port)/owner/name(.git), git://...
        private val URL_REMOTE = Regex("""^(?:https?|ssh|git)://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?/?$""")

        // git@host:owner/name(.git)
        private val SCP_REMOTE = Regex("""^(?:[^@/]+@)?([^:/]+):([^/]+)/([^/]+?)(?:\.git)?/?$""")

        fun fromPullRequestUrl(url: String): GitHubRepo? =
            PULL_REQUEST_URL.matchEntire(url.trim())?.destructured?.let { (host, owner, name) -> GitHubRepo(host, owner, name) }

        fun pullRequestNumber(url: String): Int? =
            PULL_REQUEST_URL.matchEntire(url.trim())?.groupValues?.get(4)?.toIntOrNull()

        fun fromRemoteUrl(url: String): GitHubRepo? {
            val trimmed = url.trim()
            val match = URL_REMOTE.matchEntire(trimmed) ?: SCP_REMOTE.matchEntire(trimmed) ?: return null
            val (host, owner, name) = match.destructured
            // ssh.github.com es el alias por el puerto 443 de github.com.
            return GitHubRepo(if (host.equals("ssh.github.com", true)) GITHUB_COM else host, owner, name)
        }
    }
}
