// Fixed screenshot scenario for the Stacklane Marketplace images. capture.sh copies it to the
// sandbox IDE's startup scripts, which run it once when the demo project opens. It paints IDE
// windows to PNG under build/marketplace/shots/raw and reads nothing from outside.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager

import javax.imageio.ImageIO
import javax.swing.*
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

final String ROOT = "@@ROOT@@"
final String W = "$ROOT/build/marketplace"
final String P = "$W/storefront"
final String TOOLS = "$ROOT/docs/marketplace/tools"
final File OUT = new File("$W/shots/raw")
OUT.mkdirs()
final File LOG = new File("$W/shots/capture.log")
LOG.text = ""
final File GEOM = new File("$W/shots/geometry.txt")
GEOM.text = ""
final File SCENE = new File("$W/state/scenario")
SCENE.text = "default"
final String ONLY = new File("$W/state/only").exists() ? new File("$W/state/only").text.trim() : ""

def project = IDE.project
def app = ApplicationManager.application
def CL = PluginManagerCore.plugins.find { it.name == "Stacklane" }.pluginClassLoader

def say = { String m -> LOG << "${java.time.LocalTime.now()} $m\n" }
def edt = { Closure c -> def r = null; app.invokeAndWait({ r = c() } as Runnable, ModalityState.any()); r }
def later = { Closure c -> app.invokeLater({ c() } as Runnable, ModalityState.any()) }
def pause = { long ms -> Thread.sleep(ms) }
def cls = { String n -> CL.loadClass(n) }

def field = { Object obj, String name ->
    def c = obj.getClass()
    while (c != null) {
        try { def f = c.getDeclaredField(name); f.accessible = true; return f.get(obj) }
        catch (NoSuchFieldException ignored) { c = c.superclass }
    }
    throw new NoSuchFieldException(name)
}

def walk
walk = { Component c, java.util.List acc -> acc << c; if (c instanceof Container) c.components.each { walk(it, acc) }; acc }
def all = { Component root -> walk(root, []) }
def textOf = { Component c -> (c.metaClass.respondsTo(c, "getText") ? c.text : null)?.toString() }
def ancestorNamed = { Component c, String part ->
    def p = c
    while (p != null && !p.getClass().name.contains(part)) p = p.parent
    p
}

// ------------------------------------------------------------------ painting

final double SCALE = 2d

def isOwnedBy = { Window w, Window owner ->
    def o = w.owner
    while (o != null) { if (o == owner) return true; o = o.owner }
    false
}

def shadow = { Graphics2D g, int x, int y, int w, int h ->
    def g2 = (Graphics2D) g.create()
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    for (int i = 10; i >= 1; i--) {
        g2.color = new Color(0, 0, 0, 7)
        g2.fill(new RoundRectangle2D.Double(x - i, y - i + 4, w + 2 * i, h + 2 * i, 14 + 2 * i, 14 + 2 * i))
    }
    g2.dispose()
}

def paintComponent = { Graphics2D g, JComponent c ->
    def rm = RepaintManager.currentManager(c)
    def was = rm.doubleBufferingEnabled
    rm.doubleBufferingEnabled = false
    try { c.paint(g) } finally { rm.doubleBufferingEnabled = was }
}

