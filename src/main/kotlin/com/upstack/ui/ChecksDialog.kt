package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.upstack.UpstackBundle.message
import com.upstack.actions.PrTarget
import com.upstack.actions.StackFlows
import com.upstack.gh.GhCommands
import com.upstack.gh.GhNotFoundException
import com.upstack.stack.CheckStatus
import com.upstack.stack.ChecksRead
import com.upstack.stack.FailedLog
import com.upstack.stack.FailedLogs
import com.upstack.stack.PrCheck
import com.upstack.stack.RerunOutcome
import com.upstack.stack.StackService
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * *Checks…*: every check of a pull request, read with `gh pr checks` when the window opens (not on
 * every refresh of the stack). On each failed one: *Why it failed* (the failed steps of a GitHub
 * Actions job, or what another app published about it), *Re-run* (GitHub Actions only,
 * `gh run rerun --failed`; other apps only re-run their own checks) and *Open on GitHub*. Every
 * link says on hover what it does and the command it runs, and the line at the bottom shows the
 * last command, to repeat it in the terminal.
 *
 * Narrow on purpose: names and notes wrap, and a log scrolls inside its own box, so a long line
 * never makes the window wider.
 */
internal class ChecksDialog(private val project: Project, private val target: PrTarget) : DialogWrapper(project) {

    private val service = StackService.getInstance(project)
    private val readCommand = StackFlows.checksCommand(target)

    private val header = WrappingText(listOfNotNull("#${target.number}", target.details?.title?.takeIf { it.isNotBlank() }).joinToString(" · "))
    private val statusIcon = JLabel().apply { verticalAlignment = SwingConstants.TOP }
    private val statusText = WrappingText(textFont = JBFont.small(), textColor = NamedColorUtil.getInactiveTextColor())
    private val column = Column()
    private val preview = CommandPreview()

    /** The checks last read, failed first; null until the first read succeeds. */
    private var checks: List<PrCheck>? = null
    private var reading = false
    private var reads = 0

    /** What each check shows under it after *Why it failed*, by [keyOf]. */
    private val reasons = HashMap<String, Reason>()

    /** The re-runs asked from this window, by run: every failed job of a run goes again at once. */
    private val reruns = HashMap<Long, Rerun>()

    /** The rows as last built, with their check's title, in order. */
    private var rows: List<Pair<String, JComponent>> = emptyList()
    private val jobs = mutableListOf<Job>()

    // For the tests, which see no window without a screen: what it shows and its links.
    internal val isLoaded: Boolean get() = checks != null && !reading
    internal val shownStatus: String get() = statusText.text

    internal fun shownChecks(): List<String> = rows.map { it.first }

    /** Every text of the row of [title]: name, state, notes and the log, not the links. */
    internal fun textsOf(title: String): List<String> = rowOf(title)?.let(::texts).orEmpty()

    internal fun linksOf(title: String): List<ActionLink> = rowOf(title)?.let(::links).orEmpty()

    private val refreshAction = object : DialogWrapperAction(message("checks.refresh")) {
        init {
            putValue(Action.SHORT_DESCRIPTION, Help(message("help.checks.refresh", target.number), listOf(Help.gh(readCommand))).html)
        }

        override fun doAction(e: ActionEvent) {
            service.requestRefresh()
            reload()
        }
    }

    init {
        title = message("checks.title", target.number)
        // Not modal: Dispatchers.EDT without a modality waits for modal dialogs to close, and a
        // re-run goes through StackService.execute, which saves the documents on the EDT first.
        isModal = false
        setCancelButtonText(message("checks.close"))
        init()
        reload()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(refreshAction)

    override fun getDimensionServiceKey(): String = "Upstack.Checks"

    override fun createCenterPanel(): JComponent {
        val status = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(statusIcon, BorderLayout.WEST)
            add(statusText, BorderLayout.CENTER)
        }
        val top = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(header)
            add(status)
        }
        val scroll = JBScrollPane(column).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = JBUI.size(WIDTH, HEIGHT)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(preview, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        jobs.forEach { it.cancel() }
        super.dispose()
    }

