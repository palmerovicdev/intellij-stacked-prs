package com.stacklane.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.stacklane.StacklaneBundle.message
import com.stacklane.stack.StackService

/** La ventana Stacks: la pila (pestana Stack) y los comandos ejecutados (pestana Log). */
class StackToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = StackService.getInstance(project)
        val contents = toolWindow.contentManager
        val factory = ContentFactory.getInstance()

        val panel = StackPanel(project)
        val stack = factory.createContent(panel, message("toolwindow.tab.stack"), false)
        stack.setDisposer(panel)
        contents.addContent(stack)

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
        val logPanel = SimpleToolWindowPanel(false, true).apply {
            setContent(console.component)
            val toolbar = ActionManager.getInstance()
                .createActionToolbar(LOG_TOOLBAR_PLACE, DefaultActionGroup(*console.createConsoleActions()), false)
            toolbar.targetComponent = console.component
            setToolbar(toolbar.component)
        }
        val log = factory.createContent(logPanel, message("toolwindow.tab.log"), false)
        log.setDisposer {
            service.log.detach(console)
            Disposer.dispose(console)
        }
        service.log.attach(console)
        contents.addContent(log)
    }

    private companion object {
        const val LOG_TOOLBAR_PLACE = "StacklaneLogToolbar"
    }
}

object StackToolWindow {

    const val ID = "Stacklane"

    fun show(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(ID)?.activate(null)
    }

    fun showLog(project: Project) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.activate {
            val contents = window.contentManager
            contents.getContent(1)?.let(contents::setSelectedContent)
        }
    }
}
