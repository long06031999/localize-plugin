package com.paulbaker.localize.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

class DashboardPanel(
    private val onLocalize: () -> Unit,
    private val onExport: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(24, 20)
        buildUI()
    }

    private fun buildUI() {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Title — centered horizontally
        val title = JBLabel("Choose a tool", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 15f)
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            border = JBUI.Borders.emptyBottom(20)
        }
        content.add(title)

        // Tool cards
        content.add(toolCard(
            icon        = AllIcons.Actions.Refresh,
            title       = "Localize from CSV",
            description = "Generate localized strings.xml and JSON assets\nfrom translation spreadsheets",
            onClick     = onLocalize
        ))
        content.add(Box.createRigidArea(Dimension(0, 12)))
        content.add(toolCard(
            icon        = AllIcons.Actions.Download,
            title       = "Export to Excel",
            description = "Export all strings and JSON assets\nto Excel format for translation",
            onClick     = onExport
        ))

        // Wrap in a panel that centers horizontally
        val wrapper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(content, GridBagConstraints().also { c ->
                c.fill   = GridBagConstraints.HORIZONTAL
                c.anchor = GridBagConstraints.NORTH
                c.weightx = 1.0; c.weighty = 1.0
            })
        }
        add(wrapper, BorderLayout.CENTER)
    }

    private fun toolCard(
        icon: Icon,
        title: String,
        description: String,
        onClick: () -> Unit
    ): JPanel {
        val normalBg  = UIUtil.getPanelBackground()
        val hoverBg   = JBColor(Color(230, 235, 245), Color(60, 65, 75))
        val borderCol = JBColor.border()

        val card = object : JPanel(BorderLayout(14, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(0, 0, width, height, 10, 10)
            }
        }.apply {
            isOpaque = false
            background = normalBg
            border = CompoundBorder(
                object : LineBorder(borderCol, 1, true) {},
                JBUI.Borders.empty(14, 16)
            )
            cursor = Cursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, 90)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Icon
        val iconLabel = JLabel(icon).apply {
            preferredSize = Dimension(36, 36)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment   = SwingConstants.CENTER
        }

        // Text
        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val descLabel = JLabel("<html>${description.replace("\n", "<br>")}</html>").apply {
            font       = font.deriveFont(12f)
            foreground = UIUtil.getContextHelpForeground()
        }
        val textPanel = JPanel().apply {
            layout  = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createRigidArea(Dimension(0, 4)))
            add(descLabel)
        }

        card.add(iconLabel, BorderLayout.WEST)
        card.add(textPanel, BorderLayout.CENTER)

        // Hover + click
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent)  { card.background = hoverBg;   card.repaint() }
            override fun mouseExited(e: MouseEvent)   { card.background = normalBg;  card.repaint() }
            override fun mouseClicked(e: MouseEvent)  { onClick() }
        })

        return card
    }
}
