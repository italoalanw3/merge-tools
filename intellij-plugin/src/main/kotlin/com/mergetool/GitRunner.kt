package com.mergetool

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executa comandos git via ProcessBuilder.
 * Simples, sem dependência da API Git4Idea.
 */
data class GitResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String
)

class GitRunner(private val workDir: File) {

    fun run(vararg args: String): GitResult {
        return try {
            val cmd = listOf("git") + args.toList()
            val process = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val finished = process.waitFor(120, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                GitResult(false, "", "Timeout ao executar: git ${args.joinToString(" ")}")
            } else {
                GitResult(process.exitValue() == 0, stdout, stderr)
            }
        } catch (e: Exception) {
            GitResult(false, "", "Erro: ${e.message}")
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
        // Tentar criar branch local a partir da remota
        return run("checkout", "-b", branch, "origin/$branch")
    }

    fun merge(source: String): GitResult = run("merge", source, "--no-edit")

    fun mergeAbort(): GitResult = run("merge", "--abort")

    fun push(branch: String): GitResult = run("push", "origin", branch)
}
