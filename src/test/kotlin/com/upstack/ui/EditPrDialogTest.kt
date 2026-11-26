package com.upstack.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container

class EditPrDialogTest : BasePlatformTestCase() {
    fun `test description is not repeated below the editor`() {
        val dialog = EditPrDialog(project, 12, "https://github.com/a/b/pull/12", "Title", "Original", null, null)
        try {
            val center = dialog.createCenterPanel()
            val body = "A distinctive description\nwith several lines"
            dialog.editor.bodyArea.text = body
            assertTrue("The dialog must not repeat the description below the editor", descendants(center).none { it is CommandPreview })
            assertEquals(body, dialog.newBody)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test description editor grows when its dialog is resized`() {
        val dialog = EditPrDialog(project, 12, "https://github.com/a/b/pull/12", "Title", "Body", null, null)
        try {
            val center = dialog.createCenterPanel()
            fun size(width: Int, height: Int): Pair<Int, Int> {
                center.setSize(width, height)
                fun layout(component: Component) {
                    if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
                }
                layout(center)
                return dialog.editor.bodyArea.parent.width to dialog.editor.bodyArea.parent.height
            }
            val small = size(600, 400)
            val large = size(900, 700)
            assertTrue("Description width did not grow: $small → $large", large.first > small.first)
            assertTrue("Description height did not grow: $small → $large", large.second > small.second)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())
}
