package com.stacklane.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCli

/** Settings | Version Control | Stacklane. */
class StacklaneConfigurable : BoundConfigurable(message("settings.title")) {

    override fun createPanel(): DialogPanel {
        val settings = StacklaneSettings.getInstance()
        val detected = GhCli.locate()?.toString()
        return panel {
            row(message("settings.gh.path")) {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(message("settings.gh.path.title")))
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
        }
    }
}
