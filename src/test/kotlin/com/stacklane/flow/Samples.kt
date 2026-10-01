package com.stacklane.flow

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
}
