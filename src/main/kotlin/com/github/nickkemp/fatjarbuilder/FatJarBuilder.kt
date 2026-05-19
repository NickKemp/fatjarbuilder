package com.github.nickkemp.fatjarbuilder

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import java.awt.Desktop
import java.io.File

/**
 * Runs [FatJarCore] in an IntelliJ background task with progress indicator.
 * Used by the right-click and Build menu actions.
 */
class FatJarBuilder(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState
) {
    private val project = module.project

    fun build() {
        FatJarOutputManager.clear(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Building Fat JAR for ${module.name}",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val core = FatJarCore(
                    module   = module,
                    settings = settings,
                    onProgress = { fraction, message ->
                        indicator.fraction = fraction
                        if (message.isNotBlank()) indicator.text = message
                    },
                    isCanceled = { indicator.isCanceled }
                )
                core.build()
            }

            override fun onSuccess() {
                val outputPath = "${settings.outputDirectory}/${settings.outputJarName}"
                FatJarOutputManager.logBlank(project)
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                FatJarOutputManager.log(project, "BUILD SUCCESSFUL")
                FatJarOutputManager.log(project, "Output: $outputPath")
                FatJarOutputManager.log(project, "══════════════════════════════════════")

                // Show balloon notification with open folder link
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("FatJar Builder")
                    .createNotification(
                        "Fat JAR built successfully",
                        "<b>${settings.outputJarName}</b>",
                        NotificationType.INFORMATION
                    )
                    .addAction(com.intellij.notification.NotificationAction.createSimple(
                        "Open output folder"
                    ) {
                        val outputDir = File(settings.outputDirectory)
                        if (outputDir.exists() && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(outputDir)
                        }
                    })
                    .notify(project)
            }

            override fun onThrowable(error: Throwable) {
                FatJarOutputManager.logBlank(project)
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                FatJarOutputManager.logError(project, "BUILD FAILED: ${error.message}")
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Fat JAR build failed: ${error.message}",
                            "FatJar Builder"
                        )
                    }
            }
        })
    }
}
