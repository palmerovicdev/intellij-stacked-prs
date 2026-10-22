package com.upstack.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.GroupedComboBoxRenderer
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.matching.MatchingMode
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.upstack.UpstackBundle.message
import com.upstack.gh.BranchNameProblem
import com.upstack.gh.GhCommands
import com.upstack.gh.LayerCommit
import com.upstack.gh.Staging
import com.upstack.stack.AddBranch
import com.upstack.stack.AddBranchKind
import com.upstack.stack.GhCall
import com.upstack.stack.LayerPlans
import com.upstack.stack.LocalStackEntry
import com.upstack.stack.Plan
import com.upstack.stack.Position
import com.upstack.stack.StackLayer
import com.upstack.stack.StackPlans
import com.upstack.stack.StackState
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FocusTraversalPolicy
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Texto que se parte en lineas al ancho que le deja el dialogo, sin ensancharlo: el ancho lo
 * marcan los demas campos y el texto crece hacia abajo. Como un JBLabel copiable, se puede
 * seleccionar y copiar, y no es una parada de Tab.
 */
internal open class WrappingText(
    text: String = "",
    icon: Icon? = null,
    textFont: Font = JBFont.label(),
    textColor: Color = UIUtil.getLabelForeground(),
) : JPanel(BorderLayout(JBUI.scale(4), 0)) {

    private val area = object : JBTextArea(text) {
        // Sin ancho propio: la altura es la de las lineas que salen en el ancho que le dan.
        override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = JBUI.scale(MIN_WIDTH) }

        override fun getMinimumSize(): Dimension = preferredSize
    }

    var text: String
        get() = area.text
        set(value) {
            area.text = value
        }

    init {
        isOpaque = false
        area.apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            font = textFont
            foreground = textColor
            // Un area de texto se queda el Tab; asi, si tiene el foco, pasa al siguiente campo.
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
            // Otro ancho, otras lineas: si ya no caben, la ventana crece hacia abajo.
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = fitWindow()
            })
        }
        icon?.let { add(JLabel(it).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST) }
        add(area, BorderLayout.CENTER)
        // El foco solo llega con el raton, para copiar.
        isFocusTraversalPolicyProvider = true
        focusTraversalPolicy = NoTabStops
    }

    private fun fitWindow() {
        revalidate()
        val window = SwingUtilities.getWindowAncestor(this) ?: return
        val height = window.preferredSize.height
        if (height > window.height) window.setSize(window.width, height)
    }

    private object NoTabStops : FocusTraversalPolicy() {
        override fun getComponentAfter(root: Container, component: Component): Component? = null
        override fun getComponentBefore(root: Container, component: Component): Component? = null
        override fun getFirstComponent(root: Container): Component? = null
        override fun getLastComponent(root: Container): Component? = null
        override fun getDefaultComponent(root: Container): Component? = null
    }

    private companion object {
        const val MIN_WIDTH = 80
    }
}

/**
 * La linea `$ gh ...` que acompana a cada dialogo: lo que se va a ejecutar, tal cual, para
 * poder repetirlo en la terminal. Se puede seleccionar y copiar.
 */
internal class CommandPreview : WrappingText(
    textFont = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size),
    textColor = UIUtil.getContextHelpForeground(),
) {

    fun show(commands: List<List<String>>) = showLines(commands.map(GhCommands::display))

    /** Hasta dos comandos en una linea con `&&`; mas, uno por linea, en el orden en que se ejecutan. */
    fun showLines(lines: List<String>) {
        text = if (lines.size <= 2) lines.joinToString(" && ", prefix = "$ ") else lines.joinToString("\n") { "$ $it" }
    }
}

internal fun branchProblemText(problem: BranchNameProblem, name: String): String = when (problem) {
    BranchNameProblem.EMPTY -> message("branch.problem.empty")
    BranchNameProblem.WHITESPACE -> message("branch.problem.whitespace", name)
    BranchNameProblem.CHARACTERS -> message("branch.problem.characters", name)
    BranchNameProblem.BOUNDARY -> message("branch.problem.boundary", name)
    BranchNameProblem.SEQUENCE -> message("branch.problem.sequence", name)
}

internal fun JTextComponent.onChange(block: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = block()
    })
}

