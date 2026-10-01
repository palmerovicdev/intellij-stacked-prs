package com.stacklane.gh

/**
 * Los pocos comandos de git que ejecuta el plugin: los que escriben, siempre dentro de un
 * plan que los explica (ver StackPlans y ClosePlans) o de la pregunta de rerere (ver Rerere);
 * [log], [statusTracked], [rerereConfig], [mergeBase], [lsRemoteHeads] y [rebaseHead], solo para leer. Todo
 * lo demas pasa por gh.
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

    fun deleteBranches(names: List<String>): List<String> = listOf("branch", "-D") + names

    /** Crea la rama local de [remoteBranch] (`origin/main`), con seguimiento, y cambia a ella. */
    fun switchTracking(remoteBranch: String): List<String> = listOf("switch", "--track", remoteBranch)

    fun deleteRemoteBranches(remote: String, names: List<String>): List<String> = listOf("push", remote, "--delete") + names

    /**
     * Las ramas [names] tal y como estan ahora en [remote], una por linea (`sha<TAB>refs/heads/rama`).
     * Solo lee, pero va por la red: lo que git sabe del remoto en local puede estar atrasado.
     */
    fun lsRemoteHeads(remote: String, names: Collection<String>): List<String> =
        listOf("ls-remote", "--heads", remote) + names.map { "refs/heads/$it" }

    /**
     * El commit que un rebase parado no pudo aplicar: hash corto y asunto, separados por US
     * (0x1F). Solo lee; falla si no hay `REBASE_HEAD`.
     */
    fun rebaseHead(): List<String> = listOf("log", "-1", "--format=%h%x1f%s", "REBASE_HEAD", "--")

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
