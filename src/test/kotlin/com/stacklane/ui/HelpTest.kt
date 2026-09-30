package com.stacklane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTest {

    @Test
    fun `plain text keeps the commands on the same line`() {
        assertEquals("Sync the stack", Help("Sync the stack").plain)
        assertEquals(
            "Rebase upstack (git checkout feat/a, gh stack rebase --upstack --no-trunk)",
            Help("Rebase upstack", listOf("git checkout feat/a", "gh stack rebase --upstack --no-trunk")).plain,
        )
    }

    @Test
    fun `html puts each command on its own line in code`() {
        val html = Help("Push the stack", listOf("gh stack push")).html
        assertTrue(html, html.startsWith("<html>"))
        assertTrue(html, html.contains("Push the stack<br/><code>gh stack push</code>") || html.contains("Push the stack<br><code>gh stack push</code>"))
    }

    @Test
    fun `html escapes branch names and text`() {
        val html = Help("Check out a<b", listOf(Help.checkout("feat/a&b"))).html
        assertFalse(html, html.contains("a<b"))
        assertTrue(html, html.contains("a&lt;b"))
        assertTrue(html, html.contains("feat/a&amp;b"))
    }

    @Test
    fun `commands are quoted as in the terminal`() {
        assertEquals("git checkout feat/a", Help.checkout("feat/a"))
        assertEquals("git checkout 'feat/a b'", Help.checkout("feat/a b"))
    }
}
