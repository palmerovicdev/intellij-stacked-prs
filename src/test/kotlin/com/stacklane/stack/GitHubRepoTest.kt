package com.stacklane.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepoTest {

    private val shop = GitHubRepo("github.com", "acme", "shop")

    @Test
    fun `remote urls`() {
        assertEquals(shop, GitHubRepo.fromRemoteUrl("https://github.com/acme/shop.git"))
        assertEquals(shop, GitHubRepo.fromRemoteUrl("https://github.com/acme/shop"))
        assertEquals(shop, GitHubRepo.fromRemoteUrl("https://token@github.com/acme/shop/"))
        assertEquals(shop, GitHubRepo.fromRemoteUrl("git@github.com:acme/shop.git"))
        assertEquals(shop, GitHubRepo.fromRemoteUrl("ssh://git@github.com/acme/shop.git"))
        assertEquals(shop, GitHubRepo.fromRemoteUrl("ssh://git@ssh.github.com:443/acme/shop.git"))
        assertEquals(GitHubRepo("ghe.acme.io", "team", "api"), GitHubRepo.fromRemoteUrl("git@ghe.acme.io:team/api.git"))
        assertNull(GitHubRepo.fromRemoteUrl("/local/path/repo"))
    }

    @Test
    fun `pull request urls`() {
        assertEquals(shop, GitHubRepo.fromPullRequestUrl("https://github.com/acme/shop/pull/42"))
        assertEquals(42, GitHubRepo.pullRequestNumber("https://github.com/acme/shop/pull/42/files"))
        assertNull(GitHubRepo.pullRequestNumber("https://github.com/acme/shop/issues/42"))
    }

    @Test
    fun `cli name and comparison`() {
        assertEquals("acme/shop", shop.cliName)
        assertEquals("ghe.acme.io/team/api", GitHubRepo("ghe.acme.io", "team", "api").cliName)
        assertTrue(shop.sameAs(GitHubRepo("GitHub.com", "ACME", "Shop")))
    }
}
