package com.upstack.ui

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Antes de que el dialogo reparta el ancho, el area de texto recibe un ancho negativo (el del
 * panel, 0, menos el icono). Partido a ese ancho, el texto pedia una linea por letra: miles de
 * pixeles de alto, y al aparecer el aviso el dialogo subia hasta arriba de la pantalla.
 */
class WrappingTextTest : BasePlatformTestCase() {

    private val note = "feat/websites is on origin/feat/websites: a local branch tracking it is created first, " +
        "and gh stack adds it as it is, without rebasing it on feat/websites-editor-button-actions-2."

    fun `test without a width yet it asks for one line, not one per letter`() {
        val text = WrappingText(note, AllIcons.General.Information)
        text.setSize(0, 0)
        text.doLayout()
        val line = text.getFontMetrics(text.font).height
        assertTrue("asked for ${text.preferredSize.height}px, a line is ${line}px", text.preferredSize.height <= 3 * line)
    }

    fun `test with a real width it wraps to the lines it needs`() {
        val text = WrappingText(note, AllIcons.General.Information)
        text.setSize(200, 10)
        text.doLayout()
        val line = text.getFontMetrics(text.font).height
        assertTrue("asked for ${text.preferredSize.height}px at 200px", text.preferredSize.height >= 3 * line)
    }
}
