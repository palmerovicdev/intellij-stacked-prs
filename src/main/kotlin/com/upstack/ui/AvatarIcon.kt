package com.upstack.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * The round avatar of a GitHub user. It starts as a colored disc with the initial and, on github.com, swaps in
 * the real picture (`https://github.com/<login>.png`) once it has been read; [onLoaded] asks the UI to repaint.
 */
internal class AvatarIcon(private val login: String, loadFrom: String?, private val onLoaded: () -> Unit) : Icon {
    @Volatile private var picture: BufferedImage? = null

    init {
        if (loadFrom != null && !ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().executeOnPooledThread {
                picture = runCatching {
                    val connection = URI("https://$loadFrom/${login}.png?size=48").toURL().openConnection().apply {
                        connectTimeout = 3_000
                        readTimeout = 5_000
                    }
                    connection.getInputStream().use(ImageIO::read)
                }.getOrNull()
                if (picture != null) ApplicationManager.getApplication().invokeLater(onLoaded)
            }
        }
    }

    override fun getIconWidth(): Int = JBUI.scale(16)
    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val size = iconWidth
            val image = picture
            if (image != null) {
                g2.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())
                g2.drawImage(image, x, y, size, size, null)
            } else {
                g2.color = Color.getHSBColor((login.lowercase().hashCode().mod(360)) / 360f, 0.45f, 0.6f)
                g2.fillOval(x, y, size, size)
                g2.color = Color.WHITE
                g2.font = g2.font.deriveFont(Font.BOLD, size * 0.62f)
                val initial = login.take(1).uppercase()
                val metrics = g2.fontMetrics
                g2.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2, y + (size + metrics.ascent - metrics.descent) / 2)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** One [AvatarIcon] per login, made on first use. */
internal class AvatarCache(private val onLoaded: () -> Unit) {
    private val icons = ConcurrentHashMap<String, AvatarIcon>()

    /** [host] is the GitHub host the list comes from; only github.com avatars are fetched. */
    fun of(login: String, host: String?): AvatarIcon =
        icons.getOrPut(login.lowercase()) { AvatarIcon(login, host?.takeIf { it == "github.com" }, onLoaded) }
}
