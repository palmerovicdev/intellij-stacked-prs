package com.stacklane.ui

import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.GhCommands
import com.stacklane.gh.RebaseScope
import com.stacklane.gh.Tool
import com.stacklane.stack.GhCall
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackSnapshot
import org.jetbrains.annotations.Nls
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Lo que hace una opcion y los comandos que ejecuta, tal y como se escribirian en la
 * terminal. Cada accion, boton de banda y casilla lo ensena al pasar el raton.
 *
 * Los comandos con datos que solo se conocen al ejecutar llevan su hueco en mayusculas
 * (`BRANCH`, `MESSAGE`), como en la ayuda de gh. Nunca entre `<>`: la descripcion de una
 * accion llega como HTML al tooltip de la barra de herramientas.
 */
internal class Help(val text: @Nls String, val commands: List<String> = emptyList()) {

    /** Una linea: la descripcion de la accion, que tambien pinta la barra de estado (sin HTML). */
    val plain: @Nls String
        get() = if (commands.isEmpty()) text else "$text (${commands.joinToString(", ")})"

    /** El tooltip con los colores y la letra del tema actual. Ver [html]. */
    val html: @Nls String
        get() = html(TooltipStyle.current())

    /**
     * El tooltip: la explicacion y, debajo, cada comando en su bloque con fondo, como el codigo
     * en Markdown. Las URLs van del color de un enlace. Si algo no cabe en [TooltipStyle.maxWidth]
     * se envuelve todo a ese ancho; si no, el tooltip crecia hasta medir el comando mas largo.
     */
    fun html(style: TooltipStyle): @Nls String {
        val content = HtmlBuilder().append(linked(text, style))
        commands.forEach { command ->
            content.append(HtmlChunk.div().style(style.codeCss).child(HtmlChunk.tag("code").child(linked(command, style))))
        }
        val body = if (style.overflows(text, commands)) HtmlChunk.div().style(style.widthCss).child(content.toFragment())
        else content.toFragment()
        return HtmlBuilder().append(body).wrapWithHtmlBody().toString()
    }

    companion object {

        private val URL = Regex("""https?://[^\s'"]+""")

        /** [text] escapado, con cada URL como enlace. */
        private fun linked(text: String, style: TooltipStyle): HtmlChunk {
            val builder = HtmlBuilder()
            var last = 0
            for (match in URL.findAll(text)) {
                builder.append(text.substring(last, match.range.first))
                builder.append(HtmlChunk.link(match.value, match.value).style("color: ${style.link}"))
                last = match.range.last + 1
            }
            return builder.append(text.substring(last)).toFragment()
        }

        fun gh(args: List<String>): String = GhCommands.display(args)

        fun git(args: List<String>): String = GhCommands.display(args, Tool.GIT)

        /** El checkout del IDE (git4idea), que ejecuta este `git checkout`. */
        fun checkout(branch: String): String = git(listOf("checkout", branch))

        fun of(calls: List<GhCall>): List<String> = calls.map { GhCommands.display(it.args, it.tool) }
    }
}

/**
 * Colores y ancho de los tooltips de [Help]. Dependen del tema, asi que se toman al pintar
 * cada tooltip, no una vez.
 */
