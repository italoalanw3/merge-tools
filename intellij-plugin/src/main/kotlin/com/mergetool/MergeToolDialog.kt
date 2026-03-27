package com.mergetool

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.*
import javax.swing.*
import javax.swing.border.TitledBorder

class MergeToolDialog(private val project: Project) : DialogWrapper(project, true) {

    private val repoManager = GitRepositoryManager.getInstance(project)
    private val repositories = repoManager.repositories
    private val allBranches = mutableListOf<String>()

    // UI Components
    private lateinit var repoCombo: JComboBox<String>
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

    private var selectedRepo: GitRepository? = null

    init {
        title = "Git Merge Tool"
        setSize(800, 700)
        init()
        loadBranches()
        loadLastProfile()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8))
        mainPanel.preferredSize = Dimension(780, 650)

        // ─── Top: Repo + Profile + Source ───
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Repositório
        val repoPanel = JPanel(BorderLayout(8, 0)).apply {
            border = createSection("Repositorio")
        }
        repoCombo = JComboBox(repositories.map { it.root.presentableUrl }.toTypedArray())
        repoCombo.addActionListener { loadBranches() }
        repoPanel.add(repoCombo, BorderLayout.CENTER)
        topPanel.add(repoPanel)

        // Perfil + Source (lado a lado)
        val configRow = JPanel(GridLayout(1, 2, 8, 0))

        // Perfil
        val profilePanel = JPanel(BorderLayout(4, 4)).apply {
            border = createSection("Perfil")
        }
        val profileRow = JPanel(BorderLayout(4, 0))
        profileCombo = JComboBox<String>()
        updateProfileList()
        profileCombo.addActionListener { loadProfile() }
        profileRow.add(profileCombo, BorderLayout.CENTER)

        val profileBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        profileBtns.add(JButton("Salvar").apply { addActionListener { saveProfile() } })
        profileBtns.add(JButton("Novo").apply { addActionListener { newProfile() } })
        profileBtns.add(JButton("Excluir").apply { addActionListener { deleteProfile() } })
        profileRow.add(profileBtns, BorderLayout.EAST)
        profilePanel.add(profileRow, BorderLayout.CENTER)
        configRow.add(profilePanel)

        // Source Branch com busca
        val sourcePanel = JPanel(BorderLayout(4, 4)).apply {
            border = createSection("Branch de Origem (source)")
        }
        sourceField = SearchTextField(false)
        sourceField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                filterSourceList()
            }
        })
        sourcePanel.add(sourceField, BorderLayout.NORTH)

        sourceListModel = DefaultListModel()
        sourceList = JList(sourceListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 5
            addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    selectedValue?.let { v -> sourceField.text = v }
                }
            }
        }
        sourcePanel.add(JBScrollPane(sourceList).apply {
            preferredSize = Dimension(0, 120)
        }, BorderLayout.CENTER)
        configRow.add(sourcePanel)

        topPanel.add(configRow)
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // ─── Center: Target Branches ───
        val targetOuterPanel = JPanel(BorderLayout(4, 4)).apply {
            border = createSection("Branches de Destino — selecione quais vao receber o merge")
        }

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
        targetBtns.add(JButton("Todos").apply {
            addActionListener { toggleVisible(true) }
        })
        targetBtns.add(JButton("Nenhum").apply {
            addActionListener { toggleVisible(false) }
        })
        targetBtns.add(JButton("Do Perfil").apply {
            addActionListener { selectFromProfile() }
        })
        targetTopRow.add(targetBtns, BorderLayout.EAST)
        targetOuterPanel.add(targetTopRow, BorderLayout.NORTH)

        targetPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val targetScroll = JBScrollPane(targetPanel).apply {
            preferredSize = Dimension(0, 180)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        targetOuterPanel.add(targetScroll, BorderLayout.CENTER)

        statusLabel = JBLabel("0 branches selecionadas")
        statusLabel.border = JBUI.Borders.emptyTop(4)
        targetOuterPanel.add(statusLabel, BorderLayout.SOUTH)

        mainPanel.add(targetOuterPanel, BorderLayout.CENTER)

        // ─── Bottom: Log ───
        val logPanel = JPanel(BorderLayout()).apply {
            border = createSection("Log de Execucao")
        }
        logArea = JBTextArea().apply {
            isEditable = false
            font = Font("Consolas", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }
        logPanel.add(JBScrollPane(logArea).apply {
            preferredSize = Dimension(0, 160)
        }, BorderLayout.CENTER)
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

    private fun createSection(title: String): TitledBorder {
        return BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title
        )
    }

    // ─── Branch loading ───

    private fun loadBranches() {
        val idx = repoCombo.selectedIndex
        if (idx < 0 || idx >= repositories.size) return
        selectedRepo = repositories[idx]
        val repo = selectedRepo ?: return

        allBranches.clear()

        // Local branches
        repo.branches.localBranches.forEach { allBranches.add(it.name) }
        // Remote branches (sem prefixo origin/)
        repo.branches.remoteBranches.forEach {
            val name = it.nameForRemoteOperations
            if (name !in allBranches) allBranches.add(name)
        }

        allBranches.sort()

        // Atualizar source list
        filterSourceList()

        // Atualizar target checkboxes
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
            cb.addActionListener { updateStatus() }
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

    // ─── Profile management ───

    private fun updateProfileList() {
        profileCombo.removeAllItems()
        config.state.profiles.keys.sorted().forEach { profileCombo.addItem(it) }
    }

    private fun loadProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        val profile = config.state.profiles[name] ?: return

        sourceField.text = profile.sourceBranch

        val profileBranches = profile.targetBranches.toSet()
        for ((branch, cb) in targetCheckboxes) {
            cb.isSelected = branch in profileBranches
        }
        targetSearch.text = ""
        updateStatus()
        filterTargetBranches()

        config.state.lastProfile = name
    }

    private fun loadLastProfile() {
        val last = config.state.lastProfile
        if (last.isNotEmpty()) {
            profileCombo.selectedItem = last
        }
    }

    private fun saveProfile() {
        val name = profileCombo.selectedItem as? String
        if (name.isNullOrBlank()) {
            Messages.showWarningDialog("Selecione ou crie um perfil primeiro.", "Aviso")
            return
        }

        val selected = targetCheckboxes.filter { it.value.isSelected }.keys.toList()
        config.state.profiles[name] = MergeProfile(
            sourceBranch = sourceField.text,
            targetBranches = selected
        )
        log("Perfil '$name' salvo com ${selected.size} branches!")
    }

    private fun newProfile() {
        val name = Messages.showInputDialog(
            project, "Nome do perfil:", "Novo Perfil", null
        )
        if (!name.isNullOrBlank()) {
            config.state.profiles[name] = MergeProfile()
            updateProfileList()
            profileCombo.selectedItem = name
        }
    }

    private fun deleteProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        if (Messages.showYesNoDialog(
                project, "Excluir perfil '$name'?", "Confirmar", null
            ) == Messages.YES
        ) {
            config.state.profiles.remove(name)
            updateProfileList()
            log("Perfil '$name' excluido.")
        }
    }

    private fun selectFromProfile() {
        val name = profileCombo.selectedItem as? String ?: return
        val profile = config.state.profiles[name] ?: return
        val profileBranches = profile.targetBranches.toSet()

        for ((branch, cb) in targetCheckboxes) {
            cb.isSelected = branch in profileBranches
        }
        targetSearch.text = ""
        updateStatus()
        filterTargetBranches()
    }

    // ─── Merge execution ───

    private fun startMerge() {
        val repo = selectedRepo ?: return
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

        val confirm = Messages.showYesNoDialog(
            project,
            "Merge de '$source' para ${targets.size} branches:\n\n" +
                    targets.joinToString("\n") { "  -> $it" } +
                    "\n\nDeseja continuar?",
            "Confirmar Merge",
            null
        )
        if (confirm != Messages.YES) return

        logArea.text = ""
        log("=" .repeat(50))
        log("INICIANDO MERGE: $source -> ${targets.size} branches")
        log("=".repeat(50))

        // Executar em background thread
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            executeMerge(repo, source, targets)
        }
    }

    /**
     * Executa um comando git e retorna (sucesso, stdout, stderr).
     */
    private fun runGit(repo: GitRepository, vararg args: String): Triple<Boolean, String, String> {
        val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.OTHER)
        // Limpar e adicionar os parâmetros
        args.forEach { handler.addParameters(it) }
        handler.setSilent(true)
        handler.setStdoutSuppressed(false)

        val result = git4idea.commands.Git.getInstance().runCommand(handler)
        return Triple(
            result.success(),
            result.outputAsJoinedString,
            result.errorOutputAsJoinedString
        )
    }

    private fun gitCheckout(repo: GitRepository, branch: String): Triple<Boolean, String, String> {
        val h = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.CHECKOUT)
        h.addParameters(branch)
        h.setSilent(true)
        val r = git4idea.commands.Git.getInstance().runCommand(h)
        if (r.success()) return Triple(true, r.outputAsJoinedString, "")

        // Tentar criar branch local a partir da remota
        val h2 = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.CHECKOUT)
        h2.addParameters("-b", branch, "origin/$branch")
        h2.setSilent(true)
        val r2 = git4idea.commands.Git.getInstance().runCommand(h2)
        return Triple(r2.success(), r2.outputAsJoinedString, r2.errorOutputAsJoinedString)
    }

    private fun gitMerge(repo: GitRepository, source: String): Triple<Boolean, String, String> {
        val h = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.MERGE)
        h.addParameters(source, "--no-edit")
        h.setSilent(true)
        val r = git4idea.commands.Git.getInstance().runCommand(h)
        return Triple(r.success(), r.outputAsJoinedString, r.errorOutputAsJoinedString)
    }

    private fun gitPush(repo: GitRepository, branch: String): Triple<Boolean, String, String> {
        val h = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.PUSH)
        h.addParameters("origin", branch)
        h.setSilent(true)
        val r = git4idea.commands.Git.getInstance().runCommand(h)
        return Triple(r.success(), r.outputAsJoinedString, r.errorOutputAsJoinedString)
    }

    private fun gitMergeAbort(repo: GitRepository) {
        val h = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.MERGE)
        h.addParameters("--abort")
        h.setSilent(true)
        git4idea.commands.Git.getInstance().runCommand(h)
    }

    private fun executeMerge(repo: GitRepository, source: String, targets: List<String>) {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()

        // 1. Checkout source
        log("\n[1/2] Checkout: $source")
        val (okSrc, _, errSrc) = gitCheckout(repo, source)
        if (!okSrc) {
            log("  ERRO: $errSrc")
            showResult(successes, failures)
            return
        }
        log("  OK")

        // 2. Merge em cada target
        log("\n[2/2] Iniciando merges...\n")

        for ((i, target) in targets.withIndex()) {
            log("─".repeat(40))
            log("  [${i + 1}/${targets.size}] $source -> $target")

            // Checkout target
            log("  Checkout: $target...")
            val (okCo, _, errCo) = gitCheckout(repo, target)
            if (!okCo) {
                log("    FALHOU: $errCo")
                failures.add(target to "Checkout falhou")
                continue
            }
            log("    OK")

            // Merge
            log("  Merge: $source -> $target...")
            val (okMerge, outMerge, errMerge) = gitMerge(repo, source)

            if (okMerge) {
                log("    SUCESSO")

                // Perguntar push
                val doPush = askOnEdt("Merge em '$target' OK!\n\nFazer push para origin/$target?")
                if (doPush) {
                    log("  Push: origin/$target...")
                    val (okPush, _, errPush) = gitPush(repo, target)
                    if (okPush) {
                        log("    Push OK")
                        successes.add(target)
                    } else {
                        log("    Push FALHOU: $errPush")
                        failures.add(target to "Push falhou")
                    }
                } else {
                    log("  Push ignorado para $target")
                    successes.add("$target (sem push)")
                }
            } else {
                val combined = "$outMerge $errMerge"
                if ("CONFLICT" in combined || "conflict" in combined.lowercase()) {
                    log("    CONFLITO detectado! Abortando...")
                    gitMergeAbort(repo)
                    failures.add(target to "Conflitos de merge")
                } else {
                    log("    ERRO: $errMerge")
                    gitMergeAbort(repo)
                    failures.add(target to errMerge.take(80))
                }
            }
            log("")
        }

        // Voltar para source
        log("Voltando para: $source")
        git.checkout(repo, source, null, false, false)

        // Refresh repo
        repo.update()

        showResult(successes, failures)
    }

    private fun askOnEdt(message: String): Boolean {
        var result = false
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showYesNoDialog(project, message, "Push", null) == Messages.YES
        }
        return result
    }

    private fun showResult(successes: List<String>, failures: List<Pair<String, String>>) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
                Messages.showInfoMessage(
                    project,
                    "Todas as $total branches mergeadas com sucesso!",
                    "Merge Concluido"
                )
            } else {
                Messages.showWarningDialog(
                    project,
                    "${successes.size}/$total sucesso, ${failures.size} falhas.\nVerifique o log.",
                    "Merge Concluido"
                )
            }
        }
    }

    private fun log(msg: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            logArea.append("$msg\n")
            logArea.caretPosition = logArea.document.length
        }
    }
}
