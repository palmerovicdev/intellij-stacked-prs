package com.stacklane

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.annotations.Nls

internal object Notifier {

    private const val GROUP = "Stacklane"

    fun info(project: Project, content: @Nls String, vararg actions: NotificationAction) =
        notify(project, "", content, NotificationType.INFORMATION, actions)

    fun info(project: Project, title: @Nls String, content: @Nls String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.INFORMATION, actions)

    fun warning(project: Project, title: @Nls String, content: @Nls String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.WARNING, actions)

    fun error(project: Project, title: @Nls String, content: @Nls String, vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, actions)

    /** La salida de gh como contenido de una notificacion: HTML escapado, solo el final. */
    fun html(text: String): String =
        text.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }

    fun action(text: @Nls String, run: () -> Unit): NotificationAction =
        NotificationAction.createSimpleExpiring(text) { run() }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
        actions: Array<out NotificationAction>,
    ) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, content, type)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }
}
