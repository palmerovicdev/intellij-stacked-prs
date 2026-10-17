package com.upstack.gh

import com.upstack.stack.GitHubRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentTest {

    @Test
    fun `versions as gh and gh-stack print them`() {
        // Salidas reales de gh 2.102.0 y gh-stack v0.1.1.
        assertEquals("2.102.0", Environment.parseGhVersion("gh version 2.102.0 (2026-09-30)\nhttps://github.com/cli/cli/releases/tag/v2.102.0\n"))
        assertNull(Environment.parseGhVersion("command not found"))
        assertEquals("0.1.1", Environment.parseStackVersion("gh stack version 0.1.1\n"))
        assertEquals("0.2.0", Environment.parseStackVersion("gh stack version v0.2.0\n"))
        assertNull(Environment.parseStackVersion("unknown command \"stack\" for \"gh\""))
    }

    @Test
    fun `only the reference version of gh-stack is known`() {
        assertEquals(VersionStatus.KNOWN, StackExtension(true, "0.1.1").status)
        assertEquals(VersionStatus.OLDER, StackExtension(true, "0.1.0").status)
        assertEquals(VersionStatus.OLDER, StackExtension(true, "0.0.9").status)
        assertEquals(VersionStatus.NEWER, StackExtension(true, "0.2.0").status)
        // Por numeros, no por texto: 10 va despues de 1.
        assertEquals(VersionStatus.NEWER, StackExtension(true, "0.1.10").status)
        assertEquals(VersionStatus.NEWER, StackExtension(true, "1.0").status)
        assertEquals(VersionStatus.UNREADABLE, StackExtension(true, "dev").status)
        assertEquals(VersionStatus.UNREADABLE, StackExtension(true, null).status)
    }

    @Test
    fun `the one-time code of a web login, as soon as gh prints it`() {
        assertNull(Environment.parseLoginPrompt("", "github.com"))
        assertNull(Environment.parseLoginPrompt("\n! One-time", "github.com"))
        // gh 2.102.0 sin terminal.
        val copied = "\n! One-time code (1A2B-3C4D) copied to clipboard\n"
        assertEquals(LoginPrompt("1A2B-3C4D", "https://github.com/login/device"), Environment.parseLoginPrompt(copied, "github.com"))
        // La pagina es la que diga gh; mientras no la diga, la del flujo de dispositivo del host.
        assertEquals(LoginPrompt("1A2B-3C4D", "https://ghe.acme.io/login/device"), Environment.parseLoginPrompt(copied, "ghe.acme.io"))
        val older = "! First copy your one-time code: WXYZ-0000\nOpen this URL to continue in your web browser: https://acme.ghe.com/login/device\n"
        assertEquals(LoginPrompt("WXYZ-0000", "https://acme.ghe.com/login/device"), Environment.parseLoginPrompt(older, "github.com"))
    }

    @Test
    fun `log in goes to the host of the repository, and an ssh alias is github com`() {
        assertEquals("github.com", Environment.loginHost(null))
        assertEquals("github.com", Environment.loginHost(GitHubRepo("github.com", "acme", "shop")))
        assertEquals("ghe.acme.io", Environment.loginHost(GitHubRepo("ghe.acme.io", "acme", "shop")))
        assertEquals("github.com", Environment.loginHost(GitHubRepo("github-personal", "acme", "shop")))
    }

    @Test
    fun `a session is per host, with its active and valid account`() {
        val session = GhSession.of(
            listOf(
                GhAccount("github.com", "octo", active = true, valid = true, source = "keyring"),
                GhAccount("github.com", "work", active = false, valid = true, source = "keyring"),
                GhAccount("ghe.acme.io", "octo", active = true, valid = false, source = "keyring", error = "HTTP 401"),
            )
        )
        assertTrue(session.signedIn)
        assertTrue(session.signedInTo("github.com"))
        assertTrue(session.signedInTo("GitHub.com"))
        assertFalse(session.signedInTo("ghe.acme.io"))
        assertFalse(session.signedInTo("other.example"))
        // Un alias de ssh no dice el host: basta con que haya sesion.
        assertTrue(session.signedInTo(null))
        assertFalse(GhSession.of(emptyList()).signedInTo(null))
        // Un gh sin `--json`: no se sabe en que host, solo si hay sesion.
        assertTrue(GhSession(emptyList(), signedIn = true, text = "Logged in to github.com").signedInTo("ghe.acme.io"))
    }

    @Test
    fun `gh names the fix when the session is missing or its token is rejected`() {
        val none = "To get started with GitHub CLI, please run:  gh auth login\nAlternatively, populate the GH_TOKEN environment variable with a GitHub API authentication token.\n"
        assertTrue(GhResult(listOf("api", "graphql"), 4, "", none).needsLogin)
        assertTrue(GhResult(listOf("api", "graphql"), 1, "", "gh: Bad credentials (HTTP 401)\nTry authenticating with:  gh auth login\n").needsLogin)
        assertFalse(GhResult(listOf("api", "graphql"), 1, "", "HTTP 502: Bad Gateway").needsLogin)
        assertFalse(GhResult(listOf("auth", "status"), 0, "", "To log in, run: gh auth login").needsLogin)
    }
}
