package com.stacklane.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.GitHubRepo
import com.stacklane.stack.PrRef
import com.stacklane.stack.PrText
import com.stacklane.stack.PublishLayer
import com.stacklane.stack.PublishPlans
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * *Publish Stack…*: lo que hace el editor interactivo de `gh stack submit`, que el plugin no
 * puede abrir. Una fila por capa activa, de arriba abajo como en la ventana Stacks: las
 * nuevas con el titulo y la descripcion de su PR (propuestos a partir de sus commits) y
 * todas con su casilla de listo para review. Se ejecuta `gh stack submit --auto` y despues
 * lo elegido, PR a PR (ver [PublishPlans.calls]).
 *
 * [proposals]: titulo y descripcion propuestos para cada capa nueva, por rama. [focus]: la
 * capa desde cuyo menu se abrio, que recibe el foco.
 */
internal class PublishDialog(
    project: Project,
    state: StackState.Loaded,
    proposals: Map<String, PrText>,
    private val github: GitHubRepo?,
    focus: String?,
) : DialogWrapper(project) {

    private val snapshot = state.snapshot

    /** De abajo arriba, en el orden de gh-stack: es el orden de los comandos. */
    private val rows: List<LayerRow> = snapshot.layers.filter { !it.isMerged }.map { layer ->
        val details = state.detailsOf(layer)
        LayerRow(layer, LayerStatus.of(layer, details), details?.title, layer.pr?.let(::urlOf), proposals[layer.branch])
    }
    private val focused = rows.firstOrNull { it.layer.branch == focus }
    private val preview = CommandPreview()

    init {
        title = message("publish.title")
        setOKButtonText(message("publish.ok"))
        rows.forEach { it.onChange(::update) }
        init()
        update()
    }

    /** Lo elegido, de abajo arriba. */
    fun layers(): List<PublishLayer> = rows.map(LayerRow::choice)

    override fun createCenterPanel(): JComponent = panel {
        row { cell(WrappingText(message("publish.intro"))).align(AlignX.FILL).resizableColumn() }
        if (rows.count { it.choosable } > 1) {
            row {
                cell(ActionLink(message("publish.all.ready")) { setAll(true) })
                cell(ActionLink(message("publish.all.drafts")) { setAll(false) })
            }
        }
        row {
            val graph = Graph().apply {
                rows.asReversed().forEachIndexed { index, row -> add(row.component(lineAbove = index > 0)) }
                val base = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
                    isOpaque = false
                    add(JLabel(snapshot.trunk))
                    add(Chip.status(message("layer.trunk"), StackColors.DRAFT))
                }
                add(graphRow(base, null, null, Rail.Node(Rail.Shape.SQUARE, StackColors.DRAFT), lineAbove = rows.isNotEmpty(), lineBelow = false))
            }
            cell(ScrollPaneFactory.createScrollPane(graph, true)).align(Align.FILL)
        }.resizableRow().topGap(TopGap.SMALL)
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        (focused ?: rows.lastOrNull { it.isNew } ?: rows.lastOrNull { it.choosable })?.focusTarget

    override fun doValidateAll(): List<ValidationInfo> =
        rows.mapNotNull { row -> row.titleField?.takeIf { it.text.isBlank() }?.let { ValidationInfo(message("publish.title.empty", row.layer.branch), it) } }

    // gh-stack no siempre guarda la URL: se reconstruye, o el numero si no se sabe el repositorio.
    private fun urlOf(pr: PrRef): String =
        pr.url.ifEmpty { github?.let { "https://${it.host}/${it.owner}/${it.name}/pull/${pr.number}" } ?: pr.number.toString() }

    private fun setAll(ready: Boolean) {
        rows.filter { it.choosable }.forEach { it.readyBox.isSelected = ready }
    }

    private fun update() {
        rows.forEach(LayerRow::updateChange)
        preview.show(PublishPlans.calls(layers(), github).map { shorten(it.args) })
    }

    /**
     * Una capa. Se elige draft o listo si es nueva o si su PR esta abierto con estado conocido;
     * cerrada, en la cola de merge o sin datos de GitHub, se deja como esta y se dice por que.
     */
    private class LayerRow(
        val layer: StackLayer,
        private val status: LayerStatus,
        private val prTitle: String?,
        private val prUrl: String?,
        proposal: PrText?,
    ) {

        val isNew: Boolean = layer.pr == null
        val choosable: Boolean = isNew || status == LayerStatus.DRAFT || status == LayerStatus.READY

        /** Nuevas: draft, como las crea `--auto`. Las demas, como estan. */
        val readyBox = JBCheckBox(message("publish.ready"), status == LayerStatus.READY)
        val titleField: JBTextField? = if (isNew) JBTextField(proposal?.title ?: layer.branch, 40) else null
        private val bodyArea: JBTextArea? = if (isNew) JBTextArea(proposal?.body.orEmpty(), 4, 40).apply { lineWrap = true; wrapStyleWord = true } else null
        private val change = JBLabel().apply { font = JBFont.small() }

        val focusTarget: JComponent get() = titleField ?: readyBox

        fun choice() = PublishLayer(
            branch = layer.branch,
            prUrl = prUrl,
            isDraft = when (status) {
                LayerStatus.DRAFT -> true
                LayerStatus.READY -> false
                else -> null
            },
            ready = if (choosable) readyBox.isSelected else null,
            text = titleField?.let { PrText(it.text.trim(), bodyArea?.text?.trim().orEmpty()) },
        )

        fun onChange(listener: () -> Unit) {
            readyBox.addItemListener { listener() }
            listOfNotNull<JTextComponent>(titleField, bodyArea).forEach {
                it.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = listener()
                })
            }
        }

        /** «→ Ready» o «→ Draft» junto al estado, solo si cambia. */
        fun updateChange() {
            val ready = readyBox.isSelected
            change.isVisible = !isNew && choosable && ready != (status == LayerStatus.READY)
            change.text = message(if (ready) "publish.change.ready" else "publish.change.draft")
            change.foreground = if (ready) StackColors.READY else StackColors.DRAFT
        }

        fun component(lineAbove: Boolean): JComponent {
            val name = JLabel(layer.branch).apply { if (layer.isCurrent) font = JBFont.label().asBold() }
            val chip = if (isNew) Chip.status(message("publish.new"), StackColors.READY) else Chip.status(status.text, status.color)
            val left = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
                isOpaque = false
                add(name)
                layer.pr?.let { add(secondary("#${it.number}")) }
                add(chip)
                add(change)
            }
            val header = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(left, BorderLayout.CENTER)
                add(if (choosable) readyBox else secondary(message(unchangedKey())), BorderLayout.EAST)
            }
            val node = Rail.Node(if (layer.isCurrent) Rail.Shape.FILLED else Rail.Shape.RING, status.color)
            return graphRow(header, details(), null, node, lineAbove, lineBelow = true)
        }

        /** Nuevas: titulo y descripcion. Las demas: el titulo que ya tienen en GitHub. */
        private fun details(): JComponent? {
            val title = titleField
            val body = bodyArea
            if (title == null || body == null) return prTitle?.takeIf { it.isNotBlank() }?.let { secondary(it).apply { font = JBFont.small() } }
            return panel {
                row(message("publish.pr.title")) { cell(title).align(AlignX.FILL) }
                row(message("publish.pr.body")) { scrollCell(body).align(AlignX.FILL) }
            }.apply { isOpaque = false }
        }

        private fun unchangedKey(): String = when (status) {
            LayerStatus.MERGED -> "publish.unchanged.merged"
            LayerStatus.CLOSED -> "publish.unchanged.closed"
            LayerStatus.QUEUED -> "publish.unchanged.queued"
            else -> "publish.unchanged.unknown"
        }

        private fun secondary(text: String) = JLabel(text).apply { foreground = NamedColorUtil.getInactiveTextColor() }
    }

    /** Las filas del grafo. Sigue el ancho del dialogo y, si la pila es alta, se desplaza. */
    private class Graph : JPanel(VerticalLayout(0)), Scrollable {

        init {
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension =
            preferredSize.let { Dimension(it.width, minOf(it.height, JBUI.scale(MAX_HEIGHT))) }

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val MAX_HEIGHT = 420
        const val BODY_PREVIEW = 40

        /** En la vista previa, una descripcion larga se abrevia: el Log la ensena entera. */
        fun shorten(args: List<String>): List<String> = args.mapIndexed { index, arg ->
            val long = arg.length > BODY_PREVIEW || '\n' in arg
            if (index > 0 && args[index - 1] == "--body" && long) arg.lineSequence().first().take(BODY_PREVIEW).trimEnd() + "…" else arg
        }
    }
}
