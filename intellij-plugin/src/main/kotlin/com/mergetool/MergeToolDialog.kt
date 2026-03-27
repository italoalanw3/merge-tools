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
import javax.swing.JOptionPane

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
    private lateinit var verboseCheckbox: JBCheckBox

    private val targetCheckboxes = mutableMapOf<String, JBCheckBox>()
    private val config = MergeToolConfig.getInstance(project)

    private lateinit var git: GitRunner
    private lateinit var mergeAction: DialogWrapperAction
    private var mergeRunning = false

    init {
        title = "Git Merge Tool"
        isModal = false  // Nao-modal: permite popups aparecerem por cima
        setSize(800, 700)
        init()

        val basePath = project.basePath
        if (basePath != null) {
            git = GitRunner(File(basePath), project)
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
        val logPanel = JPanel(BorderLayout(4, 4))
        logPanel.border = createSection("Log de Execucao")

        verboseCheckbox = JBCheckBox("Log verbose (mostrar comandos git)", false)
        logPanel.add(verboseCheckbox, BorderLayout.NORTH)

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
        mergeAction = object : DialogWrapperAction("INICIAR MERGE") {
            override fun doAction(e: java.awt.event.ActionEvent) {
                if (!mergeRunning) startMerge()
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
            JOptionPane.showMessageDialog(this.window, "Selecione a branch de origem.", "Aviso", JOptionPane.WARNING_MESSAGE)
            return
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this.window, "Selecione pelo menos uma branch de destino.", "Aviso", JOptionPane.WARNING_MESSAGE)
            return
        }

        val msg = "Merge de '$source' para ${targets.size} branches:\n\n" +
                targets.joinToString("\n") { "  -> $it" } + "\n\nDeseja continuar?"
        val confirm = JOptionPane.showConfirmDialog(this.window, msg, "Confirmar Merge", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return

        mergeRunning = true
        mergeAction.isEnabled = false
        logArea.text = ""
        log("=".repeat(50))
        log("INICIANDO MERGE: $source -> ${targets.size} branches")
        log("=".repeat(50))

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                executeMerge(source, targets)
            } finally {
                SwingUtilities.invokeLater {
                    mergeRunning = false
                    mergeAction.isEnabled = true
                }
            }
        }
    }

    private fun executeMerge(source: String, targets: List<String>) {
        // Configurar verbose
        val verbose = verboseCheckbox.isSelected
        git.onVerbose = if (verbose) { msg -> log(msg) } else null

        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()

        log("Diretorio: ${project.basePath}")
        log("Verbose: ${if (verbose) "SIM" else "NAO"}")
        log("")

        // 1. Checkout source
        log(">> Checkout na branch de origem: $source")
        val srcResult = git.checkout(source)
        if (!srcResult.success) {
            log("ERRO ao fazer checkout em '$source': ${srcResult.stderr}")
            log("Verifique se a branch existe e se o repositorio esta acessivel.")
            showResult(successes, failures)
            return
        }
        log("OK - branch de origem '$source' ativa")

        // 2. Merge em cada target
        log("")
        log("Iniciando merge em ${targets.size} branch(es)...")
        log("")

        for ((i, target) in targets.withIndex()) {
            log("=" .repeat(50))
            log("[${i + 1}/${targets.size}] Processando: $target")
            log("=" .repeat(50))

            // Checkout target
            log(">> git checkout $target")
            val coResult = git.checkout(target)
            if (!coResult.success) {
                log("ERRO checkout: ${coResult.stderr}")
                failures.add(target to "Checkout falhou: ${coResult.stderr.take(100)}")
                log("")
                continue
            }
            log("OK - na branch '$target'")

            // Merge
            log(">> git merge $source --no-edit")
            val mergeResult = git.merge(source)

            if (mergeResult.success) {
                if ("Already up to date" in mergeResult.stdout) {
                    log("Ja esta atualizado - nada a fazer")
                    successes.add("$target (ja atualizado)")
                } else {
                    log("MERGE OK - sem conflitos")
                    handlePushAfterMerge(target, successes, failures)
                }
            } else {
                val combined = "${mergeResult.stdout} ${mergeResult.stderr}"
                log("Merge retornou erro. stdout: ${mergeResult.stdout.take(200)}")
                log("Merge retornou erro. stderr: ${mergeResult.stderr.take(200)}")
                if ("CONFLICT" in combined.uppercase()) {
                    val conflictFiles = git.getConflictFiles()
                    log("CONFLITO detectado em ${conflictFiles.size} arquivo(s):")
                    conflictFiles.forEach { log("  >> $it") }

                    val choice = askConflictChoice(target, conflictFiles)

                    log("Usuario escolheu: ${choice.name}")
                    when (choice) {
                        ConflictChoice.KEEP_DEST -> {
                            // --ours = manter o codigo da branch atual (destino)
                            log(">> git checkout --ours (mantendo codigo de '$target')")
                            val oursResult = git.checkoutOurs(conflictFiles)
                            if (oursResult.success) {
                                log(">> git add (marcando conflitos como resolvidos)")
                                git.addFiles(conflictFiles)
                                log(">> git commit --no-edit")
                                val commitResult = git.commitMerge()
                                if (commitResult.success) {
                                    log("OK - conflitos resolvidos mantendo codigo do DESTINO")
                                    handlePushAfterMerge(target, successes, failures)
                                } else {
                                    log("COMMIT FALHOU: ${commitResult.stderr}")
                                    git.mergeAbort()
                                    failures.add(target to "Commit falhou apos checkout --ours")
                                }
                            } else {
                                log("ERRO checkout --ours: ${oursResult.stderr}")
                                git.mergeAbort()
                                failures.add(target to "checkout --ours falhou")
                            }
                        }
                        ConflictChoice.ACCEPT_SOURCE -> {
                            // --theirs = aceitar o codigo da branch source (origem)
                            log(">> git checkout --theirs (aceitando codigo de '$source')")
                            val theirsResult = git.checkoutTheirs(conflictFiles)
                            if (theirsResult.success) {
                                log(">> git add (marcando conflitos como resolvidos)")
                                git.addFiles(conflictFiles)
                                log(">> git commit --no-edit")
                                val commitResult = git.commitMerge()
                                if (commitResult.success) {
                                    log("OK - conflitos resolvidos aceitando codigo da ORIGEM")
                                    handlePushAfterMerge(target, successes, failures)
                                } else {
                                    log("COMMIT FALHOU: ${commitResult.stderr}")
                                    git.mergeAbort()
                                    failures.add(target to "Commit falhou apos checkout --theirs")
                                }
                            } else {
                                log("ERRO checkout --theirs: ${theirsResult.stderr}")
                                git.mergeAbort()
                                failures.add(target to "checkout --theirs falhou")
                            }
                        }
                        ConflictChoice.RESOLVE -> {
                            // Abrir arquivos no IDE para resolucao manual
                            openConflictFiles(conflictFiles)
                            log("Arquivos abertos no editor. Aguardando resolucao manual...")
                            val resolved = askOnEdt(
                                "Resolva os conflitos no IDE e clique SIM quando terminar.\n\n" +
                                "Arquivos com conflito:\n" +
                                conflictFiles.joinToString("\n") { "  - $it" } +
                                "\n\nConflitos resolvidos?"
                            )
                            if (resolved) {
                                log(">> git add -A")
                                git.addAll()
                                log(">> git commit --no-edit")
                                val commitResult = git.commitMerge()
                                if (commitResult.success) {
                                    log("COMMIT OK - conflitos resolvidos manualmente")
                                    handlePushAfterMerge(target, successes, failures)
                                } else {
                                    log("COMMIT FALHOU: ${commitResult.stderr}")
                                    log("Ainda ha conflitos nao resolvidos. Abortando merge...")
                                    git.mergeAbort()
                                    failures.add(target to "Conflitos nao resolvidos")
                                }
                            } else {
                                log("Cancelado pelo usuario. Abortando merge...")
                                git.mergeAbort()
                                failures.add(target to "Conflitos - cancelado pelo usuario")
                            }
                        }
                        ConflictChoice.SKIP -> {
                            log("Pulando branch '$target'. Abortando merge nesta branch...")
                            git.mergeAbort()
                            failures.add(target to "Conflitos - pulado pelo usuario")
                        }
                        ConflictChoice.STOP -> {
                            log("PARANDO toda a execucao por solicitacao do usuario.")
                            git.mergeAbort()
                            failures.add(target to "Conflitos - execucao parada")
                            break
                        }
                    }
                } else {
                    log("ERRO desconhecido no merge: ${mergeResult.stderr}")
                    git.mergeAbort()
                    failures.add(target to "Erro: ${mergeResult.stderr.take(100)}")
                }
            }
            log("")
        }

        // Voltar para source
        log(">> Retornando para branch de origem: $source")
        git.checkout(source)
        log("OK - de volta em '$source'")

        showResult(successes, failures)
    }

    private fun handlePushAfterMerge(
        target: String,
        successes: MutableList<String>,
        failures: MutableList<Pair<String, String>>
    ) {
        val doPush = askOnEdt("Merge em '$target' concluido!\n\nFazer push para origin/$target?")
        if (doPush) {
            log(">> git push origin $target")
            val pushResult = git.push(target)
            if (pushResult.success) {
                log("PUSH OK")
                successes.add(target)
            } else {
                log("PUSH FALHOU: ${pushResult.stderr}")
                failures.add(target to "Push falhou: ${pushResult.stderr.take(100)}")
            }
        } else {
            log("Push ignorado pelo usuario")
            successes.add("$target (sem push)")
        }
    }

    private enum class ConflictChoice { KEEP_DEST, ACCEPT_SOURCE, RESOLVE, SKIP, STOP }

    private fun askConflictChoice(branch: String, files: List<String>): ConflictChoice {
        val parentWindow = this.window
        var choice = ConflictChoice.SKIP
        ApplicationManager.getApplication().invokeAndWait {
            val fileList = files.joinToString("\n") { "  - $it" }
            val options = arrayOf(
                "Manter DESTINO ($branch)",
                "Aceitar ORIGEM (source)",
                "Resolver no IDE",
                "Pular esta branch",
                "Parar tudo"
            )
            val result = JOptionPane.showOptionDialog(
                parentWindow,
                "Conflitos detectados ao mergear em '$branch':\n\n$fileList\n\n" +
                "Manter DESTINO = fica o codigo que ja esta em '$branch'\n" +
                "Aceitar ORIGEM = substitui pelo codigo da branch source\n\n" +
                "O que deseja fazer?",
                "Conflito de Merge",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
            )
            choice = when (result) {
                0 -> ConflictChoice.KEEP_DEST
                1 -> ConflictChoice.ACCEPT_SOURCE
                2 -> ConflictChoice.RESOLVE
                3 -> ConflictChoice.SKIP
                else -> ConflictChoice.STOP
            }
        }
        return choice
    }

    private fun openConflictFiles(files: List<String>) {
        ApplicationManager.getApplication().invokeLater {
            val basePath = project.basePath ?: return@invokeLater
            for (filePath in files) {
                val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath("$basePath/$filePath")
                if (vFile != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(project).openFile(vFile, true)
                }
            }
        }
    }

    private fun askOnEdt(message: String): Boolean {
        val parentWindow = this.window
        var result = false
        ApplicationManager.getApplication().invokeAndWait {
            val answer = JOptionPane.showConfirmDialog(
                parentWindow,
                message,
                "Confirmar",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            result = answer == JOptionPane.YES_OPTION
        }
        return result
    }

    private fun showResult(successes: List<String>, failures: List<Pair<String, String>>) {
        val parentWindow = this.window
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
                JOptionPane.showMessageDialog(
                    parentWindow, "Todas as $total branches mergeadas!", "Concluido", JOptionPane.INFORMATION_MESSAGE)
            } else {
                JOptionPane.showMessageDialog(
                    parentWindow,
                    "${successes.size}/$total sucesso, ${failures.size} falhas.\nVerifique o log.",
                    "Concluido", JOptionPane.WARNING_MESSAGE)
            }
        }
    }

    private fun log(msg: String) {
        val timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        val line = "[$timestamp] $msg\n"
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(line)
            logArea.caretPosition = logArea.document.length
        } else {
            SwingUtilities.invokeLater {
                logArea.append(line)
                logArea.caretPosition = logArea.document.length
            }
            // Pequena pausa para dar tempo da UI atualizar
            Thread.sleep(50)
        }
    }
}
