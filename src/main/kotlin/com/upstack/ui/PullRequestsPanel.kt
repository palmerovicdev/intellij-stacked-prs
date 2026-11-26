package com.upstack.ui

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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.upstack.UpstackBundle.message
import com.upstack.actions.PrTarget
import com.upstack.actions.PullRequestDataKeys
import com.upstack.actions.StackFlows
import com.upstack.gh.GhCli
import com.upstack.gh.GhCommands
import com.upstack.gh.GhNotFoundException
import com.upstack.stack.OpenPullRequest
import com.upstack.stack.OpenPullRequests
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
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Independent list state; changing repository uses the same selection as the Stack tab. */
internal class PullRequestsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {
    private val service = StackService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val repositories = ComboBox<GitRepository>()
    private val model = CollectionListModel<OpenPullRequest>()
    private val list = PullRequestList(model)
    private val status = JBLabel()
    private var filling = false
    private var load: Job? = null
    private var shownRoot: String? = null

    init {
        repositories.renderer = textListCellRenderer("") { it.root.name }
        repositories.addActionListener {
            if (!filling) (repositories.selectedItem as? GitRepository)?.let {
                service.selectRepository(it)
                refresh()
            }
        }
        val refresh = JButton(message("prs.refresh")).apply { addActionListener { refresh() } }
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 10)
            add(JBLabel(message("toolwindow.repository")), BorderLayout.WEST)
            add(repositories, BorderLayout.CENTER)
            add(refresh, BorderLayout.EAST)
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
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
            add(status.apply { border = JBUI.Borders.empty(6, 10) }, BorderLayout.SOUTH)
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
        if (refreshOnChange && isShowing && root != shownRoot) refresh()
    }

    private fun refresh() {
        load?.cancel()
        val selectedUrl = list.selectedValue?.url
        val repository = service.repository()
        shownRoot = repository?.root?.path
        model.removeAll()
        if (repository == null) {
            status.text = message("prs.no.repository")
            return
        }
        val github = service.githubOf(repository)
        if (github == null) {
            status.text = message("prs.no.remote")
            return
        }
        if (github.isSshAlias) {
            status.text = message("prs.ssh.alias")
            return
        }
        status.text = message("prs.loading")
        load = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { GhCli.run(repository.root.toNioPath(), GhCommands.openPullRequests(github)) }
            } catch (_: GhNotFoundException) {
                status.text = message("prs.no.gh")
                return@launch
            }
            if (!result.ok) {
                status.text = message("prs.failed", result.errorText)
                return@launch
            }
            val prs = OpenPullRequests.parse(result.stdout)
            if (prs == null) {
                status.text = message("prs.invalid")
                return@launch
            }
            model.replaceAll(prs)
            prs.firstOrNull { it.url == selectedUrl }?.let { list.setSelectedValue(it, false) }
            status.text = if (prs.isEmpty()) message("prs.empty") else message("prs.count", prs.size)
        }
    }

    override fun dispose() = scope.cancel()
}
