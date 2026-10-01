package com.stacklane.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.GotItTooltip
import com.intellij.ui.InlineBanner
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.UIUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.actions.LayerSelection
import com.stacklane.actions.StackDataKeys
import com.stacklane.actions.StackFlows
import com.stacklane.gh.GhCommands
import com.stacklane.settings.StacklaneConfigurable
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.RebaseStop
import com.stacklane.stack.StackService
import com.stacklane.stack.StackState
import com.stacklane.stack.repo
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager

/** La pestana Stack: selector de repositorio, resumen, avisos y la pila. */
internal class StackPanel(private val project: Project) : SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    private val service = StackService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    private val model = CollectionListModel<StackRow>()
    private val list = StackList(model)

    private val repositories = ComboBox<GitRepository>()
    private var fillingRepositories = false
    private val repositoryRow = JPanel(BorderLayout(JBUI.scale(8), 0))
    private val summary = JPanel(HorizontalLayout(JBUI.scale(8)))
    private val banners = JPanel()

    private var state: StackState = StackState.Loading
    private var running: String? = null

    /** El «Got it» del rebase upstack, mientras la banda lo ofrece. */
    private var upstackTip: GotItTooltip? = null

    init {
        val actions = ActionManager.getInstance()
        val toolbar = actions.createActionToolbar(TOOLBAR_PLACE, actions.getAction(TOOLBAR_GROUP) as ActionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        repositories.renderer = textListCellRenderer("") { it.root.name }
        repositories.addActionListener {
            if (!fillingRepositories) (repositories.selectedItem as? GitRepository)?.let(service::selectRepository)
        }
        repositoryRow.border = JBUI.Borders.empty(6, 10, 0, 10)
        repositoryRow.add(JBLabel(message("toolwindow.repository")), BorderLayout.WEST)
        repositoryRow.add(repositories, BorderLayout.CENTER)

        summary.border = JBUI.Borders.empty(6, 10, 4, 10)
        banners.layout = BoxLayout(banners, BoxLayout.Y_AXIS)
        banners.border = JBUI.Borders.empty(0, 8)

        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(repositoryRow, summary, banners).forEach {
                it.alignmentX = Component.LEFT_ALIGNMENT
                it.isOpaque = false
                add(it)
            }
        }
        val content = JPanel(BorderLayout())
        content.add(north, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        setContent(content)

        // El menu es el de la fila bajo el raton, no el de la que estuviera seleccionada: se
        // selecciona antes de abrirlo, y fuera de las filas se quita la seleccion (menu de la pila).
        // Un popup de lista y no un JPopupMenu: solo asi cada opcion ensena su tooltip.
        list.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                list.selectAt(Point(x, y))
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null, actions.getAction(popupGroup()) as ActionGroup, DataManager.getInstance().getDataContext(list),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true, POPUP_PLACE,
                )
                // Una pila local que no esta rota no tiene acciones: nada que abrir.
                if (popup.listStep.values.isEmpty()) Disposer.dispose(popup) else popup.show(RelativePoint(comp, Point(x, y)))
            }
        })
        ListSpeedSearch.installOn(list) { it.searchText }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                // Dos clics seguidos sobre el chevron son desplegar, no un checkout.
                if (list.toggleAt(event.point) != null) return false
                activateSelection()
                return true
            }
        }.installOn(list)
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.modifiersEx == 0) {
                    activateSelection()
                    e.consume()
                }
            }
        })
        // Al volver a la ventana se relee: gh stack pudo usarse en la terminal sin mover
        // ninguna ref (un init que adopta ramas existentes, por ejemplo).
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) service.requestRefresh()
        }

        scope.launch { service.state.collect(::render) }
        scope.launch { service.pushPending.collect { renderBanners() } }
        scope.launch { service.conflicts.collect { renderBanners() } }
        scope.launch {
            service.running.collect {
                running = it
                renderSummary()
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val loaded = state as? StackState.Loaded
        val row = list.selectedValue as? StackRow.Layer
        sink[StackDataKeys.LAYER] = if (loaded != null && row != null) LayerSelection(loaded, row.layer) else null
        sink[StackDataKeys.LOCAL_STACK] = (list.selectedValue as? StackRow.Local)?.entry
    }

    // ---------------------------------------------------------------- pintado

    private fun render(newState: StackState) {
        val selectedKey = list.selectedValue?.key
        state = newState
        renderRepositories()
        renderSummary()
        renderBanners()
        renderEmptyText()
        model.replaceAll(rows(newState))
        val index = model.items.indexOfFirst { it.key == selectedKey }
        if (index >= 0) list.selectedIndex = index
    }

    private fun rows(state: StackState): List<StackRow> = when (state) {
        is StackState.Loaded -> {
            val snapshot = state.snapshot
            val layers = snapshot.layers.mapIndexed { index, layer ->
                val details = state.detailsOf(layer)
                StackRow.Layer(
                    layer = layer,
                    details = details,
                    parent = snapshot.parentOf(layer),
                    position = index + 1,
                    total = snapshot.layers.size,
                    isTop = index == snapshot.layers.lastIndex,
                    detailsLoading = state.detailsLoading,
                    wrongBase = snapshot.wrongBase(layer, details),
                )
            }
            layers.asReversed() + StackRow.Trunk(snapshot.trunk, snapshot.currentBranch == snapshot.trunk)
        }
        is StackState.NotInStack -> state.localStacks.map { StackRow.Local(it) }
        // Primero las pilas de la rama actual; despues el resto, como fuera de una pila.
        is StackState.InSeveralStacks -> {
            val (ofBranch, others) = state.localStacks.partition { it.stack.contains(state.branch) }
            ofBranch.map { StackRow.Local(it, head = state.branch) } + others.map { StackRow.Local(it) }
        }
        else -> emptyList()
    }

    private fun renderRepositories() {
        val all = service.repositories()
        repositoryRow.isVisible = all.size > 1
        if (all.size <= 1) return
        fillingRepositories = true
        try {
            repositories.removeAllItems()
            all.forEach(repositories::addItem)
            val shown = state.repo?.root
            repositories.selectedItem = all.firstOrNull { it.root == shown }
        } finally {
            fillingRepositories = false
        }
    }

    private fun renderSummary() {
        summary.removeAll()
        val current = state
        running?.let { title ->
            summary.add(JBLabel(message("summary.running", title), AnimatedIcon.Default.INSTANCE, JBLabel.LEFT))
        }
        if (running == null) when (current) {
            is StackState.Loaded -> {
                val rows = rows(current).filterIsInstance<StackRow.Layer>()
                val parts = mutableListOf(message("summary.layers", rows.size, current.snapshot.trunk))
                rows.count { it.status == LayerStatus.DRAFT }.takeIf { it > 0 }?.let { parts += message("summary.drafts", it) }
                rows.count { it.status == LayerStatus.UNPUBLISHED }.takeIf { it > 0 }?.let { parts += message("summary.unpublished", it) }
                if (current.detailsLoading) parts += message("summary.loading.details")
                summary.add(secondary(parts.joinToString(" · ")))
            }
            is StackState.NotInStack -> {
                summary.add(secondary(message("summary.not.in.stack", current.branch ?: "HEAD")))
                if (current.localStacks.isNotEmpty()) summary.add(startStackLink())
            }
            is StackState.InSeveralStacks -> {
                val count = current.stacksOfBranch.size
                // gh-stack ya dijo que son varias; si el fichero local no las da, no se inventa el numero.
                val text = if (count > 1) message("summary.several.stacks", current.branch, count)
                else message("summary.several.stacks.unknown", current.branch)
                summary.add(secondary(text))
                if (current.localStacks.isNotEmpty()) summary.add(startStackLink())
            }
            else -> Unit
        }
        summary.isVisible = summary.componentCount > 0
        summary.revalidate()
        summary.repaint()
    }

    private fun startStackLink(): JComponent =
        ActionLink(message("link.start.stack")) { StackFlows.initStack(project) }.apply { showHelp(Helps.initStack()) }

    private fun renderBanners() {
        banners.removeAll()
        val current = state
        val repo = current.repo
        repo?.rebase?.let(::renderRebaseStop)
        if (current is StackState.Loaded) {
            current.detailsError?.let { error ->
                banners.add(
                    banner(message("banner.details.error", error), EditorNotificationPanel.Status.Warning)
                        .withAction(message("action.retry"), Helps.refresh()) { service.requestRefresh() }
                )
            }
            if (!current.repo.stackRebaseInProgress) {
                renderMerged(current)
                renderNeedsRebase(current)
                renderPushPending(current)
            }
        }
        if (current !is StackState.Loaded || current.snapshot.upstackStart == null || current.repo.stackRebaseInProgress) {
            hideUpstackTip()
        }
        banners.isVisible = banners.componentCount > 0
        banners.revalidate()
        banners.repaint()
    }

    /**
     * El rebase parado: en que capa y en que commit, y lo que toca. Mientras quedan conflictos,
     * resolverlos; cuando el IDE ya no ve ninguno, el siguiente paso es *Continue*, en azul.
     * Sin la cuenta (el IDE aun no ha repasado los cambios), las tres acciones.
     */
    private fun renderRebaseStop(stop: RebaseStop) {
        val conflicts = service.conflicts.value
        val text = listOfNotNull(
            when {
                stop.branch != null && stop.position != null && stop.layers != null ->
                    message("banner.rebase.layer", stop.branch, stop.position, stop.layers)
                stop.branch != null -> message("banner.rebase.branch", stop.branch)
                else -> message("banner.rebase")
            },
            stop.commit?.let { message("banner.rebase.commit", it, stop.subject.orEmpty()) },
            when (conflicts) {
                null -> message("banner.rebase.unknown")
                0 -> message("banner.rebase.resolved")
                else -> message("banner.rebase.count", conflicts)
            },
        ).joinToString(" ")
        val status = if (conflicts == 0) EditorNotificationPanel.Status.Info else EditorNotificationPanel.Status.Warning
        val banner = banner(text, status)
        if (conflicts != 0) {
            banner.withAction(message("action.resolve.conflicts"), Helps.resolveConflicts()) { service.resolveConflicts() }
        }
        if (conflicts == null || conflicts == 0) {
            banner.withAction(message("action.rebase.continue"), Helps.continueRebase()) { service.continueRebase() }
        }
        banner.withAction(message("action.rebase.abort"), Helps.abortRebase()) { service.abortRebase() }
        banners.add(banner)
    }

    /**
     * Capas que ya no parten de la de debajo. Si es por un cambio en una capa, lo primero que
     * se ofrece es el rebase upstack, que no trae el trunk; si la de abajo se quedo atras del
     * trunk, eso solo lo arregla rebasar toda la pila.
     */
    private fun renderNeedsRebase(state: StackState.Loaded) {
        val snapshot = state.snapshot
        val outdated = snapshot.outdatedLayers
        val behind = snapshot.bottom?.takeIf { snapshot.behindTrunk }
        if (outdated.isEmpty() && behind == null) return

        val text = listOfNotNull(
            outdated.takeIf { it.isNotEmpty() }?.let { layers -> message("banner.outdated", layers.joinToString(", ") { it.branch }) },
            behind?.let { message("banner.behind.trunk", it.branch, snapshot.trunk) },
        ).joinToString(" ")
        val banner = banner(text, EditorNotificationPanel.Status.Info)
        val start = snapshot.upstackStart
        if (start != null) {
            banner.withAction(message("action.rebase.upstack.from", start.branch), Helps.rebaseUpstackFrom(snapshot, start.branch)) {
                StackFlows.rebaseUpstack(project, start.branch)
            }
        }
        banner.withAction(message("action.rebase.stack.onto", snapshot.trunk), Helps.rebaseStack()) { service.rebase() }
        banners.add(banner)
        if (start != null) showUpstackTip(snapshot.trunk)
    }

    /**
     * Capas fusionadas en GitHub cuya rama sigue aqui: el siguiente paso tras un merge, borrarlas
     * y poner el resto de la pila sobre el trunk, queda aqui hasta que se hace.
     */
    private fun renderMerged(state: StackState.Loaded) {
        val repository = service.repositoryFor(state.repo) ?: return
        val merged = StackFlows.mergedWithLocalBranch(repository, state.snapshot)
        if (merged.isEmpty()) return
        banners.add(
            banner(message("banner.merged", merged.joinToString(", "), state.snapshot.trunk), EditorNotificationPanel.Status.Info)
                .withAction(message("action.sync.prune"), Helps.syncAndPrune(state.snapshot.trunk)) { StackFlows.syncAndPrune(project) }
        )
    }

    /** Tras un rebase las ramas solo cambiaron en local: el siguiente paso, subirlas, queda aqui. */
    private fun renderPushPending(state: StackState.Loaded) {
        val layers = service.unpushedLayers(state)
        if (layers.isEmpty()) return
        val repository = service.repositoryFor(state.repo) ?: return
        banners.add(
            banner(message("banner.push", layers.joinToString(", ")), EditorNotificationPanel.Status.Info)
                .withAction(message("action.push.stack"), Helps.push()) { service.push(repository) }
        )
    }

    /** La primera vez que se ofrece un rebase upstack, que es y en que se diferencia del completo. */
    private fun showUpstackTip(trunk: String) {
        if (upstackTip != null) return
        val tip = GotItTooltip(UPSTACK_TIP_ID, message("tip.upstack.text", trunk), this)
            .withHeader(message("tip.upstack.header"))
        upstackTip = tip
        if (tip.canShow()) tip.show(banners, GotItTooltip.BOTTOM_MIDDLE)
    }

    private fun hideUpstackTip() {
        upstackTip?.let(Disposer::dispose)
        upstackTip = null
    }

    private fun renderEmptyText() {
        val text = list.emptyText
        text.clear()
        when (val current = state) {
            StackState.Loading -> text.text = message("state.loading")
            StackState.NoRepository -> text.text = message("state.no.repository")
            StackState.GhMissing -> {
                text.appendLine(message("state.gh.missing"))
                text.appendLine(message("link.install.gh"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    BrowserUtil.browse(GH_INSTALL_URL)
                }
                text.appendLine(message("link.configure.gh"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, StacklaneConfigurable::class.java)
                }
            }
            StackState.ExtensionMissing -> {
                text.appendLine(message("state.extension.missing"))
                text.appendLink(message("link.install.extension"), Helps.installExtension().commands.single()) {
                    StackFlows.installExtension(project)
                }
            }
            is StackState.NotInStack -> {
                text.appendLine(message("state.not.in.stack", current.branch ?: "HEAD"))
                appendStartOrCheckout(text)
            }
            // Solo si no se pudo leer `.git/gh-stack`: con pilas, la lista no esta vacia.
            is StackState.InSeveralStacks -> {
                text.appendLine(message("state.several.stacks", current.branch))
                appendStartOrCheckout(text)
            }
            is StackState.Failed -> {
                text.appendLine(current.message.lineSequence().firstOrNull { it.isNotBlank() } ?: message("state.failed"))
                text.appendLink(message("action.retry"), Help.gh(GhCommands.view())) {
                    service.requestRefresh()
                }
                text.appendLine(message("notification.show.log"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    StackToolWindow.showLog(project)
                }
            }
            is StackState.Loaded -> Unit
        }
    }

    private fun appendStartOrCheckout(text: StatusText) {
        text.appendLink(message("link.start.stack"), Helps.initStack().commands.single()) { StackFlows.initStack(project) }
        text.appendLink(message("link.checkout.stack"), Helps.checkoutStack().commands.single()) { StackFlows.checkoutStackByInput(project) }
    }

    private fun banner(text: String, status: EditorNotificationPanel.Status): InlineBanner =
        InlineBanner(text, status).showCloseButton(false).apply { alignmentX = Component.LEFT_ALIGNMENT }

    /** Un boton de la banda con su ayuda al pasar el raton. */
    private fun InlineBanner.withAction(text: String, help: Help, action: () -> Unit): InlineBanner = apply {
        addAction(text, null, action).showHelp(help)
    }

    /** Un enlace de la lista vacia y, debajo y en gris, el comando que ejecuta. */
    private fun StatusText.appendLink(text: String, command: String, action: () -> Unit) {
        appendLine(text, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { action() }
        appendLine(command, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, null)
    }

    private fun secondary(text: String): JComponent = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    // ---------------------------------------------------------------- acciones de la lista

    /** Sobre una capa o una pila local, lo suyo; fuera de las filas o sobre la base, la pila entera. */
    private fun popupGroup(): String = when (list.selectedValue) {
        is StackRow.Layer, is StackRow.Local -> LAYER_POPUP_GROUP
        is StackRow.Trunk, null -> STACK_POPUP_GROUP
    }

    private fun activateSelection() {
        val repository = state.repo?.let(service::repositoryFor) ?: return
        when (val row = list.selectedValue) {
            is StackRow.Layer -> if (!row.layer.isCurrent) service.checkout(repository, row.layer.branch)
            is StackRow.Trunk -> if (!row.isCurrent) service.checkout(repository, row.name)
            is StackRow.Local -> activateLocal(repository, row.entry)
            null -> Unit
        }
    }

    /**
     * Una pila local: si la rama a sacar esta en local, checkout del IDE; si solo esta en el
     * remoto, `gh stack checkout`, que la trae; si no queda ninguna, ofrecer limpiarla. Si es
     * la rama actual no hay nada que hacer: el tooltip explica por que.
     */
    private fun activateLocal(repository: GitRepository, entry: LocalStackEntry) {
        val target = entry.checkoutTarget
        when {
            entry.isStale -> StackFlows.cleanUpStaleStack(project, entry)
            target == null || target == repository.currentBranchName -> Unit
            target in entry.localBranches -> service.checkout(repository, target)
            else -> StackFlows.checkoutStack(project, target, repository)
        }
    }

    private companion object {
        const val TOOLBAR_GROUP = "Stacklane.Toolbar"
        const val TOOLBAR_PLACE = "StacklaneToolbar"
        const val LAYER_POPUP_GROUP = "Stacklane.Layer.Popup"
        const val STACK_POPUP_GROUP = "Stacklane.Stack.Popup"
        const val POPUP_PLACE = "StacklaneLayerPopup"
        const val GH_INSTALL_URL = "https://cli.github.com"
        const val UPSTACK_TIP_ID = "stacklane.rebase.upstack"
    }
}

/**
 * La lista de la pila. Sigue el ancho del viewport, sin barra horizontal: el renderer envuelve
 * cada fila a ese ancho, y cada vez que cambia se vuelven a medir las alturas. Tambien lleva
 * que filas estan desplegadas y atiende el chevron que las despliega ([ExpandToggle]).
 */
private class StackList(private val rows: CollectionListModel<StackRow>) : JBList<StackRow>(rows) {

    /** Las filas desplegadas, por [StackRow.key]. */
    private val expanded = HashSet<String>()

    /** El ancho con el que se midieron las filas. */
    private var measuredWidth = -1

    init {
        cellRenderer = StackRowRenderer { it.key in expanded }
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Las filas ya caben enteras: el popup que ensena la fila recortada sobraria.
        setExpandableItemsEnabled(false)
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
                val row = toggleAt(e.point) ?: return
                if (!expanded.remove(row.key)) expanded += row.key
                rows.allContentsChanged()
                e.consume()
            }

            override fun mouseMoved(e: MouseEvent) {
                val hand = toggleAt(e.point) != null
                if (hand != (cursor.type == Cursor.HAND_CURSOR)) {
                    cursor = if (hand) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    /**
     * Selecciona la fila bajo [point] o, si no hay ninguna, quita la seleccion. El menu por
     * teclado se abre dentro de la fila seleccionada, asi que ahi no cambia nada.
     */
    fun selectAt(point: Point) {
        val index = locationToIndex(point)
        if (index >= 0 && getCellBounds(index, index)?.contains(point) == true) selectedIndex = index else clearSelection()
    }

    /**
     * Cambiar de ancho tira las alturas aqui mismo: `JList` las guarda y no se entera de que el
     * renderer envuelve el texto con otro ancho. Solo el ancho: si cambia el alto no hay nada
     * que remedir.
     */
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width == measuredWidth) return
        measuredWidth = width
        rows.allContentsChanged()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        toggleAt(event.point)?.let { return message(if (it.key in expanded) "tooltip.collapse" else "tooltip.expand") }
        val index = locationToIndex(event.point)
        if (index < 0 || getCellBounds(index, index)?.contains(event.point) != true) return null
        return model.getElementAt(index).tooltip()
    }

    /**
     * La fila cuyo chevron esta bajo [point], o null. Se prepara la fila como se pinta, se coloca
     * en su celda y se busca el componente que queda debajo.
     */
    fun toggleAt(point: Point): StackRow? {
        val index = locationToIndex(point)
        if (index < 0) return null
        val bounds = getCellBounds(index, index)?.takeIf { it.contains(point) } ?: return null
        val row = model.getElementAt(index)
        val cell = cellRenderer.getListCellRendererComponent(this, row, index, isSelectedIndex(index), false)
        cell.setBounds(0, 0, bounds.width, bounds.height)
        layOut(cell)
        return row.takeIf { SwingUtilities.getDeepestComponentAt(cell, point.x - bounds.x, point.y - bounds.y) is ExpandToggle }
    }

    private fun layOut(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layOut)
    }
}
