package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileTask
import com.intellij.openapi.module.ModuleManager
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.artifacts.ArtifactPropertiesProvider

/**
 * Post-compile task that builds Fat JAR artifacts after the
 * normal artifact build completes.
 *
 * Registered in plugin.xml via the compiler.task extension point:
 *   <compiler.task implementation="...FatJarPostBuildTask" order="after"/>
 *
 * This replaces the deprecated CompilerManager.addAfterTask() approach.
 */
class FatJarPostBuildTask : CompileTask {

    override fun execute(context: CompileContext): Boolean {
        return buildFatJarArtifacts(context)
    }

    private fun buildFatJarArtifacts(context: CompileContext): Boolean {
        val project = context.project

        // ArtifactManager must be accessed inside a read action
        val fatJarArtifacts = ApplicationManager.getApplication()
            .runReadAction<List<com.intellij.packaging.artifacts.Artifact>> {
                ArtifactManager.getInstance(project)
                    .artifacts
                    .filter { it.artifactType is FatJarArtifactType }
            }

        if (fatJarArtifacts.isEmpty()) return true

        val provider = ArtifactPropertiesProvider.EP_NAME
            .findExtension(FatJarArtifactPropertiesProvider::class.java)
            ?: return true

        for (artifact in fatJarArtifacts) {
            val properties = artifact.getProperties(provider)
                as? FatJarArtifactProperties ?: continue

            val moduleName = properties.getModuleName()
            val mainClass  = properties.getMainClass()
            val outputPath = artifact.outputPath

            if (moduleName.isBlank() || mainClass.isBlank() || outputPath == null) continue

            val module = ModuleManager.getInstance(project)
                .findModuleByName(moduleName) ?: continue

            val settings = FatJarSettings.ModuleState(
                mainClass       = mainClass,
                outputJarName   = "${artifact.name}.jar",
                outputDirectory = outputPath,
                excludes        = properties.getExcludes()
            )

            FatJarOutputManager.clear(project)
            FatJarOutputManager.log(project,
                "Building Fat JAR artifact: ${artifact.name}")

            FatJarBuilderSync(module, settings).build()
        }
        return true
    }
}
