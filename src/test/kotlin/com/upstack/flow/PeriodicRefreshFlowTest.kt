package com.upstack.flow

import com.intellij.util.xmlb.XmlSerializer
import com.upstack.settings.UpstackSettings
import com.upstack.stack.GhCall
import com.upstack.stack.Operation
import com.upstack.stack.StackState

/**
 * El refresco periodico de Settings: con el intervalo encendido y la pestana Stack en pantalla, la
 * pila se vuelve a leer sola cada tanto; nunca durante una escritura, con 0 nunca. Un "segundo"
 * del refresco dura aqui [SECOND_MS]: el minimo de 10 s son 400 ms.
 */
class PeriodicRefreshFlowTest : FlowTestCase() {

    private val settings get() = UpstackSettings.getInstance()

    private fun views(): Int = gh.calls().count { it == VIEW }

    override fun setUp() {
        super.setUp()
        service.periodicSecondMs = SECOND_MS
    }

    /** Con la pila ya leida y nada mas en marcha. */
    private fun settle() {
        awaitState<StackState.NotInStack>()
        awaitQuiet()
    }

    /** Apaga el refresco y espera a que no quede ningun `gh` vivo al cerrar el test. */
    private fun stopRefreshing() {
        settings.refreshIntervalSeconds = 0
        service.setStackShown(false)
        awaitQuiet()
    }

    fun `test with the interval on and the stack on screen the stack is read again with nothing else asking`() {
        settle()
        val before = views()
        try {
            service.setStackShown(true)
            settings.refreshIntervalSeconds = UpstackSettings.MIN_REFRESH_INTERVAL
            waitUntil({ "two periodic reads of the stack, got ${views() - before}: ${gh.calls()}" }) { views() >= before + 2 }
        } finally {
            stopRefreshing()
        }
    }

    fun `test with the stack off screen nothing reads it until it shows`() {
        settle()
        try {
            settings.refreshIntervalSeconds = UpstackSettings.MIN_REFRESH_INTERVAL
            val before = views()
            Thread.sleep(NEGATIVE_WAIT_MS)
            assertEquals("gh stack view with the Stack tab off screen: ${gh.calls()}", before, views())

            service.setStackShown(true)
            waitUntil({ "a periodic read once the Stack tab shows: ${gh.calls()}" }) { views() > before }
        } finally {
            stopRefreshing()
        }
    }

    fun `test no periodic read while a write runs, and they come back after it`() {
        settle()
        val ready = listOf("pr", "ready", "1")
        val write = gh.respond(ready, hold = true)
        try {
            val job = service.execute(Operation("Held", listOf(GhCall(ready)), repository))
            waitUntil("the write reaches gh") { ready in gh.writes() }
            service.setStackShown(true)
            settings.refreshIntervalSeconds = UpstackSettings.MIN_REFRESH_INTERVAL
            val before = views()
            Thread.sleep(NEGATIVE_WAIT_MS)
            assertEquals("gh stack view during the write: ${gh.calls()}", before, views())

            write.release()
            awaitIdle(job)
            // La del final de la escritura y al menos una periodica.
            waitUntil({ "periodic reads after the write, got ${views() - before}: ${gh.calls()}" }) { views() >= before + 2 }
        } finally {
            write.release()
            stopRefreshing()
        }
    }

    fun `test with the interval at 0 nothing reads the stack`() {
        settle()
        try {
            service.setStackShown(true)
            settings.refreshIntervalSeconds = 0
            val before = views()
            Thread.sleep(NEGATIVE_WAIT_MS)
            assertEquals("gh stack view with the refresh off: ${gh.calls()}", before, views())
        } finally {
            stopRefreshing()
        }
    }

    fun `test the interval is saved with the settings, 60 by default, 0 for off and at least 10 seconds`() {
        assertEquals(60, UpstackSettings().refreshIntervalSeconds)
        assertEquals(120, savedAndRead(120))
        assertEquals(0, savedAndRead(0))
        assertEquals(UpstackSettings.MIN_REFRESH_INTERVAL, savedAndRead(3))
        assertEquals(0, savedAndRead(-5))
        assertEquals(UpstackSettings.MAX_REFRESH_INTERVAL, savedAndRead(UpstackSettings.MAX_REFRESH_INTERVAL + 1))
    }

    /** Lo que queda en `upstack.xml` tras poner [seconds], leido como al abrir el IDE. */
    private fun savedAndRead(seconds: Int): Int {
        settings.refreshIntervalSeconds = seconds
        val saved = XmlSerializer.serialize(settings.state)
        val reopened = UpstackSettings()
        reopened.loadState(XmlSerializer.deserialize(saved, UpstackSettings.Options::class.java))
        return reopened.refreshIntervalSeconds
    }

    private companion object {
        const val SECOND_MS = 40L

        /** Tres intervalos minimos y la pausa de cada refresco: de sobra para que hubiera salido uno. */
        const val NEGATIVE_WAIT_MS = 3 * UpstackSettings.MIN_REFRESH_INTERVAL * SECOND_MS + 300
    }
}
