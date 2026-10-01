package com.stacklane.flow

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Un `gh` sin red: un script que apunta los argumentos de cada llamada y contesta con salidas
 * grabadas. Se le pasa al plugin como ruta de `gh` en los ajustes, que es lo primero que mira
 * `GhCli.locate()`.
 *
 * Cada respuesta es un directorio en `rules/` con los argumentos esperados, el codigo de salida,
 * stdout y stderr. Gana la primera que coincide, por orden de alta; las de [fallback] van
 * siempre al final. Una llamada sin respuesta sale con 127 y lo dice en stderr.
 */
class FakeGh(private val dir: Path) {

    val executable: Path = dir.resolve("gh")
    private val rules: Path = dir.resolve("rules")
    private val calls: Path = dir.resolve("calls")
    private var next = 0
    private var nextFallback = 0

    init {
        rules.createDirectories()
        calls.writeText("")
        executable.writeText(script())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** Una respuesta registrada, para soltarla si se creo retenida ([hold]). */
    class Rule internal constructor(private val dir: Path) {
        fun release() {
            dir.resolve("hold").deleteIfExists()
        }
    }

    /**
     * Contesta a `gh args`. [prefix]: tambien a llamadas con mas argumentos detras. [times]: solo
     * las primeras veces; despues sigue buscando. [hold]: la llamada no termina hasta [Rule.release].
     */
    fun respond(
        args: List<String>,
        exit: Int = 0,
        stdout: String = "",
        stderr: String = "",
        prefix: Boolean = false,
        times: Int? = null,
        hold: Boolean = false,
    ): Rule = add("r%04d".format(next++), args, exit, stdout, stderr, prefix, times, hold)

    /** Como [respond], pero detras de todas las demas: lo que contesta si nadie mas lo hace. */
    fun fallback(args: List<String>, exit: Int = 0, stdout: String = "", stderr: String = "", prefix: Boolean = false): Rule =
        add("z%04d".format(nextFallback++), args, exit, stdout, stderr, prefix, null, false)

    private fun add(
        name: String,
        args: List<String>,
        exit: Int,
        stdout: String,
        stderr: String,
        prefix: Boolean,
        times: Int?,
        hold: Boolean,
    ): Rule {
        val rule = rules.resolve(name).createDirectories()
        rule.resolve("args").writeText(key(args))
        rule.resolve("exit").writeText(exit.toString())
        rule.resolve("stdout").writeText(stdout)
        rule.resolve("stderr").writeText(stderr)
        if (prefix) rule.resolve("prefix").writeText("")
        if (times != null) rule.resolve("times").writeText(times.toString())
        if (hold) rule.resolve("hold").writeText("")
        return Rule(rule)
    }

    /** Todas las llamadas, en orden, cada una con sus argumentos. */
    fun calls(): List<List<String>> =
        if (!calls.exists()) emptyList()
        else calls.readText().split(RECORD).filter { it.isNotEmpty() }.map { it.split(UNIT).dropLast(1) }

    /** Las llamadas que no son lecturas: ni `stack view` ni `gh api` (GraphQL o un GET). */
    fun writes(): List<List<String>> = calls().filter { !isRead(it) }

    private fun isRead(args: List<String>) = args == listOf("stack", "view", "--json") || args.firstOrNull() == "api"

    private fun key(args: List<String>) = args.joinToString("") { it + UNIT }

    // bash 3.2, el de macOS. Los argumentos, separados por US (0x1F) y cada llamada por RS (0x1E):
    // los argumentos pueden tener espacios o saltos de linea (la descripcion de un PR).
    private fun script() = """
        |#!/bin/bash
        |dir='$dir'
        |key=''
        |for a in "${'$'}@"; do key+="${'$'}a"${'$'}'\x1f'; done
        |printf '%s\x1e' "${'$'}key" >> "${'$'}dir/calls"
        |for rule in "${'$'}dir"/rules/*/; do
        |  expected=${'$'}(<"${'$'}rule/args")
        |  if [[ -f "${'$'}rule/prefix" ]]; then
        |    [[ "${'$'}key" == "${'$'}expected"* ]] || continue
        |  else
        |    [[ "${'$'}key" == "${'$'}expected" ]] || continue
        |  fi
        |  if [[ -f "${'$'}rule/times" ]]; then
        |    left=${'$'}(<"${'$'}rule/times")
        |    (( left > 0 )) || continue
        |    echo ${'$'}((left - 1)) > "${'$'}rule/times"
        |  fi
        |  waited=0
        |  while [[ -f "${'$'}rule/hold" && ${'$'}waited -lt 600 ]]; do sleep 0.05; waited=${'$'}((waited + 1)); done
        |  cat "${'$'}rule/stdout"
        |  cat "${'$'}rule/stderr" >&2
        |  exit ${'$'}(<"${'$'}rule/exit")
        |done
        |echo "fake gh: no response for: ${'$'}*" >&2
        |exit 127
        |""".trimMargin()

    private companion object {
        const val UNIT = "\u001f"
        const val RECORD = "\u001e"
    }
}
