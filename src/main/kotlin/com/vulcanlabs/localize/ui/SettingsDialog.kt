package com.vulcanlabs.localize.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vulcanlabs.localize.config.ConfigPersistence
import com.vulcanlabs.localize.config.GenerateMode
import java.awt.*
import java.nio.file.Path
import javax.swing.*

class SettingsDialog(
    private val project: Project,
    private val projectDir: Path,
    private val persistence: ConfigPersistence   // shared instance from LocalizePanel
) : DialogWrapper(project) {

    // Default paths — shown in fields when no custom path is set
    private val defaultValuesDir = projectDir.resolve("app/src/main/res/values").toString()
    private val defaultAssetsDir = projectDir.resolve("app/src/main/assets").toString()
    private val defaultReportDir = projectDir.toString()

    // Generate mode
    private val mergeRadio   = JRadioButton("Merge (safe)").apply { font = font.deriveFont(13f) }
    private val replaceRadio = JRadioButton("Full Replace").apply { font = font.deriveFont(13f) }
    private val modeGroup    = ButtonGroup().also { it.add(mergeRadio); it.add(replaceRadio) }

    // Directory pickers
    private val valuesDirField  = createDirPicker()
    private val assetsDirField  = createDirPicker()
    private val reportDirField  = createDirPicker()

    init {
        title = "Settings"
        setOKButtonText("Save")
        init()
        load()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 14)
        }

        // ── Generate Mode ──────────────────────────────────────────────────────
        root.add(section("Generate Mode"))
        root.add(vgap(6))

        val mergeDesc   = desc("Keep existing translations for strings not covered by CSV — safe for incremental updates")
        val replaceDesc = desc("Overwrite output file entirely from CSV — strings not in CSV will be lost")

        root.add(radioRow(mergeRadio,   mergeDesc))
        root.add(vgap(8))
        root.add(radioRow(replaceRadio, replaceDesc))
        root.add(vgap(14))

        // ── Directories ────────────────────────────────────────────────────────
        root.add(section("Directories  (leave blank to use defaults)"))
        root.add(vgap(6))
        root.add(dirRow("Values dir", "Default: app/src/main/res/values", valuesDirField))
        root.add(vgap(4))
        root.add(dirRow("Assets dir",  "Default: app/src/main/assets",    assetsDirField))
        root.add(vgap(4))
        root.add(dirRow("Report dir",  "Default: project root",           reportDirField))
        root.add(vgap(8))

        // Reset to defaults button
        val resetBtn = JButton("Reset to defaults").apply {
            isBorderPainted = false; isContentAreaFilled = false
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(Font.ITALIC, 11f)
            cursor = Cursor(Cursor.HAND_CURSOR)
            addActionListener { resetToDefaults() }
            alignmentX = Component.RIGHT_ALIGNMENT
        }
        root.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            add(resetBtn)
        })

        root.preferredSize = Dimension(520, root.preferredSize.height)
        return root
    }

    // ── Load / Save ────────────────────────────────────────────────────────────

    private fun load() {
        if (persistence.generateMode == GenerateMode.FULL_REPLACE) replaceRadio.isSelected = true
        else mergeRadio.isSelected = true
        // Show saved custom path, or fall back to default so user always sees what will be used
        valuesDirField.text = persistence.valuesDir.ifEmpty { defaultValuesDir }
        assetsDirField.text = persistence.assetsDir.ifEmpty { defaultAssetsDir }
        reportDirField.text = persistence.reportDir.ifEmpty { defaultReportDir }
    }

    override fun doOKAction() {
        persistence.generateMode = if (replaceRadio.isSelected) GenerateMode.FULL_REPLACE else GenerateMode.MERGE
        // Save as empty if user left it at the default (so future default changes are picked up)
        persistence.valuesDir = valuesDirField.text.trim().let { if (it == defaultValuesDir) "" else it }
        persistence.assetsDir = assetsDirField.text.trim().let { if (it == defaultAssetsDir) "" else it }
        persistence.reportDir = reportDirField.text.trim().let { if (it == defaultReportDir) "" else it }
        super.doOKAction()
    }

    private fun resetToDefaults() {
        mergeRadio.isSelected = true
        valuesDirField.text   = defaultValuesDir
        assetsDirField.text   = defaultAssetsDir
        reportDirField.text   = defaultReportDir
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun section(text: String) = TitledSeparator(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun desc(text: String) = JBLabel("<html><i>$text</i></html>").apply {
        foreground = UIUtil.getContextHelpForeground()
        font       = font.deriveFont(11f)
        border     = JBUI.Borders.emptyLeft(22)
    }

    private fun radioRow(radio: JRadioButton, descLabel: JBLabel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(radio.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(descLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    private fun dirRow(label: String, placeholder: String, picker: TextFieldWithBrowseButton): JPanel {
        picker.textField.toolTipText = placeholder
        return JPanel(BorderLayout(8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            add(JBLabel(label).apply { preferredSize = Dimension(90, 26) }, BorderLayout.WEST)
            add(picker, BorderLayout.CENTER)
        }
    }

    private fun vgap(h: Int): Component = Box.createRigidArea(Dimension(0, h))

    private fun createDirPicker() = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener("Select Directory", null, project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
}
