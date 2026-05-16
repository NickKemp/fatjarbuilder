package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the FatJar Builder tool window content.
 * Registered in plugin.xml — appears at the bottom of the IDE.
 * doNotActivateOnStart is set in plugin.xml not here.
 */
class FatJarToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel   = FatJarOutputPanel()
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        FatJarOutputManager.register(project, panel)
    }

    /**
     * Replaces deprecated isApplicable() — show tool window for all projects.
     */
    override fun shouldBeAvailable(project: Project): Boolean = true
}