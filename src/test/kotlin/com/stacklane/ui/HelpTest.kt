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

    // Cinco pixeles por caracter: con 100 de ancho maximo caben 20.
    private val style = TooltipStyle(code = "#eeeeee", link = "#0969da", maxWidth = 100) { text, _ -> text.length * 5 }

    @Test
    fun `html puts each command in its own code block`() {
        val html = Help("Push the stack", listOf("gh stack push", "gh stack sync")).html(style)
        assertTrue(html, html.startsWith("<html>"))
        assertTrue(html, html.contains("Push the stack<div"))
        assertTrue(html, html.contains("background-color: #eeeeee"))
        assertTrue(html, html.contains("<code>gh stack push</code></div>"))
        assertTrue(html, html.contains("<code>gh stack sync</code></div>"))
    }

    @Test
    fun `html escapes branch names and text`() {
        val html = Help("Check out a<b", listOf(Help.checkout("feat/a&b"))).html(style)
        assertFalse(html, html.contains("a<b"))
        assertTrue(html, html.contains("a&lt;b"))
        assertTrue(html, html.contains("feat/a&amp;b"))
    }

    @Test
    fun `urls look like links`() {
        val url = "https://github.com/acme/shop/pull/13"
        val html = Help("Mark ready", listOf("gh pr ready $url")).html(style)
        assertTrue(html, html.contains("<code>gh pr ready <a href=\"$url\" style=\"color: #0969da\">$url</a></code>"))
    }

    @Test
    fun `only what does not fit is wrapped to the maximum width`() {
        assertFalse(Help("Sync", listOf("gh stack sync")).html(style).contains("width:"))
        // Swing lee los px de CSS como puntos (x1,3): 100 pixeles se escriben como 77.
        assertEquals("width: 77px", style.widthCss)
        assertTrue(Help("Sync the stack with the remote", listOf("gh stack sync")).html(style).contains(style.widthCss))
        assertTrue(Help("Sync", listOf("gh stack sync --remote origin")).html(style).contains(style.widthCss))
    }

    @Test
    fun `commands are quoted as in the terminal`() {
        assertEquals("git checkout feat/a", Help.checkout("feat/a"))
        assertEquals("git checkout 'feat/a b'", Help.checkout("feat/a b"))
    }
}
