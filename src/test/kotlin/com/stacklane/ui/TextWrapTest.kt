package com.stacklane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWrapTest {

    /** Un pixel por letra. */
    private val chars: (String) -> Int = { it.length }

    @Test
    fun `a text that fits stays on one line`() {
        val fit = TextWrap.fit("feat: add flows", 20, 2, chars)
        assertEquals(listOf("feat: add flows"), fit.lines)
        assertFalse(fit.clipped)
    }

    @Test
    fun `wraps by words and the trailing space does not count`() {
        val fit = TextWrap.fit("add initial proposal", 11, 3, chars)
        assertEquals(listOf("add initial", "proposal"), fit.lines)
        assertFalse(fit.clipped)
    }

    @Test
    fun `what does not fit in max lines ends in an ellipsis and says so`() {
        val fit = TextWrap.fit("add initial proposal for flow view", 12, 2, chars)
        assertEquals(listOf("add initial", "proposal fo…"), fit.lines)
        assertTrue(fit.clipped)
    }

    @Test
    fun `a text that fits exactly in max lines is not clipped`() {
        val fit = TextWrap.fit("add initial proposal", 11, 2, chars)
        assertEquals(listOf("add initial", "proposal"), fit.lines)
        assertFalse(fit.clipped)
    }

    @Test
    fun `a word wider than the line is split by letters`() {
        val fit = TextWrap.fit("see feat/website-editor-flows now", 10, 10, chars)
        assertEquals(listOf("see", "feat/websi", "te-editor-", "flows now"), fit.lines)
        assertFalse(fit.clipped)
    }

    @Test
    fun `one line is an ellipsized name`() {
        val fit = TextWrap.fit("feat/website-editor-flows", 10, 1, chars)
        assertEquals(listOf("feat/webs…"), fit.lines)
        assertTrue(fit.clipped)
    }

    @Test
    fun `without a width yet there is nothing to wrap`() {
        val fit = TextWrap.fit("  add initial proposal  ", 0, 2, chars)
        assertEquals(listOf("add initial proposal"), fit.lines)
        assertFalse(fit.clipped)
    }

    @Test
    fun `an empty text has no lines`() {
        assertEquals(emptyList<String>(), TextWrap.fit("   ", 10, 2, chars).lines)
    }
}