internal class TooltipStyle(
    /** Fondo de los bloques de comandos, `#rrggbb`. */
    val code: String,
    /** Color de las URLs, `#rrggbb`. */
    val link: String,
    val maxWidth: Int,
    /** Ancho en pixeles de una linea: texto normal, o un comando si el segundo es `true`. */
    private val measure: (String, Boolean) -> Int,
) {
    val codeCss: String get() = "background-color: $code; padding: 2px 6px; margin-top: 4px"

    /**
     * [maxWidth] en CSS. El HTML de Swing multiplica los `px` de CSS por 1,3 (los toma por
     * puntos) y el IDE no activa `JEditorPane.W3C_LENGTH_UNITS`: se divide antes.
     */
    val widthCss: String get() = "width: ${(maxWidth / SWING_PX).roundToInt()}px"

    fun overflows(text: String, commands: List<String>): Boolean =
        measure(text, false) > maxWidth || commands.any { measure(it, true) + CODE_PADDING > maxWidth }

    companion object {
        /** Algo mas estrecho que un comando largo: una URL de PR cabe en una linea, el resto baja. */
        private const val MAX_WIDTH = 520
        private const val CODE_PADDING = 12
        private const val SWING_PX = 1.3

        // Los de los enlaces de GitHub: el tooltip puede ser oscuro en un tema claro.
        private val LIGHT_LINK = Color(0x0969DA)
        private val DARK_LINK = Color(0x4493F8)

        fun current(): TooltipStyle {
            val background = UIUtil.getToolTipBackground()
            val font = UIUtil.getToolTipFont()
            val mono = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            val context = FontRenderContext(null, true, true)
            return TooltipStyle(
                code = ColorUtil.toHtmlColor(ColorUtil.mix(background, UIUtil.getToolTipForeground(), 0.12)),
                link = ColorUtil.toHtmlColor(if (ColorUtil.isDark(background)) DARK_LINK else LIGHT_LINK),
                maxWidth = JBUI.scale(MAX_WIDTH),
                measure = { line, isCode -> ceil((if (isCode) mono else font).getStringBounds(line, context).width).toInt() },
            )
        }
    }
}

/**
 * La ayuda de una accion. La descripcion (barra de estado y tooltip de la barra de
 * herramientas) es texto plano; el tooltip de los menus de lista, HTML.
 */
internal fun Presentation.showHelp(help: Help) {
    description = help.plain
    putClientProperty(ActionUtil.TOOLTIP_TEXT, help.html)
}

internal fun JComponent.showHelp(help: Help) {
    toolTipText = help.html
}

/** Las ayudas que se ofrecen desde varios sitios: la barra, las bandas y los enlaces. */
internal object Helps {

    fun refresh() = Help(
        message("action.Stacklane.Refresh.description"),
        listOf(Help.gh(GhCommands.view()), "gh api graphql …"),
    )

    fun initStack() = Help(message("action.Stacklane.InitStack.description"), listOf(INIT))

    fun checkoutStack() = Help(message("help.checkout.stack"), listOf("gh stack checkout STACK | PR | BRANCH"))

    fun installExtension() = Help(message("help.install.extension"), listOf(Help.gh(GhCommands.installExtension())))

    fun rebase(scope: RebaseScope, text: @Nls String) = Help(text, listOf(Help.gh(GhCommands.rebase(scope))))

    fun rebaseStack() = rebase(RebaseScope.STACK, message("action.Stacklane.Rebase.description"))

    /** Desde [branch]: si no es la rama actual, antes el checkout, y al terminar se vuelve. */
    fun rebaseUpstackFrom(snapshot: StackSnapshot, branch: String) = Help(
        message("help.rebase.upstack.from", branch, snapshot.trunk),
        listOfNotNull(
            branch.takeIf { it != snapshot.currentBranch }?.let(Help::checkout),
            Help.gh(GhCommands.rebase(RebaseScope.UPSTACK)),
        ),
    )

    fun continueRebase() = Help(message("help.rebase.continue"), listOf(Help.gh(GhCommands.rebaseContinue())))

    fun abortRebase() = Help(message("help.rebase.abort"), listOf(Help.gh(GhCommands.rebaseAbort())))

    fun resolveConflicts() = Help(message("help.resolve.conflicts"))

    fun push() = Help(message("help.push"), listOf(Help.gh(GhCommands.push())))

    /**
     * El IDE compara desde el ultimo commit comun con la capa de debajo: es lo que hace `...`
     * en `git diff`, aunque el comando que corre por dentro sea otro.
     */
    fun layerChanges(snapshot: StackSnapshot, layer: StackLayer): Help {
        val parent = snapshot.parentOf(layer)
        return Help(message("help.layer.changes", layer.branch, parent), listOf(Help.git(listOf("diff", "$parent...${layer.branch}"))))
    }

    private const val INIT = "gh stack init --base BASE BRANCH…"
}
