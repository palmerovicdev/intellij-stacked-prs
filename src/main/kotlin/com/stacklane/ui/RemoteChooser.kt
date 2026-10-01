package com.stacklane.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import com.stacklane.StacklaneBundle.message
import git4idea.repo.GitRepository
import javax.swing.JComponent

/** Se pregunta una vez por proyecto, cuando gh-stack no sabe a que remoto publicar. */
internal object RemoteChooser {

    fun choose(project: Project, repository: GitRepository): String? {
        val names = repository.remotes.map { it.name }.sorted()
        if (names.isEmpty()) return null
        val combo = ComboBox(names.toTypedArray()).apply {
            selectedItem = names.firstOrNull { it == "origin" } ?: names.first()
            showHelp(Help(message("help.remote"), listOf("--remote REMOTE")))
        }
        val dialog = object : DialogWrapper(project) {
            init {
                title = message("remote.title")
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row { text(message("remote.text")) }
                row(message("remote.label")) { cell(combo) }
            }
        }
        // show() y no showAndGet(): en un dialogo modal es lo mismo, y show() es lo que pueden
        // interceptar los tests (showAndGet() falla antes en el IDE headless de los tests).
        dialog.show()
        return if (dialog.isOK) combo.selectedItem as? String else null
    }
}
