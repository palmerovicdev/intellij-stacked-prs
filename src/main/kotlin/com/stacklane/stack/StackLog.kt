package com.stacklane.stack

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.stacklane.gh.GhOutputSink
import java.nio.file.Path

/**
 * La pestana Log: cada comando que ejecuta el plugin, tal cual se escribiria en la
 * terminal, con su salida. Guarda lo ultimo aunque la ventana no se haya abierto todavia
 * (una operacion lanzada desde el menu de Pull Requests, por ejemplo) y lo vuelca al abrirla.
 */
class StackLog : GhOutputSink {

    private class Entry(val text: String, val type: ConsoleViewContentType)

    private val lock = Any()
    private val backlog = ArrayDeque<Entry>()
    private var console: ConsoleView? = null

    fun attach(view: ConsoleView) {
        synchronized(lock) {
            console = view
            for (entry in backlog) view.print(entry.text, entry.type)
        }
    }

    fun detach(view: ConsoleView) {
        synchronized(lock) {
            if (console === view) console = null
        }
    }

    override fun started(workDir: Path, command: String) {
        print("\n${workDir.fileName} $ ", ConsoleViewContentType.SYSTEM_OUTPUT)
        print("$command\n", ConsoleViewContentType.USER_INPUT)
    }

    override fun output(text: String) {
        print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    override fun finished(exitCode: Int) {
        if (exitCode != 0) print("exit code $exitCode\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private fun print(text: String, type: ConsoleViewContentType) {
        synchronized(lock) {
            backlog.addLast(Entry(text, type))
            while (backlog.size > BACKLOG_LIMIT) backlog.removeFirst()
            console?.print(text, type)
        }
    }

    private companion object {
        const val BACKLOG_LIMIT = 2_000
    }
}
