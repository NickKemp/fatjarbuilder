package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.module.Module

/**
 * Runs [FatJarCore] synchronously — no progress indicator.
 * Used by [FatJarBeforeRunTaskProvider] and [FatJarPostBuildTask]
 * which must block until the build completes.
 *
 * [buildSource] is shown in the success/failure message so the user
 * can see which build method triggered the build e.g.
 * "ARTIFACT BUILD SUCCESSFUL" or "BEFORE LAUNCH BUILD SUCCESSFUL".
 */
class FatJarBuilderSync(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState,
    private val buildSource: String = "BUILD"
) {
    private val project = module.project

    fun build(): Boolean {
        return try {
            val core = FatJarCore(module = module, settings = settings)
            core.build()
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            FatJarOutputManager.log(project, "$buildSource SUCCESSFUL")
            FatJarOutputManager.log(project, "Output: ${settings.outputDirectory}/${settings.outputJarName}")
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            true
        } catch (e: Exception) {
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            FatJarOutputManager.logError(project, "$buildSource FAILED: ${e.message}")
            FatJarOutputManager.log(project, "══════════════════════════════════════")
            false
        }
    }
}
