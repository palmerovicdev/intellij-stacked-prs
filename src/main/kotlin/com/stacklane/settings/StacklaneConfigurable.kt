package com.stacklane.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCli

/** Settings | Tools | Stacklane. */
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
        }
    }
}
