package com.stacklane.gh

import com.stacklane.stack.GitHubRepo
import com.stacklane.stack.StackJson
import java.nio.file.Path

/** Una cuenta de `gh auth status`. [valid]: GitHub acepto su token al comprobarlo. */
data class GhAccount(
    val host: String,
    val login: String,
    val active: Boolean,
    val valid: Boolean,
    /** De donde sale el token: `keyring`, un fichero o una variable como `GH_TOKEN`. */
    val source: String,
    val error: String? = null,
)

/**
 * La sesion de gh. Con `--json`, [accounts]; con un gh anterior solo se sabe si la hay
 * ([signedIn]) y lo que imprimio ([text]).
 */
data class GhSession(val accounts: List<GhAccount>, val signedIn: Boolean, val text: String? = null) {

    /**
     * Hay una cuenta activa y valida en [host]. Sin el detalle por host (gh antiguo) o con un alias de
     * `~/.ssh/config`, del que no se sabe el host real, basta con que haya sesion.
     */
    fun signedInTo(host: String?): Boolean =
        if (host == null || text != null) signedIn
        else accounts.any { it.valid && it.active && it.host.equals(host, true) }

    companion object {
        fun of(accounts: List<GhAccount>) = GhSession(accounts, accounts.any { it.valid && it.active })
    }
}

/** La version de gh-stack frente a la de referencia ([StackExtension.REFERENCE]). */
enum class VersionStatus { KNOWN, OLDER, NEWER, UNREADABLE }

/** La extension gh-stack. [version]: null si no esta o si `--version` no dijo nada reconocible. */
data class StackExtension(val installed: Boolean, val version: String?) {

    val status: VersionStatus
        get() {
            val parts = version?.let(::numbers) ?: return VersionStatus.UNREADABLE
            if (version in KNOWN) return VersionStatus.KNOWN
            return if (compare(parts, numbers(REFERENCE).orEmpty()) < 0) VersionStatus.OLDER else VersionStatus.NEWER
        }

    companion object {
        /** La version de la que se leyeron el JSON y los codigos de salida. */
        const val REFERENCE = "0.1.1"

        /** Las comprobadas: con cualquier otra, un aviso y no un bloqueo. */
        val KNOWN = setOf(REFERENCE)

        /** `0.2.0-rc1` -> 0, 2, 0. */
        private fun numbers(version: String): List<Int>? {
            val parts = version.substringBefore('-').split('.').map(String::toIntOrNull)
            return parts.filterNotNull().takeIf { it.size == parts.size }
        }

        private fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (difference != 0) return difference
            }
            return 0
        }
    }
}

/** Un remoto de git y el repositorio de GitHub al que apunta, si apunta a alguno. */
data class RemoteInfo(val name: String, val url: String, val github: GitHubRepo?)

/** Si GitHub admite pilas en el repositorio: lo que haria salir a `gh stack` con 9. */
enum class StacksSupport { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class RepositoryEnvironment(
    val name: String,
    val remotes: List<RemoteInfo>,
    /** El repositorio de GitHub que usa el plugin, o null si ningun remoto apunta a GitHub. */
    val github: GitHubRepo?,
    val stacks: StacksSupport = StacksSupport.UNKNOWN,
    /** Con [StacksSupport.UNKNOWN], por que no se pudo saber; null si ni se pregunto (sin sesion). */
    val stacksError: String? = null,
)

/** Lo que encontro [EnvironmentCheck]. Sin `gh` no hay nada mas que mirar. */
data class EnvironmentReport(
    val gh: Path?,
    /** La ruta de los ajustes, si se escribio una y no es un ejecutable. */
    val invalidPath: String? = null,
    val ghVersion: String? = null,
    val session: GhSession? = null,
    val extension: StackExtension? = null,
    val repository: RepositoryEnvironment? = null,
) {
    /** El host en el que iniciar sesion: el del repositorio si se sabe, o github.com. */
    val loginHost: String get() = Environment.loginHost(repository?.github)
}

/** El codigo de un solo uso de `gh auth login --web` y la pagina donde se escribe. */
data class LoginPrompt(val code: String, val url: String)

/** Lectura de lo que imprimen `gh --version`, `gh stack --version` y `gh auth login --web`. */
object Environment {