/** Paints [w] (its root pane) and the popups it owns, in root-pane coordinates. */
def shot = { String name, Window w ->
    edt {
        JComponent root = (w instanceof RootPaneContainer) ? ((RootPaneContainer) w).rootPane : (JComponent) w
        Rectangle area = new Rectangle(0, 0, root.width, root.height)
        def origin = root.locationOnScreen
        def owned = Window.windows.findAll { it.isShowing() && it != w && isOwnedBy(it, w) && !(it instanceof Dialog) }
        if (w instanceof Dialog) {
            owned.each { Window p ->
                def l = p.locationOnScreen
                area = area.union(new Rectangle(((int) l.x) - ((int) origin.x) - 24, ((int) l.y) - ((int) origin.y) - 24, p.width + 48, p.height + 48))
            }
        }
        def img = new BufferedImage((int) Math.ceil(area.width * SCALE), (int) Math.ceil(area.height * SCALE), BufferedImage.TYPE_INT_ARGB)
        def g = img.createGraphics()
        g.scale(SCALE, SCALE)
        g.translate(-area.x, -area.y)
        paintComponent(g, root)
        owned.each { Window p ->
            JComponent pr = (p instanceof RootPaneContainer) ? ((RootPaneContainer) p).rootPane : null
            if (pr == null || pr.width < 4 || pr.height < 4) return
            p.validate()
            def loc = pr.locationOnScreen
            int x = loc.x - origin.x, y = loc.y - origin.y
            shadow(g, x, y, pr.width, pr.height)
            def g2 = (Graphics2D) g.create()
            g2.translate(x, y)
            g2.clip(new RoundRectangle2D.Double(0, 0, pr.width, pr.height, 12, 12))
            paintComponent(g2, pr)
            g2.dispose()
            say("  + popup ${p.getClass().simpleName} at $x,$y ${pr.width}x${pr.height}")
        }
        g.dispose()
        ImageIO.write(img, "png", new File(OUT, name + ".png"))
        say("shot $name ${img.width}x${img.height} origin ${area.x},${area.y}")
    }
}

def rectIn = { Component c, JComponent root -> SwingUtilities.convertRectangle(c.parent, c.bounds, root) }
def geom = { String name, Rectangle r -> GEOM << "$name ${r.x} ${r.y} ${r.width} ${r.height}\n" }
def step = { String name, Closure body ->
    if (ONLY && !ONLY.split(",").any { name.contains(it) } && name != "layout") { say("- skip $name"); return }
    try { say("> $name"); body() } catch (Throwable t) {
        def root = t; while (root.cause != null && root.cause != root) root = root.cause
        def where = root.stackTrace.findAll { it.className.startsWith("Script") }.take(3).collect { "line ${it.lineNumber}" }.join(", ")
        say("! $name failed: $root ($where)")
    }
}

// ------------------------------------------------------------------ scenario

