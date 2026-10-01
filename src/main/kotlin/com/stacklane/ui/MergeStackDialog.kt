package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.gh.MergeMethod
import com.stacklane.stack.ChecksState
import com.stacklane.stack.MergeBlock
import com.stacklane.stack.MergeCandidate
import com.stacklane.stack.MergeChoice
import com.stacklane.stack.MergePlans
import com.stacklane.stack.MergeSettings
import com.stacklane.stack.MergeTargets
import com.stacklane.stack.PrState
import com.stacklane.stack.ReviewDecision
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackState
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * *Merge Up to Here…* y *Merge Stack…*: hasta que PR se fusiona, que capas entran (con su review
 * y su CI) y con que metodo. Es la confirmacion: nada se fusiona sin pulsar el boton.
 *
 * [settings]: lo que admite el repositorio; null si no se pudo leer, y entonces se ofrecen los
 * tres metodos y GitHub lo comprueba al fusionar. Con cola de merge no se elige metodo.
 */
internal class MergeStackDialog(
    project: Project,
    private val state: StackState.Loaded,
    private val targets: MergeTargets,
    private val settings: MergeSettings?,
    lastMethod: MergeMethod?,
    private val initial: MergeCandidate,
) : DialogWrapper(project) {

    private val trunk = targets.trunk
    private val queue = settings?.usesMergeQueue == true

    /** Arriba la cima, como en la ventana Stacks. */
    private val upTo = ComboBox(targets.candidates.asReversed().toTypedArray()).apply {
        renderer = textListCellRenderer("") { "#${it.number} ${it.layer.branch}" }
        selectedItem = initial
    }
    private val methods: Map<MergeMethod, JBRadioButton> = MergeMethod.entries.associateWith { method ->
        JBRadioButton(methodText(method)).apply { isEnabled = settings == null || method in settings.allowed }
    }
    private val rows = state.snapshot.layers
        .filter { !it.isMerged && state.detailsOf(it)?.state != PrState.MERGED }
        .map(::LayerRow)
    private val warning = WrappingText(icon = AllIcons.General.Warning)
    private val preview = CommandPreview()

    val choice: MergeChoice
        get() = MergeChoice(target, if (queue) null else methods.entries.first { it.value.isSelected }.key)

    private val target: MergeCandidate get() = upTo.item ?: initial

    init {
        title = message("merge.title")
        methods.getValue(MergePlans.initialMethod(settings, lastMethod)).isSelected = true
        upTo.addActionListener { update() }
        methods.values.forEach { it.addActionListener { update() } }
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(WrappingText(message("merge.intro", trunk))).align(AlignX.FILL).resizableColumn() }
        row(message("merge.up.to")) { cell(upTo).align(AlignX.FILL) }.topGap(TopGap.SMALL)
        row {
            val graph = DialogGraph().apply {
                rows.asReversed().forEachIndexed { index, row -> add(row.component(lineAbove = index > 0)) }
                addBase(trunk, lineAbove = rows.isNotEmpty())
            }
            cell(ScrollPaneFactory.createScrollPane(graph, true)).align(Align.FILL)
        }.resizableRow()
        targets.blocker?.let { blocker ->
            row { note(message("merge.blocked", describe(blocker.layer), mergeBlockText(blocker.reason)), AllIcons.General.Information) }
        }
        row { cell(warning).align(AlignX.FILL).resizableColumn() }
        when {
            queue -> row { note(message("merge.queue", trunk), AllIcons.General.Information) }.topGap(TopGap.SMALL)
            else -> {
                // Las casillas solo se agrupan dentro de buttonsGroup: el UI DSL no admite otra cosa.
                buttonsGroup(message("merge.method")) {
                    methods.values.forEach { button -> row { cell(button) } }
                }
                if (settings == null) row { note(message("merge.settings.unknown"), AllIcons.General.Information) }
            }
        }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent = upTo

    private fun Row.note(text: String, icon: Icon) =
        cell(WrappingText(text, icon)).align(AlignX.FILL).resizableColumn()

    private fun update() {
        val included = targets.upTo(target)
        val numbers = included.mapTo(HashSet()) { it.number }
        rows.forEach { it.update(it.layer.pr?.number in numbers) }

        warning.text = warnings(included)
        warning.isVisible = warning.text.isNotEmpty()
        setOKButtonText(message(if (queue) "merge.ok.queue" else "merge.ok", included.size))

        upTo.showHelp(Help(message("help.merge.up.to", trunk), listOf(Help.gh(GhCommands.merge(target.number, choice.method)))))
        methods.forEach { (method, button) ->
            val help = if (button.isEnabled) Help(methodHelp(method), listOf(Help.gh(GhCommands.merge(target.number, method))))
            else Help(message("help.merge.method.disallowed", methodText(method)))
            button.showHelp(help)
        }
        preview.show(listOf(MergePlans.call(choice).args))
        // La nota de avisos aparece y desaparece: el dialogo crece con ella, nunca encoge.
        val window = window ?: return
        if (window.isVisible && window.preferredSize.height > window.height) pack()
    }

    /**
     * Lo que GitHub va a comprobar al fusionar y quiza no deje: gh-stack solo mira que esten
     * abiertos y no sean draft. Si uno no se puede fusionar, no se fusiona ninguno.
     */
    private fun warnings(included: List<MergeCandidate>): String {
        fun numbers(filter: (MergeCandidate) -> Boolean) = included.filter(filter).joinToString(", ") { "#${it.number}" }
        val parts = listOfNotNull(
            numbers { it.details.review != null && it.details.review != ReviewDecision.APPROVED }.ifEmpty { null }
                ?.let { message("merge.warning.review", it) },
            numbers { it.details.checks == ChecksState.FAILURE || it.details.checks == ChecksState.PENDING }.ifEmpty { null }
                ?.let { message("merge.warning.checks", it) },
            numbers { it.details.hasConflicts }.ifEmpty { null }?.let { message("merge.warning.conflicts", it) },
            numbers { it.details.isBehind }.ifEmpty { null }?.let { message("merge.warning.behind", it, trunk) },
        )
        return if (parts.isEmpty()) "" else message("merge.warning", parts.joinToString(" "))
    }

    private fun describe(layer: StackLayer): String = layer.pr?.let { "#${it.number} ${layer.branch}" } ?: layer.branch

    /** Una capa: rama, PR, estado, review y CI, y a la derecha si entra en el merge o por que no. */
    private inner class LayerRow(val layer: StackLayer) {

        private val details = state.detailsOf(layer)
        private val status = LayerStatus.of(layer, details)
        private val candidate = targets.candidateOf(layer.branch)
        private val fate = JBLabel().apply { font = JBFont.small() }

        fun update(merges: Boolean) {
            val block = targets.blocker?.takeIf { it.layer == layer }?.reason
            fate.text = when {
                merges -> message("merge.fate.merges")
                block != null -> mergeBlockText(block)
                else -> message("merge.fate.stays")
            }
            fate.foreground = if (merges) StackColors.MERGED else NamedColorUtil.getInactiveTextColor()
        }

        fun component(lineAbove: Boolean): JComponent {
            val name = JLabel(layer.branch).apply { if (layer.isCurrent) font = JBFont.label().asBold() }
            val left = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
                isOpaque = false
                add(name)
                layer.pr?.let { add(secondary("#${it.number}")) }
                add(Chip.status(status.text, status.color))
            }
            val header = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(left, BorderLayout.CENTER)
                add(fate, BorderLayout.EAST)
            }
            val node = Rail.Node(if (candidate != null) Rail.Shape.FILLED else Rail.Shape.RING, status.color)
            return graphRow(header, badges(), null, node, lineAbove, lineBelow = true)
        }

        /** El titulo y, debajo, review, CI y conflictos: lo que GitHub mira al fusionar. */
        private fun badges(): JComponent? {
            val info = details ?: return null
            val chips = listOfNotNull(
                info.review?.let(Chip::review),
                info.checks?.let(Chip::checks),
                if (info.hasConflicts) Chip.status(message("pr.conflicts"), StackColors.CLOSED) else null,
                if (info.isBehind) Chip.status(message("pr.behind"), StackColors.QUEUED) else null,
            )
            val title = info.title.takeIf { it.isNotBlank() }?.let { secondary(it).apply { font = JBFont.small() } }
            if (chips.isEmpty()) return title
            return JPanel(HorizontalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                title?.let(::add)
                chips.forEach(::add)
            }
        }

        private fun secondary(text: String) = JLabel(text).apply { foreground = NamedColorUtil.getInactiveTextColor() }
    }

    private companion object {
        fun methodText(method: MergeMethod): String = when (method) {
            MergeMethod.SQUASH -> message("merge.method.squash")
            MergeMethod.MERGE -> message("merge.method.merge")
            MergeMethod.REBASE -> message("merge.method.rebase")
        }

        fun methodHelp(method: MergeMethod): String = when (method) {
            MergeMethod.SQUASH -> message("help.merge.method.squash")
            MergeMethod.MERGE -> message("help.merge.method.merge")
            MergeMethod.REBASE -> message("help.merge.method.rebase")
        }

    }
}

/** Por que una capa no se puede fusionar, en la fila del dialogo y en los avisos. */
internal fun mergeBlockText(block: MergeBlock): String = when (block) {
    MergeBlock.DRAFT -> message("merge.block.draft")
    MergeBlock.CLOSED -> message("merge.block.closed")
    MergeBlock.UNPUBLISHED -> message("merge.block.unpublished")
    MergeBlock.UNKNOWN -> message("merge.block.unknown")
}
