package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.module.Module

/**
 * Runs [FatJarCore] synchronously — no progress indicator.
 * Used by [FatJarBeforeRunTaskProvider] and [FatJarPostBuildTask]
 * which must block until the build completes.
 */
class FatJarBuilderSync(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState
) {
    private val project = module.project

    fun build(): Boolean {
        return try {
            val core = FatJarCore(module = module, settings = settings)
            core.build()
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            FatJarOutputManager.log(project, "BUILD SUCCESSFUL")
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            true
        } catch (e: Exception) {
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            FatJarOutputManager.logError(project, "BUILD FAILED: ${e.message}")
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            false
        }
    }
}
