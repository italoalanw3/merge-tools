package com.mergetool

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class OpenMergeToolAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            Messages.showWarningDialog("Abra um projeto primeiro.", "Git Merge Tool")
            return
        }
        val dialog = MergeToolDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        // Sempre visivel, mesmo sem projeto aberto
        e.presentation.isEnabled = e.project != null
        e.presentation.isVisible = true
    }
}
