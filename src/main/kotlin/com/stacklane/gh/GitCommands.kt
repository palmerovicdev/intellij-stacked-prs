package com.stacklane.gh

/**
 * Los pocos comandos de git que ejecuta el plugin, siempre dentro de un plan que los
 * explica (ver StackPlans). Todo lo demas pasa por gh.
 */
object GitCommands {

    fun branch(name: String, startPoint: String): List<String> = listOf("branch", name, startPoint)

    fun switch(branch: String): List<String> = listOf("switch", branch)

    fun switchDetached(revision: String): List<String> = listOf("switch", "--detach", revision)

    fun deleteBranch(name: String): List<String> = listOf("branch", "-D", name)
}
