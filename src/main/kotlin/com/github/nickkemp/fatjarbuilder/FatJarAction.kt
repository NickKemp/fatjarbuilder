package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.Messages

class FatJarAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun findModule(e: AnActionEvent): Module? {
        val project = e.project ?: return null
        val virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE) ?: return null
        return ModuleUtilCore.findModuleForFile(virtualFile, project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val module = findModule(e)
        if (module == null) {
            Messages.showErrorDialog(
                e.project,
                "Please right-click on a module or file to build a Fat JAR.",
                "FatJar Builder"
            )
            return
        }
        val dialog = FatJarDialog(module)
        if (dialog.showAndGet()) {
            FatJarBuilder(module, dialog.getSettings()).build()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = findModule(e) != null
    }
}
