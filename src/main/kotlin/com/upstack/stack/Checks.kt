package com.upstack.stack

import java.net.URI

/** How a check ended (or is going), in the groups of `bucket` from `gh pr checks --json`. */
enum class CheckStatus {
    FAIL, CANCELLED, PENDING, PASS, SKIPPED;

    companion object {
        /** [bucket] when gh gives it; otherwise GitHub's raw [state] (an older gh). */
        fun of(bucket: String?, state: String?): CheckStatus = when (bucket) {
            "pass" -> PASS
            "fail" -> FAIL
            "pending" -> PENDING
            "skipping" -> SKIPPED
            "cancel" -> CANCELLED
            else -> when (state) {
                "SUCCESS" -> PASS
                "FAILURE", "ERROR", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE" -> FAIL
                "CANCELLED" -> CANCELLED
                "SKIPPED", "NEUTRAL" -> SKIPPED
                else -> PENDING
            }
        }
    }
}

/**
 * The GitHub Actions run behind a check: what `gh run view` and `gh run rerun` need. Taken
 * from the check's link, `https://HOST/OWNER/REPO/actions/runs/RUN/job/JOB`, so the run is
 * always looked up in the repository that ran it.
 */
data class ActionsRun(val repo: GitHubRepo, val id: Long, val job: Long?) {

    companion object {
        private val LINK = Regex("""^https?://([^/]+)/([^/]+)/([^/]+)/actions/runs/(\d+)(?:/job/(\d+))?(?:[/?#].*)?$""")

        fun fromLink(link: String): ActionsRun? {
            val (host, owner, name, run, job) = LINK.matchEntire(link.trim())?.destructured ?: return null
            val id = run.toLongOrNull() ?: return null
            return ActionsRun(GitHubRepo(host, owner, name), id, job.toLongOrNull())
        }
    }
}

/** One check of a pull request, as `gh pr checks --json` gives it. */
data class PrCheck(
    val name: String,
    val status: CheckStatus,
    /** The GitHub Actions workflow; empty for checks of other apps. */
    val workflow: String,
    /** The check's page, on GitHub or on the app that reported it. */
    val link: String,
    /** What the app published as the check's title or summary; often empty for Actions. */
    val description: String,
    /** null: it is not a GitHub Actions check, so it cannot be re-run from here. */
    val run: ActionsRun?,
) {
    /** `workflow / job` for Actions, as GitHub lists them; the bare name for other apps. */
    val title: String get() = if (workflow.isEmpty()) name else "$workflow / $name"

    /** Failed or cancelled: what *Why it failed* and *Re-run* are for. */
    val needsAttention: Boolean get() = status == CheckStatus.FAIL || status == CheckStatus.CANCELLED

    /** Who reported it: GitHub Actions, the app its link points to, or null if the link does not say. */
    val app: String? get() = if (run != null) ACTIONS else CheckApps.of(link)

    companion object {
        const val ACTIONS = "GitHub Actions"
    }
}

/** The app behind a check that is not from GitHub Actions, guessed from its link. */
internal object CheckApps {

    private val KNOWN = listOf(
        "vercel.com" to "Vercel",
        "circleci.com" to "CircleCI",
        "travis-ci.com" to "Travis CI",
        "travis-ci.org" to "Travis CI",
        "netlify.com" to "Netlify",
        "netlify.app" to "Netlify",
        "codecov.io" to "Codecov",
        "sonarcloud.io" to "SonarCloud",
        "buildkite.com" to "Buildkite",
        "dev.azure.com" to "Azure Pipelines",
        "gitlab.com" to "GitLab",
        "bitrise.io" to "Bitrise",
        "codeclimate.com" to "Code Climate",
        "snyk.io" to "Snyk",
    )

    // The page GitHub gives a check run whose app has no page of its own: it does not name the app.
    private val GITHUB_RUN_PAGE = Regex("""^/[^/]+/[^/]+/runs/\d+.*""")

    fun of(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        KNOWN.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.let { return it.second }
        if (host == "github.com" || GITHUB_RUN_PAGE.matches(uri.path.orEmpty())) return null
        return host
    }
}

/** What the checks window shows after reading `gh pr checks`: the checks, or why they could not be read. */
sealed interface ChecksRead {
    data class Loaded(val checks: List<PrCheck>) : ChecksRead
    data class Failed(val error: String) : ChecksRead
}

/** How a *Re-run* asked from the checks window ended. */
sealed interface RerunOutcome {
    /** GitHub accepted it: the failed jobs are queued again. */
    data object Requested : RerunOutcome

    /** gh refused it (no write access, a run too old…): [error] is what gh said. */
    data class Refused(val error: String) : RerunOutcome

    /** It did not run: another operation was running, or there is no gh. A notification says which. */
    data object NotRun : RerunOutcome
}

/** What the window shows of `gh run view --log-failed`: the last lines, readable. */
data class FailedLog(val text: String, val omitted: Int)

/**
 * Makes `gh run view --log-failed` readable. gh prints every line as `JOB<TAB>STEP<TAB>TIMESTAMP
 * TEXT`; here each step gets one header line (`▶ job › step`) and its lines keep only the text,
 * without the timestamp or terminal colours. A run can have several failed jobs: only the lines of
 * [job] (the check's name, which is the job's name) are kept, if gh printed any.
 */
object FailedLogs {

    const val MAX_LINES = 300

    private val TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z ?""")
    private val ANSI = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
    private const val GROUP = "##[group]"
    private const val END_GROUP = "##[endgroup]"

    private class Line(val job: String, val step: String, val text: String)

    /** The last [max] lines of [output] (of [job] when gh printed any), each after its step's header. */
    fun tail(output: String, job: String? = null, max: Int = MAX_LINES): FailedLog {
        val all = parse(output)
        val lines = all.filter { it.job == job }.ifEmpty { all }
        val kept = lines.takeLast(max)
        val text = buildList {
            var section: Pair<String, String>? = null
            for (line in kept) {
                val current = line.job to line.step
                if (current != section && (line.job.isNotEmpty() || line.step.isNotEmpty())) add("▶ ${line.job} › ${line.step}")
                section = current
                add(line.text)
            }
        }.joinToString("\n")
        return FailedLog(text, lines.size - kept.size)
    }

    private fun parse(output: String): List<Line> {
        var job = ""
        var step = ""
        return output.lineSequence().mapNotNull { raw ->
            val parts = raw.split('\t', limit = 3)
            // A line without the columns continues the previous one (a message gh printed, or a cut line).
            val text = if (parts.size == 3) {
                job = parts[0]
                step = parts[1]
                parts[2]
            } else {
                raw
            }
            val clean = text.removePrefix("﻿").replace(TIMESTAMP, "").replace(ANSI, "").removePrefix("﻿").trimEnd()
            when {
                clean.startsWith(END_GROUP) -> null
                parts.size != 3 && clean.isEmpty() -> null
                else -> Line(job, step, clean.removePrefix(GROUP))
            }
        }.toList()
    }
}
