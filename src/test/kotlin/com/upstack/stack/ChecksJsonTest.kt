package com.upstack.stack

import com.upstack.gh.GhCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksJsonTest {

    // The shape of `gh pr checks --json name,state,bucket,link,workflow,description` (gh 2.103).
    private val checks = """
        [
          {"bucket":"fail","description":"","link":"https://github.com/acme/shop/actions/runs/12345678901/job/33445566778","name":"test","state":"FAILURE","workflow":"build"},
          {"bucket":"fail","description":"Deployment has failed","link":"https://vercel.com/acme/shop/AbC123","name":"Vercel","state":"FAILURE","workflow":""},
          {"bucket":"pending","description":"","link":"https://github.com/acme/shop/actions/runs/7002/job/9002","name":"deploy-preview","state":"IN_PROGRESS","workflow":"preview"},
          {"bucket":"pass","description":"","link":"https://github.com/acme/shop/actions/runs/7001/job/9003","name":"lint","state":"SUCCESS","workflow":"build"}
        ]
    """.trimIndent()

    private fun parse(): List<PrCheck> = requireNotNull(StackJson.parsePrChecks(checks))

    @Test
    fun `a failed GitHub Actions check carries its run and job`() {
        val test = parse()[0]
        assertEquals("test", test.name)
        assertEquals("build", test.workflow)
        assertEquals("build / test", test.title)
        assertEquals(CheckStatus.FAIL, test.status)
        assertTrue(test.needsAttention)
        assertEquals(ActionsRun(GitHubRepo("github.com", "acme", "shop"), 12345678901L, 33445566778L), test.run)
        assertEquals(PrCheck.ACTIONS, test.app)
        assertEquals("https://github.com/acme/shop/actions/runs/12345678901/job/33445566778", test.link)
    }

    @Test
    fun `a failed check of another app has no run and names the app`() {
        val vercel = parse()[1]
        assertEquals("Vercel", vercel.title)
        assertEquals(CheckStatus.FAIL, vercel.status)
        assertNull(vercel.run)
        assertEquals("Vercel", vercel.app)
        assertEquals("Deployment has failed", vercel.description)
        assertEquals("https://vercel.com/acme/shop/AbC123", vercel.link)
    }

    @Test
    fun `running and passed checks`() {
        val (_, _, preview, lint) = parse()
        assertEquals(CheckStatus.PENDING, preview.status)
        assertFalse(preview.needsAttention)
        assertEquals("preview / deploy-preview", preview.title)
        assertEquals(7002L, preview.run?.id)
        assertEquals(CheckStatus.PASS, lint.status)
        assertFalse(lint.needsAttention)
    }

    @Test
    fun `without bucket the state decides, as an older gh prints it`() {
        val text = """[
            {"name":"a","state":"TIMED_OUT","link":"","workflow":""},
            {"name":"b","state":"CANCELLED","link":"","workflow":""},
            {"name":"c","state":"NEUTRAL","link":"","workflow":""},
            {"name":"d","state":"QUEUED","link":"","workflow":""},
            {"name":"e","state":"SUCCESS","link":"","workflow":""}
        ]"""
        assertEquals(
            listOf(CheckStatus.FAIL, CheckStatus.CANCELLED, CheckStatus.SKIPPED, CheckStatus.PENDING, CheckStatus.PASS),
            StackJson.parsePrChecks(text)?.map { it.status },
        )
        assertTrue(StackJson.parsePrChecks(text)!![1].needsAttention)
    }

    @Test
    fun `an Actions run on GitHub Enterprise keeps its host`() {
        val run = ActionsRun.fromLink("https://ghe.acme.dev/acme/shop/actions/runs/5/job/6")
        assertEquals(ActionsRun(GitHubRepo("ghe.acme.dev", "acme", "shop"), 5, 6), run)
        assertEquals("ghe.acme.dev/acme/shop", run?.repo?.cliName)
        assertEquals(ActionsRun(GitHubRepo("github.com", "acme", "shop"), 5, null), ActionsRun.fromLink("https://github.com/acme/shop/actions/runs/5"))
    }

    @Test
    fun `the app of a check that only links to GitHub is unknown`() {
        assertNull(CheckApps.of("https://github.com/acme/shop/runs/123"))
        assertNull(CheckApps.of("https://ghe.acme.dev/acme/shop/runs/123"))
        assertNull(CheckApps.of(""))
        assertEquals("CircleCI", CheckApps.of("https://app.circleci.com/pipelines/github/acme/shop/1"))
        assertEquals("ci.acme.dev", CheckApps.of("https://ci.acme.dev/job/shop/12"))
    }

    @Test
    fun `not a JSON list is null, no checks is an empty list`() {
        assertNull(StackJson.parsePrChecks("no checks reported on the 'feat/b' branch"))
        assertNull(StackJson.parsePrChecks("{\"message\":\"Not Found\"}"))
        assertEquals(emptyList<PrCheck>(), StackJson.parsePrChecks("[]"))
    }

    @Test
    fun `the commands of the checks window`() {
        val run = requireNotNull(ActionsRun.fromLink("https://github.com/acme/shop/actions/runs/7001/job/9001"))
        assertEquals(
            "gh pr checks 12 --repo acme/shop --json name,state,bucket,link,workflow,description",
            GhCommands.display(GhCommands.prChecks("12", GitHubRepo("github.com", "acme", "shop"))),
        )
        assertEquals("gh run view 7001 --repo acme/shop --log-failed", GhCommands.display(GhCommands.failedLog(run)))
        assertEquals("gh run rerun 7001 --failed --repo acme/shop", GhCommands.display(GhCommands.rerunFailed(run)))
    }

    // `gh run view --log-failed`: JOB, STEP and a timestamped line, with the colours of the runner.
    private val log = listOf(
        "test\tSet up job\t﻿2026-11-24T10:00:00.0000000Z Current runner version: '2.320.0'",
        "test\tRun tests\t2026-11-24T10:01:00.1234567Z ##[group]Run npm test",
        "test\tRun tests\t2026-11-24T10:01:00.1334567Z npm test",
        "test\tRun tests\t2026-11-24T10:01:00.1434567Z ##[endgroup]",
        "test\tRun tests\t2026-11-24T10:01:01.1234567Z \u001B[31mFAIL\u001B[0m src/cart.test.ts",
        "test\tRun tests\t2026-11-24T10:01:01.2234567Z ##[error]Process completed with exit code 1.",
        "lint\tRun lint\t2026-11-24T10:01:00.2234567Z error  x is unused",
    ).joinToString("\n", postfix = "\n")

    @Test
    fun `the failed log of one job, without timestamps or colours, a header per step`() {
        val shown = FailedLogs.tail(log, job = "test")
        assertEquals(
            listOf(
                "▶ test › Set up job",
                "Current runner version: '2.320.0'",
                "▶ test › Run tests",
                "Run npm test",
                "npm test",
                "FAIL src/cart.test.ts",
                "##[error]Process completed with exit code 1.",
            ).joinToString("\n"),
            shown.text,
        )
        assertEquals(0, shown.omitted)
    }

    @Test
    fun `the tail keeps the last lines and the header of their step`() {
        val shown = FailedLogs.tail(log, job = "test", max = 2)
        assertEquals("▶ test › Run tests\nFAIL src/cart.test.ts\n##[error]Process completed with exit code 1.", shown.text)
        assertEquals(3, shown.omitted)
    }

    @Test
    fun `a job gh did not print shows every job`() {
        val shown = FailedLogs.tail(log, job = "build")
        assertTrue(shown.text, shown.text.contains("▶ lint › Run lint\nerror  x is unused"))
        assertTrue(shown.text, shown.text.startsWith("▶ test › Set up job"))
    }
}
