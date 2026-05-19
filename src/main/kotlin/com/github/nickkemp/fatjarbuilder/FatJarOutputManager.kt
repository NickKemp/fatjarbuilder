package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Singleton bridge between [FatJarBuilder] and [FatJarOutputPanel].
 *
 * The builder calls [log] and [clear] from a background thread.
 * This class marshals all UI updates onto the EDT via invokeLater.
 */
object FatJarOutputManager {

    private val panels = mutableMapOf<Project, FatJarOutputPanel>()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun register(project: Project, panel: FatJarOutputPanel) {
        panels[project] = panel
    }

    /**
     * Clear the output panel and bring the tool window to the front.
     * Call this at the start of each build.
     */
    fun clear(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            showToolWindow(project)
            panels[project]?.clear()
        }
    }

    /**
     * Append a timestamped line to the output panel.
     */
    fun log(project: Project, line: String) {
        val timestamp = LocalTime.now().format(timeFmt)
        val formatted = if (line.isBlank()) "" else "[$timestamp] $line"
        ApplicationManager.getApplication().invokeLater {
            // Ensure tool window is open and panel registered before logging
            if (!panels.containsKey(project)) showToolWindow(project)
            panels[project]?.appendLine(formatted)
        }
    }

    /**
     * Append a blank separator line — no timestamp.
     */
    fun logBlank(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            panels[project]?.appendLine("")
        }
    }

    /**
     * Append an error line prefixed with ERROR:.
     */
    fun logError(project: Project, line: String) {
        log(project, "ERROR: $line")
    }

    /**
     * Append a warning line prefixed with WARN:.
     */
    fun logWarn(project: Project, line: String) {
        log(project, "WARN:  $line")
    }

    private fun showToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("FatJar Builder") ?: return
        if (!toolWindow.isVisible) {
            toolWindow.activate(null)
        } else {
            toolWindow.show()
        }
    }
}
