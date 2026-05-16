package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.components.*
import com.intellij.openapi.module.Module

/**
 * Persistent settings stored per module.
 * IntelliJ automatically saves/loads these via the PersistentStateComponent mechanism.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "FatJarSettings",
    storages = [Storage("fatjar-settings.xml")]
)
class FatJarSettings : PersistentStateComponent<FatJarSettings.State> {

    data class State(
        var mainClass: String = "",
        var outputJarName: String = "output.jar",
        var outputDirectory: String = "",
        var excludes: MutableList<String> = mutableListOf(),
        var moduleSettings: MutableMap<String, ModuleState> = mutableMapOf()
    )

    data class ModuleState(
        var mainClass: String = "",
        var outputJarName: String = "output.jar",
        var outputDirectory: String = "",
        var excludes: MutableList<String> = mutableListOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getModuleSettings(module: Module): ModuleState {
        return state.moduleSettings.getOrPut(module.name) {
            ModuleState(
                outputJarName = "${module.name}.jar",
                outputDirectory = getDefaultOutputDir(module)
            )
        }
    }

    fun saveModuleSettings(module: Module, moduleState: ModuleState) {
        state.moduleSettings[module.name] = moduleState
    }

    private fun getDefaultOutputDir(module: Module): String {
        val project = module.project
        return "${project.basePath}/${module.name}/target"
    }

    companion object {
        fun getInstance(module: Module): FatJarSettings {
            return module.project.service<FatJarSettings>()
        }
    }
}