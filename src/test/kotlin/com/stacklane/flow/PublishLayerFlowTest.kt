package com.stacklane.flow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.stacklane.actions.StackFlows
import com.stacklane.stack.StackState
import com.stacklane.ui.PublishLayerDialog
import java.nio.file.Files

/** *Publish as Draft…* / *Ready for Review…* desde el menu de una capa sin PR. */
class PublishLayerFlowTest : FlowTestCase() {

    /** main ← feat/a (#11) ← feat/b sin PR, con un commit propio. */
    private fun loadStack(): StackState.Loaded {
        git("switch", "-c", "feat/a")
        git("switch", "-c", "feat/b")
        Files.writeString(repoDir.resolve("flows.txt"), "flows\n")
        git("add", ".")
        git("commit", "-m", "Flow view", "-m", "Shows the flows.")
        gh.respond(
            VIEW,
            stdout = """
                {"trunk":"main","currentBranch":"feat/b","branches":[
                  {"name":"feat/a","isCurrent":false,"isMerged":false,"isQueued":false,"needsRebase":false,
                   "pr":{"number":11,"url":"${Samples.URL_A}","state":"OPEN"}},
                  {"name":"feat/b","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false}
                ]}
            """.trimIndent(),
        )
        gh.respond(listOf("api", "graphql"), prefix = true, stdout = Samples.DETAILS_TWO_LAYERS)
        return awaitState { !it.detailsLoading }
    }

    private fun acceptProposal() {
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishLayerDialog>(PublishLayerDialog::class.java) {
            override fun doIntercept(component: PublishLayerDialog) {
                assertNull(component.doValidate())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })
    }

    fun `test ready for review titles it from its commit and marks it ready`() {
        val state = loadStack()
        preferredRemote = "origin"
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "Flow view", "--body", "Shows the flows.")
        val ready = listOf("pr", "ready", "feat/b", "--repo", "acme/shop")
        listOf(submit, edit, ready).forEach { gh.respond(it) }
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = true) }
        awaitNotificationWith(msg("op.publish.layer.ready", "feat/b"))

        assertEquals(listOf(submit, edit, ready), gh.writes())
        awaitQuiet()
    }

    fun `test as a draft it is only titled`() {
        val state = loadStack()
        preferredRemote = "origin"
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "Flow view", "--body", "Shows the flows.")
        listOf(submit, edit).forEach { gh.respond(it) }
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitNotificationWith(msg("op.publish.layer.draft", "feat/b"))

        assertEquals(listOf(submit, edit), gh.writes())
        awaitQuiet()
    }
}
