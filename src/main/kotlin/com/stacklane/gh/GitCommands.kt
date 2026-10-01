package com.stacklane.gh

/**
 * Los pocos comandos de git que ejecuta el plugin: los que escriben, siempre dentro de un
 * plan que los explica (ver StackPlans) o de la pregunta de rerere (ver Rerere); [log],
 * [statusTracked], [rerereConfig] y [mergeBase], solo para leer. Todo lo demas pasa por gh.
 */
object GitCommands {

    /** El ultimo commit comun: desde ahi, lo que anade [branch] es solo suyo. Solo lee. */
    fun mergeBase(base: String, branch: String): List<String> = listOf("merge-base", base, branch)

    /**
     * Los commits de [branch] que no estan en [base], del mas antiguo al mas nuevo. Cada uno
     * empieza por RS (0x1E) y separa asunto y cuerpo con US (0x1F). Ver PublishPlans.parseLog.
     */
    fun log(base: String, branch: String): List<String> =
        listOf("log", "--reverse", "--no-merges", "--format=%x1e%s%x1f%b", "$base..$branch", "--")

    fun branch(name: String, startPoint: String): List<String> = listOf("branch", name, startPoint)

    fun switch(branch: String): List<String> = listOf("switch", branch)

    fun switchDetached(revision: String): List<String> = listOf("switch", "--detach", revision)

    fun deleteBranch(name: String): List<String> = listOf("branch", "-D", name)

    /** Cambios en ficheros con seguimiento; con alguno, git no empieza un rebase. Solo lee. */
    fun statusTracked(): List<String> = listOf("status", "--porcelain", "--untracked-files=no")

    /**
     * Las dos claves con las que gh-stack decide si pregunta por rerere: activado, o ya
     * rechazado. Solo lee. `--type=bool` normaliza `yes`, `on` o `1` a `true`.
     */
    fun rerereConfig(): List<String> =
        listOf("config", "--type=bool", "--get-regexp", """^(rerere\.enabled|gh-stack\.rerere-declined)$""")

    /** En la configuracion del repositorio, no en la global: lo mismo que hace gh-stack. */
    fun configSet(key: String, value: String): List<String> = listOf("config", key, value)
}
