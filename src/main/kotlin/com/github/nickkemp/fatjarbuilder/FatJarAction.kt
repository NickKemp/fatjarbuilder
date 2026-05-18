package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project

class FatJarAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun findModule(e: AnActionEvent): Module? {
        val project = e.project ?: return null
        val virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE)
        return if (virtualFile != null) {
            ModuleUtilCore.findModuleForFile(virtualFile, project)
        } else null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Try to get module from selected file first
        val module = findModule(e) ?: pickModule(project)

        if (module == null) {
            Messages.showErrorDialog(
                project,
                "Please select a module to build a Fat JAR for.",
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
        // Always visible — if no module selected we show a picker
        e.presentation.isEnabledAndVisible = e.project != null
    }


    /**
     * Shows a module picker dialog when no module can be determined
     * from the current selection — used when triggered from the Build menu.
     */
    private fun pickModule(project: Project): Module? {
        val modules = ModuleManager.getInstance(project).modules
            .sortedBy { it.name }

        if (modules.isEmpty()) return null

        if (modules.size == 1) return modules[0]

        val names = modules.map { it.name }.toTypedArray()
        val choice = Messages.showChooseDialog(
            project,
            "Select module to build Fat JAR for:",
            "FatJar Builder",
            null,
            names,
            names[0]
        )

        return if (choice >= 0) modules[choice] else null
    }
}