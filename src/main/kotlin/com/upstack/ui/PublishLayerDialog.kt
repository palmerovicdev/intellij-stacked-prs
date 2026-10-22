package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.upstack.UpstackBundle.message
import com.upstack.stack.GitHubRepo
import com.upstack.stack.PrText
import com.upstack.stack.PublishPlans
import com.upstack.stack.StackLayer
import com.upstack.stack.StackSnapshot
import javax.swing.JComponent

/**
 * *Publish as Draft…* / *Publish Ready for Review…* desde el menu de una capa sin PR: su titulo
 * (el nombre de la rama) y su descripcion (de sus commits), que se pueden cambiar, y los comandos
 * de [PublishPlans.layerPlan].
 * Es la confirmacion.
 */
internal class PublishLayerDialog(
    project: Project,
    private val snapshot: StackSnapshot,
    private val layer: StackLayer,
    proposal: PrText,
    private val ready: Boolean,
    private val github: GitHubRepo?,
    /** La rama actual: si hay que rehacer la pila, se vuelve a ella. */
    private val current: String?,
) : DialogWrapper(project) {

    private val titleField = JBTextField(proposal.title, 48)
    private val bodyArea = JBTextArea(proposal.body, 6, 48).apply { lineWrap = true; wrapStyleWord = true }
    private val below = PublishPlans.unpublishedBelow(snapshot, layer)
    private val above = PublishPlans.unpublishedAbove(snapshot, layer)
    private val preview = CommandPreview()

    val text: PrText get() = PrText(titleField.text.trim(), bodyArea.text.trim())

    init {
        title = message(if (ready) "publish.layer.title.ready" else "publish.layer.title.draft", layer.branch)
        setOKButtonText(message(if (ready) "publish.layer.ok.ready" else "publish.layer.ok.draft"))
        titleField.onChange(::update)
        bodyArea.onChange(::update)
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("publish.layer.base")) { label("${layer.branch} → ${snapshot.parentOf(layer)}") }
        row(message("title.field")) { cell(titleField).align(AlignX.FILL) }
        row(message("publish.layer.body")) { cell(ScrollPaneFactory.createScrollPane(bodyArea)).align(Align.FILL) }.resizableRow()
        if (below.isNotEmpty()) {
            row {
                cell(WrappingText(message("publish.layer.below", below.size, below.joinToString(", ") { it.branch }), AllIcons.General.Information))
                    .align(AlignX.FILL).resizableColumn()
            }
        }
        if (above.isNotEmpty()) {
            row {
                cell(WrappingText(message("publish.layer.above", above.size, above.joinToString(", ") { it.branch }), AllIcons.General.Information))
                    .align(AlignX.FILL).resizableColumn()
            }
        }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    // Publica: los tests comprueban que lo propuesto vale.
    public override fun doValidate(): ValidationInfo? =
        if (text.title.isEmpty()) ValidationInfo(message("title.empty"), titleField) else null

    private fun update() {
        preview.showLines(Help.of(PublishPlans.layerPlan(snapshot, layer, text, ready, github, current).calls))
    }
}
