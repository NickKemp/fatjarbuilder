package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.module.ModuleManager
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.artifacts.ArtifactProperties
import com.intellij.packaging.artifacts.ArtifactPropertiesProvider
import com.intellij.packaging.ui.ArtifactEditorContext
import com.intellij.packaging.ui.ArtifactPropertiesEditor

/**
 * Stores the configuration for a Fat JAR artifact:
 * - which module to build
 * - main class
 * - excludes
 *
 * Persisted in the project .idea/artifacts/ folder.
 */
class FatJarArtifactProperties : ArtifactProperties<FatJarArtifactProperties.State>() {

    data class State(
        var moduleName: String = "",
        var mainClass: String  = "",
        var excludes: MutableList<String> = mutableListOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getModuleName(): String = state.moduleName
    fun setModuleName(name: String) { state.moduleName = name }
    fun getMainClass(): String = state.mainClass
    fun setMainClass(cls: String) { state.mainClass = cls }
    fun getExcludes(): MutableList<String> = state.excludes
    fun setExcludes(excludes: MutableList<String>) { state.excludes = excludes }

    override fun createEditor(context: ArtifactEditorContext): ArtifactPropertiesEditor {
        return FatJarArtifactPropertiesEditor(this, context)
    }

    /**
     * Called when the artifact is built.
     * Note: also handled by FatJarPostBuildTask as a safety net.
     */
    override fun onBuildFinished(artifact: Artifact, context: CompileContext) {
        val project = context.project
        val module  = ModuleManager.getInstance(project)
            .findModuleByName(state.moduleName) ?: return

        if (state.mainClass.isBlank()) return

        val settings = FatJarSettings.ModuleState(
            mainClass       = state.mainClass,
            outputJarName   = "${artifact.name}.jar",
            outputDirectory = artifact.outputPath ?: return,
            excludes        = state.excludes
        )

        FatJarOutputManager.clear(project)
        FatJarOutputManager.log(project, "Building artifact: ${artifact.name}")
        FatJarBuilderSync(module, settings).build()
    }
}

/**
 * Provider that creates FatJarArtifactProperties instances.
 * Registered in fatjar-packaging.xml.
 */
class FatJarArtifactPropertiesProvider : ArtifactPropertiesProvider(
    "fat-jar-properties"
) {
    override fun isAvailableFor(
        type: com.intellij.packaging.artifacts.ArtifactType
    ): Boolean = type is FatJarArtifactType

    override fun createProperties(
        artifactType: com.intellij.packaging.artifacts.ArtifactType
    ): ArtifactProperties<*> = FatJarArtifactProperties()
}
