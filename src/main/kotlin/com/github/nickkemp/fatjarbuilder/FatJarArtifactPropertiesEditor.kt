package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.packaging.ui.ArtifactEditorContext
import com.intellij.packaging.ui.ArtifactPropertiesEditor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*
import javax.swing.BorderFactory

/**
 * Editor UI shown in:
 *   File -> Project Structure -> Artifacts -> [Fat JAR artifact] -> Fat JAR Settings tab
 *
 * Allows configuring:
 * - Module to build
 * - Main class (with browse button)
 * - Package excludes
 */
class FatJarArtifactPropertiesEditor(
    private val properties: FatJarArtifactProperties,
    private val context: ArtifactEditorContext
) : ArtifactPropertiesEditor() {

    private val project = context.project

    // UI Components
    private val moduleCombo      = JComboBox<String>()
    private val mainClassField   = JTextField(40)
    private val excludeListModel = DefaultListModel<String>()
    private val excludeList      = JBList(excludeListModel)

    init {
        // Populate module list
        ModuleManager.getInstance(project).modules
            .map { it.name }
            .sorted()
            .forEach { moduleCombo.addItem(it) }

        // Load existing settings
        moduleCombo.selectedItem  = properties.getModuleName()
        mainClassField.text       = properties.getMainClass()
        properties.getExcludes().forEach { excludeListModel.addElement(it) }
    }

    override fun getTabName(): String = "► Fat JAR Settings"

    override fun createComponent(): JComponent {
        val wrapper = JPanel(BorderLayout())

        val hint = JLabel(
            "<html><i>Configure settings here. The 'Output Layout' tab is not used by Fat JAR Builder.</i></html>"
        )
        hint.border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
        wrapper.add(hint, BorderLayout.NORTH)
        wrapper.add(buildSettingsPanel(), BorderLayout.CENTER)
        return wrapper
    }

    private fun buildSettingsPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }

        // Module
        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Module:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0
        panel.add(moduleCombo, gc)

        // Main class with browse button
        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0.0
        panel.add(JLabel("Main class:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0
        val mainClassPanel = JPanel(BorderLayout(4, 0))
        mainClassPanel.add(mainClassField, BorderLayout.CENTER)
        val browseButton = JButton("Browse...")
        browseButton.addActionListener { browseMainClass() }
        mainClassPanel.add(browseButton, BorderLayout.EAST)
        panel.add(mainClassPanel, gc)

        // Excludes
        gc.gridx = 0; gc.gridy = 2; gc.fill = GridBagConstraints.NONE
        gc.weightx = 0.0; gc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("Exclude packages:"), gc)

        gc.gridx = 1; gc.fill = GridBagConstraints.BOTH
        gc.weightx = 1.0; gc.weighty = 1.0

        val excludePanel = JPanel(BorderLayout(0, 4))
        excludeList.preferredSize = Dimension(300, 100)
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

        panel.preferredSize = Dimension(500, 320)
        return panel
    }

    override fun isModified(): Boolean {
        return moduleCombo.selectedItem as? String != properties.getModuleName() ||
               mainClassField.text.trim()          != properties.getMainClass()  ||
               currentExcludes()                   != properties.getExcludes()
    }

    override fun apply() {
        properties.setModuleName(moduleCombo.selectedItem as? String ?: "")
        properties.setMainClass(mainClassField.text.trim())
        properties.setExcludes(currentExcludes())
    }

    override fun reset() {
        moduleCombo.selectedItem = properties.getModuleName()
        mainClassField.text      = properties.getMainClass()
        excludeListModel.clear()
        properties.getExcludes().forEach { excludeListModel.addElement(it) }
    }

    // Browse for main class

    private fun browseMainClass() {
        val moduleName = moduleCombo.selectedItem as? String ?: return
        val module = ModuleManager.getInstance(project)
            .findModuleByName(moduleName) ?: return

        val outputPath = CompilerModuleExtension
            .getInstance(module)?.compilerOutputPath?.path ?: run {
            Messages.showInfoMessage(
                project,
                "No compiler output found for module $moduleName.\n" +
                "Please build the module first.",
                "FatJar Builder"
            )
            return
        }

        val mainClasses = mutableListOf<String>()
        scanForMainClasses(File(outputPath), File(outputPath), mainClasses)

        if (mainClasses.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No classes with a main() method found in $moduleName.\n" +
                "Please build the module first.",
                "FatJar Builder"
            )
            return
        }

        PopupChooserBuilder(JBList(mainClasses.sorted()))
            .setTitle("Select Main Class")
            .setItemChosenCallback { selected ->
                mainClassField.text = selected
            }
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun scanForMainClasses(root: File, dir: File, result: MutableList<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanForMainClasses(root, file, result)
            } else if (file.name.endsWith(".class") && !file.name.contains('$')) {
                val className = file.relativeTo(root).path
                    .replace(File.separatorChar, '.')
                    .removeSuffix(".class")
                val content = file.readBytes().toString(Charsets.ISO_8859_1)
                if (content.contains("main") &&
                    content.contains("([Ljava/lang/String;)V"))
                    result.add(className)
            }
        }
    }

    // Excludes

    private fun addExclude() {
        val value = JOptionPane.showInputDialog(
            null,
            "Enter package path to exclude (e.g. com/example/legacy/legacy/**):",
            "Add Exclude",
            JOptionPane.PLAIN_MESSAGE
        )?.trim()
        if (!value.isNullOrBlank()) excludeListModel.addElement(value)
    }

    private fun removeExclude() {
        val selected = excludeList.selectedIndex
        if (selected >= 0) excludeListModel.remove(selected)
    }

    private fun currentExcludes(): MutableList<String> {
        return (0 until excludeListModel.size())
            .map { excludeListModel.getElementAt(it) }
            .toMutableList()
    }
}
