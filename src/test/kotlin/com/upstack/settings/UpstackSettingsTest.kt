package com.upstack.settings

import com.intellij.util.xmlb.XmlSerializer
import com.upstack.stack.PrTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstackSettingsTest {

    private fun reload(settings: UpstackSettings): UpstackSettings {
        val xml = XmlSerializer.serialize(settings.state)
        return UpstackSettings().apply { loadState(requireNotNull(XmlSerializer.deserialize(xml, UpstackSettings.Options::class.java))) }
    }

    @Test
    fun `templates and where the text is edited survive saving`() {
        val settings = UpstackSettings()
        settings.prTemplates = listOf(PrTemplate("Feature", "feat: {branch}", "## What\n{commits}"), PrTemplate("Fix", "fix: {branch}", ""))
        settings.prTextInFile = true

        val loaded = reload(settings)

        assertEquals(settings.prTemplates, loaded.prTemplates)
        assertEquals("## What\n{commits}", loaded.prTemplates.first().body)
        assertTrue(loaded.prTextInFile)
    }

    @Test
    fun `a template without a name is not saved and by default the text is edited in the dialog`() {
        val settings = UpstackSettings()
        assertFalse(settings.prTextInFile)
        assertEquals(emptyList<PrTemplate>(), settings.prTemplates)
        settings.prTemplates = listOf(PrTemplate("  ", "t", "b"), PrTemplate(" Keep ", "t", "b"))
        assertEquals(listOf(PrTemplate("Keep", "t", "b")), reload(settings).prTemplates)
    }
}
