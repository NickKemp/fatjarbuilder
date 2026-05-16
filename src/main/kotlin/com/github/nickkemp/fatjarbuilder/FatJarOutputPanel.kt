package com.github.nickkemp.fatjarbuilder

import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

class FatJarOutputPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable    = false
        font          = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap      = false
        wrapStyleWord = false
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