package com.upstack.ui

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
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCommands
import com.upstack.stack.GitHubRepo
import com.upstack.stack.PrRef
import com.upstack.stack.PrFile
import com.upstack.stack.PrText
import com.upstack.stack.PublishLayer
import com.upstack.stack.PublishPlans
import com.upstack.stack.StackLayer
import com.upstack.stack.StackState
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * *Publish Stack…*: lo que hace el editor interactivo de `gh stack submit`, que el plugin no
 * puede abrir. Una fila por capa activa, de arriba abajo como en la ventana Stacks: las
 * nuevas con el titulo y la descripcion de su PR (el nombre de la rama y sus commits) y
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
    /** La raiz del repositorio, donde estan los md de las capas (`.upstack/<rama>.md`). */
    root: Path? = null,
) : DialogWrapper(project) {

    private val snapshot = state.snapshot

    /** De abajo arriba, en el orden de gh-stack: es el orden de los comandos. */
    private val rows: List<LayerRow> = snapshot.layers.filter { !it.isMerged }.map { layer ->
        val details = state.detailsOf(layer)
        LayerRow(
            layer, LayerStatus.of(layer, details), details?.title, layer.pr?.let(::urlOf), proposals[layer.branch],
            project, root, onOpen = { close(OPEN_EXIT_CODE) },
        )
    }
    private val focused = rows.firstOrNull { it.layer.branch == focus }
    private val preview = CommandPreview()

    init {
        title = message("publish.title")
        setOKButtonText(message("publish.ok"))
        rows.forEach { it.onChange(::update) }
        rows.forEach { it.readyBox.showHelp(readyHelp(it)) }
        init()
        update()
    }

    /** Se cerro con *Open in Editor* en alguna capa: nada se publica y su md se abre en el IDE (ver [openFile]). */
    val openedFile: Boolean get() = exitCode == OPEN_EXIT_CODE

    fun openFile() = rows.firstNotNullOfOrNull { it.openRequested() }

    /** Lo elegido, de abajo arriba. */
    fun layers(): List<PublishLayer> = rows.map(LayerRow::choice)

    override fun createCenterPanel(): JComponent = panel {
        row { cell(WrappingText(message("publish.intro"))).align(AlignX.FILL).resizableColumn() }
        if (rows.count { it.choosable } > 1) {
            row {
                cell(ActionLink(message("publish.all.ready")) { setAll(true) }.apply { showHelp(Help(message("help.publish.all.ready"))) })
                cell(ActionLink(message("publish.all.drafts")) { setAll(false) }.apply { showHelp(Help(message("help.publish.all.drafts"))) })
            }
        }
        row {
            val graph = DialogGraph().apply {
                rows.asReversed().forEachIndexed { index, row -> add(row.component(lineAbove = index > 0)) }
                addBase(snapshot.trunk, lineAbove = rows.isNotEmpty())
            }
            cell(ScrollPaneFactory.createScrollPane(graph, true)).align(Align.FILL)
        }.resizableRow().topGap(TopGap.SMALL)
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        (focused ?: rows.lastOrNull { it.isNew } ?: rows.lastOrNull { it.choosable })?.focusTarget

    // Publica: los tests comprueban lo propuesto.
    public override fun doValidateAll(): List<ValidationInfo> =
        rows.mapNotNull { row -> row.editor?.problem?.let { ValidationInfo(message("publish.title.problem", row.layer.branch, it), row.editor.focusTarget) } }

    // gh-stack no siempre guarda la URL: se reconstruye, o el numero si no se sabe el repositorio.
    private fun urlOf(pr: PrRef): String =
        pr.url.ifEmpty { github?.let { "https://${it.host}/${it.owner}/${it.name}/pull/${pr.number}" } ?: pr.number.toString() }

    /** Lo que ejecuta la casilla: la de una capa nueva, marcarla lista; la de un PR que ya existe, en los dos sentidos. */
    private fun readyHelp(row: LayerRow): Help {
        val url = row.prUrl
        return if (url == null) Help(message("help.publish.ready.new"), listOf(Help.gh(GhCommands.markReady(row.layer.branch, github))))
        else Help(message("help.publish.ready.existing"), listOf(Help.gh(GhCommands.markReady(url)), Help.gh(GhCommands.markDraft(url))))
    }

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
        val prUrl: String?,
        proposal: PrText?,
        project: Project,
        root: Path?,
        onOpen: () -> Unit,
    ) {

        val isNew: Boolean = layer.pr == null
        val choosable: Boolean = isNew || status == LayerStatus.DRAFT || status == LayerStatus.READY

        /** Nuevas: draft, como las crea `--auto`. Las demas, como estan. */
        val readyBox = JBCheckBox(message("publish.ready"), status == LayerStatus.READY)
        val editor: PrTextEditor? = if (isNew) {
            PrTextEditor(
                project, PrText(proposal?.title ?: layer.branch, proposal?.body.orEmpty()), layer.branch, proposal?.body.orEmpty(),
                root?.let { PrFile.path(it, layer.branch) }, 4, onOpen,
            )
        } else null
        private val change = JBLabel().apply { font = JBFont.small() }

        val focusTarget: JComponent get() = editor?.focusTarget ?: readyBox

        /** El md de esta capa, si fue ella la que cerro el dialogo con *Open in Editor*: lo abre. */
        fun openRequested(): Unit? = editor?.takeIf { it.inFileMode && it.openClicked }?.openInEditor()

        fun choice() = PublishLayer(
            branch = layer.branch,
            prUrl = prUrl,
            isDraft = when (status) {
                LayerStatus.DRAFT -> true
                LayerStatus.READY -> false
                else -> null
            },
            ready = if (choosable) readyBox.isSelected else null,
            text = editor?.text,
        )

        fun onChange(listener: () -> Unit) {
            readyBox.addItemListener { listener() }
            editor?.onChange(listener)
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
            val editor = editor ?: return prTitle?.takeIf { it.isNotBlank() }?.let { secondary(it).apply { font = JBFont.small() } }
            return panel { row { cell(editor.component).align(AlignX.FILL) } }.apply { isOpaque = false }
        }

        private fun unchangedKey(): String = when (status) {
            LayerStatus.MERGED -> "publish.unchanged.merged"
            LayerStatus.CLOSED -> "publish.unchanged.closed"
            LayerStatus.QUEUED -> "publish.unchanged.queued"
            else -> "publish.unchanged.unknown"
        }

        private fun secondary(text: String) = JLabel(text).apply { foreground = NamedColorUtil.getInactiveTextColor() }
    }

    private companion object {
        const val OPEN_EXIT_CODE = NEXT_USER_EXIT_CODE
        const val BODY_PREVIEW = 40

        /** En la vista previa, una descripcion larga se abrevia: el Log la ensena entera. */
        fun shorten(args: List<String>): List<String> = args.mapIndexed { index, arg ->
            val long = arg.length > BODY_PREVIEW || '\n' in arg
            if (index > 0 && args[index - 1] == "--body" && long) arg.lineSequence().first().take(BODY_PREVIEW).trimEnd() + "…" else arg
        }
    }
}
