package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.actions.PrTarget
import com.upstack.actions.PullRequestDataKeys
import com.upstack.actions.StackFlows
import com.upstack.gh.GhCli
import com.upstack.gh.GhNotFoundException
import com.upstack.settings.UpstackSettings
import com.upstack.stack.OpenPullRequest
import com.upstack.stack.OpenPullRequestRead
import com.upstack.stack.OpenPullRequestReader
import com.upstack.stack.PullRequestPage
import com.upstack.stack.PullRequestState
import com.upstack.stack.GitHubRepo
import com.upstack.stack.StackService
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/** Independent list state; changing repository uses the same selection as the Stack tab. */
internal class PullRequestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {
    private val service = StackService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val repositories = FilterChip<GitRepository>({ AllIcons.Nodes.Folder to it.root.name }, { it.root.name })
    private val model = CollectionListModel<OpenPullRequest>()
    private val list = PullRequestList(model).apply { border = JBUI.Borders.empty(0, 6) }
    private val search = SearchTextField(false)
    private var labelColors: Map<String, String> = emptyMap()
    private val statusFilter = chip<StatusFilter>(null, { false }) { it.icon }
    private val avatars = AvatarCache { repaint() }
    private val authors = chip<String>(message("prs.author.filter"), { it == message("prs.author.all") }) {
        if (it == message("prs.author.all")) AllIcons.General.User else avatars.of(it, shownGithub?.host)
    }
    private val labels = chip<LabelOption>(message("prs.label.filter"), { it.name == null }) {
        if (it.name == null) AllIcons.General.Filter else DotsIcon.forLabel(labelColors[it.name.lowercase()])
    }
    private val assignees = chip<AssigneeOption>(message("prs.assignee.filter"), { it.name == null && !it.unassigned }) {
        when {
            it.unassigned -> IconLoader.getDisabledIcon(AllIcons.General.User)
            it.name != null -> avatars.of(it.name, shownGithub?.host)
            else -> AllIcons.General.User
        }
    }
    private val sortOrder = chip<SortOrder>(message("prs.sort"), { it == SortOrder.DEFAULT }) { it.icon }
    private val status = JBLabel()
    private var allPrs: List<OpenPullRequest> = emptyList()
    private var assigneesByPr: Map<Int, List<String>> = emptyMap()
    private var filling = false
    private var fillingAuthors = false
    private var fillingLabels = false
    private var fillingAssignees = false
    private var load: Job? = null
    private var nextCursor: String? = null
    private val loadMoreButton = JButton(message("prs.load.more"), AllIcons.General.ArrowDown).apply {
        isVisible = false
        addActionListener { loadMore() }
    }
    private var shownRoot: String? = null
    private var shownGithub: GitHubRepo? = null

