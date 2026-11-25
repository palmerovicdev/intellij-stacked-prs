package com.upstack.flow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.upstack.actions.StackFlows
import com.upstack.settings.UpstackSettings
import com.upstack.stack.PrTemplate
import com.upstack.stack.StackState
import com.upstack.ui.PublishDialog
import com.upstack.ui.PublishLayerDialog
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
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

    /** main ← feat/a (#11) ← feat/b ← feat/c, las dos de arriba sin PR, en feat/c. */
    private fun loadStackWithLayerAbove(): StackState.Loaded {
        git("switch", "-c", "feat/a")
        git("switch", "-c", "feat/b")
        Files.writeString(repoDir.resolve("flows.txt"), "flows\n")
        git("add", ".")
        git("commit", "-m", "Flow view", "-m", "Shows the flows.")
        git("switch", "-c", "feat/c")
        gh.respond(
            VIEW,
            stdout = """
                {"trunk":"main","currentBranch":"feat/c","branches":[
                  {"name":"feat/a","isCurrent":false,"isMerged":false,"isQueued":false,"needsRebase":false,
                   "pr":{"number":11,"url":"${Samples.URL_A}","state":"OPEN"}},
                  {"name":"feat/b","isCurrent":false,"isMerged":false,"isQueued":false,"needsRebase":false},
                  {"name":"feat/c","isCurrent":true,"isMerged":false,"isQueued":false,"needsRebase":false}
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

    fun `test ready for review titles it with the branch name and marks it ready`() {
        val state = loadStack()
        preferredRemote = "origin"
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "feat/b", "--body", "Shows the flows.")
        val ready = listOf("pr", "ready", "feat/b", "--repo", "acme/shop")
        listOf(submit, edit, ready).forEach { gh.respond(it) }
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = true) }
        awaitNotificationWith(msg("op.publish.layer.ready", "feat/b"))

        assertEquals(listOf(submit, edit, ready), gh.writes())
        awaitQuiet()
    }

    fun `test a template fills the dialog and what it says is published`() {
        val state = loadStack()
        preferredRemote = "origin"
        UpstackSettings.getInstance().prTemplates = listOf(PrTemplate("Feature", "feat: {branch}", "## What\n{commits}"))
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "feat: feat/b", "--body", "## What\nShows the flows.")
        listOf(submit, edit).forEach { gh.respond(it) }
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishLayerDialog>(PublishLayerDialog::class.java) {
            override fun doIntercept(component: PublishLayerDialog) {
                component.editor.applyTemplate("Feature")
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitNotificationWith(msg("op.publish.layer.draft", "feat/b"))

        assertEquals(listOf(submit, edit), gh.writes())
        awaitQuiet()
    }

    fun `test starting empty needs a title`() {
        val state = loadStack()
        var problem: String? = null
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishLayerDialog>(PublishLayerDialog::class.java) {
            override fun doIntercept(component: PublishLayerDialog) {
                component.editor.applyTemplate(null)
                problem = component.doValidate()?.message
                component.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        })

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitQuiet()

        assertEquals(msg("title.empty"), problem)
        assertEquals(emptyList<List<String>>(), gh.writes())
    }

    fun `test the markdown file of the layer is what is published`() {
        val state = loadStack()
        preferredRemote = "origin"
        val file = repoDir.resolve(".upstack/feat/b.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# From the file\n\nBody from the file.\n")
        UpstackSettings.getInstance().prTextInFile = true
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "From the file", "--body", "Body from the file.")
        listOf(submit, edit).forEach { gh.respond(it) }
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishLayerDialog>(PublishLayerDialog::class.java) {
            override fun doIntercept(component: PublishLayerDialog) {
                assertTrue(component.editor.inFileMode)
                assertNull(component.doValidate())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitNotificationWith(msg("op.publish.layer.draft", "feat/b"))

        assertEquals(listOf(submit, edit), gh.writes())
        awaitQuiet()
    }

    fun `test switching to the file creates it from the fields and a template rewrites it`() {
        val state = loadStack()
        UpstackSettings.getInstance().prTemplates = listOf(PrTemplate("Fix", "fix: {branch}", "Fixes it."))
        val file = repoDir.resolve(".upstack/feat/b.md")
        var created = ""
        var rewritten = ""
        var noTitle: String? = null
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishLayerDialog>(PublishLayerDialog::class.java) {
            override fun doIntercept(component: PublishLayerDialog) {
                component.editor.selectFile(true)
                created = Files.readString(file)
                // Con otro contenido pregunta antes de pisarlo.
                TestDialogManager.setTestDialog(TestDialog.OK)
                component.editor.applyTemplate("Fix")
                rewritten = Files.readString(file)
                Files.writeString(file, "no heading here\n")
                noTitle = component.doValidate()?.message
                component.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        })

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitQuiet()

        assertEquals("# feat/b\n\nShows the flows.\n", created)
        assertEquals("# fix: feat/b\n\nFixes it.\n", rewritten)
        assertEquals(msg("pr.file.no.title", ".upstack/feat/b.md"), noTitle)
        assertEquals(emptyList<List<String>>(), gh.writes())
    }

    fun `test in Publish Stack a new layer takes its text from its markdown file`() {
        val state = loadStack()
        preferredRemote = "origin"
        val file = repoDir.resolve(".upstack/feat/b.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# Stack title\n\nStack body.\n")
        UpstackSettings.getInstance().prTextInFile = true
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "Stack title", "--body", "Stack body.")
        listOf(submit, edit).forEach { gh.respond(it) }
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<PublishDialog>(PublishDialog::class.java) {
            override fun doIntercept(component: PublishDialog) {
                assertEquals(emptyList<Any>(), component.doValidateAll())
                component.close(DialogWrapper.OK_EXIT_CODE)
            }
        })

        onEdt { StackFlows.publishStack(project) }
        awaitNotificationWith(msg("op.publish.done"))

        assertEquals(listOf(submit, edit), gh.writes())
        awaitQuiet()
    }

    fun `test as a draft it is only titled`() {
        val state = loadStack()
        preferredRemote = "origin"
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "feat/b", "--body", "Shows the flows.")
        listOf(submit, edit).forEach { gh.respond(it) }
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers.last(), ready = false) }
        awaitNotificationWith(msg("op.publish.layer.draft", "feat/b"))

        assertEquals(listOf(submit, edit), gh.writes())
        awaitQuiet()
    }

    fun `test a layer below an unpublished one is submitted with the stack tracked only up to it`() {
        val state = loadStackWithLayerAbove()
        preferredRemote = "origin"
        val unstack = listOf("stack", "unstack", "--local")
        val initUpTo = listOf("stack", "init", "--base", "main", "feat/a", "feat/b")
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val edit = listOf("pr", "edit", "feat/b", "--repo", "acme/shop", "--title", "feat/b", "--body", "Shows the flows.")
        val ready = listOf("pr", "ready", "feat/b", "--repo", "acme/shop")
        val initWhole = listOf("stack", "init", "--base", "main", "feat/a", "feat/b", "feat/c")
        listOf(unstack, initUpTo, submit, edit, ready, initWhole).forEach { gh.respond(it) }
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers[1], ready = true) }
        awaitNotificationWith(msg("op.publish.layer.ready", "feat/b"))

        assertEquals(listOf(unstack, initUpTo, submit, edit, ready, unstack, initWhole), gh.writes())
        assertEquals("feat/c", git("branch", "--show-current").trim())
        awaitQuiet()
    }

    fun `test if the submit fails the stack is tracked whole again`() {
        val state = loadStackWithLayerAbove()
        preferredRemote = "origin"
        val unstack = listOf("stack", "unstack", "--local")
        val initUpTo = listOf("stack", "init", "--base", "main", "feat/a", "feat/b")
        val submit = listOf("stack", "submit", "--auto", "--remote", "origin")
        val initWhole = listOf("stack", "init", "--base", "main", "feat/a", "feat/b", "feat/c")
        gh.respond(unstack)
        gh.respond(initUpTo)
        gh.respond(submit, exit = 1, stderr = "boom\n")
        gh.respond(initWhole)
        acceptProposal()

        onEdt { StackFlows.publishLayer(project, state.snapshot.layers[1], ready = false) }
        awaitNotificationWith(msg("operation.failed", msg("op.publish.layer", "feat/b")))
        awaitIdle()

        assertEquals(listOf(unstack, initUpTo, submit, unstack, initWhole), gh.writes())
        awaitQuiet()
    }
}
