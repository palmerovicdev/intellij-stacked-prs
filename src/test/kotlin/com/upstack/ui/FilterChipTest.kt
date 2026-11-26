package com.upstack.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.event.ActionListener

class FilterChipTest : BasePlatformTestCase() {
    private fun chip(vararg values: String) = FilterChip<String>({ DotsIcon(Color.RED) to it }).apply { values.forEach(::addItem) }

    fun `test the chip is as wide as the text it shows and grows with it`() {
        val chip = chip("Bo", "A longer value than the first")
        val short = chip.preferredSize.width
        chip.selectedIndex = 1
        assertTrue("${chip.preferredSize.width} should be wider than $short", chip.preferredSize.width > short)
    }

    fun `test a text over 50 characters stops growing`() {
        // Cut to 49 characters and an ellipsis: 51 and 80 end up the same width.
        assertEquals(chip("x".repeat(51)).preferredSize.width, chip("x".repeat(80)).preferredSize.width)
        assertTrue(chip("x".repeat(30)).preferredSize.width < chip("x".repeat(50)).preferredSize.width)
        assertTrue(chip("x".repeat(80)).preferredSize.width < chip("x".repeat(60) ).preferredSize.width + chip("x".repeat(20)).preferredSize.width)
    }

    fun `test the selection tells its listeners only when it changes`() {
        val chip = chip("a", "b")
        var heard = 0
        chip.addActionListener(ActionListener { heard++ })
        chip.selectedIndex = 0
        assertEquals(0, heard)
        chip.selectedItem = "b"
        assertEquals(1, heard)
        assertEquals(1, chip.selectedIndex)
        chip.selectedItem = "unknown"
        assertEquals(1, chip.selectedIndex)
        assertEquals(1, heard)
    }
}
