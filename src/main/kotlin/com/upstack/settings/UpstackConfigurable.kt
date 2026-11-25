package com.upstack.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.upstack.UpstackBundle.message
import com.upstack.gh.GhCli
import com.upstack.gh.GhCommands
import com.upstack.stack.GitHubRepo
import com.upstack.ui.EnvironmentPanel
import com.upstack.ui.Help
import com.upstack.ui.Helps
import com.upstack.ui.showHelp
import java.awt.Color

/** Settings | Version Control | Upstack. Los ajustes son globales; el proyecto es para comprobar el entorno. */
class UpstackConfigurable(private val project: Project) : BoundConfigurable(message("settings.title")) {

    private var environment: EnvironmentPanel? = null

    override fun createPanel(): DialogPanel {
        val settings = UpstackSettings.getInstance()
        val detected = GhCli.locate()?.toString()
        lateinit var ghPath: Cell<TextFieldWithBrowseButton>
        val environment = EnvironmentPanel(
            project,
            ghPath = { ghPath.component.text },
            // Solo la ruta: el resto del formulario sigue pendiente de Apply.
            applyGhPath = { settings.ghPath = ghPath.component.text },
        ).also { this.environment = it }
        return panel {
            row(message("settings.gh.path")) {
                ghPath = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(message("settings.gh.path.title")))
                    .bindText(settings::ghPath)
                    .align(AlignX.FILL)
                    .comment(
                        if (detected != null) message("settings.gh.path.detected", detected)
                        else message("settings.gh.path.missing")
                    )
            }
            group(message("settings.final.group")) {
                row(message("settings.final.label")) {
                    textField()
                        .bindText(settings::finalLabel)
                        .validationOnInput { field ->
                            if (field.text.trim().length > UpstackSettings.MAX_LABEL_NAME) error(message("settings.final.label.long", UpstackSettings.MAX_LABEL_NAME))
                            else null
                        }
                        .comment(message("settings.final.label.comment"))
                }
                row(message("settings.final.color")) {
                    // El selector solo da colores validos; se guarda en hex, como lo pide gh label create.
                    cell(ColorPanel())
                        .bind(
                            { panel -> panel.selectedColor?.let { hex(it) } ?: settings.finalLabelColor },
                            { panel, value -> panel.selectedColor = Color(value.toInt(16)) },
                            MutableProperty({ settings.finalLabelColor }, { settings.finalLabelColor = it }),
                        )
                        .comment(message("settings.final.color.comment"))
                }
                row(message("settings.final.description")) {
                    textField()
                        .bindText(settings::finalLabelDescription)
                        .align(AlignX.FILL)
                        .validationOnInput { field ->
                            if (field.text.trim().length > UpstackSettings.MAX_LABEL_DESCRIPTION) {
                                error(message("settings.final.label.long", UpstackSettings.MAX_LABEL_DESCRIPTION))
                            } else null
                        }
                        .comment(message("settings.final.description.comment"))
                }
                row {
                    checkBox(message("settings.final.before.merge"))
                        .bindSelected(settings::finalLabelBeforeMerge)
                        .applyToComponent { showHelp(beforeMergeHelp()) }
                        .comment(message("settings.final.before.merge.comment"))
                }
            }
            row(message("settings.restack")) {
                comboBox(RestackMode.entries, textListCellRenderer("") { mode ->
                    when (mode) {
                        RestackMode.ASK -> message("settings.restack.ask")
                        RestackMode.ALWAYS -> message("settings.restack.always")
                        RestackMode.NEVER -> message("settings.restack.never")
                    }
                })
                    .bindItem(settings::restackAfterCommit.toNullableProperty())
                    .comment(message("settings.restack.comment"))
            }
            group(message("settings.environment")) {
                row {
                    button(message("settings.environment.check")) { environment.check() }
                        .applyToComponent { showHelp(Helps.checkEnvironment()) }
                }
                row { cell(environment).align(AlignX.FILL) }
            }
        }
    }

    /** Lo que ejecuta Merge Stack con la opcion encendida, con los nombres de ejemplo en mayusculas. */
    private fun beforeMergeHelp(): Help {
        val example = GitHubRepo("github.com", "OWNER", "REPO")
        return Help(
            message("help.final.before.merge"),
            listOf(
                Help.gh(GhCommands.createLabel(example, "NAME", "RRGGBB", "TEXT")),
                Help.gh(GhCommands.editLabels("PR", listOf("NAME"), emptyList())),
                Help.gh(GhCommands.editLabels("OTHER_PR", emptyList(), listOf("NAME"))),
                Help.gh(GhCommands.merge(0, null).map { if (it == "0") "PR_NUMBER" else it }),
            ),
        )
    }

    /** `RRGGBB`, como lo guarda [UpstackSettings.finalLabelColor]. */
    private fun hex(color: Color): String = "%02X%02X%02X".format(color.red, color.green, color.blue)

    override fun disposeUIResources() {
        environment?.dispose()
        environment = null
        super.disposeUIResources()
    }
}
