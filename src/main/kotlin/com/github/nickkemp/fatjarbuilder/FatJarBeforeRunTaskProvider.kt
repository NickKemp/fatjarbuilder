package com.github.nickkemp.fatjarbuilder

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/**
 * Provides a "Build Fat JAR" task that can be added to any run configuration
 * as a Before launch step.
 *
 * Appears in:
 *   Run -> Edit Configurations -> Before launch -> + -> Build Fat JAR
 */
class FatJarBeforeRunTaskProvider : BeforeRunTaskProvider<FatJarBeforeRunTaskProvider.FatJarBeforeRunTask>() {

    companion object {
        val ID = Key.create<FatJarBeforeRunTask>("FatJarBuilder.BeforeRunTask")
    }

    // Task definition

    class FatJarBeforeRunTask : BeforeRunTask<FatJarBeforeRunTask>(ID) {

        var moduleName: String = ""

        override fun writeExternal(element: org.jdom.Element) {
            super.writeExternal(element)
            element.setAttribute("moduleName", moduleName)
        }

        override fun readExternal(element: org.jdom.Element) {
            super.readExternal(element)
            moduleName = element.getAttributeValue("moduleName") ?: ""
        }
    }

    // Provider metadata

    override fun getId(): Key<FatJarBeforeRunTask> = ID

    override fun getName(): String = "Build Fat JAR"

    override fun getDescription(task: FatJarBeforeRunTask): String {
        return if (task.moduleName.isBlank()) "Build Fat JAR"
        else "Build Fat JAR - ${task.moduleName}"
    }

    override fun getIcon(): javax.swing.Icon = AllIcons.Nodes.Artifact

    override fun getTaskIcon(task: FatJarBeforeRunTask): javax.swing.Icon =
        AllIcons.Nodes.Artifact

    override fun createTask(runConfiguration: RunConfiguration): FatJarBeforeRunTask =
        FatJarBeforeRunTask()

    // Configure task

    override fun configureTask(
        context: DataContext,
        configuration: RunConfiguration,
        task: FatJarBeforeRunTask
    ): Promise<Boolean> {
        val promise = AsyncPromise<Boolean>()
        val project = configuration.project
        val modules = ModuleManager.getInstance(project).modules
            .map { it.name }
            .sorted()

        if (modules.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project,
                    "No modules found in this project.", "FatJar Builder")
                promise.setResult(false)
            }
            return promise
        }

        ApplicationManager.getApplication().invokeLater {
            val selected = Messages.showEditableChooseDialog(
                "Select module to build Fat JAR for:",
                "FatJar Builder - Select Module",
                null,
                modules.toTypedArray(),
                if (task.moduleName.isBlank()) modules[0] else task.moduleName,
                null
            )
            if (selected != null) {
                task.moduleName = selected
                promise.setResult(true)
            } else {
                promise.setResult(false)
            }
        }
        return promise
    }

    override fun canExecuteTask(
        configuration: RunConfiguration,
        task: FatJarBeforeRunTask
    ): Boolean = task.moduleName.isNotBlank()

    // Execute task

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        env: ExecutionEnvironment,
        task: FatJarBeforeRunTask
    ): Boolean {
        val project = configuration.project

        val module = ModuleManager.getInstance(project).modules
            .firstOrNull { it.name.equals(task.moduleName, ignoreCase = true) }
            ?: run {
                showError(project, "Module '${task.moduleName}' not found")
                return false
            }

        val settings = FatJarSettings.getInstance(module).getModuleSettings(module)

        if (settings.mainClass.isBlank()) {
            showError(project,
                "Fat JAR not configured for module '${task.moduleName}'.\n" +
                "Right-click the module -> FatJar Builder -> Build Fat JAR to configure.")
            return false
        }

        FatJarOutputManager.clear(project)
        FatJarOutputManager.log(project,
            "Before launch: Building Fat JAR for ${task.moduleName}...")

        return try {
            FatJarBuilderSync(module, settings).build()
        } catch (e: Exception) {
            FatJarOutputManager.logError(project, e.message ?: "Unknown error")
            false
        }
    }

    // Helpers

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "FatJar Builder")
        }
    }
}