Thread.start("stacklane-shots") {
    try {
        say("waiting for smart mode")
        DumbService.getInstance(project).waitForSmartMode()
        pause(4000)

        def frame = edt { WindowManager.getInstance().getFrame(project) }
        def twm = ToolWindowManager.getInstance(project)
        def tw = edt { twm.getToolWindow("Stacklane") }
        def fem = FileEditorManager.getInstance(project)
        def service = project.getService(cls("com.stacklane.stack.StackService"))
        def settings = app.getService(cls("com.stacklane.settings.StacklaneSettings"))
        def flows = cls("com.stacklane.actions.StackFlows").getField("INSTANCE").get(null)

        def stateName = { edt { service.state.value.getClass().simpleName } }
        def loaded = { ->
            def s = service.state.value
            s.getClass().simpleName == "Loaded" && !s.detailsLoading
        }
        def waitLoaded = { String why ->
            for (int i = 0; i < 60 && !loaded(); i++) pause(500)
            say("state for $why: ${stateName()} ${loaded() ? '' : '(not loaded)'}")
            pause(1200)
        }
        def refresh = { String why ->
            def grm = cls("git4idea.repo.GitRepositoryManager").getMethod("getInstance", com.intellij.openapi.project.Project).invoke(null, project)
            def repo = edt { grm.repositories[0] }
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, repo.root)
            def before = service.state.value
            repo.update()
            service.requestRefresh()
            // A refresh always publishes a new state: wait for it, not for the one already there.
            for (int i = 0; i < 40 && service.state.value.is(before); i++) pause(250)
            pause(500)
            waitLoaded(why)
        }
        def scene = { String name ->
            SCENE.text = name
            refresh("scene $name")
        }
        def git = { String... args ->
            def proc = new ProcessBuilder(["git"] + args.toList()).directory(new File(P)).redirectErrorStream(true).start()
            def out = proc.inputStream.text
            proc.waitFor()
            say("git ${args.join(' ')} -> ${proc.exitValue()} ${out.trim().take(200)}")
        }
        def panel = { edt { all(tw.component).find { it.getClass().name == "com.stacklane.ui.StackPanel" } } }
        def list = { edt { field(panel(), "list") } }
        def rowIndex = { String branch ->
            edt {
                def l = list()
                (0..<l.model.size).find { i ->
                    def row = l.model.getElementAt(i)
                    row.getClass().simpleName == "Layer" && row.layer.branch == branch
                }
            }
        }
        def select = { String branch -> edt { list().selectedIndex = rowIndex(branch) ?: -1 } }
        def toolRect = { edt { rectIn(ancestorNamed(tw.component, "InternalDecorator") ?: tw.component, frame.rootPane) } }
        def dumpTree = { String label, Component root ->
            edt {
                def lines = all(root).findAll { it.showing && it.width > 0 }.collect { c ->
                    def t = textOf(c)
                    "${c.getClass().name}${t ? ' "' + t.take(80) + '"' : ''} ${c.width}x${c.height}"
                }
                say("tree $label:\n  " + lines.join("\n  "))
            }
        }
        def windows = { edt { Window.windows.findAll { it.showing }.collect { w -> "${w.getClass().simpleName}${(w instanceof Dialog) ? ' "' + w.title + '"' : ''}@${w.bounds.x},${w.bounds.y} ${w.width}x${w.height}" } } }
        def openFile = { String rel, int line ->
            def vf = LocalFileSystem.instance.refreshAndFindFileByPath("$P/$rel")
            edt { fem.openTextEditor(new OpenFileDescriptor(project, vf, line, 0), true) }
        }
        def cleanEditor = {
            edt {
                def editor = fem.selectedTextEditor
                if (editor == null) return
                editor.scrollingModel.disableAnimation()
                editor.scrollingModel.scrollVertically(0)
                editor.settings.caretRowShown = false
                editor.settings.showIntentionBulb = false
                try { editor.markupModel.setTrafficLightIconVisible(false) } catch (Throwable t) { say("traffic light: $t") }
            }
        }
        def expireNotifications = {
            edt { NotificationsManager.notificationsManager.getNotificationsOfType(Notification, project).each { it.expire() } }
        }
        def dialogTitled = { String part ->
            def d = null
            for (int i = 0; i < 40 && d == null; i++) {
                d = edt { Window.windows.find { it instanceof JDialog && it.showing && it.title?.contains(part) } }
                if (d == null) pause(500)
            }
            say("dialog '$part': ${d != null}; windows ${windows()}")
            d
        }
        def closeDialog = { Window d ->
            if (d == null) return
            edt {
                def wrapper = DialogWrapper.findInstance(((JDialog) d).contentPane)
                if (wrapper != null) wrapper.close(DialogWrapper.CANCEL_EXIT_CODE) else d.dispose()
            }
            pause(1000)
        }
        def post = { AWTEvent e -> Toolkit.defaultToolkit.systemEventQueue.postEvent(e) }
        def press = { Component c, int x, int y, int button, boolean popup, int clicks = 1 ->
            def now = System.currentTimeMillis()
            int mods = button == MouseEvent.BUTTON3 ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK : java.awt.event.InputEvent.BUTTON1_DOWN_MASK
            post(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false))
            post(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, mods, x, y, clicks, popup, button))
            post(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, x, y, clicks, false, button))
            if (!popup) post(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, x, y, clicks, false, button))
        }
        def hover = { Component c, int x, int y ->
            def now = System.currentTimeMillis()
            post(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false))
            post(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false))
            post(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now + 20, 0, x + 1, y, 0, false))
        }
        // The list popup that is showing, and the JList inside it.
        def popupList = {
            edt {
                def lists = Window.windows.findAll { it.showing && !(it instanceof Dialog) && it != frame }
                    .collectMany { w -> all(w).findAll { it instanceof JList && it.showing } }
                lists ? lists.last() : null
            }
        }
        def itemIndex = { JList pl, String prefix ->
            edt {
                (0..<pl.model.size).find { i ->
                    def v = pl.model.getElementAt(i)
                    def t = v.metaClass.respondsTo(v, "getText") ? v.text : v.toString()
                    t?.toString()?.startsWith(prefix)
                }
            }
        }
        def rightClick = { String branch ->
            def l = list()
            def r = edt { l.getCellBounds(rowIndex(branch), rowIndex(branch)) }
            edt { press(l, (int) (r.x + r.width - 280), (int) r.y + 10, MouseEvent.BUTTON3, true) }
            pause(1500)
            popupList()
        }
        def toolbarButton = { String actionId ->
            edt {
                def target = ActionManager.instance.getAction(actionId)
                all(tw.component).find { it.getClass().simpleName.contains("ActionButton") && it.showing && it.action == target }
            }
        }
        def closePopups = {
            edt {
                MenuSelectionManager.defaultManager().clearSelectedPath()
                Window.windows.findAll { it.showing && !(it instanceof Dialog) && it != frame && it.getClass().simpleName == "HeavyWeightWindow" }.each { w ->
                    def popup = all(w).collect { com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(it) }.find { it != null }
                    if (popup != null) popup.cancel() else w.visible = false
                }
                com.intellij.ide.IdeTooltipManager.instance.hideCurrentNow(false)
            }
            pause(800)
        }

        def mainLayout = {
            edt {
                twm.toolWindowIds.each { id -> if (id != "Stacklane") twm.getToolWindow(id)?.hide() }
                frame.setSize(1280, 700)
                def screen = frame.graphicsConfiguration.bounds
                frame.setLocation((int) (screen.x + screen.width - 1280), (int) screen.y + 60)
                tw.show()
            }
            pause(600)
            edt { tw.stretchWidth(560 - (ancestorNamed(tw.component, "InternalDecorator") ?: tw.component).width) }
            pause(600)
        }

        step("layout") {
            edt {
                settings.ghPath = "$TOOLS/gh"
                all(frame.rootPane).findAll { c ->
                    def t = textOf(c) ?: c.accessibleContext?.accessibleName
                    t != null && t.toString().contains("Unlock Ultimate")
                }.each { c -> c.visible = false }
                frame.extendedState = Frame.NORMAL
                frame.setSize(1280, 700)
                def screen = frame.graphicsConfiguration.bounds
                frame.setLocation((int) (screen.x + screen.width - 1280), (int) screen.y + 60)
                say("screen $screen, frame ${frame.bounds}")
                twm.toolWindowIds.each { id -> if (id != "Stacklane") twm.getToolWindow(id)?.hide() }
                fem.openFiles.each { fem.closeFile(it) }
                try { com.intellij.codeInsight.codeVision.settings.CodeVisionSettings.getInstance().codeVisionEnabled = false } catch (Throwable t) { say("codevision: $t") }
                try { com.intellij.codeInsight.hints.InlayHintsSettings.instance().setEnabledGlobally(false) } catch (Throwable t) { say("inlay: $t") }
                try { com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance().stickyLinesShown = false } catch (Throwable t) { say("sticky: $t") }
                try {
                    def ui = com.intellij.ide.ui.UISettings.getInstance()
                    ui.showNavigationBar = false
                    ui.fireUISettingsChanged()
                } catch (Throwable t) { say("navbar: $t") }
            }
            service.requestRefresh()
            pause(800)
            edt { tw.show() }
            pause(800)
            edt { tw.stretchWidth(560 - (ancestorNamed(tw.component, "InternalDecorator") ?: tw.component).width) }
            openFile("src/api/refunds.ts", 13)
            pause(2500)
            cleanEditor()
            waitLoaded("layout")
            expireNotifications()
            pause(800)
            geom("frame", edt { new Rectangle(0, 0, frame.rootPane.width, frame.rootPane.height) })
            geom("toolwindow", toolRect())
            geom("statusbar", edt { rectIn(WindowManager.getInstance().getStatusBar(project).component, frame.rootPane) })
        }

        step("hero") {
            select("refunds/partial-refunds-api")
            pause(800)
            shot("01-hero", frame)
        }

        step("glance") {
            scene("glance")
            select("refunds/partial-refunds-api")
            pause(600)
            shot("02-glance-plain", frame)
            def l = list()
            def r = edt { l.getCellBounds(rowIndex("refunds/refund-dialog"), rowIndex("refunds/refund-dialog")) }
            edt { hover(l, (int) r.x + 120, (int) r.y + 10) }
            pause(2500)
            shot("02-glance", frame)
            closePopups()
            scene("default")
        }

        step("layer menu") {
            def pl = rightClick("refunds/currency-conversion")
            say("layer menu: ${pl?.getClass()?.name} items ${edt { pl ? (0..<pl.model.size).collect { pl.model.getElementAt(it).text } : [] }}")
            if (pl != null) {
                def i = itemIndex(pl, "Rebase Upstack from Here")
                edt { pl.selectedIndex = i ?: 0 }
                pause(600)
                def b = edt { pl.getCellBounds(i ?: 0, i ?: 0) }
                edt { hover(pl, (int) b.x + 60, (int) b.y + 8) }
                pause(2500)
                say("windows with menu: ${windows()}")
                shot("03-layer-menu", frame)
                i = itemIndex(pl, "Merge Up to Here")
                edt { pl.selectedIndex = i ?: 0 }
                b = edt { pl.getCellBounds(i ?: 0, i ?: 0) }
                edt { hover(pl, (int) b.x + 60, (int) b.y + 8) }
                pause(2500)
                shot("03-layer-menu-merge", frame)
            }
            closePopups()
        }

        step("toolbar tooltip") {
            def button = toolbarButton("Stacklane.PublishDraft")
            say("publish draft button: $button")
            if (button != null) {
                edt { hover(button, 12, 12) }
                pause(3000)
                say("windows with tooltip: ${windows()}")
                shot("04-toolbar-tooltip", frame)
            }
            closePopups()
        }

        step("new stack") {
            git("checkout", "-q", "main")
            refresh("on main")
            later { flows.initStack(project, null) }
            def d = dialogTitled("New Stack")
            if (d != null) {
                def fieldOf = edt { all(d).find { it instanceof JTextField && it.showing && it.editable } }
                edt { fieldOf?.text = "search/fuzzy-matching search/typo-tolerance search/results-page" }
                pause(500)
                edt { d.setSize((int) d.width + 140, (int) d.height); d.validate(); fieldOf?.caretPosition = 0 }
                pause(1500)
                dumpTree("new stack", d)
                shot("05-new-stack", d)
                closeDialog(d)
            }
            git("checkout", "-q", "refunds/partial-refunds-api")
            refresh("back on the layer")
        }

        step("add layer") {
            later { flows.addLayer(project) }
            def d = dialogTitled("Add Layer")
            if (d != null) {
                def fields = edt { all(d).findAll { it instanceof JTextField && it.showing } }
                def box = edt { all(d).find { it instanceof JCheckBox && it.showing } }
                edt {
                    if (fields) fields[0].text = "refunds/refund-emails"
                    box?.doClick()
                }
                pause(500)
                fields = edt { all(d).findAll { it instanceof JTextField && it.showing } }
                edt { if (fields.size() > 1) fields[1].text = "Email the customer when a refund goes through" }
                pause(1500)
                dumpTree("add layer", d)
                shot("06-add-layer", d)
                closeDialog(d)
            }
        }

        step("publish") {
            scene("publish")
            later { flows.publishStack(project, null) }
            def d = dialogTitled("Publish Stack")
            if (d != null) {
                pause(1000)
                def texts = edt { all(d).findAll { it instanceof javax.swing.text.JTextComponent && it.showing && it.editable } }
                say("publish fields: ${texts.size()}")
                def values = ["Record every refund in an audit log",
                              "Finance reconciles refunds against this log every night. Each entry keeps who refunded, how much and why.",
                              "Refund dialog on the orders page",
                              "A Refund button on each order opens a dialog with the amount and a reason."]
                edt { texts.eachWithIndex { t, i -> if (i < values.size()) { t.text = values[i]; t.caretPosition = 0 } } }
                def ready = edt { all(d).findAll { it instanceof JCheckBox && it.showing } }
                say("publish checkboxes: ${ready.collect { it.text + '=' + it.selected }}")
                edt { if (ready.size() > 1 && !ready[1].selected) ready[1].doClick() }
                edt { d.setSize((int) d.width, 770); d.validate() }
                edt { texts.each { it.select(0, 0) }; d.rootPane.requestFocusInWindow() }
                pause(1500)
                dumpTree("publish", d)
                shot("07-publish", d)
                closeDialog(d)
            }
            scene("default")
        }

        step("labels") {
            def pl = rightClick("refunds/currency-conversion")
            def i = pl == null ? null : itemIndex(pl, "Labels")
            if (i != null) {
                edt { pl.selectedIndex = i }
                pause(300)
                edt {
                    def popup = com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(pl)
                    popup.handleSelect(true)
                }
                def d = dialogTitled("Labels")
                if (d == null) d = edt { Window.windows.find { it instanceof JDialog && it.showing } }
                if (d != null) {
                    pause(1500)
                    dumpTree("labels", d)
                    shot("08-labels", d)
                    closeDialog(d)
                }
            }
            closePopups()
        }

        step("merge") {
            later { flows.mergeStack(project, "refunds/currency-conversion") }
            def d = dialogTitled("Merge")
            if (d != null) {
                pause(1500)
                dumpTree("merge", d)
                shot("09-merge", d)
                closeDialog(d)
            }
        }

        step("close") {
            later { flows.closeStack(project) }
            def d = dialogTitled("Close Stack")
            if (d != null) {
                pause(1500)
                dumpTree("close", d)
                shot("10-close", d)
                closeDialog(d)
            }
        }

        step("layer changes") {
            def pl = rightClick("refunds/currency-conversion")
            def i = pl == null ? null : itemIndex(pl, "Show Layer Changes")
            if (i != null) {
                edt { pl.selectedIndex = i }
                pause(300)
                edt { com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(pl).handleSelect(true) }
                pause(4000)
                edt { tw.hide(); frame.setSize(1440, 780); frame.setLocation((int) (frame.graphicsConfiguration.bounds.x + frame.graphicsConfiguration.bounds.width - 1440), (int) frame.y) }
                pause(1500)
                def tree = edt { all(frame.rootPane).find { it instanceof JTree && it.showing && it.getClass().name.contains("Changes") } }
                say("changes tree: ${tree?.getClass()?.name}")
                if (tree != null) {
                    def row = edt { (0..<tree.rowCount).find { tree.getPathForRow(it).lastPathComponent.toString().contains("payment-service.ts") } }
                    say("payment-service row: $row")
                    if (row != null) {
                        edt {
                            try {
                                def ds = com.intellij.diff.impl.DiffSettingsHolder.DiffSettings.getSettings()
                                ds.diffToolsOrder = ["com.intellij.diff.tools.fragmented.UnifiedDiffTool", "com.intellij.diff.tools.simple.SimpleDiffTool"]
                            } catch (Throwable t) { say("unified: $t") }
                            // Unchanged lines folded, as in a pull request's Files changed.
                            def holder = com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings
                            ([holder.getSettings()] + [null, "Default", "ChangesView", "VcsLogChanges", "Commit"].collect { holder.getSettings(it) }).each {
                                it.expandByDefault = false
                                it.contextRange = 2
                            }
                            tree.selectionRow = row
                            def browser = tree.parent
                            while (browser != null && !browser.metaClass.respondsTo(browser, "getSelectedChanges")) browser = browser.parent
                            def changes = browser.selectedChanges
                            say("selected changes: ${changes.size()}")
                            com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction.showDiffForChange(project, changes)
                        }
                        pause(4000)
                    }
                }
                say("windows after diff: ${windows()}")
                geom("layer-changes-frame", edt { new Rectangle(0, 0, frame.rootPane.width, frame.rootPane.height) })
                shot("11-layer-changes", frame)
                edt { fem.openFiles.findAll { !it.name.endsWith("refunds.ts") }.each { fem.closeFile(it) } }
            }
            closePopups()
            mainLayout()
            openFile("src/api/refunds.ts", 13)
            pause(1000)
            cleanEditor()
        }

        step("needs rebase") {
            git("update-ref", "refs/heads/refunds/currency-conversion", "refs/demo/currency-fix")
            refresh("needs rebase")
            def marked = { def st = service.state.value; st.getClass().simpleName == "Loaded" && st.snapshot.layers.any { it.needsRebase } }
            for (int i = 0; i < 40 && !marked(); i++) pause(250)
            say("needs rebase marked: ${marked()}")
            waitLoaded("needs rebase, marked")
            select("refunds/currency-conversion")
            pause(1500)
            shot("12-needs-rebase-gotit", frame)
            edt {
                def tip = field(panel(), "upstackTip")
                say("upstack tip: ${tip?.getClass()?.name}")
                try { tip?.hidePopup() } catch (Throwable t) { say("hide tip: $t") }
            }
            pause(1000)
            shot("12-needs-rebase", frame)
            def button = toolbarButton("Stacklane.RebaseMenu")
            say("rebase menu button: $button")
            if (button != null) {
                edt { press(button, 12, 12, MouseEvent.BUTTON1, false) }
                pause(2000)
                def pl = popupList()
                say("rebase menu: ${pl?.getClass()?.name}")
                pause(1000)
                say("windows with rebase menu: ${windows()}")
                shot("12-rebase-menu", frame)
            }
            closePopups()
        }

        step("conflicts") {
            later { flows.rebaseUpstack(project, "refunds/currency-conversion") }
            def d = null
            for (int i = 0; i < 60 && d == null; i++) {
                pause(1000)
                d = edt { Window.windows.find { it instanceof JDialog && it.showing } }
            }
            say("after rebase: ${windows()}")
            pause(2500)
            if (d != null) {
                edt { d.setSize(680, 400); d.validate() }
                pause(1500)
                dumpTree("conflicts dialog", d)
                shot("13-conflicts-dialog", d)
                closeDialog(d)
            }
            pause(2000)
            expireNotifications()
            mainLayout()
            refresh("conflicts")
            pause(3000)
            waitLoaded("conflicts, again")
            dumpTree("toolwindow in conflict", tw.component)
            shot("13-conflicts-frame", frame)
        }

        step("settings") {
            def conf = cls("com.stacklane.settings.StacklaneConfigurable").getConstructor(com.intellij.openapi.project.Project).newInstance(project)
            later { ShowSettingsUtil.instance.editConfigurable(project, conf) }
            def d = dialogTitled("Stacklane")
            if (d != null) {
                edt { d.setSize(860, 520); d.validate() }
                pause(1500)
                def check = edt { all(d).find { it instanceof JButton && it.showing && it.text == "Check" } }
                say("check button: $check")
                edt { check?.doClick() }
                pause(6000)
                edt {
                    all(d).each { c ->
                        def t = textOf(c)
                        if (t != null && t.contains("$TOOLS/gh") && c.metaClass.respondsTo(c, "setText", String)) {
                            c.text = t.replace("$TOOLS/gh", "/opt/homebrew/bin/gh")
                        }
                    }
                    d.validate()
                }
                pause(800)
                dumpTree("settings", d)
                shot("15-settings", d)
                closeDialog(d)
            }
        }

        step("login") {
            later { flows.logIn(project, "github.com") }
            def d = dialogTitled("Log In")
            if (d != null) {
                pause(2500)
                dumpTree("login", d)
                shot("16-login", d)
                closeDialog(d)
            }
        }

        step("log") {
            edt {
                def cm = tw.contentManager
                cm.setSelectedContent(cm.contents.find { it.displayName == "Log" })
            }
            pause(1500)
            def wrap = edt { all(tw.component).find { it.getClass().simpleName.contains("ActionButton") && it.showing && it.action.getClass().name.toLowerCase().contains("wrap") } }
            say("soft wrap button: ${wrap?.action?.getClass()?.name}")
            if (wrap != null) edt { press(wrap, 10, 10, MouseEvent.BUTTON1, false) }
            pause(1500)
            shot("14-log", frame)
            edt {
                def cm = tw.contentManager
                cm.setSelectedContent(cm.contents.find { it.displayName == "Stack" })
            }
        }

        say("DONE")
    } catch (Throwable t) {
        def sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); say("FATAL\n$sw")
    }
}
"capture scenario scheduled"