    // ---------------------------------------------------------------- reading

    private fun reload() {
        val read = ++reads
        reading = true
        refreshAction.isEnabled = false
        showStatus(AnimatedIcon.Default(), message("checks.loading", target.number))
        preview.show(listOf(readCommand))
        jobs += service.launch {
            val result = StackFlows.readChecks(project, target)
            onEdt { if (read == reads) loaded(result) }
        }
    }

    private fun loaded(result: ChecksRead) {
        reading = false
        refreshAction.isEnabled = true
        when (result) {
            is ChecksRead.Failed -> showStatus(AllIcons.General.Error, message("checks.read.failed", result.error))
            is ChecksRead.Loaded -> {
                val sorted = result.checks.sortedWith(compareBy<PrCheck> { it.status.ordinal }.thenBy { it.title.lowercase() })
                checks = sorted
                if (sorted.isEmpty()) showStatus(null, message("checks.none", target.number))
                else showStatus(null, summary(sorted))
            }
        }
        rebuild()
    }

    private fun summary(checks: List<PrCheck>): String = CheckStatus.entries.mapNotNull { status ->
        val count = checks.count { it.status == status }
        if (count == 0) null else message("checks.summary.${key(status)}", count)
    }.joinToString(" · ")

    private fun showStatus(icon: Icon?, text: String) {
        statusIcon.icon = icon
        statusText.text = text
    }

    // ---------------------------------------------------------------- what each link does

    private fun why(check: PrCheck) {
        val key = keyOf(check)
        when (reasons[key]) {
            null -> Unit
            Reason.Reading -> return
            // Shown: the same link hides it.
            else -> {
                reasons.remove(key)
                return rebuild()
            }
        }
        val run = check.run
        if (run == null) {
            reasons[key] = Reason.App
            return rebuild()
        }
        reasons[key] = Reason.Reading
        preview.show(listOf(GhCommands.failedLog(run)))
        rebuild()
        jobs += service.launch {
            val reason = try {
                val result = StackFlows.readFailedLog(project, target, run)
                if (result.ok) Reason.Log(FailedLogs.tail(result.stdout, job = check.name))
                else Reason.Error(result.errorText.ifEmpty { "exit code ${result.exitCode}" })
            } catch (_: GhNotFoundException) {
                Reason.Error(message("state.gh.missing"))
            }
            onEdt {
                reasons[key] = reason
                rebuild()
            }
        }
    }

    private fun rerun(check: PrCheck) {
        val run = check.run ?: return
        if (reruns[run.id] == Rerun.Asking) return
        reruns[run.id] = Rerun.Asking
        preview.show(listOf(GhCommands.rerunFailed(run)))
        rebuild()
        StackFlows.rerunFailed(project, target, check) { outcome ->
            onEdt {
                when (outcome) {
                    RerunOutcome.Requested -> {
                        reruns[run.id] = Rerun.Requested
                        reload()
                    }
                    is RerunOutcome.Refused -> reruns[run.id] = Rerun.Refused(outcome.error)
                    RerunOutcome.NotRun -> reruns.remove(run.id)
                }
                rebuild()
            }
        }
    }

    // ---------------------------------------------------------------- rows

    private fun rebuild() {
        val built = checks.orEmpty().map { it.title to row(it) }
        column.removeAll()
        built.forEach { column.add(it.second) }
        rows = built
        column.revalidate()
        column.repaint()
    }