internal fun EditorTextField.onChange(block: () -> Unit) {
    addDocumentListener(object : DocumentListener {
        override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = block()
    })
}

/** Una opcion de base: una rama concreta, o la rama por defecto del repositorio (null). */
internal data class BaseOption(val branch: String?, val remoteOnly: String? = null)

/**
 * `gh stack init [--base rama] capas...`. La base puede ser cualquier rama: el trunk, una
 * rama de release, la de un companero o una capa de otra pila. Si solo existe en el remoto,
 * gh-stack la trae. Por defecto se propone la rama actual, como `git checkout -b`.
 *
 * Debajo de los campos, la pila que va a quedar pintada como en la ventana Stacks: asi se
 * ve el orden de las capas y cuales se crean y cuales se adoptan.
 *
 * [leaveLayerFor]: la rama actual es capa de otra pila y gh-stack no deja iniciar desde
 * ahi; antes se hace checkout de esa rama (el trunk de la pila actual).
 *
 * [tracked]: las pilas que gh-stack sigue en local. Una capa con el nombre de una rama de
 * otra pila activa no se puede crear; si esa pila ya no tiene ramas (se borraron a mano),
 * se olvida primero —[forgetFirst]— y se ofrece recuperar sus ramas en su ultimo commit
 * —[restore]— para no perder su trabajo. [from]: donde se vuelve tras olvidarla.
 */
