package com.upstack.flow

import com.intellij.openapi.util.Disposer
import com.upstack.ui.PullRequestsPanel
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseWheelEvent
import javax.swing.JViewport

class PullRequestsFilterRowScrollTest : FlowTestCase() {
    private fun layoutAll(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layoutAll) } }
    private fun walk(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::walk) ?: emptyList())

    fun `test a narrow window keeps the chips at their width and the wheel scrolls the row sideways`() {
        val panel = onEdt { PullRequestsPanel(project).also { Disposer.register(testRootDisposable, it) } }
        onEdt {
            panel.setSize(150, 600)
            repeat(3) { layoutAll(panel) }
            val chip = chips(panel).first()
            val viewport = walk(panel).filterIsInstance<JViewport>().first { chips(it.view ?: return@first false).isNotEmpty() }
            val row = viewport.view
            assertTrue("row ${row.width} should be wider than the viewport ${viewport.width}", row.width > viewport.width)
            assertEquals(0, viewport.viewPosition.x)
            val wheel = MouseWheelEvent(chip, MouseWheelEvent.MOUSE_WHEEL, 0L, 0, 5, 5, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 3)
            viewport.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
            assertTrue("the row did not move: ${viewport.viewPosition}", viewport.viewPosition.x > 0)
        }
    }
}