    /** The state as a coloured mark, the name, who reported it and, if it failed, what can be done. */
    private fun row(check: PrCheck): JComponent {
        val lines = JPanel(VerticalLayout(JBUI.scale(3))).apply { isOpaque = false }
        lines.add(WrappingText(check.title))
        val source = listOfNotNull(message("checks.status.${key(check.status)}"), check.app).joinToString(" · ")
        lines.add(small(source))
        if (check.needsAttention) {
            lines.add(linkRow(failedLinks(check)))
            if (check.run == null) lines.add(small(message("checks.rerun.other", appOf(check))))
            check.run?.let { reruns[it.id] }?.let { lines.add(rerunNote(it)) }
            reasons[keyOf(check)]?.let { lines.add(reasonView(check, it)) }
        } else if (check.link.isNotBlank()) {
            lines.add(linkRow(listOf(openLink(check))))
        }
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(mark(check.status), BorderLayout.WEST)
            add(lines, BorderLayout.CENTER)
        }
    }

    private fun failedLinks(check: PrCheck): List<ActionLink> = buildList {
        val run = check.run
        val reasonShown = reasons[keyOf(check)].let { it != null && it !is Reason.Reading }
        add(
            ActionLink(message(if (reasonShown) "checks.why.hide" else "checks.why")) { why(check) }.apply {
                showHelp(
                    if (run != null) Help(message("help.checks.why", check.name), listOf(Help.gh(GhCommands.failedLog(run))))
                    else Help(message("help.checks.why.app", appOf(check)))
                )
            }
        )
        if (run != null) {
            add(
                ActionLink(message("checks.rerun")) { rerun(check) }.apply {
                    isEnabled = reruns[run.id] != Rerun.Asking
                    // The run id as text: MessageFormat would group its digits (7,001).
                    showHelp(Help(message("help.checks.rerun", check.workflow.ifEmpty { check.name }, run.id.toString()), listOf(Help.gh(GhCommands.rerunFailed(run)))))
                }
            )
        }
        if (check.link.isNotBlank()) add(openLink(check))
    }

    private fun openLink(check: PrCheck): ActionLink = ActionLink(message("checks.open")) { BrowserUtil.browse(check.link) }.apply {
        showHelp(Help(message("help.checks.open", check.link)))
    }

    private fun linkRow(links: List<ActionLink>): JComponent = JPanel(HorizontalLayout(JBUI.scale(LINK_GAP))).apply {
        isOpaque = false
        links.forEach(::add)
    }

    private fun rerunNote(rerun: Rerun): JComponent = when (rerun) {
        Rerun.Asking -> JBLabel(message("checks.rerun.asking"), AnimatedIcon.Default(), SwingConstants.LEADING).apply { font = JBFont.small() }
        Rerun.Requested -> WrappingText(message("checks.rerun.requested"), AllIcons.General.Information, JBFont.small())
        is Rerun.Refused -> WrappingText(message("checks.rerun.failed", rerun.error), AllIcons.General.Error, JBFont.small())
    }

    private fun reasonView(check: PrCheck, reason: Reason): JComponent = when (reason) {
        Reason.Reading -> JBLabel(message("checks.why.reading"), AnimatedIcon.Default(), SwingConstants.LEADING).apply { font = JBFont.small() }
        is Reason.Error -> WrappingText(reason.text, AllIcons.General.Error, JBFont.small())
        is Reason.Log -> logView(reason.log)
        Reason.App -> JPanel(VerticalLayout(JBUI.scale(3))).apply {
            isOpaque = false
            add(WrappingText(check.description.ifBlank { message("checks.why.none", appOf(check)) }))
            add(small(message("checks.why.app", appOf(check))))
        }
    }

    /** The log, selectable, in a box of its own that scrolls both ways: long lines do not widen the window. */
    private fun logView(log: FailedLog): JComponent {
        if (log.text.isBlank()) return small(message("checks.why.empty"))
        val area = JBTextArea(log.text).apply {
            isEditable = false
            lineWrap = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
        }
        val scroll = object : JBScrollPane(area) {
            // No width of its own: as wide as the row lets it, never wider.
            override fun getPreferredSize(): Dimension = Dimension(
                JBUI.scale(LOG_MIN_WIDTH),
                minOf(area.preferredSize.height + horizontalScrollBar.preferredSize.height + JBUI.scale(4), JBUI.scale(LOG_HEIGHT)),
            )
        }
        // The end first, once laid out: that is where a failed step says why.
        onEdt { scroll.verticalScrollBar.let { it.value = it.maximum } }
        if (log.omitted == 0) return scroll
        return JPanel(VerticalLayout(JBUI.scale(3))).apply {
            isOpaque = false
            add(scroll)
            if (log.omitted > 0) add(small(message("checks.why.omitted", FailedLogs.MAX_LINES)))
        }
    }

    private fun small(text: String): WrappingText = WrappingText(text, textFont = JBFont.small(), textColor = NamedColorUtil.getInactiveTextColor())

    private fun mark(status: CheckStatus): JComponent {
        val (text, color) = when (status) {
            CheckStatus.FAIL -> "✗" to StackColors.CLOSED
            CheckStatus.CANCELLED -> "⊘" to StackColors.WARNING
            CheckStatus.PENDING -> "●" to StackColors.QUEUED
            CheckStatus.PASS -> "✓" to StackColors.READY
            CheckStatus.SKIPPED -> "–" to StackColors.DRAFT
        }
        return JBLabel(text).apply {
            foreground = color
            font = JBFont.label().asBold()
            verticalAlignment = SwingConstants.TOP
        }
    }

    private fun appOf(check: PrCheck): String = check.app ?: message("checks.app.unknown")

    private fun key(status: CheckStatus): String = status.name.lowercase()

    private fun keyOf(check: PrCheck): String = check.title + "\u0000" + check.link

    private fun rowOf(title: String): JComponent? = rows.firstOrNull { it.first == title }?.second

    private fun texts(component: Component): List<String> = when (component) {
        is WrappingText -> listOf(component.text)
        is JTextArea -> listOf(component.text)
        is ActionLink -> emptyList()
        is JLabel -> listOfNotNull(component.text?.takeIf { it.isNotBlank() })
        is Container -> component.components.flatMap(::texts)
        else -> emptyList()
    }

    private fun links(component: Component): List<ActionLink> = when (component) {
        is ActionLink -> listOf(component)
        is Container -> component.components.flatMap(::links)
        else -> emptyList()
    }

    // Only to touch its components, whatever dialog is open, and not after it closed.
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }

    private sealed interface Reason {
        data object Reading : Reason
        data object App : Reason
        data class Log(val log: FailedLog) : Reason
        data class Error(val text: String) : Reason
    }

    private sealed interface Rerun {
        data object Asking : Rerun
        data object Requested : Rerun
        data class Refused(val error: String) : Rerun
    }

    /** The rows, one under another, as wide as the window: names and notes wrap instead of widening it. */
    private class Column : JPanel(VerticalLayout(JBUI.scale(4))), Scrollable {
        init {
            border = JBUI.Borders.empty(0, 4)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        /** The windows open in a project, by pull request URL: one per pull request. */
        private val OPEN = Key.create<MutableMap<String, ChecksDialog>>("upstack.checks.open")

        /** The window of [target]'s pull request: the one already open, brought to the front, or a new one. On the EDT. */
        fun show(project: Project, target: PrTarget) {
            val open = project.getUserData(OPEN) ?: HashMap<String, ChecksDialog>().also { project.putUserData(OPEN, it) }
            open[target.url]?.takeUnless { it.isDisposed }?.let {
                it.toFront()
                return
            }
            val dialog = ChecksDialog(project, target)
            open[target.url] = dialog
            Disposer.register(dialog.disposable) { open.remove(target.url, dialog) }
            dialog.show()
        }

        private const val WIDTH = 480
        private const val HEIGHT = 380
        private const val LOG_HEIGHT = 220
        private const val LOG_MIN_WIDTH = 80
        private const val LINK_GAP = 12
    }
}
