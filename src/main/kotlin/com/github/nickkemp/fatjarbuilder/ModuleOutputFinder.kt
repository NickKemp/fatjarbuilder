package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import java.io.File

/**
 * Utility for finding the compiled output directory of a module.
 *
 * Centralises output path detection so all builders and dialogs
 * use the same logic — avoids duplicating the fallback chain in
 * FatJarBuilder, FatJarBuilderSync, FatJarDialog and
 * FatJarArtifactPropertiesEditor.
 *
 * Detection order:
 * 1. CompilerModuleExtension — standard IntelliJ/Maven output
 * 2. build/classes/java/main — Gradle Java
 * 3. build/classes/kotlin/main — Gradle Kotlin
 * 4. build/classes/main — Gradle legacy
 */
object ModuleOutputFinder {

    fun findOutputPath(module: Module): String? {
        // Try standard IntelliJ/Maven path first
        val standard = CompilerModuleExtension
            .getInstance(module)?.compilerOutputPath?.path
        if (standard != null && File(standard).exists()) return standard

        // Fall back to Gradle output paths
        val modulePath = ModuleRootManager
            .getInstance(module).contentRoots.firstOrNull()?.path
            ?: return null

        return listOf(
            "$modulePath/build/classes/java/main",
            "$modulePath/build/classes/kotlin/main",
            "$modulePath/build/classes/main"
        ).firstOrNull { File(it).exists() }
    }
}
