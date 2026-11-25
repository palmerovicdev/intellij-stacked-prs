package com.upstack.stack

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class PrTemplatesTest {

    @Test
    fun `a template fills the branch and the commits`() {
        val template = PrTemplate("Feature", "feat: {branch}", "## What\n{commits}\n\n## Why\n")
        assertEquals(
            PrText("feat: fix/leaked-secrets", "## What\n- a\n- b\n\n## Why"),
            template.render("fix/leaked-secrets", "- a\n- b"),
        )
    }

    @Test
    fun `without commits the placeholder is empty and other braces stay`() {
        assertEquals(PrText("t", "x {other}"), PrTemplate("n", "t", "{commits}x {other}").render("b"))
    }

    @Test
    fun `the file of a layer is under dot upstack and the title is the first heading`() {
        assertEquals(Path.of("/repo/.upstack/fix/a.md"), PrFile.path(Path.of("/repo"), "fix/a"))
        assertEquals(".upstack/fix/a.md", PrFile.display("fix/a"))
        assertEquals(PrText("Fix a", "Body line\n\nmore"), PrFile.parse("\n# Fix a\n\nBody line\n\nmore\n"))
        assertEquals(PrText("Fix a", ""), PrFile.parse("# Fix a"))
        assertEquals(PrText("Fix a", "x"), PrFile.parse("# Fix a\r\n\r\nx\r\n"))
    }

    @Test
    fun `a file without a heading has no title`() {
        assertEquals(PrText("", "just text"), PrFile.parse("just text\n"))
        assertEquals(PrText("", ""), PrFile.parse("  \n"))
    }

    @Test
    fun `an empty heading is an empty title and a second level heading is not a title`() {
        assertEquals(PrText("", "body"), PrFile.parse("#\n\nbody"))
        assertEquals(PrText("", "## Notes"), PrFile.parse("## Notes"))
    }

    @Test
    fun `format and parse are inverse`() {
        for (text in listOf(PrText("T", ""), PrText("T", "a\n\nb"))) assertEquals(text, PrFile.parse(PrFile.format(text)))
    }
}
