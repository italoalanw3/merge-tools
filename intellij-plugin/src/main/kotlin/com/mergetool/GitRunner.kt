package com.mergetool

import com.intellij.openapi.project.Project
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.charset.StandardCharsets

data class GitResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val command: String
)

class GitRunner(private val workDir: File, private val project: Project) {

    /** Callback para log verbose */
    var onVerbose: ((String) -> Unit)? = null

    /**
     * Descobre o caminho do git configurado no IntelliJ.
     */
    private fun getGitExecutable(): String {
        return try {
            val gitClass = Class.forName("git4idea.config.GitExecutableManager")
            val instance = gitClass.getMethod("getInstance").invoke(null)
            val pathMethod = gitClass.getMethod("getPathToGit", Project::class.java)
            pathMethod.invoke(instance, project) as? String ?: "git"
        } catch (e: Exception) {
            "git"
        }
    }

    fun run(vararg args: String): GitResult {
        val cmdStr = "git ${args.joinToString(" ")}"
        onVerbose?.invoke("  \$ $cmdStr")

        return try {
            val commandLine = GeneralCommandLine()
            commandLine.exePath = getGitExecutable()
            commandLine.addParameters(*args)
            commandLine.setWorkDirectory(workDir)
            commandLine.charset = StandardCharsets.UTF_8

            // Usar o askpass helper do IntelliJ para credenciais
            try {
                val helperClass = Class.forName("git4idea.commands.GitHandlerAuthenticationManager")
                // O IntelliJ injeta GIT_ASKPASS automaticamente via environment
            } catch (_: Exception) { }

            val handler = CapturingProcessHandler(commandLine)
            val output = handler.runProcess(30_000) // 30s timeout

            val stdout = output.stdout.trim()
            val stderr = output.stderr.trim()
            val exitCode = output.exitCode

            if (output.isTimeout) {
                onVerbose?.invoke("  TIMEOUT (30s)!")
                GitResult(false, "", "TIMEOUT (30s): $cmdStr", cmdStr)
            } else {
                if (stdout.isNotEmpty()) onVerbose?.invoke("  stdout: $stdout")
                if (stderr.isNotEmpty()) onVerbose?.invoke("  stderr: $stderr")
                onVerbose?.invoke("  exit: $exitCode")
                GitResult(exitCode == 0, stdout, stderr, cmdStr)
            }
        } catch (e: Exception) {
            onVerbose?.invoke("  EXCEPTION: ${e.message}")
            GitResult(false, "", "Exception: ${e.message}", cmdStr)
        }
    }

    fun getBranches(): List<String> {
        val result = run("branch", "-a")
        if (!result.success) return emptyList()

        val branches = mutableListOf<String>()
        for (line in result.stdout.lines()) {
            var branch = line.trim()
            if (branch.startsWith("* ")) branch = branch.substring(2)
            branch = branch.trim()
            if (branch.isEmpty() || "HEAD" in branch) continue
            if (branch.startsWith("remotes/origin/")) {
                branch = branch.substring("remotes/origin/".length)
            }
            if (branch !in branches) branches.add(branch)
        }
        return branches.sorted()
    }

    fun checkout(branch: String): GitResult {
        val r = run("checkout", branch)
        if (r.success) return r
        return run("checkout", "-b", branch, "origin/$branch")
    }

    fun merge(source: String): GitResult = run("merge", source, "--no-edit")

    fun mergeAbort(): GitResult = run("merge", "--abort")

    fun addAll(): GitResult = run("add", "-A")

    fun addFiles(files: List<String>): GitResult {
        val args = mutableListOf("add")
        args.addAll(files)
        return run(*args.toTypedArray())
    }

    fun commitMerge(): GitResult = run("commit", "--no-edit")