internal class InitStackDialog(
    project: Project,
    repository: GitRepository,
    layersSuggestion: String?,
    baseSuggestion: String?,
    private val leaveLayerFor: String?,
    private val tracked: List<LocalStackEntry> = emptyList(),
    private val from: Position = Position(repository.currentBranchName, repository.currentRevision),
) : DialogWrapper(project) {

    private val localBranches = repository.branches.localBranches.map { it.name }.toSet()
    private val staleNote = JBLabel("", AllIcons.General.Warning, JBLabel.LEFT)
    private val restoreBox = JBCheckBox("", true)
    private val branchesField = JBTextField(layersSuggestion.orEmpty(), 32)
    private val baseCombo = ComboBox(baseOptions(repository).toTypedArray())
    private val stackPreview = StackPreview()
    private val preview = CommandPreview()

    val branches: List<String> get() = branchesField.text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    private val baseOption: BaseOption get() = baseCombo.selectedItem as? BaseOption ?: BaseOption(null)

    val base: String? get() = baseOption.branch

    /** Pilas sin ramas que registran alguno de los nombres elegidos: se olvidan antes del init. */
    val forgetFirst: List<LocalStackEntry>
        get() = branches.toSet().let { names -> tracked.filter { it.isStale && it.stack.branches.any(names::contains) } }

    /** Ramas elegidas que se recuperan en su ultimo commit conocido, por nombre. */
    val restore: Map<String, String>
        get() = if (!restoreBox.isSelected) emptyMap() else restorable()

    private fun restorable(): Map<String, String> {
        val names = branches.toSet()
        return forgetFirst.flatMap { it.restorable.entries }.filter { it.key in names }.associate { it.key to it.value }
    }

    /** Toda la secuencia: olvidar las pilas sin ramas, recuperar ramas y `gh stack init`. */
    fun plan(): Plan? {
        var plan = Plan(emptyList())
        for (entry in forgetFirst) plan = plan.then(StackPlans.forget(entry.stack, from) ?: return null)
        return plan.then(StackPlans.restore(restore)).then(listOf(GhCall(GhCommands.init(branches, base))))
    }

    init {
        title = message("init.title")
        setOKButtonText(message("init.ok"))
        val options = (0 until baseCombo.itemCount).map(baseCombo::getItemAt)
        baseCombo.renderer = BaseOptionRenderer(baseCombo, repository.currentBranchName, options)
        // Tan ancho como el campo de capas: una rama remota de nombre largo no ensancha el dialogo.
        baseCombo.setMinimumAndPreferredWidth(branchesField.preferredSize.width)
        ComboboxSpeedSearch.installSpeedSearch(baseCombo) { it.branch ?: message("init.base.default") }
        baseCombo.selectedItem = options.firstOrNull { it.branch == baseSuggestion } ?: options.first()
        baseCombo.showHelp(Help(message("help.init.base"), listOf("--base BASE")))
        restoreBox.showHelp(Help(message("help.init.restore"), listOf("git branch BRANCH COMMIT")))
        branchesField.onChange(::updatePreview)
        baseCombo.addActionListener { updatePreview() }
        restoreBox.addActionListener { updatePreview() }
        init()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("init.base")) {
            cell(baseCombo).align(AlignX.FILL)
        }
        row(message("init.branches")) {
            cell(branchesField).align(AlignX.FILL).comment(message("init.branches.comment"))
        }
        // Etiqueta vacia: la vista previa queda en la columna de los campos.
        row("") {
            cell(stackPreview).align(AlignX.FILL)
        }
        if (leaveLayerFor != null) {
            row {
                cell(WrappingText(message("init.leave.layer", leaveLayerFor), AllIcons.General.Information))
                    .align(AlignX.FILL).resizableColumn()
            }
        }
        row { cell(staleNote) }
        row { cell(restoreBox) }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }.topGap(TopGap.SMALL)
    }

    override fun getPreferredFocusedComponent(): JComponent = branchesField

    override fun doValidate(): ValidationInfo? {
        val names = branches
        if (names.isEmpty()) return ValidationInfo(message("branch.problem.empty"), branchesField)
        for (name in names) {
            GhCommands.branchNameProblem(name)?.let { return ValidationInfo(branchProblemText(it, name), branchesField) }
        }
        names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
            return ValidationInfo(message("init.duplicate", it.key), branchesField)
        }
        base?.let { base -> if (base in names) return ValidationInfo(message("init.base.in.branches", base), branchesField) }
        // Una rama de otra pila activa: gh-stack la rechaza con «already exists in a stack».
        tracked.filterNot { it.isStale }.firstNotNullOfOrNull { entry ->
            names.firstOrNull { it in entry.stack.branches }?.let { it to entry }
        }?.let { (name, entry) ->
            return ValidationInfo(message("init.in.active.stack", name, describe(entry)), branchesField)
        }
        if (forgetFirst.isNotEmpty() && plan() == null) return ValidationInfo(message("init.detached"), branchesField)
        return null
    }

    private fun updatePreview() {
        val stale = forgetFirst
        staleNote.isVisible = stale.isNotEmpty()
        staleNote.text = message("init.forget.first", stale.flatMap { it.stack.branches }.filter { it in branches }.joinToString(", "))
        val restorable = restorable()
        restoreBox.isVisible = restorable.isNotEmpty()
        restoreBox.text = message("init.restore", restorable.entries.joinToString(", ") { "${it.key} (${it.value.take(7)})" })

        val steps = plan()?.calls?.dropLast(1).orEmpty().map { GhCommands.display(it.args, it.tool) }
        val init = GhCommands.display(GhCommands.init(branches.ifEmpty { listOf("…") }, base))
        preview.showLines(listOfNotNull(leaveLayerFor?.let { "git checkout $it" }) + steps + init)
        stackPreview.show(branches, baseOption, localBranches + restore.keys)
        // La vista previa crece con cada capa: el dialogo crece con ella, nunca encoge.
        val window = window ?: return
        if (window.isVisible && window.preferredSize.let { it.height > window.height || it.width > window.width }) pack()
    }

    /** Icono de rama y, en gris, el remoto de las que solo estan alli o «current». Locales y remotas, separadas. */
    private class BaseOptionRenderer(
        combo: JComponent,
        private val current: String?,
        // Collection y no List: dentro de la clase, List es GroupedElementsRenderer.List.
        options: Collection<BaseOption>,
    ) : GroupedComboBoxRenderer<BaseOption?>(combo) {

        private val firstLocal = options.firstOrNull { it.branch != null && it.remoteOnly == null }
        private val firstRemote = options.firstOrNull { it.remoteOnly != null }

        override fun getText(item: BaseOption?): String = item?.branch ?: message("init.base.default")

        override fun getSecondaryText(item: BaseOption?): String? = when {
            item?.remoteOnly != null -> item.remoteOnly
            item?.branch != null && item.branch == current -> message("init.base.current")
            else -> null
        }

        override fun getIcon(item: BaseOption?): Icon = AllIcons.Vcs.Branch

        override fun separatorFor(value: BaseOption?): ListSeparator? = when (value) {
            null -> null
            firstLocal -> ListSeparator(message("init.base.group.local"))
            firstRemote -> ListSeparator(message("init.base.group.remote"))
            else -> null
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        fun describe(entry: LocalStackEntry): String = (listOf(entry.stack.trunk) + entry.stack.branches).joinToString(" ← ")

        /**
         * La rama por defecto, luego las locales y despues las que solo estan en un remoto
         * (por su nombre corto: `develop`, no `origin/develop`, que es lo que espera gh-stack).
         */
        fun baseOptions(repository: GitRepository): List<BaseOption> {
            val local = repository.branches.localBranches.map { it.name }.toSortedSet()
            val remote = repository.branches.remoteBranches
                .filter { it.nameForRemoteOperations != "HEAD" && it.nameForRemoteOperations !in local }
                .distinctBy { it.nameForRemoteOperations }
                .sortedBy { it.nameForRemoteOperations }
                .map { BaseOption(it.nameForRemoteOperations, it.remote.name) }
            return listOf(BaseOption(null)) + local.map { BaseOption(it) } + remote
        }
    }
}

