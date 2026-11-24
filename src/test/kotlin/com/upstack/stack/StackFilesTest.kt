package com.upstack.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class StackFilesTest {
    @Test
    fun `shared state is preferred and legacy state remains available`() {
        val root = Files.createTempDirectory("upstack-worktree")
        try {
            val common = Files.createDirectories(root.resolve("main.git"))
            val admin = Files.createDirectories(common.resolve("worktrees/linked"))
            val linked = Files.createDirectories(root.resolve("linked"))
            Files.writeString(linked.resolve(".git"), "gitdir: $admin\n")
            Files.writeString(admin.resolve("commondir"), "../..\n")
            val legacy = Files.writeString(admin.resolve("gh-stack"), "legacy")
            assertEquals(legacy, StackFiles.stateFile(linked, "gh-stack"))
            val shared = Files.writeString(common.resolve("gh-stack"), "shared")
            assertEquals(shared, StackFiles.stateFile(linked, "gh-stack"))
            val rebase = Files.writeString(common.resolve("gh-stack-rebase-state"), "{}")
            assertEquals(rebase, StackFiles.stateFile(linked, "gh-stack-rebase-state"))
            assertNull(StackFiles.stateFile(root, "gh-stack"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
