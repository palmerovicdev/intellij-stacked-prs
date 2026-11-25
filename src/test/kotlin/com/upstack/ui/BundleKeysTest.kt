package com.upstack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Una clave repetida en el bundle no falla: gana la ultima y una opcion muestra la ayuda de otra. */
class BundleKeysTest {

    @Test
    fun `every key of the bundle is defined once`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/messages/UpstackBundle.properties")).reader().readText()
        val keys = mutableListOf<String>()
        var continued = false
        for (line in text.lines()) {
            val starts = !continued && line.isNotBlank() && line.first() !in "#! \t"
            if (starts) keys += line.substringBefore('=').substringBefore(':').trim()
            continued = line.endsWith("\\")
        }
        assertEquals(emptyList<String>(), keys.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }
}
