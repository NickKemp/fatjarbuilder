package com.github.nickkemp.fatjarbuilder

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
 * Core fat JAR building logic shared by [FatJarBuilder] (background task)
 * and [FatJarBuilderSync] (synchronous, used by before-run task and artifact system).
 *
 * All progress reporting is done via a [ProgressCallback] so the same
 * logic works with or without an IntelliJ progress indicator.
 */
class FatJarCore(
    private val module: Module,
    private val settings: FatJarSettings.ModuleState,
    private val onProgress: (fraction: Double, message: String) -> Unit = { _, _ -> },
    private val isCanceled: () -> Boolean = { false }
) {
    private val project = module.project

    // ══════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs the full fat JAR build.
     * Returns the output file on success, throws on failure.
     */
    fun build(): File {
        val entries  = mutableMapOf<String, ByteArray>()
        val services = mutableMapOf<String, StringBuilder>()

        log(0.0, "Module    : ${module.name}")
        log(0.0, "Main class: ${settings.mainClass}")
        log(0.0, "Output    : ${settings.outputDirectory}/${settings.outputJarName}")
        FatJarOutputManager.logBlank(project)

        // ── Step 1: Compiled classes ──────────────────────────────────────
        log(0.1, "Step 1/5 — Processing compiled classes...")

        val allModules = mutableSetOf<Module>()
        collectModules(module, allModules)
        log(0.1, "  Found ${allModules.size} module(s) to include")

        allModules.forEach { mod ->
            if (isCanceled()) return@forEach
            val outputPath = ModuleOutputFinder.findOutputPath(mod)
            if (outputPath == null) {
                FatJarOutputManager.logWarn(project,
                    "No compiler output path for module ${mod.name} — skipping")
            } else {
                val outputDir = File(outputPath)
                if (!outputDir.exists()) {
                    FatJarOutputManager.logWarn(project,
                        "Output directory missing for module ${mod.name}: $outputPath")
                } else {
                    log(0.1, "  Module: ${mod.name} ($outputPath)")
                    processDirectory(outputDir, outputDir, entries, services)
                }
            }
        }

        // ── Step 2: Dependency JARs ───────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(0.2, "Step 2/5 — Processing dependency JARs...")

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

        log(0.2, "  Found ${allJars.size} dependency JAR(s)")
        FatJarOutputManager.logBlank(project)

        val jarErrors = mutableListOf<String>()
        allJars.forEachIndexed { index, jar ->
            if (isCanceled()) return@forEachIndexed
            val fraction = 0.2 + (0.5 * index / allJars.size)
            log(fraction, "  [${index + 1}/${allJars.size}] ${jar.name}")
            processJar(jar, entries, services)?.let { jarErrors.add(it) }
        }

        if (jarErrors.isNotEmpty()) {
            FatJarOutputManager.logBlank(project)
            FatJarOutputManager.logWarn(project, "${jarErrors.size} JAR(s) had warnings:")
            jarErrors.forEach { FatJarOutputManager.logWarn(project, "  $it") }
        }

        // ── Step 3: Merge services ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(0.72, "Step 3/5 — Merging META-INF/services...")
        services.forEach { (name, content) ->
            entries["META-INF/services/$name"] = content.toString().toByteArray()
        }
        log(0.72, "  Merged ${services.size} service provider file(s)")

        // ── Step 4: Apply excludes ────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(0.75, "Step 4/5 — Applying excludes...")

        if (settings.excludes.isEmpty()) {
            log(0.75, "  No excludes configured")
        } else {
            val patterns = settings.excludes.map {
                it.replace("**", ".*").replace("*", "[^/]*").toRegex()
            }
            val excluded = entries.keys.filter { path ->
                patterns.any { pattern -> pattern.containsMatchIn(path) }
            }
            excluded.forEach { entries.remove(it) }
            log(0.75, "  Excluded ${excluded.size} entr(ies) matching " +
                    "${settings.excludes.size} pattern(s)")
            if (excluded.isNotEmpty()) {
                excluded.take(10).forEach { FatJarOutputManager.log(project, "    - $it") }
                if (excluded.size > 10)
                    FatJarOutputManager.log(project, "    ... and ${excluded.size - 10} more")
            }
        }

        // ── Step 5: Write JAR ─────────────────────────────────────────────
        FatJarOutputManager.logBlank(project)
        log(0.8, "Step 5/5 — Writing fat JAR...")

        val outputDir  = File(settings.outputDirectory)
        outputDir.mkdirs()
        val outputFile = File(outputDir, settings.outputJarName)
        val tempFile   = File.createTempFile(
            settings.outputJarName.removeSuffix(".jar"), ".jar.tmp"
        ).also { it.deleteOnExit() }

        log(0.8, "  Writing ${entries.size} entries...")
        writeJar(tempFile, entries)

        // Use NIO atomic move with REPLACE_EXISTING — handles Windows file locks
        // better than File.renameTo or copyTo
        try {
            java.nio.file.Files.move(
                tempFile.toPath(),
                outputFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Atomic move not supported across filesystems — fall back
            java.nio.file.Files.move(
                tempFile.toPath(),
                outputFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }

        // Read size AFTER the stream is closed
        val size = outputFile.length()
        val sizeStr = when {
            size < 1024              -> "%,d bytes".format(size)
            size < 1024 * 1024      -> "%,.1f KB".format(size / 1024.0)
            else                    -> "%,.1f MB".format(size / (1024.0 * 1024.0))
        }
        log(1.0, "  JAR size: $sizeStr")

        return outputFile
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Process compiled class directory
    // ══════════════════════════════════════════════════════════════════════

    private fun processDirectory(
        root: File,
        dir: File,
        entries: MutableMap<String, ByteArray>,
        services: MutableMap<String, StringBuilder>
    ) {
        dir.listFiles()?.forEach { file ->
            if (isCanceled()) return
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

    // ══════════════════════════════════════════════════════════════════════
    //  Process a dependency JAR — returns error string or null
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
            null
        } catch (e: Exception) {
            "${jar.name}: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Write the output JAR
    // ══════════════════════════════════════════════════════════════════════

    private fun writeJar(outputFile: File, entries: Map<String, ByteArray>) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (settings.mainClass.isNotBlank()) {
            manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = settings.mainClass
        }

        JarOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile)), manifest
        ).use { jos ->
            val total = entries.size.toDouble()
            entries.entries.forEachIndexed { index, (name, bytes) ->
                if (isCanceled()) return
                onProgress(0.8 + (0.2 * index / total), "")
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        // Stream is now closed — file is complete and size is accurate
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun collectModules(mod: Module, result: MutableSet<Module>) {
        if (!result.add(mod)) return
        ModuleRootManager.getInstance(mod)
            .getDependencies(true)
            .forEach { dep -> collectModules(dep, result) }
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("META-INF/") && (
                upper.endsWith(".SF")  ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".EC"))
    }

    private fun log(fraction: Double, message: String) {
        onProgress(fraction, message)
        FatJarOutputManager.log(project, message)
    }
}
