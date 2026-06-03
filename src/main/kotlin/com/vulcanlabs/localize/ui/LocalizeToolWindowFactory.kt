package com.vulcanlabs.localize.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class LocalizeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        try {
            val panel = LocalizePanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            toolWindow.contentManager.addContent(content)
            project.putUserData(LocalizeOutputPanel.KEY, panel.outputPanel)
        } catch (ex: Exception) {
            // Fallback: show error message so the tool window is never blank
            val errPanel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                add(com.intellij.ui.components.JBLabel(
                    "Localize plugin failed to load: ${ex.message}"
                ).apply { foreground = com.intellij.ui.JBColor.RED }, java.awt.BorderLayout.NORTH)
            }
            toolWindow.contentManager.addContent(
                ContentFactory.getInstance().createContent(errPanel, "", false)
            )
            ex.printStackTrace()
        }
    }
}

class LocalizeOutputPanel : JPanel(BorderLayout()) {

    val textPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        background = UIManager.getColor("EditorPane.background") ?: UIUtil.getPanelBackground()
        foreground = UIManager.getColor("EditorPane.foreground") ?: JBColor.foreground()
        border = JBUI.Borders.empty(8)
    }

    init {
        add(JBScrollPane(textPane), BorderLayout.CENTER)
    }

    fun clear() = textPane.document.remove(0, textPane.document.length)

    fun append(text: String, level: OutputLevel = OutputLevel.INFO) {
        val doc = textPane.styledDocument
        val style = SimpleAttributeSet().also {
            StyleConstants.setForeground(it, level.color)
            if (level == OutputLevel.SECTION) StyleConstants.setBold(it, true)
        }
        doc.insertString(doc.length, text + "\n", style)
        textPane.caretPosition = doc.length
    }

    companion object {
        val KEY: Key<LocalizeOutputPanel> = Key.create("LocalizeOutputPanel")
    }
}

enum class OutputLevel(val color: Color) {
    INFO(JBColor(Color(60, 60, 60), Color(200, 200, 200))),
    SECTION(JBColor(Color(30, 100, 180), Color(100, 170, 255))),
    SUCCESS(JBColor(Color(0, 140, 0), Color(80, 220, 80))),
    WARN(JBColor(Color(160, 100, 0), Color(255, 200, 80))),
    ERROR(JBColor(Color(200, 0, 0), Color(255, 100, 100)))
}