/**
 * La pila que va a crear `gh stack init`, con el grafo de la ventana Stacks: las capas de
 * arriba abajo y la base al final. Las ramas que ya existen se adoptan; las demas, nuevas.
 */
private class StackPreview : JPanel(VerticalLayout(0)) {

    init {
        isOpaque = false
    }

    fun show(layers: List<String>, base: BaseOption, existing: Set<String>) {
        removeAll()
        if (layers.isEmpty()) {
            val hint = secondary(message("init.preview.empty")).apply { font = JBFont.label().asItalic() }
            add(graphRow(hint, null, null, Rail.Node(Rail.Shape.RING, StackColors.DRAFT), lineAbove = false, lineBelow = true))
        }
        layers.asReversed().forEachIndexed { index, branch ->
            val adopted = branch in existing
            val color = if (adopted) StackColors.DRAFT else StackColors.READY
            val line = line(JLabel(branch), Chip.status(message(if (adopted) "init.preview.existing" else "init.preview.new"), color))
            add(graphRow(line, null, null, Rail.Node(Rail.Shape.RING, color), lineAbove = index > 0, lineBelow = true))
        }
        val name = base.branch?.let(::JLabel) ?: secondary(message("init.base.default")).apply { font = JBFont.label().asItalic() }
        val fetched = base.remoteOnly?.let { secondary(message("init.preview.fetched", it)) }
        val line = line(*listOfNotNull(name, Chip.status(message("layer.trunk"), StackColors.DRAFT), fetched).toTypedArray())
        add(graphRow(line, null, null, Rail.Node(Rail.Shape.SQUARE, StackColors.DRAFT), lineAbove = true, lineBelow = false))
        revalidate()
        repaint()
    }

    private fun line(vararg parts: JComponent): JComponent =
        JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            parts.forEach(::add)
        }

    private fun secondary(text: String) = JLabel(text).apply { foreground = NamedColorUtil.getInactiveTextColor() }
}

/**
 * `gh stack add rama`, con el commit opcional de `-A`/`-u`/`-m`. Si no se esta en la capa
 * superior, antes `gh stack top`: gh-stack solo anade encima de la pila.
 *
 * El nombre se autocompleta con [suggestions], las ramas del proyecto, locales y remotas (ver
 * [LayerPlans.addSuggestions]): gh-stack adopta una existente tal cual, sin rebase sobre la de
 * arriba. Son sugerencias: vale cualquier otro nombre, y entonces gh-stack crea la rama.
 */
