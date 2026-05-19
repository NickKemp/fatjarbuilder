package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*

class FatJarDialog(private val module: Module) : DialogWrapper(module.project) {

    private val settings     = FatJarSettings.getInstance(module)
    private val moduleState  = settings.getModuleSettings(module)

    private val mainClassField   = JTextField(moduleState.mainClass, 40)
    private val outputJarField   = JTextField(moduleState.outputJarName, 40)
    private val outputDirField   = JTextField(moduleState.outputDirectory, 40)
    private val excludeListModel = DefaultListModel<String>()
    private val excludeList      = JBList(excludeListModel)

    init {
        title = "FatJar Builder — ${module.name}"
        moduleState.excludes.forEach { excludeListModel.addElement(it) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }

        // ── Main class ────────────────────────────────────────────────────
        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Main class:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0
        val mainClassPanel = JPanel(BorderLayout(4, 0))
        mainClassPanel.add(mainClassField, BorderLayout.CENTER)
        val browseMainButton = JButton("Browse...")
        browseMainButton.addActionListener { browseMainClass() }
        mainClassPanel.add(browseMainButton, BorderLayout.EAST)
        panel.add(mainClassPanel, gc)

        // ── Output JAR name ───────────────────────────────────────────────
        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0.0
        panel.add(JLabel("Output JAR name:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0
        panel.add(outputJarField, gc)

        // ── Output directory ──────────────────────────────────────────────
        gc.gridx = 0; gc.gridy = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0.0
        panel.add(JLabel("Output directory:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0
        val dirPanel = JPanel(BorderLayout(4, 0))
        dirPanel.add(outputDirField, BorderLayout.CENTER)
        val browseDirButton = JButton("Browse...")
        browseDirButton.addActionListener { browseOutputDir() }
        dirPanel.add(browseDirButton, BorderLayout.EAST)
        panel.add(dirPanel, gc)

        // ── Excludes ──────────────────────────────────────────────────────
        gc.gridx = 0; gc.gridy = 3; gc.fill = GridBagConstraints.NONE
        gc.weightx = 0.0; gc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("Exclude packages:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.BOTH
        gc.weightx = 1.0; gc.weighty = 1.0

        val excludePanel = JPanel(BorderLayout(0, 4))
        excludeList.preferredSize = Dimension(300, 120)
        excludePanel.add(JBScrollPane(excludeList), BorderLayout.CENTER)

        val buttonPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        val addButton    = JButton("Add")
        val removeButton = JButton("Remove")
        addButton.addActionListener    { addExclude() }
        removeButton.addActionListener { removeExclude() }
        buttonPanel.add(addButton)
        buttonPanel.add(Box.createHorizontalStrut(4))
        buttonPanel.add(removeButton)
        excludePanel.add(buttonPanel, BorderLayout.SOUTH)

        panel.add(excludePanel, gc)

        panel.preferredSize = Dimension(600, 360)
        return panel
    }

    // ── Browse for main class ─────────────────────────────────────────────

    private fun browseMainClass() {
        val mainClasses = findMainClasses()
        if (mainClasses.isEmpty()) {
            Messages.showInfoMessage(
                module.project,
                "No classes with a main() method found in the module output.\n" +
                "Make sure the module has been compiled.",
                "FatJar Builder"
            )
            return
        }

        // Modern API — createPopupChooserBuilder takes the list directly
        PopupChooserBuilder(JBList(mainClasses))
            .setTitle("Select Main Class")
            .setItemChosenCallback { selected ->
                mainClassField.text = selected
            }
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showCenteredInCurrentWindow(module.project)
    }

    private fun findMainClasses(): List<String> {
        val mainClasses = mutableListOf<String>()
        val outputPath  = ModuleOutputFinder.findOutputPath(module) ?: return emptyList()
        scanDirForMainClasses(File(outputPath), File(outputPath), mainClasses)
        return mainClasses.sorted()
    }

    private fun scanDirForMainClasses(root: File, dir: File, result: MutableList<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirForMainClasses(root, file, result)
            } else if (file.name.endsWith(".class") && !file.name.contains('$')) {
                val className = file.relativeTo(root).path
                    .replace(File.separatorChar, '.')
                    .removeSuffix(".class")
                if (hasMainMethod(file)) result.add(className)
            }
        }
    }

    private fun hasMainMethod(classFile: File): Boolean {
        return try {
            val bytes = classFile.readBytes()
            val content = bytes.toString(Charsets.ISO_8859_1)

            // Skip interfaces — check access flags at bytes 6-7
            // Interface flag is 0x0200
            if (bytes.size > 7) {
                val accessFlags = ((bytes[6].toInt() and 0xFF) shl 8) or
                                   (bytes[7].toInt() and 0xFF)
                if (accessFlags and 0x0200 != 0) return false
            }

            // Must have main method name and correct descriptor in constant pool
            content.contains("main") &&
            content.contains("([Ljava/lang/String;)V")
        } catch (e: Exception) {
            false
        }
    }

    // ── Browse for output directory ───────────────────────────────────────

    private fun browseOutputDir() {
        val descriptor = com.intellij.openapi.fileChooser
            .FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            descriptor, module.project, null
        )
        chosen?.let { outputDirField.text = it.path }
    }

    // ── Excludes ──────────────────────────────────────────────────────────

    private fun addExclude() {
        val value = Messages.showInputDialog(
            module.project,
            "Enter package path to exclude (e.g. com/ziheliu/**):",
            "Add Exclude",
            null
        )
        if (!value.isNullOrBlank()) excludeListModel.addElement(value.trim())
    }

    private fun removeExclude() {
        val selected = excludeList.selectedIndex
        if (selected >= 0) excludeListModel.remove(selected)
    }

    // ── OK ────────────────────────────────────────────────────────────────

    override fun doOKAction() {
        if (mainClassField.text.isBlank()) {
            Messages.showErrorDialog(module.project,
                "Main class must not be empty.", "FatJar Builder")
            return
        }
        if (outputJarField.text.isBlank()) {
            Messages.showErrorDialog(module.project,
                "Output JAR name must not be empty.", "FatJar Builder")
            return
        }
        settings.saveModuleSettings(module, FatJarSettings.ModuleState(
            mainClass       = mainClassField.text.trim(),
            outputJarName   = outputJarField.text.trim(),
            outputDirectory = outputDirField.text.trim(),
            excludes        = (0 until excludeListModel.size())
                .map { excludeListModel.getElementAt(it) }
                .toMutableList()
        ))
        super.doOKAction()
    }

    fun getSettings(): FatJarSettings.ModuleState = settings.getModuleSettings(module)
}
