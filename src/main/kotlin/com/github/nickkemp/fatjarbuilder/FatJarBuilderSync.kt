package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Synchronous fat JAR builder — used by [FatJarBeforeRunTaskProvider].
 *
 * Unlike [FatJarBuilder] which runs in a background progress task,
 * this runs synchronously so it can be used as a Before/After launch step
 * which must block until complete before the run configuration starts.
 *
 * Logs output to [FatJarOutputManager] for visibility in the tool window.
 */
class FatJarBuilderSync(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState
) {
    private val project = module.project

    fun build(): Boolean {
        return try {
            buildFatJar()
            true
        } catch (e: Exception) {
            FatJarOutputManager.logError(project, "Build failed: ${e.message}")
            false
        }
    }

    private fun buildFatJar() {
        val entries  = mutableMapOf<String, ByteArray>()
        val services = mutableMapOf<String, StringBuilder>()

        log("Module    : ${module.name}")
        log("Main class: ${settings.mainClass}")
        log("Output    : ${settings.outputDirectory}/${settings.outputJarName}")
        FatJarOutputManager.logBlank(project)

        // ── Step 1: Compiled classes ──────────────────────────────────────
        log("Step 1/5 — Processing compiled classes...")
        val allModules = mutableSetOf<Module>()
        collectModules(module, allModules)
        log("  Found ${allModules.size} module(s)")

        allModules.forEach { mod ->
            val outputPath = CompilerPaths.getModuleOutputPath(mod, false)
            if (outputPath == null) {
                FatJarOutputManager.logWarn(project,
                    "No compiler output path for module ${mod.name}")
            } else {
                val outputDir = File(outputPath)
                if (outputDir.exists()) {
                    log("  Module: ${mod.name}")
                    processDirectory(outputDir, outputDir, entries, services)
                }
            }
        }

        // ── Step 2: Dependency JARs ───────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log("Step 2/5 — Processing dependency JARs...")

        val allJars = mutableListOf<File>()
        OrderEnumerator.orderEntries(module)
            .recursively()
            .withoutSdk()
            .classesRoots
            .forEach { virtualFile ->
                val path = virtualFile.path.removeSuffix("!/").removeSuffix("!")
                val file = File(path)
                if (file.exists() && file.extension == "jar") allJars.add(file)
            }

        log("  Found ${allJars.size} JAR(s)")
        allJars.forEachIndexed { index, jar ->
            log("  [${index + 1}/${allJars.size}] ${jar.name}")
            processJar(jar, entries, services)
        }

        // ── Step 3: Merge services ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log("Step 3/5 — Merging META-INF/services...")
        services.forEach { (name, content) ->
            entries["META-INF/services/$name"] = content.toString().toByteArray()
        }
        log("  Merged ${services.size} service file(s)")

        // ── Step 4: Apply excludes ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log("Step 4/5 — Applying excludes...")
        if (settings.excludes.isNotEmpty()) {
            val patterns = settings.excludes.map {
                it.replace("**", ".*").replace("*", "[^/]*").toRegex()
            }
            val excluded = entries.keys.filter { path ->
                patterns.any { pattern -> pattern.containsMatchIn(path) }
            }
            excluded.forEach { entries.remove(it) }
            log("  Excluded ${excluded.size} entries")
        } else {
            log("  No excludes configured")
        }

        // ── Step 5: Write JAR ─────────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log("Step 5/5 — Writing fat JAR...")

        val outputDir  = File(settings.outputDirectory)
        outputDir.mkdirs()
        val outputFile = File(outputDir, settings.outputJarName)

        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (settings.mainClass.isNotBlank()) {
            manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = settings.mainClass
        }

        JarOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile)), manifest
        ).use { jos ->
            entries.entries.forEach { (name, bytes) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }

        val sizeMb = "%.1f".format(outputFile.length() / (1024.0 * 1024.0))
        log("  Written: ${outputFile.absolutePath} (${sizeMb} MB)")
        FatJarOutputManager.logBlank(project)
        log("══════════════════════════════════════")
        log("BUILD SUCCESSFUL")
        log("══════════════════════════════════════")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun collectModules(mod: Module, result: MutableSet<Module>) {
        if (!result.add(mod)) return
        ModuleRootManager.getInstance(mod)
            .getDependencies(true)
            .forEach { dep -> collectModules(dep, result) }
    }

    private fun processDirectory(
        root: File, dir: File,
        entries: MutableMap<String, ByteArray>,
        services: MutableMap<String, StringBuilder>
    ) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                processDirectory(root, file, entries, services)
            } else {
                val relativePath = file.relativeTo(root).path.replace("\\", "/")
                when {
                    isSignatureFile(relativePath)                  -> { }
                    relativePath == "META-INF/MANIFEST.MF"        -> { }
                    relativePath.startsWith("META-INF/services/") -> {
                        val name = relativePath.removePrefix("META-INF/services/")
                        services.getOrPut(name) { StringBuilder() }
                            .append(file.readText()).append("\n")
                    }
                    else -> entries[relativePath] = file.readBytes()
                }
            }
        }
    }

    private fun processJar(
        jar: File,
        entries: MutableMap<String, ByteArray>,
        services: MutableMap<String, StringBuilder>
    ) {
        try {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name
                    when {
                        isSignatureFile(name)                      -> { }
                        name == "META-INF/MANIFEST.MF"            -> { }
                        name == "module-info.class"                -> { }
                        name.endsWith("/module-info.class")        -> { }
                        name.startsWith("META-INF/services/")     -> {
                            val svcName = name.removePrefix("META-INF/services/")
                            val content = zip.getInputStream(entry)
                                .readBytes().toString(Charsets.UTF_8)
                            services.getOrPut(svcName) { StringBuilder() }
                                .append(content).append("\n")
                        }
                        !entries.containsKey(name)                -> {
                            entries[name] = zip.getInputStream(entry).readBytes()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FatJarOutputManager.logWarn(project, "${jar.name}: ${e.message}")
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") && (
                upper.endsWith(".SF")  ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".EC"))
    }

    private fun log(message: String) = FatJarOutputManager.log(project, message)
}
