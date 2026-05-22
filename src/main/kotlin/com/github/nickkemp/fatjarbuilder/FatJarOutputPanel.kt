package com.github.nickkemp.fatjarbuilder

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

class FatJarOutputPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable    = false
        lineWrap      = false
        wrapStyleWord = false

        // Use IntelliJ's configured editor font to match the Build window
        val editorFont = EditorColorsManager.getInstance().globalScheme.getFont(
            com.intellij.openapi.editor.colors.EditorFontType.PLAIN
        )
        font = editorFont ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    init {
        add(JBScrollPane(textArea), BorderLayout.CENTER)
    }

    fun clear() {
        textArea.text = ""
    }

    fun appendLine(line: String) {
        textArea.append(line + "\n")
        textArea.caretPosition = textArea.document.length
    }
}