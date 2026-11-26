package com.upstack.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.actions.PrTarget
import com.upstack.actions.StackFlows
import com.upstack.gh.GhCli
import com.upstack.gh.GhNotFoundException
import com.upstack.gh.GhResult
import com.upstack.stack.ReviewDiffRead
import com.upstack.stack.ReviewDiffReader
import com.upstack.stack.ReviewFile
import com.upstack.stack.ReviewFileRead
import com.upstack.stack.ReviewSnapshot
import com.upstack.stack.ReviewThread
import com.upstack.stack.StackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Read-only PR diff, with all conversations retained even if they cannot be placed inline. */
internal class ReviewChangesDialog(private val project: Project, private val target: PrTarget) : DialogWrapper(project) {
    private val service = StackService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val runGh: suspend (List<String>) -> GhResult = { args ->
        withContext(Dispatchers.IO) { GhCli.run(StackFlows.workDirFor(service, target), args) }
    }
    private val model = CollectionListModel<ReviewFile>()
    private val files = JBList(model)
    private val summary = JBLabel()
    private val commentArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private var snapshot: ReviewSnapshot? = null
    private var loadJob: Job? = null
    private var fileJob: Job? = null
    private var generation = 0

    internal var loadedFile: String? = null
        private set
    internal val commentsText: String get() = commentArea.text
    internal val statusText: String get() = summary.text
    internal val fileLoadCompleted: Boolean get() = fileJob?.isCompleted ?: true
    internal val refreshAction: Action = object : DialogWrapperAction(message("checks.refresh")) {
        override fun doAction(event: ActionEvent) = reload()
    }

    init {
        title = message("review.changes.title", target.number)
        isModal = false
        isResizable = true
        setCancelButtonText(message("checks.close"))
        files.selectionMode = ListSelectionModel.SINGLE_SELECTION
        files.cellRenderer = textListCellRenderer("") { it.newPath ?: it.oldPath.orEmpty() }
        files.addListSelectionListener { if (!it.valueIsAdjusting) files.selectedValue?.let(::openFile) }
        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val filePanel = JPanel(BorderLayout()).apply {
            add(JBLabel(message("review.changes.files")).apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(files, true), BorderLayout.CENTER)
            minimumSize = JBUI.size(160, 100)
        }
        val comments = JPanel(BorderLayout()).apply {
            add(JBLabel(message("review.changes.comments")).apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(commentArea, true), BorderLayout.CENTER)
            minimumSize = JBUI.size(160, 90)
        }
        val right = JBSplitter(true, 0.74f).apply {
            firstComponent = diff.component
            secondComponent = comments
        }
        return JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(6))).apply {
            preferredSize = Dimension(JBUI.scale(1050), JBUI.scale(650))
            add(JBSplitter(false, 0.23f).apply { firstComponent = filePanel; secondComponent = right }, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.empty(4, 6); putClientProperty("html.disable", true) }, BorderLayout.SOUTH)
        }
    }

    override fun getDimensionServiceKey(): String = "Upstack.ReviewChanges"
    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun createLeftSideActions(): Array<Action> = arrayOf(refreshAction)

    private fun reload() {
        generation++
        loadJob?.cancel()
        fileJob?.cancel()
        snapshot = null
        loadedFile = null
        model.removeAll()
        commentArea.text = ""
        summary.text = message("review.changes.loading")
        diff.setRequest(MessageDiffRequest(message("review.changes.loading")))
        loadJob = scope.launch {
            val result = try { ReviewDiffReader.read(target.github, target.number, target.url, runGh) }
            catch (_: GhNotFoundException) { showError(message("prs.no.gh")); return@launch }
            when (result) {
                is ReviewDiffRead.Failure -> showError(result.message)
                is ReviewDiffRead.Success -> {
                    snapshot = result.snapshot
                    commentArea.text = formatThreads(result.snapshot.threads)
                    commentArea.caretPosition = 0
                    model.replaceAll(result.snapshot.files)
                    if (model.size > 0) files.selectedIndex = 0
                    else {
                        summary.text = message("review.changes.no.files")
                        diff.setRequest(MessageDiffRequest(message("review.changes.no.files")))
                    }
                }
            }
        }
    }

    private fun openFile(file: ReviewFile) {
        val current = snapshot ?: return
        fileJob?.cancel()
        val version = ++generation
        loadedFile = null
        val path = file.newPath ?: file.oldPath.orEmpty()
        summary.text = message("review.changes.loading.file", path)
        diff.setRequest(MessageDiffRequest(message("review.changes.loading.file", path)))
        fileJob = scope.launch {
            val contents = try {
                coroutineScope {
                    val left = async { ReviewDiffReader.readContent(target.github, file.oldPath, current.mergeBase, runGh) }
                    val right = async { ReviewDiffReader.readContent(target.github, file.newPath, current.revisions.head, runGh) }
                    left.await() to right.await()
                }
            } catch (_: GhNotFoundException) { showError(message("prs.no.gh")); return@launch }
            val failure = listOf(contents.first, contents.second).filterIsInstance<ReviewFileRead.Failure>().firstOrNull()
            if (failure != null) { showError(failure.message); return@launch }
            val left = (contents.first as ReviewFileRead.Text).content
            val right = (contents.second as ReviewFileRead.Text).content
            val type = FileTypeManager.getInstance().getFileTypeByFileName(path)
            val content = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(path, content.create(project, left, type), content.create(project, right, type),
                message("review.changes.base"), message("review.changes.head"))
            request.putUserData(ReviewDiffExtension.REVIEW, ReviewDecoration(file, current.threads) { unplaced ->
                if (version == generation && !isDisposed) {
                    summary.text = when {
                        unplaced == null -> message("review.changes.unsupported")
                        unplaced > 0 -> message("review.changes.unplaced", unplaced)
                        else -> message("review.changes.loaded", path)
                    }
                }
            })
            loadedFile = path
            diff.setRequest(request)
        }
    }

    private fun showError(error: String) {
        val text = message("review.changes.error", error.ifBlank { message("review.changes.error.unknown") })
        summary.text = text
        diff.setRequest(MessageDiffRequest(text))
    }

    private fun formatThreads(threads: List<ReviewThread>): String {
        if (threads.isEmpty()) return message("review.changes.no.comments")
        return threads.joinToString("\n\n") { thread ->
            val location = thread.line?.toString() ?: thread.originalLine?.toString() ?: "—"
            val state = message(if (thread.resolved) "review.thread.resolved" else "review.thread.unresolved")
            val outdated = if (thread.outdated) " · ${message("review.changes.outdated")}" else ""
            val header = "${thread.path}:$location · ${thread.side} · $state$outdated"
            val comments = thread.comments.joinToString("\n\n") { comment ->
                "${comment.author ?: message("review.thread.deleted.author")}:\n${comment.body}"
            }
            "$header\n$comments"
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    companion object {
        private val OPEN = Key.create<MutableMap<String, ReviewChangesDialog>>("upstack.review.changes.open")

        fun show(project: Project, target: PrTarget) {
            val open = project.getUserData(OPEN) ?: HashMap<String, ReviewChangesDialog>().also { project.putUserData(OPEN, it) }
            open[target.url]?.takeUnless { it.isDisposed }?.let { it.toFront(); return }
            val dialog = ReviewChangesDialog(project, target)
            open[target.url] = dialog
            Disposer.register(dialog.disposable) { open.remove(target.url, dialog) }
            dialog.show()
        }
    }
}
