package com.stacklane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class LabelStyleTest {

    private val bug = Color(0xD73A4A)
    private val documentation = Color(0x0075CA)
    private val enhancement = Color(0xA2EEEF)
    private val wontfix = Color(0xFFFFFF)

    @Test
    fun `dark theme tints the fill and lightens the text of the same hue`() {
        val style = LabelStyle.of(bug, dark = true)
        assertEquals(bug.rgb and 0xFFFFFF, style.fill.rgb and 0xFFFFFF)
        assertTrue("fill should be faint", style.fill.alpha < 64)
        assertTrue("text should be lighter than the label", lightness(style.text) > lightness(bug))
        assertTrue("text should keep the hue", style.text.red > style.text.green && style.text.red > style.text.blue)
        assertNotNull(style.outline)
    }

    @Test
    fun `dark theme leaves an already light label as is`() {
        assertEquals(wontfix, LabelStyle.of(wontfix, dark = true).text)
    }

    @Test
    fun `light theme is the solid color with white or black text`() {
        val dark = LabelStyle.of(documentation, dark = false)
        assertEquals(documentation, dark.fill)
        assertEquals(Color.WHITE, dark.text)
        assertNull(dark.outline)

        val light = LabelStyle.of(enhancement, dark = false)
        assertEquals(Color.BLACK, light.text)
        assertNull(light.outline)
    }

    @Test
    fun `light theme outlines an almost white label`() {
        val style = LabelStyle.of(wontfix, dark = false)
        assertEquals(Color.BLACK, style.text)
        assertNotNull(style.outline)
    }

    private fun lightness(c: Color): Float = (c.red * 0.2126f + c.green * 0.7152f + c.blue * 0.0722f) / 255f
}
