package com.stacklane.stack

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.stacklane.settings.RestackMode
import com.stacklane.settings.StacklaneSettings

/**
 * Tras un commit del IDE, mira si dejo atras capas de la pila (ver [StackService.afterCommit]).
 * Los commits de la terminal no pasan por aqui: esos los avisa la banda *needs rebase*.
 */
internal class RestackCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        object : CheckinHandler() {
            override fun checkinSuccessful() {
                // Con el ajuste en «no hacer nada», ni siquiera se crea el servicio.
                if (StacklaneSettings.getInstance().restackAfterCommit == RestackMode.NEVER) return
                StackService.getInstance(panel.project).afterCommit(panel.roots)
            }
        }
}