    init {
        repositories.addActionListener {
            if (!filling) (repositories.selectedItem as? GitRepository)?.let {
                service.selectRepository(it)
                refresh()
            }
        }
        // Just the icon: no fill, no border, only as big as the icon plus a little air.
        val refresh = object : JButton(AllIcons.Actions.Refresh) {
            override fun getPreferredSize(): Dimension = Dimension(icon.iconWidth + JBUI.scale(8), icon.iconHeight + JBUI.scale(8))
            override fun getMaximumSize(): Dimension = preferredSize
        }.apply {
            toolTipText = message("prs.refresh")
            accessibleContext.accessibleName = message("prs.refresh")
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { refresh() }
        }
        search.textEditor.emptyText.text = message("prs.search.placeholder")
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filterRows()
        })
        StatusFilter.entries.forEach(statusFilter::addItem)
        statusFilter.addActionListener { refresh(clear = true) }
        authors.addItem(message("prs.author.all"))
        authors.addActionListener { if (!fillingAuthors) filterRows() }
        labels.addItem(LabelOption(null))
        labels.addActionListener { if (!fillingLabels) filterRows() }
        assignees.addItem(AssigneeOption())
        assignees.addItem(AssigneeOption(unassigned = true))
        assignees.addActionListener { if (!fillingAssignees) filterRows() }
        SortOrder.entries.forEach(sortOrder::addItem)
        sortOrder.addActionListener { filterRows() }
        // The search field with Refresh beside it, as the lists of IntelliJ have their toolbar.
        val searchRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 6, 2, 6)
            add(search, BorderLayout.CENTER)
            add(refresh, BorderLayout.EAST)
        }
        val filterRow = FilterRow().apply {
            border = JBUI.Borders.empty(0, 4)
            add(JLabel(AllIcons.General.Filter).apply { border = JBUI.Borders.empty(0, 4, 0, 6) })
            listOf(statusFilter, authors, labels, assignees, sortOrder).forEach(::add)
        }
        // One row that scrolls sideways (wheel and trackpad) instead of wrapping onto several lines. A plain
        // viewport with no bars: IntelliJ's scroll pane squeezes a view to the window when it has no bar.
        val filters = object : JViewport() {
            override fun getPreferredSize(): Dimension = Dimension(0, view?.preferredSize?.height ?: 0)
        }.apply {
            isOpaque = false
            view = filterRow
            addMouseWheelListener { event ->
                val most = maxOf(0, (view?.width ?: 0) - width)
                val x = (viewPosition.x + event.preciseWheelRotation * JBUI.scale(24)).toInt().coerceIn(0, most)
                viewPosition = Point(x, 0)
            }
        }
        val controls = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(searchRow, BorderLayout.NORTH)
            add(filters, BorderLayout.CENTER)
        }
        val actions = ActionManager.getInstance()
        list.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = list.locationToIndex(Point(x, y))
                if (index < 0 || list.getCellBounds(index, index)?.contains(x, y) != true) return
                list.selectedIndex = index
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null, actions.getAction("Upstack.PullRequest.Popup") as ActionGroup,
                    selectionContext(), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true, "UpstackPullRequestPopup",
                )
                if (popup.listStep.values.isEmpty()) Disposer.dispose(popup) else popup.show(RelativePoint(comp, Point(x, y)))
            }
        })
        ListSpeedSearch.installOn(list) { "#${it.number} ${it.title} ${it.head} ${it.author.orEmpty()}" }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 1 || !SwingUtilities.isLeftMouseButton(event)) return
                val pr = list.checksAt(event.point) ?: return
                list.setSelectedValue(pr, false)
                currentTarget()?.let { StackFlows.showChecks(project, it) }
                event.consume()
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.checksAt(event.point) != null) return false
                list.selectedValue?.let { BrowserUtil.browse(it.url) } ?: return false
                return true
            }
        }.installOn(list)
        val content = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
            // Under everything: the repository, the count and Load more.
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                border = JBUI.Borders.empty(2, 2)
                add(repositories, BorderLayout.WEST)
                add(status.apply { border = JBUI.Borders.empty(0, 4) }, BorderLayout.CENTER)
                add(loadMoreButton, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        setContent(content)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                updateRepositories(refreshOnChange = false)
                refresh()
            }
        }
        scope.launch { service.state.collect { updateRepositories() } }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PullRequestDataKeys.TARGET] = currentTarget()
    }

    internal fun selectionContext(): DataContext {
        val builder = SimpleDataContext.builder().setParent(DataManager.getInstance().getDataContext(list))
            .add(CommonDataKeys.PROJECT, project)
        currentTarget()?.let { builder.add(PullRequestDataKeys.TARGET, it) }
        return builder.build()
    }

    private fun currentTarget(): PrTarget? = list.selectedValue?.let { pr ->
        PrTarget.fromPullRequest(pr) { if (isShowing) refresh() }
    }

    private fun filterRows() {
        val selectedUrl = list.selectedValue?.url
        val query = search.text.trim().removePrefix("#")
        val author = (authors.selectedItem as? String)?.takeUnless { authors.selectedIndex == 0 }
        val label = (labels.selectedItem as? LabelOption)?.name
        val assignee = assignees.selectedItem as? AssigneeOption
        val filtered = allPrs.filter { pr ->
            (author == null || pr.author?.equals(author, ignoreCase = true) == true) &&
                (label == null || pr.details?.hasLabel(label) == true) &&
                (assignee == null || when {
                    assignee.unassigned -> assigneesByPr[pr.number].isNullOrEmpty()
                    assignee.name != null -> assigneesByPr[pr.number].orEmpty().any { it.equals(assignee.name, ignoreCase = true) }
                    else -> true
                }) &&
                (query.isEmpty() || listOf(pr.number.toString(), pr.title, pr.head, pr.base, pr.author.orEmpty())
                    .any { it.contains(query, ignoreCase = true) })
        }
        val ordered = when (sortOrder.selectedItem as? SortOrder) {
            SortOrder.NEWEST -> filtered.sortedWith(compareBy(nullsLast(reverseOrder<Instant>())) { createdAt(it) })
            SortOrder.OLDEST -> filtered.sortedWith(compareBy(nullsLast(naturalOrder<Instant>())) { createdAt(it) })
            else -> filtered
        }
        model.replaceAll(ordered)
        ordered.firstOrNull { it.url == selectedUrl }?.let { list.setSelectedValue(it, false) }
        list.emptyText.text = if (query.isEmpty() && author == null && label == null && assignee?.name == null && assignee?.unassigned != true) "" else message("prs.search.empty")
    }

    private fun updateAuthors(reset: Boolean = false) {
        val selected = (authors.selectedItem as? String)?.takeUnless { reset || authors.selectedIndex == 0 }
        val names = (allPrs.mapNotNull { it.author?.takeIf(String::isNotBlank) } + listOfNotNull(selected))
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        fillingAuthors = true
        try {
            authors.removeAllItems()
            authors.addItem(message("prs.author.all"))
            names.forEach(authors::addItem)
            if (selected != null) authors.selectedItem = names.first { it.equals(selected, ignoreCase = true) }
        } finally {
            fillingAuthors = false
        }
    }

    private fun updateLabels(reset: Boolean = false) {
        labelColors = allPrs.flatMap { it.details?.labels.orEmpty() }.associate { it.name.lowercase() to it.color }
        val selected = (labels.selectedItem as? LabelOption)?.name?.takeUnless { reset }
        val names = (allPrs.flatMap { it.details?.labels.orEmpty() }.map { it.name } + listOfNotNull(selected))
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        fillingLabels = true
        try {
            labels.removeAllItems()
            labels.addItem(LabelOption(null))
            names.forEach { labels.addItem(LabelOption(it)) }
            if (selected != null) labels.selectedItem = LabelOption(names.first { it.equals(selected, ignoreCase = true) })
        } finally {
            fillingLabels = false
        }
    }

    /** The row of chips keeps the width its chips need, however narrow the window: the scroll pane moves it sideways. */
    private class FilterRow : JPanel(HorizontalLayout(JBUI.scale(2))), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(24)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.width
        override fun getScrollableTracksViewportWidth(): Boolean = false
        override fun getScrollableTracksViewportHeight(): Boolean = true
    }

    private data class LabelOption(val name: String?) {
        override fun toString(): String = name ?: message("prs.label.all")
    }

    private fun updateAssignees(reset: Boolean = false) {
        val selected = (assignees.selectedItem as? AssigneeOption)?.takeUnless { reset } ?: AssigneeOption()
        val names = (assigneesByPr.values.flatten() + listOfNotNull(selected.name))
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        fillingAssignees = true
        try {
            assignees.removeAllItems()
            assignees.addItem(AssigneeOption())
            assignees.addItem(AssigneeOption(unassigned = true))
            names.forEach { assignees.addItem(AssigneeOption(name = it)) }
            assignees.selectedItem = if (selected.name == null) selected
                else AssigneeOption(name = names.first { it.equals(selected.name, ignoreCase = true) })
        } finally {
            fillingAssignees = false
        }
    }

    private data class AssigneeOption(val name: String? = null, val unassigned: Boolean = false) {
        override fun toString(): String = when {
            unassigned -> message("prs.assignee.none")
            name != null -> name
            else -> message("prs.assignee.all")
        }
    }

    private enum class SortOrder(private val textKey: String, val icon: Icon) {
        DEFAULT("prs.sort.default", AllIcons.ObjectBrowser.Sorted),
        NEWEST("prs.sort.newest", AllIcons.General.ArrowDown),
        OLDEST("prs.sort.oldest", AllIcons.General.ArrowUp);
        override fun toString(): String = message(textKey)
    }

    /** Which pull requests GitHub is asked for; changing it reloads the list. */
    private enum class StatusFilter(
        private val textKey: String, val states: Set<PullRequestState>, val icon: Icon, val countKey: String, val emptyKey: String,
    ) {
        OPEN("status.open", setOf(PullRequestState.OPEN), DotsIcon(StackColors.READY), "prs.count", "prs.empty"),
        CLOSED("status.closed", setOf(PullRequestState.CLOSED), DotsIcon(StackColors.CLOSED), "prs.count.closed", "prs.empty.closed"),
        MERGED("status.merged", setOf(PullRequestState.MERGED), DotsIcon(StackColors.MERGED), "prs.count.merged", "prs.empty.merged"),
        ALL(
            "prs.status.all", PullRequestState.entries.toSet(),
            DotsIcon(listOf(StackColors.READY, StackColors.MERGED, StackColors.CLOSED)), "prs.count.all", "prs.empty.all",
        );
        override fun toString(): String = message(textKey)
    }

    private fun createdAt(pr: OpenPullRequest): Instant? = try {
        pr.createdAt?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun updateRepositories(refreshOnChange: Boolean = true) {
        val all = service.repositories()
        val selected = service.repository()
        filling = true
        try {
            repositories.removeAllItems()
            all.forEach(repositories::addItem)
            repositories.selectedItem = selected
        } finally {
            filling = false
        }
        val root = selected?.root?.path
        val github = selected?.let(service::githubOf)
        if (refreshOnChange && isShowing && (root != shownRoot || github != shownGithub)) refresh()
    }

    private fun refresh(clear: Boolean = false) {
        load?.cancel()
        val repository = service.repository()
        val root = repository?.root?.path
        val github = repository?.let(service::githubOf)
        if (clear || root != shownRoot || github != shownGithub || github == null || github.isSshAlias) {
            allPrs = emptyList()
            assigneesByPr = emptyMap()
            nextCursor = null
            model.removeAll()
            updateAuthors(reset = true)
            updateLabels(reset = true)
            updateAssignees(reset = true)
        }
        shownRoot = root
        shownGithub = github
        updateLoadMore()
        if (repository == null) {
            status.text = message("prs.no.repository")
            return
        }
        if (github == null) {
            status.text = message("prs.no.remote")
            return
        }
        if (github.isSshAlias) {
            status.text = message("prs.ssh.alias")
            return
        }
        fetch(repository, github, after = null)
    }

    /** The next page of the same list, appended to the rows already shown. */
    private fun loadMore() {
        val after = nextCursor ?: return
        val repository = service.repository() ?: return
        val github = service.githubOf(repository) ?: return
        if (repository.root.path != shownRoot || github != shownGithub) return refresh()
        load?.cancel()
        fetch(repository, github, after)
    }

    private fun fetch(repository: GitRepository, github: GitHubRepo, after: String?) {
        status.text = message("prs.loading")
        loadMoreButton.isEnabled = false
        val filter = statusFilter.selectedItem as? StatusFilter ?: StatusFilter.OPEN
        val page = PullRequestPage(after, UpstackSettings.getInstance().pullRequestsPageSize)
        load = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    OpenPullRequestReader.read(github, filter.states, page) { args -> GhCli.run(repository.root.toNioPath(), args) }
                }
            } catch (_: GhNotFoundException) {
                status.text = message("prs.no.gh")
                updateLoadMore()
                return@launch
            }
            if (result is OpenPullRequestRead.Failure) {
                status.text = if (result.invalid) message("prs.invalid") else message("prs.failed", result.message)
                updateLoadMore()
                return@launch
            }
            val success = result as OpenPullRequestRead.Success
            if (after == null) {
                allPrs = success.pullRequests
                assigneesByPr = success.assignees
            } else {
                // A PR opened meanwhile shifts the pages: skip what is already listed.
                val known = allPrs.mapTo(HashSet()) { it.number }
                allPrs = allPrs + success.pullRequests.filter { it.number !in known }
                assigneesByPr = assigneesByPr + success.assignees
            }
            nextCursor = success.nextCursor
            updateAuthors()
            updateLabels()
            updateAssignees()
            filterRows()
            updateLoadMore()
            status.text = when {
                allPrs.isEmpty() -> message(filter.emptyKey)
                nextCursor != null -> message("prs.count.partial", allPrs.size)
                else -> message(filter.countKey, allPrs.size)
            }
        }
    }

    private fun updateLoadMore() {
        loadMoreButton.isVisible = nextCursor != null
        loadMoreButton.isEnabled = true
    }

    override fun dispose() = scope.cancel()
}