    fun getConflictFiles(): List<String> {
        val result = run("diff", "--name-only", "--diff-filter=U")
        if (!result.success || result.stdout.isBlank()) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    /** Resolve conflitos mantendo o codigo da branch DESTINO (atual) */
    fun checkoutOurs(files: List<String>): GitResult {
        val args = mutableListOf("checkout", "--ours")
        args.addAll(files)
        return run(*args.toTypedArray())
    }

    /** Resolve conflitos aceitando o codigo da branch ORIGEM (source) */
    fun checkoutTheirs(files: List<String>): GitResult {
        val args = mutableListOf("checkout", "--theirs")
        args.addAll(files)
        return run(*args.toTypedArray())
    }

    fun push(branch: String): GitResult {
        // Tentar usar a API git4idea do IntelliJ (que gerencia credenciais automaticamente)
        try {
            return pushViaIde(branch)
        } catch (e: Exception) {
            onVerbose?.invoke("  git4idea push indisponivel (${e.message}), usando fallback...")
        }
        // Fallback: comando direto com timeout maior
        return runWithTimeout("push", timeout = 120_000, "origin", branch)
    }

    /**
     * Obtem o ClassLoader do plugin Git4Idea (necessario para acessar suas classes via reflection).
     */
    private fun getGit4IdeaClassLoader(): ClassLoader {
        val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")
        val findId = pluginIdClass.getMethod("findId", String::class.java)
        val gitPluginId = findId.invoke(null, "Git4Idea")

        val pluginManagerClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore")
        val getPlugin = pluginManagerClass.getMethod("getPlugin", pluginIdClass)
        val plugin = getPlugin.invoke(null, gitPluginId)
            ?: throw Exception("Plugin Git4Idea nao encontrado")

        val getClassLoader = plugin.javaClass.getMethod("getPluginClassLoader")
        return getClassLoader.invoke(plugin) as ClassLoader
    }

    /**
     * Push usando a API interna do IntelliJ (git4idea).
     * Isso permite que o IntelliJ gerencie credenciais automaticamente,
     * da mesma forma que o push pelo menu Git do IntelliJ funciona.
     */
    private fun pushViaIde(branch: String): GitResult {
        val cmdStr = "git push origin $branch (via IDE)"
        onVerbose?.invoke("  \$ $cmdStr")

        // Usar o ClassLoader do plugin Git4Idea para carregar as classes
        val gitCL = getGit4IdeaClassLoader()
        onVerbose?.invoke("  ClassLoader do Git4Idea obtido com sucesso")

        // Encontrar o VirtualFile do diretorio de trabalho
        val vfsClass = Class.forName("com.intellij.openapi.vfs.LocalFileSystem")
        val vfs = vfsClass.getMethod("getInstance").invoke(null)
        val vFile = vfsClass.getMethod("findFileByPath", String::class.java)
            .invoke(vfs, workDir.absolutePath)
            ?: throw Exception("VirtualFile nao encontrado para ${workDir.absolutePath}")

        // GitCommand.PUSH (carregado com o ClassLoader do git4idea)
        val gitCommandClass = Class.forName("git4idea.commands.GitCommand", true, gitCL)
        val pushCommand = gitCommandClass.getField("PUSH").get(null)

        // Criar GitLineHandler(project, root, command)
        val handlerClass = Class.forName("git4idea.commands.GitLineHandler", true, gitCL)
        val vfClass = Class.forName("com.intellij.openapi.vfs.VirtualFile")
        val handler = handlerClass.getConstructor(
            Project::class.java, vfClass, gitCommandClass
        ).newInstance(project, vFile, pushCommand)

        // addParameters - tentar varargs (String[]) primeiro, depois List
        try {
            val addParamsMethod = handlerClass.getMethod("addParameters", Array<String>::class.java)
            addParamsMethod.invoke(handler, arrayOf("origin", branch))
        } catch (_: NoSuchMethodException) {
            try {
                val addParamsMethod = handlerClass.getMethod("addParameters", List::class.java)
                addParamsMethod.invoke(handler, listOf("origin", branch))
            } catch (_: NoSuchMethodException) {
                // Tentar adicionar um por um
                val addParamMethod = handlerClass.getMethod("addParameters", String::class.java)
                addParamMethod.invoke(handler, "origin")
                addParamMethod.invoke(handler, branch)
            }
        }
        onVerbose?.invoke("  Handler criado, executando push...")

        // Git.getInstance().runCommand(handler)
        val gitClass = Class.forName("git4idea.commands.Git", true, gitCL)
        val git = gitClass.getMethod("getInstance").invoke(null)

        // runCommand aceita GitLineHandler (ou seu parent GitHandler)
        val gitHandlerClass = Class.forName("git4idea.commands.GitHandler", true, gitCL)
        val runMethod = try {
            gitClass.getMethod("runCommand", handlerClass)
        } catch (_: NoSuchMethodException) {
            gitClass.getMethod("runCommand", gitHandlerClass)
        }
        val result = runMethod.invoke(git, handler)

        // Extrair resultado do GitCommandResult
        val resultClass = result.javaClass
        val success = resultClass.getMethod("success").invoke(result) as Boolean

        val stdout = try {
            resultClass.getMethod("getOutputAsJoinedString").invoke(result) as String
        } catch (_: Exception) { "" }

        val stderr = try {
            resultClass.getMethod("getErrorOutputAsJoinedString").invoke(result) as String
        } catch (_: Exception) { "" }

        if (stdout.isNotEmpty()) onVerbose?.invoke("  stdout: $stdout")
        if (stderr.isNotEmpty()) onVerbose?.invoke("  stderr: $stderr")
        onVerbose?.invoke("  success: $success")

        return GitResult(success, stdout, stderr, cmdStr)
    }

    /**
     * Executa comando git com timeout customizado (para operacoes de rede).
     */
    private fun runWithTimeout(command: String, timeout: Long, vararg extraArgs: String): GitResult {
        val allArgs = arrayOf(command) + extraArgs
        val cmdStr = "git ${allArgs.joinToString(" ")}"
        onVerbose?.invoke("  \$ $cmdStr (timeout: ${timeout / 1000}s)")

        return try {
            val commandLine = GeneralCommandLine()
            commandLine.exePath = getGitExecutable()
            commandLine.addParameters(*allArgs)
            commandLine.setWorkDirectory(workDir)
            commandLine.charset = StandardCharsets.UTF_8

            val handler = CapturingProcessHandler(commandLine)
            val output = handler.runProcess(timeout.toInt())

            val stdout = output.stdout.trim()
            val stderr = output.stderr.trim()
            val exitCode = output.exitCode

            if (output.isTimeout) {
                onVerbose?.invoke("  TIMEOUT (${timeout / 1000}s)!")
                GitResult(false, "", "TIMEOUT: $cmdStr", cmdStr)
            } else {
                if (stdout.isNotEmpty()) onVerbose?.invoke("  stdout: $stdout")
                if (stderr.isNotEmpty()) onVerbose?.invoke("  stderr: $stderr")
                onVerbose?.invoke("  exit: $exitCode")
                GitResult(exitCode == 0, stdout, stderr, cmdStr)
            }
        } catch (e: Exception) {
            onVerbose?.invoke("  EXCEPTION: ${e.message}")
            GitResult(false, "", "Exception: ${e.message}", cmdStr)
        }
    }
}
