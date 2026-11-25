package com.upstack.stack

import java.nio.file.Path

/** Una plantilla de PR de Settings: su nombre y el titulo y la descripcion con que se rellena. */
data class PrTemplate(val name: String, val title: String, val body: String) {

    /**
     * El texto de la plantilla para [branch]: `{branch}` es el nombre de la rama y `{commits}`
     * lo que se propone de sus commits ([commits], vacio si no se sabe). Lo demas va tal cual.
     */
    fun render(branch: String, commits: String = ""): PrText = PrText(fill(title, branch, commits).trim(), fill(body, branch, commits).trim())

    private fun fill(text: String, branch: String, commits: String) = text.replace(BRANCH, branch).replace(COMMITS, commits)

    companion object {
        const val BRANCH = "{branch}"
        const val COMMITS = "{commits}"
    }
}

/**
 * El Markdown de una capa: el texto de su PR en `.upstack/<rama>.md`, en la raiz del repositorio.
 * La primera linea `# ` es el titulo y lo que sigue, la descripcion.
 */
object PrFile {

    const val DIRECTORY = ".upstack"

    /** Donde esta el md de [branch] en el repositorio de [root]. Una rama no puede llevar `..`, asi que no sale de `.upstack`. */
    fun path(root: Path, branch: String): Path = root.resolve(DIRECTORY).resolve("$branch.md")

    /** La ruta como se ensena: relativa a la raiz del repositorio. */
    fun display(branch: String): String = "$DIRECTORY/$branch.md"

    fun parse(markdown: String): PrText {
        val lines = markdown.replace("\r\n", "\n").lines()
        val index = lines.indexOfFirst { it.isNotBlank() }
        // Un editor que quita los espacios del final deja `#` en vez de `# `: tambien es un titulo vacio.
        if (index < 0 || !(lines[index].startsWith("# ") || lines[index].trim() == "#")) return PrText("", markdown.trim())
        return PrText(lines[index].removePrefix("#").trim(), lines.drop(index + 1).joinToString("\n").trim())
    }

    fun format(text: PrText): String = buildString {
        append("# ").append(text.title).append('\n')
        if (text.body.isNotEmpty()) append('\n').append(text.body).append('\n')
    }
}
