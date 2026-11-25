package com.upstack.flow

/** Salidas grabadas para [FakeGh], con la forma de las de gh-stack v0.1.1 y de la API de GitHub. */
object Samples {

    const val URL_A = "https://github.com/acme/shop/pull/11"
    const val URL_B = "https://github.com/acme/shop/pull/12"

    /** main ← feat/a (#11, draft) ← feat/b (#12), en feat/b. */
    val VIEW_TWO_LAYERS = """
        {"trunk":"main","currentBranch":"feat/b","branches":[
          {"name":"feat/a","isCurrent":false,"isMerged":false,"isQueued":false,"needsRebase":false,
           "pr":{"number":11,"url":"$URL_A","state":"OPEN"}},
          {"name":"feat/b","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false,
           "pr":{"number":12,"url":"$URL_B","state":"OPEN"}}
        ]}
    """.trimIndent()

    /** main ← feat/a (#11), en feat/a: una pila de una capa, como otra que tambien sale de main. */
    val VIEW_ONE_LAYER = """
        {"trunk":"main","currentBranch":"feat/a","branches":[
          {"name":"feat/a","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false,
           "pr":{"number":11,"url":"$URL_A","state":"OPEN"}}
        ]}
    """.trimIndent()

    /** La misma pila sin publicar todavia. */
    val VIEW_UNPUBLISHED = """
        {"trunk":"main","currentBranch":"feat/b","branches":[
          {"name":"feat/a","isCurrent":false,"isMerged":false,"isQueued":false,"needsRebase":false},
          {"name":"feat/b","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false}
        ]}
    """.trimIndent()

    val DETAILS_TWO_LAYERS = """
        {"data":{"repository":{
          "pr11":{"number":11,"title":"Domain","url":"$URL_A","state":"OPEN","isDraft":true,
                  "reviewDecision":null,"baseRefName":"main","additions":10,"deletions":2,"changedFiles":1,
                  "mergeable":"MERGEABLE","mergeStateStatus":"CLEAN",
                  "labels":{"nodes":[]},"commits":{"nodes":[]}},
          "pr12":{"number":12,"title":"API","url":"$URL_B","state":"OPEN","isDraft":false,
                  "reviewDecision":"REVIEW_REQUIRED","baseRefName":"feat/a","additions":5,"deletions":0,"changedFiles":2,
                  "mergeable":"MERGEABLE","mergeStateStatus":"CLEAN",
                  "labels":{"nodes":[{"name":"stack-final","color":"8250DF"}]},
                  "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS"}}}]}}
        }}}
    """.trimIndent()

    /** [DETAILS_TWO_LAYERS] con #11 listo para review: las dos capas se pueden fusionar. */
    val DETAILS_READY = DETAILS_TWO_LAYERS.replace(""""isDraft":true""", """"isDraft":false""")

    /** [DETAILS_TWO_LAYERS] con los checks de #12 fallando. */
    val DETAILS_FAILING_CHECKS = DETAILS_TWO_LAYERS.replace(""""statusCheckRollup":{"state":"SUCCESS"}""", """"statusCheckRollup":{"state":"FAILURE"}""")

    /**
     * `gh pr checks 12 --json name,state,bucket,link,workflow,description`: un job de GitHub Actions
     * que falla (ejecucion 7001), un check de Vercel que falla, uno en marcha y uno que pasa.
     */
    val CHECKS_FAILING = """
        [
          {"bucket":"pass","description":"","link":"https://github.com/acme/shop/actions/runs/7001/job/9003","name":"lint","state":"SUCCESS","workflow":"build"},
          {"bucket":"pending","description":"","link":"https://github.com/acme/shop/actions/runs/7002/job/9002","name":"deploy-preview","state":"IN_PROGRESS","workflow":"preview"},
          {"bucket":"fail","description":"Deployment has failed","link":"https://vercel.com/acme/shop/AbC123","name":"Vercel","state":"FAILURE","workflow":""},
          {"bucket":"fail","description":"","link":"https://github.com/acme/shop/actions/runs/7001/job/9001","name":"test","state":"FAILURE","workflow":"build"}
        ]
    """.trimIndent()

    /** `gh run view 7001 --log-failed`: el job `test` y otro job de la misma ejecucion. */
    val FAILED_LOG = listOf(
        "test\tRun tests\t2026-11-24T10:01:01.1234567Z FAIL src/cart.test.ts",
        "test\tRun tests\t2026-11-24T10:01:01.2234567Z   expected 3 items, got 2",
        "test\tRun tests\t2026-11-24T10:01:01.3234567Z ##[error]Process completed with exit code 1.",
        "e2e\tRun e2e\t2026-11-24T10:01:00.2234567Z timeout waiting for the server",
    ).joinToString("\n", postfix = "\n")
}