internal class AddLayerDialog(
    project: Project,
    private val state: StackState.Loaded,
    private val top: StackLayer,
    private val needsTop: Boolean,
    private val topIsEmpty: Boolean,
    private val suggestions: List<AddBranch>,
) : DialogWrapper(project) {

    private val byName = suggestions.associateBy { it.name }

    // Las sugerencias salen solas, al entrar en el campo y al escribir, aunque el IDE tenga
    // desactivado *Show suggestions as you type*. TextFieldWithAutoCompletion.create no las fuerza.
    private val branchField = TextFieldWithCompletion(
        project,
        BranchSuggestions(suggestions),
        prefixOf(top.branch),
        true, // oneLineMode
        true, // autoPopup
        true, // forceAutoPopup: al escribir
        true, // showHint: instala el AUTO_POPUP_ON_FOCUS_GAINED de abajo
    )
    private val adoptNote = JBLabel("", AllIcons.General.Information, JBLabel.LEFT)
    private val commitBox = JBCheckBox(message("add.commit"))
    private val messageField = JBTextField(32)
    private val stagingAll = JBRadioButton(message("add.staging.all"), true)
    private val stagingTracked = JBRadioButton(message("add.staging.tracked"))
    private val stagingStaged = JBRadioButton(message("add.staging.staged"))
    private val emptyWarning = JBLabel(message("add.empty.top", top.branch), AllIcons.General.Warning, JBLabel.LEFT)
    private val preview = CommandPreview()

    val branch: String get() = branchField.text.trim()

    /** La rama escrita, si es una del proyecto. */
    private val chosen: AddBranch? get() = byName[branch]

    /** Lo que se ejecuta (ver [LayerPlans.add]). */
    val plan: Plan get() = LayerPlans.add(branch, commit, needsTop, chosen?.remoteBranch)

    val commit: LayerCommit?
        get() = if (commitBox.isSelected) LayerCommit(messageField.text.trim(), staging) else null

    private val staging: Staging
        get() = when {
            stagingTracked.isSelected -> Staging.TRACKED
            stagingStaged.isSelected -> Staging.STAGED
            else -> Staging.ALL
        }

    init {
        title = message("add.title")
        setOKButtonText(message("add.ok"))
        // El editor del campo se crea al mostrarse: es entonces cuando el cursor va tras el prefijo
        // y las sugerencias se piden al entrar en el campo, ya con el prefijo escrito.
        branchField.addSettingsProvider {
            it.caretModel.moveToOffset(it.document.textLength)
            if (suggestions.isNotEmpty()) it.putUserData(AutoPopupController.AUTO_POPUP_ON_FOCUS_GAINED, true)
        }
        branchField.onChange(::update)
        messageField.onChange(::update)
        commitBox.addActionListener { update() }
        listOf(stagingAll, stagingTracked, stagingStaged).forEach { it.addActionListener { update() } }
        commitBox.showHelp(Help(message("help.add.commit"), listOf("gh stack add -m MESSAGE BRANCH")))
        stagingAll.showHelp(Help(message("help.add.staging.all"), listOfNotNull(Staging.ALL.flag)))
        stagingTracked.showHelp(Help(message("help.add.staging.tracked"), listOfNotNull(Staging.TRACKED.flag)))
        stagingStaged.showHelp(Help(message("help.add.staging.staged"), listOfNotNull(Staging.STAGED.flag)))
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        val pr = top.pr?.let { " (#${it.number})" }.orEmpty()
        row(message("add.on.top")) {
            cell(JBLabel(top.branch + pr).apply { font = JBFont.label().asBold() })
        }
        if (needsTop) {
            row {
                cell(JBLabel(message("add.needs.top", state.snapshot.currentBranch, top.branch), AllIcons.General.Information, JBLabel.LEFT))
            }
        }
        row(message("add.branch")) {
            cell(branchField).align(AlignX.FILL)
        }
        row { cell(adoptNote) }
        row { cell(commitBox) }
        indent {
            row(message("add.message")) { cell(messageField).align(AlignX.FILL) }
            // El DSL exige que los radio buttons vivan en un buttonsGroup: es el que crea su ButtonGroup.
            buttonsGroup {
                row {
                    cell(stagingAll)
                    cell(stagingTracked)
                    cell(stagingStaged)
                }
            }
            row { cell(emptyWarning) }
        }
        row { cell(preview).align(AlignX.FILL).resizableColumn() }
    }

    override fun getPreferredFocusedComponent(): JComponent = branchField

    /** Escribe [name] en el campo, como el usuario. Los tests no pueden teclear en un dialogo sin pantalla. */
    internal fun enterName(name: String) {
        branchField.text = name
    }

    // Publica: los tests comprueban que el nombre que escriben vale.
    public override fun doValidate(): ValidationInfo? {
        GhCommands.branchNameProblem(branch)?.let { return ValidationInfo(branchProblemText(it, branch), branchField) }
        if (state.snapshot.layers.any { it.branch == branch } || branch == state.snapshot.trunk) {
            return ValidationInfo(message("add.exists", branch), branchField)
        }
        if (chosen?.kind == AddBranchKind.OTHER_STACK) return ValidationInfo(message("add.other.stack", branch), branchField)
        if (commitBox.isSelected && messageField.text.isBlank()) return ValidationInfo(message("add.message.empty"), messageField)
        return null
    }

    private fun update() {
        val committing = commitBox.isSelected
        listOf(messageField, stagingAll, stagingTracked, stagingStaged).forEach { it.isEnabled = committing }
        emptyWarning.isVisible = committing && topIsEmpty
        val chosen = chosen
        adoptNote.isVisible = chosen?.usable == true
        adoptNote.text = when (chosen?.kind) {
            AddBranchKind.REMOTE -> message("add.adopt.remote", branch, top.branch, chosen.remoteBranch.orEmpty())
            else -> message("add.adopt", branch, top.branch)
        }
        val shown = commit?.let { if (it.message.isEmpty()) it.copy(message = "…") else it }
        preview.showLines(Help.of(LayerPlans.add(branch.ifEmpty { "…" }, shown, needsTop, chosen?.remoteBranch).calls))
        // Un aviso que aparece no cabe en el alto de antes: el dialogo crece, nunca encoge.
        val window = window ?: return
        if (window.isVisible && window.preferredSize.let { it.height > window.height || it.width > window.width }) pack()
    }

    internal companion object {
        /** `feat/payment-domain` -> `feat/`: la capa nueva suele seguir la convencion de la anterior. */
        fun prefixOf(branch: String): String = branch.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    }
}

