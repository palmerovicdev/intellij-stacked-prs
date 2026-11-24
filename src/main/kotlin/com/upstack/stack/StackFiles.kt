package com.upstack.stack

import java.nio.file.Files
import java.nio.file.Path

/** Los ficheros locales de gh-stack 0.1 y el catalogo compartido desde 0.2. */
internal object StackFiles {
    fun gitDir(root: Path): Path? {
        val dotGit = root.resolve(".git")
        return when {
            Files.isDirectory(dotGit) -> dotGit
            Files.isRegularFile(dotGit) -> runCatching {
                Files.readString(dotGit).lineSequence().firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()?.let { root.resolve(it).normalize() }
            }.getOrNull()
            else -> null
        }
    }

    fun stateFile(root: Path, name: String): Path? {
        val dir = gitDir(root) ?: return null
        val common = runCatching {
            dir.resolve("commondir").takeIf(Files::isRegularFile)
                ?.let { dir.resolve(Files.readString(it).trim()).normalize() }
        }.getOrNull() ?: dir
        val shared = common.resolve(name)
        return if (Files.exists(shared)) shared else dir.resolve(name)
    }
}
