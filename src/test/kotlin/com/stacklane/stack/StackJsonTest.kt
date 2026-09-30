package com.stacklane.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StackJsonTest {

    // Salida real de `gh stack view --json` (gh-stack v0.1.1) en un repositorio sin PRs.
    private val viewWithoutPrs = """
        {
          "trunk": "main",
          "currentBranch": "feat/b",
          "branches": [
            { "name": "feat/a", "base": "6ef805d5", "isCurrent": false, "isMerged": false, "isQueued": false, "needsRebase": false },
            { "name": "feat/b", "base": "d36d7ca0", "isCurrent": true, "isMerged": false, "isQueued": false, "needsRebase": false }
          ]
        }
    """.trimIndent()

    @Test
    fun `view without pull requests`() {
        val snapshot = StackJson.parseView(viewWithoutPrs)
        assertEquals("main", snapshot.trunk)
        assertEquals("feat/b", snapshot.currentBranch)
        assertEquals(listOf("feat/a", "feat/b"), snapshot.layers.map { it.branch })
        assertEquals("feat/b", snapshot.top?.branch)
        assertEquals("feat/b", snapshot.current?.branch)
        assertNull(snapshot.layers[0].pr)
        assertEquals("main", snapshot.parentOf(snapshot.layers[0]))
        assertEquals("feat/a", snapshot.parentOf(snapshot.layers[1]))
    }

    @Test
    fun `view with pull requests, merged layer and noise before the json`() {
        val text = """
            ! warning printed on the same stream
            {"trunk":"main","currentBranch":"feat/api","branches":[
              {"name":"feat/domain","isCurrent":false,"isMerged":true,"isQueued":false,"needsRebase":false,
               "pr":{"number":12,"url":"https://github.com/acme/shop/pull/12","state":"MERGED"}},
              {"name":"feat/api","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":true,
               "pr":{"number":13,"url":"https://github.com/acme/shop/pull/13","state":"OPEN"},"unknownField":42}
            ]}
        """.trimIndent()
        val snapshot = StackJson.parseView(text)
        val (domain, api) = snapshot.layers
        assertTrue(domain.isMerged)
        assertEquals(12, domain.pr?.number)
        assertTrue(api.needsRebase)
        assertEquals("https://github.com/acme/shop/pull/13", api.pr?.url)
        // La capa fusionada no cuenta como base ni como cima.
        assertEquals("main", snapshot.parentOf(api))
        assertEquals("feat/api", snapshot.top?.branch)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `view that is not json fails`() {
        StackJson.parseView("current branch \"main\" is not part of a stack")
    }

    @Test
    fun `pull request details from graphql, with a partial error`() {
        val text = """
            {"data":{"repository":{
              "pr12":{"number":12,"title":"Payment domain","url":"https://github.com/acme/shop/pull/12","state":"OPEN",
                      "isDraft":true,"reviewDecision":"REVIEW_REQUIRED","baseRefName":"main",
                      "labels":{"nodes":[{"name":"backend","color":"0e8a16"}]},
                      "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS"}}}]}},
              "pr13":{"number":13,"title":"Payment API","url":"https://github.com/acme/shop/pull/13","state":"OPEN",
                      "isDraft":false,"reviewDecision":null,"baseRefName":"feat/domain",
                      "labels":{"nodes":[{"name":"stack-final","color":"8250df"}]},
                      "commits":{"nodes":[{"commit":{"statusCheckRollup":null}}]}},
              "pr99":null}},
             "errors":[{"message":"Could not resolve to a PullRequest with the number of 99."}]}
        """.trimIndent()
        val details = StackJson.parsePrDetails(text)
        assertEquals(setOf(12, 13), details.keys)
        val domain = details.getValue(12)
        assertTrue(domain.isDraft)
        assertEquals(ReviewDecision.REVIEW_REQUIRED, domain.review)
        assertEquals(ChecksState.SUCCESS, domain.checks)
        assertEquals(listOf(PrLabel("backend", "0e8a16")), domain.labels)
        val api = details.getValue(13)
        assertFalse(api.isDraft)
        assertNull(api.review)
        assertNull(api.checks)
        assertTrue(api.hasLabel("STACK-FINAL"))
        assertEquals("feat/domain", api.baseRef)
    }

    @Test
    fun `details of an error response are empty`() {
        assertTrue(StackJson.parsePrDetails("gh: To get started with GitHub CLI, please run:  gh auth login").isEmpty())
    }

    @Test
    fun `repository labels and pull request labels`() {
        val labels = StackJson.parseRepoLabels(
            """[{"name":"bug","color":"d73a4a","description":"Something isn't working"},{"name":"stack-final","color":"8250DF","description":""}]"""
        )
        assertEquals(listOf("bug", "stack-final"), labels.map { it.name })
        assertEquals("d73a4a", labels[0].color)

        val applied = StackJson.parsePrLabelNames("""{"labels":[{"id":"LA_1","name":"bug","description":"","color":"d73a4a"}]}""")
        assertEquals(setOf("bug"), applied)
    }

    @Test
    fun `local stacks file, schema 1 only`() {
        // .git/gh-stack real, escrito por gh-stack v0.1.1.
        val file = """
            {"schemaVersion":1,"repository":"","stacks":[
              {"trunk":{"branch":"main","head":"6ef805d5"},
               "branches":[{"branch":"feat/a","base":"6ef805d5"},{"branch":"feat/b","base":"d36d7ca0"}]}]}
        """.trimIndent()
        assertEquals(listOf(LocalStack("main", listOf("feat/a", "feat/b"))), StackJson.parseLocalStacks(file))
        // Tras publicar, gh-stack guarda tambien el ultimo commit de cada rama (fichero real).
        val published = """
            {"schemaVersion":1,"repository":"","stacks":[{"trunk":{"branch":"main","head":"c5dbe3db"},
             "branches":[{"branch":"feat/website-editor","head":"488d11fa","base":"a019ddbf",
                          "pullRequest":{"number":1020,"url":"https://github.com/boostibeauty-lab/staffMobileApp/pull/1020"}}]}]}
        """.trimIndent()
        assertEquals(mapOf("feat/website-editor" to "488d11fa"), StackJson.parseLocalStacks(published).single().heads)
        assertTrue(StackJson.parseLocalStacks(file.replace("\"schemaVersion\":1", "\"schemaVersion\":2")).isEmpty())
        assertTrue(StackJson.parseLocalStacks("not json").isEmpty())
    }
}
