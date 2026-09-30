package com.stacklane.gh

/**
 * Los pocos comandos de git que ejecuta el plugin: los que escriben, siempre dentro de un
 * plan que los explica (ver StackPlans); [log], solo para leer. Todo lo demas pasa por gh.
 */
object GitCommands {

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
}