/**
 * Las sugerencias de *Add Layer…*: cada rama dice a la derecha que es. Las que `gh stack add` no
 * acepta salen al final y en gris: se ven, pero el dialogo no deja usarlas.
 *
 * Se buscan como en el popup de ramas del IDE (ver [BranchMatcher]), y `/`, `.` o `-` siguen
 * filtrando: por defecto, `/` cerraria la lista y `.` insertaria la rama elegida.
 */
internal class BranchSuggestions(items: List<AddBranch>) : TextFieldWithAutoCompletionListProvider<AddBranch>(items) {

    override fun getLookupString(item: AddBranch): String = item.name

    override fun getIcon(item: AddBranch): Icon = AllIcons.Vcs.Branch

    override fun getTypeText(item: AddBranch): String = when (item.kind) {
        AddBranchKind.LOCAL -> message("add.kind.local")
        AddBranchKind.REMOTE -> item.remote.orEmpty()
        AddBranchKind.THIS_STACK -> message("add.kind.this.stack")
        AddBranchKind.OTHER_STACK -> message("add.kind.other.stack")
    }

    // El orden de la lista: el mismo que LayerPlans.addSuggestions.
    override fun compare(item1: AddBranch, item2: AddBranch): Int = compareValuesBy(item1, item2, { !it.usable }, { it.name })

    override fun createLookupBuilder(item: AddBranch): LookupElementBuilder =
        super.createLookupBuilder(item).let { if (item.usable) it else it.withItemTextForeground(NamedColorUtil.getInactiveTextColor()) }

    override fun createPrefixMatcher(prefix: String): PrefixMatcher = BranchMatcher(prefix)

    // Un nombre de rama no lleva espacios: con uno se cierra la lista sin insertar nada.
    override fun acceptChar(c: Char): CharFilter.Result =
        if (c.isWhitespace()) CharFilter.Result.HIDE_LOOKUP else CharFilter.Result.ADD_TO_PREFIX

    /**
     * Por palabras, en cualquier parte del nombre y sin mirar mayusculas: `feat/pay` encuentra
     * `feat/business-configuration-pay-runs`, y `pay`, cualquier rama con esa palabra. El del
     * padre solo busca el texto tal cual.
     */
    private class BranchMatcher(prefix: String) : PrefixMatcher(prefix) {
        private val matcher = NameUtil.buildMatcher("*$prefix", MatchingMode.IGNORE_CASE)

        override fun prefixMatches(name: String): Boolean = matcher.matches(name)

        override fun isStartMatch(name: String): Boolean = name.startsWith(prefix, ignoreCase = true)

        override fun matchingDegree(string: String): Int = matcher.matchingDegree(string)

        override fun cloneWithPrefix(prefix: String): PrefixMatcher = if (prefix == this.prefix) this else BranchMatcher(prefix)
    }
}
