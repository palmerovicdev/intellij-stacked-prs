package com.upstack.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CollectionListModel
import com.upstack.stack.*
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JLabel
import javax.swing.SwingUtilities

class PullRequestRowRendererTest : BasePlatformTestCase() {
    fun `test narrow list renders metadata and wrapped chips without overflow`() {
        val details = PrDetails(
            1365, "A very long title about choosing columns for the website editor", "https://github.com/a/b/pull/1365",
            PrState.OPEN, false, ReviewDecision.CHANGES_REQUESTED, ChecksState.FAILURE,
            listOf(PrLabel("website-editor", "8250df"), PrLabel("bug", "d73a4a")), "dev", PrSize(120, 30, 4), true,
        )
        val pr = OpenPullRequest(details.number, details.title, details.url, "feature/some-very-long-branch", "dev", false,
            "victor", "2026-11-25T10:00:00Z", details)
        val model = CollectionListModel(listOf(pr))
        val list = PullRequestList(model)
        list.setSize(300, 300)
        val cell = list.cellRenderer.getListCellRendererComponent(list, pr, 0, false, false)
        assertTrue("cell width ${cell.preferredSize.width} exceeds list width", cell.preferredSize.width <= list.width)
        val labels = descendants(cell).filterIsInstance<JLabel>().map { it.text.orEmpty() }
        assertTrue(labels.any { it.contains("#1365") && it.contains("victor") })
        assertTrue(labels.any { it.contains("…") })
        assertTrue(labels.any { it.contains("website-editor") })
        assertTrue(labels.any { it.contains("Checks") })
        assertTrue(labels.any { it.contains("Changes requested") })
        assertTrue(list.scrollableTracksViewportWidth)
        val bounds = requireNotNull(list.getCellBounds(0, 0))
        cell.setBounds(0, 0, bounds.width, bounds.height)
        fun layout(component: Component) {
            if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
        }
        layout(cell)
        val checks = descendants(cell).single { Chip.isChecks(it) }
        val position = SwingUtilities.convertPoint(checks, checks.width / 2, checks.height / 2, cell)
        assertEquals(pr, list.checksAt(Point(bounds.x + position.x, bounds.y + position.y)))
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

}
