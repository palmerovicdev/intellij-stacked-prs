package com.stacklane.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCli
import com.stacklane.ui.EnvironmentPanel
import com.stacklane.ui.Helps
import com.stacklane.ui.showHelp

/** Settings | Version Control | Stacklane. Los ajustes son globales; el proyecto es para comprobar el entorno. */
class StacklaneConfigurable(private val project: Project) : BoundConfigurable(message("settings.title")) {

    private var environment: EnvironmentPanel? = null

    override fun createPanel(): DialogPanel {
        val settings = StacklaneSettings.getInstance()
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
            row(message("settings.final.label")) {
                textField()
                    .bindText(settings::finalLabel)
                    .comment(message("settings.final.label.comment"))
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

    override fun disposeUIResources() {
        environment?.dispose()
        environment = null
        super.disposeUIResources()
    }
}
