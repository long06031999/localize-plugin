package com.paulbaker.localize.ui

import com.intellij.openapi.project.Project
import java.awt.CardLayout
import javax.swing.JPanel

/**
 * Root panel for the tool window — owns navigation between dashboard and tool panels.
 * Uses CardLayout: "dashboard" | "localize" | "export"
 */
class MainPanel(project: Project) : JPanel() {

    private val cardLayout   = CardLayout()
    private val contentArea  = JPanel(cardLayout)

    val localizePanel  = LocalizePanel(project).also { it.mainPanel = this }
    val exportPanel    = ExportPanel(project).also  { it.mainPanel = this }
    private val dashboardPanel = DashboardPanel(
        onLocalize = { navigateTo("localize") },
        onExport   = { navigateTo("export") }
    )

    init {
        layout = java.awt.BorderLayout()
        contentArea.add(dashboardPanel, "dashboard")
        contentArea.add(localizePanel,  "localize")
        contentArea.add(exportPanel,    "export")
        add(contentArea, java.awt.BorderLayout.CENTER)
        cardLayout.show(contentArea, "dashboard")
    }

    fun navigateTo(card: String) {
        cardLayout.show(contentArea, card)
    }
}