    const val GITHUB_COM = "github.com"

    private val GH_VERSION = Regex("""gh version (\S+)""")
    private val STACK_VERSION = Regex("""gh stack version v?(\S+)""")
    private val DEVICE_CODE = Regex("""(?<![A-Za-z0-9-])[A-Z0-9]{4}-[A-Z0-9]{4}(?![A-Za-z0-9-])""")
    private val URL = Regex("""https://[^\s'"]+""")

    /** `gh version 2.102.0 (2026-09-30)` -> `2.102.0`. */
    fun parseGhVersion(output: String): String? = GH_VERSION.find(output)?.groupValues?.get(1)

    /** `gh stack version 0.1.1` -> `0.1.1`. */
    fun parseStackVersion(output: String): String? = STACK_VERSION.find(output)?.groupValues?.get(1)

    /**
     * El codigo de un solo uso en lo que `gh auth login --web` lleva impreso, o null si aun no
     * ha salido. La pagina es la que diga gh detras del codigo; si no la dice (aun), la del
     * flujo de dispositivo de [host], que es la misma en github.com y en GitHub Enterprise.
     */
    fun parseLoginPrompt(output: String, host: String): LoginPrompt? {
        val code = DEVICE_CODE.find(output) ?: return null
        val url = URL.find(output, code.range.last)?.value ?: "https://$host/login/device"
        return LoginPrompt(code.value, url)
    }

    /** Un alias de `~/.ssh/config` no es un host de GitHub: se entra en github.com. */
    fun loginHost(github: GitHubRepo?): String = github?.takeUnless { it.isSshAlias }?.host ?: GITHUB_COM
}

/**
 * Comprueba el entorno con los mismos comandos que se escribirian en la terminal: que `gh` hay,
 * con que sesion, que version de gh-stack y si el repositorio admite pilas. Solo lee.
 */
object EnvironmentCheck {

    /**
     * [ghPath]: la ruta de los ajustes, aplicada o no. [repository]: sus remotos, sin lo de las
     * pilas, que se pregunta aqui.
     */
    suspend fun run(
        ghPath: String,
        workDir: Path,
        repository: RepositoryEnvironment?,
        sink: GhOutputSink? = null,
    ): EnvironmentReport {
        val invalidPath = ghPath.trim().takeIf { it.isNotEmpty() && GhCli.configured(it) == null }
        val gh = GhCli.locate(Tool.GH, ghPath) ?: return EnvironmentReport(null, invalidPath, repository = repository)
        suspend fun gh(args: List<String>) = GhCli.run(workDir, args, sink, executable = gh)

        val version = Environment.parseGhVersion(gh(GhCommands.version()).stdout)
        val session = session(::gh)
        val stack = gh(GhCommands.stackVersion())
        val extension = if (stack.isMissingExtension) StackExtension(false, null)
        else StackExtension(true, Environment.parseStackVersion(stack.stdout))
        return EnvironmentReport(gh, invalidPath, version, session, extension, repository?.let { stacks(it, session, ::gh) })
    }

    private suspend fun session(gh: suspend (List<String>) -> GhResult): GhSession {
        val json = gh(GhCommands.authStatus())
        StackJson.parseAuthHosts(json.stdout)?.takeIf { json.ok }?.let { return GhSession.of(it) }
        // Un gh sin `--json` en `auth status`: lo que imprime, tal cual.
        val text = gh(GhCommands.authStatusText())
        return GhSession(emptyList(), text.ok, (text.stderr + text.stdout).trim())
    }

    private suspend fun stacks(
        repository: RepositoryEnvironment,
        session: GhSession,
        gh: suspend (List<String>) -> GhResult,
    ): RepositoryEnvironment {
        val github = repository.github ?: return repository
        // Sin sesion la API contesta lo mismo en cualquier repositorio: no dice nada de este.
        if (!session.signedInTo(github.host.takeUnless { github.isSshAlias })) return repository
        val result = gh(GhCommands.stacksAvailable(github))
        return when {
            result.ok -> repository.copy(stacks = StacksSupport.AVAILABLE)
            result.errorText.contains("HTTP 404") -> repository.copy(stacks = StacksSupport.UNAVAILABLE)
            else -> repository.copy(stacksError = result.errorText.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit code ${result.exitCode}")
        }
    }
}
