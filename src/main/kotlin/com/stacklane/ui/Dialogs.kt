package com.stacklane.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.GroupedComboBoxRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.stacklane.StacklaneBundle.message
import com.stacklane.gh.BranchNameProblem
import com.stacklane.gh.GhCommands
import com.stacklane.gh.LayerCommit
import com.stacklane.gh.Staging
import com.stacklane.stack.GhCall
import com.stacklane.stack.LocalStackEntry
import com.stacklane.stack.Plan
import com.stacklane.stack.Position
import com.stacklane.stack.StackLayer
import com.stacklane.stack.StackPlans
import com.stacklane.stack.StackState
import git4idea.repo.GitRepository
import java.awt.Font
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

/**
 * La linea `$ gh ...` que acompana a cada dialogo: lo que se va a ejecutar, tal cual, para
 * poder repetirlo en la terminal. Se puede seleccionar y copiar.
 */
internal class CommandPreview : JBLabel() {

    init {
        setCopyable(true)
        foreground = UIUtil.getContextHelpForeground()
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
    }

    fun show(commands: List<List<String>>) = showLines(commands.map(GhCommands::display))

    /** Hasta dos comandos en una linea con `&&`; mas, uno por linea, en el orden en que se ejecutan. */
    fun showLines(lines: List<String>) {
        text = if (lines.size <= 2) lines.joinToString(" && ", prefix = "$ ")
        else lines.joinToString("<br>", prefix = "<html>", postfix = "</html>") { "$ " + StringUtil.escapeXmlEntities(it) }
    }
}

internal fun branchProblemText(problem: BranchNameProblem, name: String): String = when (problem) {
    BranchNameProblem.EMPTY -> message("branch.problem.empty")
    BranchNameProblem.WHITESPACE -> message("branch.problem.whitespace", name)
    BranchNameProblem.CHARACTERS -> message("branch.problem.characters", name)
    BranchNameProblem.BOUNDARY -> message("branch.problem.boundary", name)
    BranchNameProblem.SEQUENCE -> message("branch.problem.sequence", name)
}

private fun JTextField.onChange(block: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = block()
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
                cell(JBLabel(message("init.leave.layer", leaveLayerFor), AllIcons.General.Information, JBLabel.LEFT))
            }
        }
        row { cell(staleNote) }
        row { cell(restoreBox) }
        row { cell(preview) }.topGap(TopGap.SMALL)
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
 */
internal class AddLayerDialog(
    project: Project,
    private val state: StackState.Loaded,
    private val top: StackLayer,
    private val needsTop: Boolean,
    private val topIsEmpty: Boolean,
) : DialogWrapper(project) {

    private val branchField = JBTextField(prefixOf(top.branch), 32)
    private val commitBox = JBCheckBox(message("add.commit"))
    private val messageField = JBTextField(32)
    private val stagingAll = JBRadioButton(message("add.staging.all"), true)
    private val stagingTracked = JBRadioButton(message("add.staging.tracked"))
    private val stagingStaged = JBRadioButton(message("add.staging.staged"))
    private val emptyWarning = JBLabel(message("add.empty.top", top.branch), AllIcons.General.Warning, JBLabel.LEFT)
    private val preview = CommandPreview()

    val branch: String get() = branchField.text.trim()

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
        branchField.caretPosition = branchField.text.length
        branchField.onChange(::update)
        messageField.onChange(::update)
        commitBox.addActionListener { update() }
        listOf(stagingAll, stagingTracked, stagingStaged).forEach { it.addActionListener { update() } }
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
        row { cell(preview) }
    }

    override fun getPreferredFocusedComponent(): JComponent = branchField

    override fun doValidate(): ValidationInfo? {
        GhCommands.branchNameProblem(branch)?.let { return ValidationInfo(branchProblemText(it, branch), branchField) }
        if (state.snapshot.layers.any { it.branch == branch } || branch == state.snapshot.trunk) {
            return ValidationInfo(message("add.exists", branch), branchField)
        }
        if (commitBox.isSelected && messageField.text.isBlank()) return ValidationInfo(message("add.message.empty"), messageField)
        return null
    }

    private fun update() {
        val committing = commitBox.isSelected
        listOf(messageField, stagingAll, stagingTracked, stagingStaged).forEach { it.isEnabled = committing }
        emptyWarning.isVisible = committing && topIsEmpty
        val commands = buildList {
            if (needsTop) add(GhCommands.top())
            add(GhCommands.add(branch.ifEmpty { "…" }, commit?.let { if (it.message.isEmpty()) it.copy(message = "…") else it }))
        }
        preview.show(commands)
    }

    private companion object {
        /** `feat/payment-domain` -> `feat/`: la capa nueva suele seguir la convencion de la anterior. */
        fun prefixOf(branch: String): String = branch.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    }
}
