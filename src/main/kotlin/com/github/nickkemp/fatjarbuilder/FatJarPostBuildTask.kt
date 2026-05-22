package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.artifacts.ArtifactPropertiesProvider

/**
 * Listens for compilation completion and builds Fat JAR artifacts
 * marked with "Include in project build" (isBuildOnMake = true).
 *
 * Uses CompilationStatusListener which fires after the entire build
 * pipeline completes — including artifact building — so compiled
 * classes are always available when we run.
 *
 * Registered as a postStartupActivity in plugin.xml.
 */
class FatJarPostBuildTask : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect()
        connection.subscribe(CompilerTopics.COMPILATION_STATUS, object : CompilationStatusListener {
            override fun compilationFinished(
                aborted: Boolean,
                errors: Int,
                warnings: Int,
                compileContext: CompileContext
            ) {
                // Skip if aborted or there were compile errors
                if (aborted || errors > 0) return
                buildFatJarArtifacts(compileContext)
            }
        })
    }

    private fun buildFatJarArtifacts(context: CompileContext) {
        val project = context.project

        val fatJarArtifacts = ApplicationManager.getApplication()
            .runReadAction<List<com.intellij.packaging.artifacts.Artifact>> {
                ArtifactManager.getInstance(project)
                    .artifacts
                    .filter { it.artifactType is FatJarArtifactType }
                    .filter { it.isBuildOnMake }
            }

        if (fatJarArtifacts.isEmpty()) return

        val provider = ArtifactPropertiesProvider.EP_NAME
            .findExtension(FatJarArtifactPropertiesProvider::class.java)
            ?: return

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

            FatJarBuilderSync(module, settings, "ARTIFACT BUILD").build()
        }
    }
}
