package com.mergetool

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.TitledBorder

class MergeToolDialog(private val project: Project) : DialogWrapper(project, true) {

    private val allBranches = mutableListOf<String>()

    // UI
    private lateinit var sourceField: SearchTextField
    private lateinit var sourceList: JList<String>
    private lateinit var sourceListModel: DefaultListModel<String>
    private lateinit var targetPanel: JPanel
    private lateinit var targetSearch: SearchTextField
    private lateinit var logArea: JBTextArea
    private lateinit var statusLabel: JBLabel
    private lateinit var profileCombo: JComboBox<String>

    private val targetCheckboxes = mutableMapOf<String, JBCheckBox>()
    private val config = MergeToolConfig.getInstance(project)

    private lateinit var git: GitRunner

    init {
        title = "Git Merge Tool"
        setSize(800, 700)
        init()

        val basePath = project.basePath
        if (basePath != null) {
            git = GitRunner(File(basePath))
            loadBranches()
        }
        loadLastProfile()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8))
        mainPanel.preferredSize = Dimension(780, 650)

        // ─── Top ───
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)

        // Perfil + Source (lado a lado)
        val configRow = JPanel(GridLayout(1, 2, 8, 0))

        // Perfil
        val profilePanel = JPanel(BorderLayout(4, 4))
        profilePanel.border = createSection("Perfil")
        val profileRow = JPanel(BorderLayout(4, 0))
        profileCombo = JComboBox<String>()
        updateProfileList()
        profileCombo.addActionListener { loadProfile() }
        profileRow.add(profileCombo, BorderLayout.CENTER)

        val profileBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        profileBtns.add(createBtn("Salvar") { saveProfile() })
        profileBtns.add(createBtn("Novo") { newProfile() })
        profileBtns.add(createBtn("Excluir") { deleteProfile() })
        profileRow.add(profileBtns, BorderLayout.EAST)
        profilePanel.add(profileRow, BorderLayout.CENTER)
        configRow.add(profilePanel)

        // Source Branch com busca
        val sourcePanel = JPanel(BorderLayout(4, 4))
        sourcePanel.border = createSection("Branch de Origem (source)")
        sourceField = SearchTextField(false)
        sourceField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                filterSourceList()
            }
        })
        sourcePanel.add(sourceField, BorderLayout.NORTH)

        sourceListModel = DefaultListModel()
        sourceList = JList(sourceListModel)
        sourceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sourceList.visibleRowCount = 5
        sourceList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val v = sourceList.selectedValue
                if (v != null) sourceField.text = v
            }
        }
        val sourceScroll = JBScrollPane(sourceList)
        sourceScroll.preferredSize = Dimension(0, 120)
        sourcePanel.add(sourceScroll, BorderLayout.CENTER)
        configRow.add(sourcePanel)

        topPanel.add(configRow)
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // ─── Center: Target Branches ───
        val targetOuterPanel = JPanel(BorderLayout(4, 4))
        targetOuterPanel.border = createSection("Branches de Destino")

        // Search + buttons
        val targetTopRow = JPanel(BorderLayout(8, 0))
        targetSearch = SearchTextField(false)
        targetSearch.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                filterTargetBranches()
            }
        })
        targetTopRow.add(targetSearch, BorderLayout.CENTER)

        val targetBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        targetBtns.add(createBtn("Todos") { toggleVisible(true) })
        targetBtns.add(createBtn("Nenhum") { toggleVisible(false) })
        targetBtns.add(createBtn("Do Perfil") { selectFromProfile() })
        targetTopRow.add(targetBtns, BorderLayout.EAST)
        targetOuterPanel.add(targetTopRow, BorderLayout.NORTH)

        targetPanel = JPanel()
        targetPanel.layout = BoxLayout(targetPanel, BoxLayout.Y_AXIS)
        val targetScroll = JBScrollPane(targetPanel)
        targetScroll.preferredSize = Dimension(0, 180)
        targetScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        targetOuterPanel.add(targetScroll, BorderLayout.CENTER)

        statusLabel = JBLabel("0 branches selecionadas")
        statusLabel.border = JBUI.Borders.emptyTop(4)
        targetOuterPanel.add(statusLabel, BorderLayout.SOUTH)

        mainPanel.add(targetOuterPanel, BorderLayout.CENTER)

        // ─── Bottom: Log ───
        val logPanel = JPanel(BorderLayout())
        logPanel.border = createSection("Log de Execucao")
        logArea = JBTextArea()
        logArea.isEditable = false
        logArea.font = Font("Consolas", Font.PLAIN, 12)
        logArea.lineWrap = true
        logArea.wrapStyleWord = true
        val logScroll = JBScrollPane(logArea)
        logScroll.preferredSize = Dimension(0, 160)
        logPanel.add(logScroll, BorderLayout.CENTER)
        mainPanel.add(logPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    override fun createActions(): Array<Action> {
        val mergeAction = object : DialogWrapperAction("INICIAR MERGE") {
            override fun doAction(e: java.awt.event.ActionEvent) {
                startMerge()
            }
        }
        return arrayOf(mergeAction, cancelAction)
    }

    private fun createSection(title: String): TitledBorder =
        BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title)

    private fun createBtn(text: String, action: () -> Unit): JButton {
        val btn = JButton(text)
        btn.addActionListener { action() }
        return btn
    }

    // ─── Branch loading ───

    private fun loadBranches() {
        allBranches.clear()
        allBranches.addAll(git.getBranches())
        filterSourceList()
        rebuildTargetCheckboxes()
        log("${allBranches.size} branches encontradas")
    }

    private fun filterSourceList() {
        val query = sourceField.text.lowercase().trim()
        sourceListModel.clear()
        for (branch in allBranches) {
            if (query.isEmpty() || query in branch.lowercase()) {
                sourceListModel.addElement(branch)
            }
        }
    }

    private fun rebuildTargetCheckboxes(selected: Set<String> = emptySet()) {
        targetPanel.removeAll()
        targetCheckboxes.clear()
        for (branch in allBranches) {
            val cb = JBCheckBox(branch, branch in selected)
            cb.addActionListener { updateStatus(); filterTargetBranches() }
            targetCheckboxes[branch] = cb
            targetPanel.add(cb)
        }
        updateStatus()
        filterTargetBranches()
        targetPanel.revalidate()
        targetPanel.repaint()
    }

    private fun filterTargetBranches() {
        val query = targetSearch.text.lowercase().trim()
        for ((branch, cb) in targetCheckboxes) {
            cb.isVisible = if (query.isNotEmpty()) {
                query in branch.lowercase()
            } else {
                cb.isSelected
            }
        }
        targetPanel.revalidate()
    }

    private fun toggleVisible(state: Boolean) {
        val query = targetSearch.text.lowercase().trim()
        for ((branch, cb) in targetCheckboxes) {
            if (query.isEmpty() || query in branch.lowercase()) {
                cb.isSelected = state
            }
        }
        updateStatus()
        filterTargetBranches()
    }

    private fun updateStatus() {
        val count = targetCheckboxes.values.count { it.isSelected }
        statusLabel.text = "$count de ${targetCheckboxes.size} branches selecionadas para merge"
    }

    // ─── Profiles ───

    private fun updateProfileList() {
        profileCombo.removeAllItems()
        config.state.profiles.keys.sorted().forEach { profileCombo.addItem(it) }
    }

    private fun loadProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        val profile = config.state.profiles[name] ?: return
        sourceField.text = profile.sourceBranch
        val set = profile.targetBranches.toSet()
        for ((branch, cb) in targetCheckboxes) { cb.isSelected = branch in set }
        targetSearch.text = ""
        updateStatus()
        filterTargetBranches()
        config.state.lastProfile = name
    }

    private fun loadLastProfile() {
        val last = config.state.lastProfile
        if (last.isNotEmpty()) profileCombo.selectedItem = last
    }

    private fun saveProfile() {
        val name = profileCombo.selectedItem as? String
        if (name.isNullOrBlank()) {
            Messages.showWarningDialog("Selecione ou crie um perfil primeiro.", "Aviso")
            return
        }
        val selected = targetCheckboxes.filter { it.value.isSelected }.keys.toList()
        config.state.profiles[name] = MergeProfile(sourceField.text, selected)
        log("Perfil '$name' salvo com ${selected.size} branches!")
    }

    private fun newProfile() {
        val name = Messages.showInputDialog(project, "Nome do perfil:", "Novo Perfil", null)
        if (!name.isNullOrBlank()) {
            config.state.profiles[name] = MergeProfile()
            updateProfileList()
            profileCombo.selectedItem = name
        }
    }

    private fun deleteProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        if (Messages.showYesNoDialog(project, "Excluir perfil '$name'?", "Confirmar", null) == Messages.YES) {
            config.state.profiles.remove(name)
            updateProfileList()
            log("Perfil '$name' excluido.")
        }
    }

    private fun selectFromProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        val profile = config.state.profiles[name] ?: return
        val set = profile.targetBranches.toSet()
        for ((branch, cb) in targetCheckboxes) { cb.isSelected = branch in set }
        targetSearch.text = ""
        updateStatus()
        filterTargetBranches()
    }

    // ─── Merge ───

    private fun startMerge() {
        val source = sourceField.text.trim()
        val targets = targetCheckboxes.filter { it.value.isSelected }.keys.toList()

        if (source.isBlank()) {
            Messages.showWarningDialog("Selecione a branch de origem.", "Aviso")
            return
        }
        if (targets.isEmpty()) {
            Messages.showWarningDialog("Selecione pelo menos uma branch de destino.", "Aviso")
            return
        }

        val msg = "Merge de '$source' para ${targets.size} branches:\n\n" +
                targets.joinToString("\n") { "  -> $it" } + "\n\nDeseja continuar?"
        if (Messages.showYesNoDialog(project, msg, "Confirmar Merge", null) != Messages.YES) return

        logArea.text = ""
        log("=".repeat(50))
        log("INICIANDO MERGE: $source -> ${targets.size} branches")
        log("=".repeat(50))

        ApplicationManager.getApplication().executeOnPooledThread {
            executeMerge(source, targets)
        }
    }

    private fun executeMerge(source: String, targets: List<String>) {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()

        // 1. Checkout source
        log("\n[1/2] Checkout: $source")
        val srcResult = git.checkout(source)
        if (!srcResult.success) {
            log("  ERRO: ${srcResult.stderr}")
            showResult(successes, failures)
            return
        }
        log("  OK")

        // 2. Merge em cada target
        log("\n[2/2] Iniciando merges...\n")

        for ((i, target) in targets.withIndex()) {
            log("-".repeat(40))
            log("  [${i + 1}/${targets.size}] $source -> $target")

            // Checkout target
            log("  Checkout: $target...")
            val coResult = git.checkout(target)
            if (!coResult.success) {
                log("    FALHOU: ${coResult.stderr}")
                failures.add(target to "Checkout falhou")
                continue
            }
            log("    OK")

            // Merge
            log("  Merge: $source -> $target...")
            val mergeResult = git.merge(source)

            if (mergeResult.success) {
                log("    SUCESSO")

                // Perguntar push
                val doPush = askOnEdt("Merge em '$target' OK!\n\nFazer push para origin/$target?")
                if (doPush) {
                    log("  Push: origin/$target...")
                    val pushResult = git.push(target)
                    if (pushResult.success) {
                        log("    Push OK")
                        successes.add(target)
                    } else {
                        log("    Push FALHOU: ${pushResult.stderr}")
                        failures.add(target to "Push falhou")
                    }
                } else {
                    log("  Push ignorado")
                    successes.add("$target (sem push)")
                }
            } else {
                val combined = "${mergeResult.stdout} ${mergeResult.stderr}"
                if ("CONFLICT" in combined.uppercase()) {
                    log("    CONFLITO detectado! Abortando...")
                    git.mergeAbort()
                    failures.add(target to "Conflitos de merge")
                } else {
                    log("    ERRO: ${mergeResult.stderr}")
                    git.mergeAbort()
                    failures.add(target to mergeResult.stderr.take(80))
                }
            }
            log("")
        }

        // Voltar para source
        log("Voltando para: $source")
        git.checkout(source)

        showResult(successes, failures)
    }

    private fun askOnEdt(message: String): Boolean {
        var result = false
        ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showYesNoDialog(project, message, "Push", null) == Messages.YES
        }
        return result
    }

    private fun showResult(successes: List<String>, failures: List<Pair<String, String>>) {
        ApplicationManager.getApplication().invokeLater {
            log("\n" + "=".repeat(50))
            log("RESUMO FINAL")
            log("=".repeat(50))
            if (successes.isNotEmpty()) {
                log("\nSucesso:")
                successes.forEach { log("  [OK] $it") }
            }
            if (failures.isNotEmpty()) {
                log("\nFalhas:")
                failures.forEach { (b, r) -> log("  [FALHOU] $b: $r") }
            }
            val total = successes.size + failures.size
            log("\nTotal: ${successes.size}/$total branches mergeadas com sucesso")

            if (failures.isEmpty()) {
                Messages.showInfoMessage(project, "Todas as $total branches mergeadas!", "Concluido")
            } else {
                Messages.showWarningDialog(project,
                    "${successes.size}/$total sucesso, ${failures.size} falhas.\nVerifique o log.", "Concluido")
            }
        }
    }

    private fun log(msg: String) {
        ApplicationManager.getApplication().invokeLater {
            logArea.append("$msg\n")
            logArea.caretPosition = logArea.document.length
        }
    }
}
