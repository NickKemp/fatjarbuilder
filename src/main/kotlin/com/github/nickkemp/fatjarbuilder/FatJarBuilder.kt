package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.ui.Messages
import java.io.*
import java.util.jar.*
import java.util.zip.ZipFile

class FatJarBuilder(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState
) {

    private val project = module.project

    // ══════════════════════════════════════════════════════════════════════
    //  Entry point
    // ══════════════════════════════════════════════════════════════════════

    fun build() {
        FatJarOutputManager.clear(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Building Fat JAR for ${module.name}",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    buildFatJar(indicator)
                } catch (e: Exception) {
                    FatJarOutputManager.logError(project, e.message ?: "Unknown error")
                    showError("Fat JAR build failed: ${e.message}")
                }
            }

            override fun onSuccess() {
                val outputPath = "${settings.outputDirectory}/${settings.outputJarName}"
                FatJarOutputManager.logBlank(project)
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                FatJarOutputManager.log(project, "BUILD SUCCESSFUL")
                FatJarOutputManager.log(project, "Output: $outputPath")
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                Messages.showInfoMessage(
                    project,
                    "Fat JAR built successfully:\n$outputPath",
                    "FatJar Builder"
                )
            }

            override fun onThrowable(error: Throwable) {
                FatJarOutputManager.logBlank(project)
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                FatJarOutputManager.logError(project, "BUILD FAILED: ${error.message}")
                FatJarOutputManager.log(project, "══════════════════════════════════════")
                showError("Fat JAR build failed: ${error.message}")
            }
        })
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Build
    // ══════════════════════════════════════════════════════════════════════

    private fun buildFatJar(indicator: ProgressIndicator) {
        val entries  = mutableMapOf<String, ByteArray>()
        val services = mutableMapOf<String, StringBuilder>()

        log(indicator, "Module    : ${module.name}")
        log(indicator, "Main class: ${settings.mainClass}")
        log(indicator, "Output    : ${settings.outputDirectory}/${settings.outputJarName}")
        FatJarOutputManager.logBlank(project)

        // ── Step 1: Compiled classes — this module AND all dependent modules ──
        log(indicator, "Step 1/5 — Processing compiled classes...")
        indicator.fraction = 0.1

        // Collect all modules in the dependency graph recursively
        val allModules = mutableSetOf<Module>()
        collectModules(module, allModules)

        log(indicator, "  Found ${allModules.size} module(s) to include")

        allModules.forEach { mod ->
            val outputPath = CompilerPaths.getModuleOutputPath(mod, false)
            if (outputPath == null) {
                FatJarOutputManager.logWarn(project,
                    "No compiler output path for module ${mod.name} — skipping")
            } else {
                val outputDir = File(outputPath)
                if (!outputDir.exists()) {
                    FatJarOutputManager.logWarn(project,
                        "Output directory missing for module ${mod.name}: $outputPath")
                } else {
                    log(indicator, "  Module: ${mod.name} ($outputPath)")
                    processDirectory(outputDir, outputDir, entries, services, indicator)
                }
            }
        }

        // ── Step 2: Dependency JARs ───────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(indicator, "Step 2/5 — Processing dependency JARs...")
        indicator.fraction = 0.2

        val allJars = mutableListOf<File>()
        OrderEnumerator.orderEntries(module)
            .recursively()
            .withoutSdk()
            .classesRoots
            .forEach { virtualFile ->
                val path = virtualFile.path
                    .removeSuffix("!/")
                    .removeSuffix("!")
                val file = File(path)
                if (file.exists() && file.extension == "jar") {
                    allJars.add(file)
                }
            }

        log(indicator, "  Found ${allJars.size} dependency JAR(s)")
        FatJarOutputManager.logBlank(project)

        val jarErrors = mutableListOf<String>()
        allJars.forEachIndexed { index, jar ->
            if (indicator.isCanceled) return
            val progress = 0.2 + (0.5 * index / allJars.size)
            indicator.fraction = progress
            log(indicator, "  [${index + 1}/${allJars.size}] ${jar.name}")
            val error = processJar(jar, entries, services)
            if (error != null) jarErrors.add(error)
        }

        if (jarErrors.isNotEmpty()) {
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.logWarn(project, "${jarErrors.size} JAR(s) had warnings:")
            jarErrors.forEach { FatJarOutputManager.logWarn(project, "  $it") }
        }

        // ── Step 3: Merge services ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(indicator, "Step 3/5 — Merging META-INF/services...")
        indicator.fraction = 0.72

        services.forEach { (name, content) ->
            entries["META-INF/services/$name"] = content.toString().toByteArray()
        }
        log(indicator, "  Merged ${services.size} service provider file(s)")

        // ── Step 4: Apply excludes ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(indicator, "Step 4/5 — Applying excludes...")
        indicator.fraction = 0.75

        if (settings.excludes.isEmpty()) {
            log(indicator, "  No excludes configured")
        } else {
            val patterns = settings.excludes.map {
                it.replace("**", ".*").replace("*", "[^/]*").toRegex()
            }
            val excluded = entries.keys.filter { path ->
                patterns.any { pattern -> pattern.containsMatchIn(path) }
            }
            excluded.forEach { entries.remove(it) }
            log(indicator, "  Excluded ${excluded.size} entr(ies) matching " +
                    "${settings.excludes.size} pattern(s)")
            if (excluded.isNotEmpty()) {
                excluded.take(10).forEach {
                    FatJarOutputManager.log(project, "    - $it")
                }
                if (excluded.size > 10)
                    FatJarOutputManager.log(project,
                        "    ... and ${excluded.size - 10} more")
            }
        }

        // ── Step 5: Write JAR ─────────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(indicator, "Step 5/5 — Writing fat JAR...")
        indicator.fraction = 0.8

        val outputDir  = File(settings.outputDirectory)
        outputDir.mkdirs()
        val outputFile = File(outputDir, settings.outputJarName)

        log(indicator, "  Writing ${entries.size} entries...")
        writeJar(outputFile, entries, settings.mainClass, indicator)

        val sizeMb = "%.1f".format(outputFile.length() / (1024.0 * 1024.0))
        log(indicator, "  JAR size: ${sizeMb} MB")
        indicator.fraction = 1.0
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Process compiled class directory
    // ══════════════════════════════════════════════════════════════════════

    private fun processDirectory(
        root: File,
        dir: File,
        entries: MutableMap<String, ByteArray>,
        services: MutableMap<String, StringBuilder>,
        indicator: ProgressIndicator
    ) {
        dir.listFiles()?.forEach { file ->
            if (indicator.isCanceled) return
            if (file.isDirectory) {
                processDirectory(root, file, entries, services, indicator)
            } else {
                val relativePath = file.relativeTo(root).path.replace("\\", "/")
                when {
                    isSignatureFile(relativePath)                      -> { /* strip */ }
                    relativePath == "META-INF/MANIFEST.MF"            -> { /* skip — generated */ }
                    relativePath.startsWith("META-INF/services/")     -> {
                        val name = relativePath.removePrefix("META-INF/services/")
                        services.getOrPut(name) { StringBuilder() }
                            .append(file.readText()).append("\n")
                    }
                    else -> entries[relativePath] = file.readBytes()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Process a dependency JAR
    //  Returns an error string if there was a problem, null if OK
    // ══════════════════════════════════════════════════════════════════════

    private fun processJar(
        jar: File,
        entries: MutableMap<String, ByteArray>,
        services: MutableMap<String, StringBuilder>
    ): String? {
        return try {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name
                    when {
                        isSignatureFile(name)                          -> { /* strip */ }
                        name == "META-INF/MANIFEST.MF"                -> { /* skip */ }
                        name == "module-info.class"                    -> { /* skip */ }
                        name.endsWith("/module-info.class")            -> { /* skip */ }
                        name.startsWith("META-INF/services/")         -> {
                            val svcName = name.removePrefix("META-INF/services/")
                            val content = zip.getInputStream(entry)
                                .readBytes().toString(Charsets.UTF_8)
                            services.getOrPut(svcName) { StringBuilder() }
                                .append(content).append("\n")
                        }
                        !entries.containsKey(name)                    -> {
                            entries[name] = zip.getInputStream(entry).readBytes()
                        }
                        // Duplicate entry — first one wins, silently skip
                    }
                }
            }
            null // no error
        } catch (e: Exception) {
            "${jar.name}: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Write the output JAR
    // ══════════════════════════════════════════════════════════════════════

    private fun writeJar(
        outputFile: File,
        entries: Map<String, ByteArray>,
        mainClass: String,
        indicator: ProgressIndicator
    ) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (mainClass.isNotBlank()) {
            manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }

        JarOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile)), manifest
        ).use { jos ->
            val total = entries.size.toDouble()
            entries.entries.forEachIndexed { index, (name, bytes) ->
                if (indicator.isCanceled) return
                indicator.fraction = 0.8 + (0.2 * index / total)
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun collectModules(mod: Module, result: MutableSet<Module>) {
        if (!result.add(mod)) return // already visited
        com.intellij.openapi.roots.ModuleRootManager.getInstance(mod)
            .getDependencies(true)
            .forEach { dep -> collectModules(dep, result) }
    }

    private fun log(indicator: ProgressIndicator, message: String) {
        indicator.text = message
        FatJarOutputManager.log(project, message)
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") && (
                upper.endsWith(".SF")  ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".EC"))
    }

    private fun showError(message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .invokeLater {
                Messages.showErrorDialog(project, message, "FatJar Builder")
            }
    }
}
