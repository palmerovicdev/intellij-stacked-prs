package com.upstack.gh

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.upstack.settings.UpstackSettings
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** Codigos de salida que documenta gh-stack (cmd/utils.go de la v0.1.1). */
object GhExit {
    const val NOT_IN_STACK = 2
    const val CONFLICT = 3
    const val API_FAILURE = 4
    const val INVALID_ARGS = 5
    const val DISAMBIGUATE = 6
    const val REBASE_ACTIVE = 7
    const val LOCK_FAILED = 8
    const val STACKS_UNAVAILABLE = 9
    const val MODIFY_RECOVERY = 10
}

data class GhResult(val args: List<String>, val exitCode: Int, val stdout: String, val stderr: String) {

    val ok: Boolean get() = exitCode == 0

    /** Lo que se ensena cuando falla: stderr, o stdout si gh dejo ahi el error. */
    val errorText: String get() = stderr.trim().ifEmpty { stdout.trim() }

    /** `gh` no conoce el subcomando `stack`: la extension no esta instalada. */
    val isMissingExtension: Boolean get() = !ok && stderr.contains("unknown command \"stack\"")

    /**
     * Falta la sesion o el token ya no vale: `gh` (y gh-stack, que usa su cliente) lo dice
     * siempre nombrando el comando que lo arregla, con salida 4 sin sesion y 1 con un 401.
     */
    val needsLogin: Boolean get() = !ok && errorText.contains("gh auth login")

    /** Varios remotos sin `remote.pushDefault`: gh-stack solo pregunta en una terminal interactiva. */
    val isRemoteAmbiguous: Boolean get() = !ok && errorText.contains("multiple remotes", ignoreCase = true)
}

/**
 * Los ejecutables que usa el plugin. `gh` para todo; `git` solo para los rodeos que gh-stack
 * no cubre (olvidar una pila cuyas ramas ya no existen, recuperar una rama borrada).
 */
enum class Tool(val command: String) { GH("gh"), GIT("git") }

class GhNotFoundException(val tool: Tool = Tool.GH) : Exception("${tool.command} not found")

/** Recibe un comando mientras se ejecuta. La pestana Log es la unica implementacion. */
interface GhOutputSink {
    fun started(workDir: Path, command: String)
    fun output(text: String)
    fun finished(exitCode: Int)
}

/**
 * Ejecuta `gh` como lo haria el usuario en la terminal: mismo binario, mismo entorno de
 * shell y la misma configuracion de `gh auth`. El plugin no guarda credenciales ni habla
 * con la API de GitHub por su cuenta.
 */
object GhCli {

    // Donde lo dejan los instaladores habituales, por si el IDE no ve el PATH del shell.
    private val FALLBACK_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/home/linuxbrew/.linuxbrew/bin")

    // Nada puede quedarse esperando a un teclado que no existe: sin prompts de gh, sin
    // editor para los mensajes de `rebase --continue` y sin pedir credenciales a git.
    private val ENVIRONMENT = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        "GH_NO_EXTENSION_UPDATE_NOTIFIER" to "1",
        "GH_SPINNER_DISABLED" to "1",
        "NO_COLOR" to "1",
        "CLICOLOR" to "0",
        "GIT_EDITOR" to "true",
        "GIT_TERMINAL_PROMPT" to "0",
    )

    /** [configured]: la ruta a `gh` de los ajustes, o la que se esta escribiendo en ellos sin aplicar. */
    fun locate(tool: Tool = Tool.GH, configured: String = UpstackSettings.getInstance().ghPath): Path? {
        if (tool == Tool.GH) {
            configured(configured)?.let { return it }
        }
        val name = if (SystemInfo.isWindows) "${tool.command}.exe" else tool.command
        PathEnvironmentVariableUtil.findInPath(name)?.let { return it.toPath() }
        return FALLBACK_DIRS.map { Path.of(it, tool.command) }.firstOrNull(Files::isExecutable)
    }

    /** [path] si es un ejecutable; null si esta vacia o no lo es (ni siquiera una ruta). */
    fun configured(path: String): Path? =
        path.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf(Files::isExecutable)

    /**
     * Ejecuta `gh args` (o `git args`) en [workDir]. Cancelar la corrutina mata el proceso.
     * [executable]: en vez del de los ajustes o el PATH, para comprobar una ruta sin aplicar.
     *
     * @throws GhNotFoundException si no hay ejecutable.
     */
    suspend fun run(
        workDir: Path,
        args: List<String>,
        sink: GhOutputSink? = null,
        tool: Tool = Tool.GH,
        executable: Path? = null,
    ): GhResult {
        val binary = executable ?: locate(tool) ?: throw GhNotFoundException(tool)
        val commandLine = GeneralCommandLine(listOf(binary.toString()) + args)
            .withWorkingDirectory(workDir)
            .withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(ENVIRONMENT)

        val handler = try {
            KillableProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            return GhResult(args, -1, "", e.message ?: e.toString())
        }

        sink?.started(workDir, GhCommands.display(args, tool))
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val exit = CompletableDeferred<Int>()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputTypes.STDOUT -> stdout.append(event.text)
                    ProcessOutputTypes.STDERR -> stderr.append(event.text)
                    else -> return
                }
                sink?.output(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                exit.complete(event.exitCode)
            }
        })
        handler.startNotify()

        val code = try {
            exit.await()
        } catch (e: CancellationException) {
            handler.destroyProcess()
            throw e
        }
        sink?.finished(code)
        return GhResult(args, code, stdout.toString(), stderr.toString())
    }
}
