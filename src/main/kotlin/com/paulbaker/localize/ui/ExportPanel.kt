package com.paulbaker.localize.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.paulbaker.localize.config.AssetConfig
import com.paulbaker.localize.config.ConfigPersistence
import com.paulbaker.localize.config.DEFAULT_TRANSLATE_FIELDS
import com.paulbaker.localize.core.ExcelExporter
import com.paulbaker.localize.core.JsonLocalizer
import com.paulbaker.localize.core.TranslationDb
import java.awt.*
import java.nio.file.Paths
import javax.swing.*
import kotlin.io.path.exists

class ExportPanel(val project: Project) : JPanel(BorderLayout()) {

    private val persistence = ConfigPersistence(project)
    private val projectDir  = Paths.get(project.basePath ?: ".")

    var mainPanel: MainPanel? = null

    // ── Top bar ────────────────────────────────────────────────────────────────
    private val backBtn = JButton(AllIcons.Actions.Back).apply {
        isBorderPainted = false; isContentAreaFilled = false
        preferredSize   = Dimension(30, 30)
        cursor          = Cursor(Cursor.HAND_CURSOR)
        toolTipText     = "Back to dashboard"
        addActionListener { mainPanel?.navigateTo("dashboard") }
    }
    private val exportBtn = JButton("Export").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        preferredSize = Dimension(100, 30)
        addActionListener { onExport() }
    }

    // ── Source pickers ─────────────────────────────────────────────────────────
    private val valuesDirField = createDirPicker()
    private val assetsDirField = createDirPicker()
    private val outputField    = createFileSavePicker()

    // ── Language checkboxes (dynamically populated from values-{locale}/ dirs) ─
    private val localeBoxes = mutableMapOf<String, JBCheckBox>()  // locale → checkbox
    private val localesRow  = JPanel(WrapLayout(FlowLayout.LEFT, 8, 4))

    // ── XML file checkboxes (dynamically populated from values/ dir) ──────────
    private val xmlFileBoxes = mutableMapOf<String, JBCheckBox>()  // filename → checkbox
    private val xmlFilesRow  = JPanel(WrapLayout(FlowLayout.LEFT, 8, 4))

    // ── JSON asset rows ────────────────────────────────────────────────────────
    data class AssetRow(val assetBox: JBCheckBox, var selectedFields: MutableList<String>, var config: AssetConfig)
    private val assetRows   = mutableMapOf<String, AssetRow>()
    private val assetPanel  = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val ignoredList = mutableListOf<Triple<String, AssetConfig, String>>()
    private val ignoredSectionPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); alignmentX = Component.LEFT_ALIGNMENT }
    private var ignoredExpanded = false

    // ── Log ────────────────────────────────────────────────────────────────────
    val outputPanel = LocalizeOutputPanel()

    init {
        buildUI()
        restoreAndScan()
    }

    private fun buildUI() {
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 10, 4, 10)
            add(backBtn,    BorderLayout.WEST)
            add(exportBtn,  BorderLayout.EAST)
        }
        add(topBar, BorderLayout.NORTH)

        val config = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        config.border = JBUI.Borders.empty(4, 10, 8, 10)

        config.add(section("Source"))
        config.add(vgap(4))
        config.add(dirRow("Values dir:", valuesDirField))
        config.add(vgap(3))
        config.add(dirRow("Assets dir:", assetsDirField))
        config.add(vgap(10))

        config.add(section("Languages  (locales to export as columns)"))
        config.add(vgap(4))
        localesRow.alignmentX = Component.LEFT_ALIGNMENT
        config.add(localesRow)
        config.add(vgap(10))

        config.add(section("XML Files  (from values/ directory)"))
        config.add(vgap(4))
        xmlFilesRow.alignmentX = Component.LEFT_ALIGNMENT
        config.add(xmlFilesRow)
        config.add(vgap(10))

        config.add(section("JSON Assets"))
        config.add(vgap(4))
        assetPanel.alignmentX = Component.LEFT_ALIGNMENT
        config.add(assetPanel)
        config.add(vgap(10))

        config.add(section("Output"))
        config.add(vgap(4))
        config.add(dirRow("Output file:", outputField))

        val configScroll = JBScrollPane(config).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val logHeader = JBLabel("  Log").apply {
            font = font.deriveFont(Font.BOLD, 12f); foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(4, 6, 4, 0)
        }
        val logWrapper = JPanel(BorderLayout()).apply {
            add(logHeader, BorderLayout.NORTH)
            add(JBScrollPane(outputPanel.textPane).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }
        val split = OnePixelSplitter(true, 0.65f).apply {
            firstComponent  = configScroll
            secondComponent = logWrapper
        }
        add(split, BorderLayout.CENTER)
    }

    private fun restoreAndScan() {
        valuesDirField.text = persistence.valuesDir.ifEmpty {
            projectDir.resolve("app/src/main/res/values").toString()
        }
        assetsDirField.text = persistence.assetsDir.ifEmpty {
            projectDir.resolve("app/src/main/assets").toString()
        }
        outputField.text = persistence.exportOutputPath.ifEmpty {
            projectDir.resolve("localize_export.xlsx").toString()
        }
        scanLocales()
        scanXmlFiles()
        scanAssets()

        // Re-scan when values dir changes
        valuesDirField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent)  { scanLocales(); scanXmlFiles() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent)  { scanLocales(); scanXmlFiles() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { scanLocales(); scanXmlFiles() }
        })
        assetsDirField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent)  = scanAssets()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent)  = scanAssets()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scanAssets()
        })
    }

    private fun scanLocales() {
        val valDir = Paths.get(valuesDirField.text.trim()).takeIf { it.toFile().isDirectory } ?: run {
            localesRow.removeAll(); localeBoxes.clear()
            localesRow.revalidate(); localesRow.repaint(); return
        }
        val savedLocales = persistence.exportLocales
        localesRow.removeAll(); localeBoxes.clear()
        valDir.parent?.toFile()?.listFiles { f ->
            f.isDirectory && f.name.startsWith("values-") && f.name.length > 7
        }?.sortedBy { it.name }?.forEach { dir ->
            val locale = dir.name.removePrefix("values-")
            if (locale.isEmpty()) return@forEach
            localeBoxes[locale] = JBCheckBox(locale).apply {
                isSelected = savedLocales.isEmpty() || locale in savedLocales
                font = font.deriveFont(13f)
            }
            localesRow.add(localeBoxes[locale])
        }
        if (localeBoxes.isEmpty())
            localesRow.add(JBLabel("(no locales found — select values dir)").apply {
                foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(Font.ITALIC, 11f)
            })
        localesRow.revalidate(); localesRow.repaint()
    }

    private fun scanXmlFiles() {
        val dir = Paths.get(valuesDirField.text.trim()).takeIf { it.toFile().isDirectory } ?: run {
            xmlFilesRow.removeAll(); xmlFileBoxes.clear()
            xmlFilesRow.add(JBLabel("(select values dir first)").apply {
                foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(Font.ITALIC, 11f)
            })
            xmlFilesRow.revalidate(); xmlFilesRow.repaint(); return
        }
        val savedFiles = persistence.exportXmlFiles
        xmlFilesRow.removeAll(); xmlFileBoxes.clear()
        dir.toFile().listFiles { f -> f.extension == "xml" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                if (file.name in com.paulbaker.localize.config.XML_NON_TRANSLATABLE_NAMES) return@forEach
                if (!file.readText().contains("<string")) return@forEach
                val box = JBCheckBox(file.name).apply {
                    isSelected = savedFiles.isEmpty() || file.name in savedFiles
                    font = font.deriveFont(13f)
                }
                xmlFileBoxes[file.name] = box
                xmlFilesRow.add(box)
            }
        if (xmlFileBoxes.isEmpty())
            xmlFilesRow.add(JBLabel("(no translatable XML found)").apply { foreground = UIUtil.getContextHelpForeground() })
        xmlFilesRow.revalidate(); xmlFilesRow.repaint()
    }

    private fun scanAssets() {
        val dir = Paths.get(assetsDirField.text.trim()).takeIf { it.toFile().isDirectory } ?: return
        val localizer = JsonLocalizer(TranslationDb())
        assetPanel.removeAll(); assetRows.clear()
        ignoredList.clear()

        dir.toFile().listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val baseFile = d.listFiles { f ->
                f.extension == "json" && !f.nameWithoutExtension.contains(Regex("_[a-z]{2}(-r[A-Z]{2})?$"))
            }?.firstOrNull() ?: return@forEach
            val stub    = AssetConfig(d.name, d.toPath(), baseFile.name, DEFAULT_TRANSLATE_FIELDS)
            val dataKey = localizer.detectDataKey(stub)
            val withKey = stub.copy(dataKey = dataKey)
            val reason  = localizer.ignoreReason(withKey)
            if (reason != null) { ignoredList += Triple(d.name, withKey, reason); return@forEach }

            val auto    = localizer.detectTranslateFields(withKey)
            val allFlds = localizer.detectAllStringFields(withKey)
            val saved   = persistence.exportAssetFields[d.name]?.filter { it in allFlds }
            val initial = (saved ?: auto).toMutableList()
            val row     = AssetRow(
                JBCheckBox(d.name).apply { isSelected = true; font = font.deriveFont(13f) },
                initial, withKey.copy(translateFields = initial)
            )
            assetRows[d.name] = row

            val summaryLabel = JBLabel("  ${initial.joinToString(", ")}").apply {
                foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(11f)
            }
            val gearBtn = JButton("⚙").apply {
                isBorderPainted = false; isContentAreaFilled = false
                font = font.deriveFont(14f); cursor = Cursor(Cursor.HAND_CURSOR); preferredSize = Dimension(28, 24)
                isVisible = row.assetBox.isSelected
                addActionListener {
                    val locales = dir.toFile().listFiles { f -> f.isDirectory }?.flatMap { sd ->
                        sd.listFiles { f -> f.extension == "json" &&
                            f.nameWithoutExtension.contains(Regex("_[a-z]{2}$")) }
                            ?.map { it.nameWithoutExtension.substringAfterLast("_") } ?: emptyList()
                    }?.distinct() ?: emptyList()
                    val dialog = AssetConfigDialog(project, withKey, row.selectedFields, locales)
                    if (dialog.showAndGet()) {
                        row.selectedFields = dialog.selectedFields().toMutableList()
                        row.config = withKey.copy(translateFields = row.selectedFields)
                        summaryLabel.text = "  ${row.selectedFields.joinToString(", ")}"
                        persistence.exportAssetFields = assetRows.mapValues { it.value.selectedFields }
                    }
                }
            }
            row.assetBox.addItemListener { gearBtn.isVisible = row.assetBox.isSelected }

            assetPanel.add(JPanel(BorderLayout(6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 30)
                border = JBUI.Borders.empty(2, 0)
                add(row.assetBox, BorderLayout.WEST); add(summaryLabel, BorderLayout.CENTER); add(gearBtn, BorderLayout.EAST)
            })
        }

        // Ignored section
        assetPanel.add(ignoredSectionPanel)
        rebuildIgnoredSection()
        assetPanel.revalidate(); assetPanel.repaint()
    }

    private fun rebuildIgnoredSection() {
        ignoredSectionPanel.removeAll()
        if (ignoredList.isEmpty()) { ignoredSectionPanel.isVisible = false; ignoredSectionPanel.revalidate(); return }
        ignoredSectionPanel.isVisible = true
        val arrow = if (ignoredExpanded) "▼" else "▶"
        val hdr   = JLabel("$arrow  Ignored  (${ignoredList.size})").apply {
            font = font.deriveFont(Font.ITALIC, 11f); foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor(Cursor.HAND_CURSOR)
        }
        hdr.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                ignoredExpanded = !ignoredExpanded; rebuildIgnoredSection()
                ignoredSectionPanel.revalidate(); ignoredSectionPanel.repaint()
            }
        })
        ignoredSectionPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 26)
            border = JBUI.Borders.empty(4, 0, 2, 0); add(hdr)
        })
        if (!ignoredExpanded) { ignoredSectionPanel.revalidate(); ignoredSectionPanel.repaint(); return }
        ignoredList.toList().forEach { (name, cfg, reason) ->
            val addBtn = JButton("+ Add").apply {
                font = font.deriveFont(11f); isBorderPainted = true; isContentAreaFilled = false
                cursor = Cursor(Cursor.HAND_CURSOR); preferredSize = Dimension(60, 22)
                addActionListener {
                    ignoredList.removeIf { it.first == name }
                    val localizer = JsonLocalizer(TranslationDb())
                    val auto = localizer.detectTranslateFields(cfg)
                    val row  = AssetRow(JBCheckBox(name).apply { isSelected = true; font = font.deriveFont(13f) }, auto.toMutableList(), cfg.copy(translateFields = auto))
                    assetRows[name] = row
                    val sl = JBLabel("  ${auto.joinToString(", ")}").apply { foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(11f) }
                    val idx = assetPanel.componentCount - 1
                    assetPanel.add(JPanel(BorderLayout(6, 0)).apply {
                        alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 30); border = JBUI.Borders.empty(2, 0)
                        add(row.assetBox, BorderLayout.WEST); add(sl, BorderLayout.CENTER)
                    }, idx)
                    rebuildIgnoredSection(); assetPanel.revalidate(); assetPanel.repaint()
                }
            }
            ignoredSectionPanel.add(JPanel(BorderLayout(6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 26); border = JBUI.Borders.empty(1, 8)
                add(JBLabel("  $name").apply { font = font.deriveFont(12f); preferredSize = Dimension(160, 24) }, BorderLayout.WEST)
                add(JBLabel("⚠ $reason").apply { font = font.deriveFont(Font.ITALIC, 11f); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
                add(addBtn, BorderLayout.EAST)
            })
        }
        ignoredSectionPanel.revalidate(); ignoredSectionPanel.repaint()
    }

    // ── Generate ───────────────────────────────────────────────────────────────

    private fun onExport() {
        val valDir  = Paths.get(valuesDirField.text.trim()).takeIf { it.toFile().isDirectory }
        val assDir  = Paths.get(assetsDirField.text.trim()).takeIf { it.toFile().isDirectory }
        val outPath = Paths.get(outputField.text.trim())

        if (valDir == null) { outputPanel.append("⚠ Values dir not found.", OutputLevel.WARN); return }
        if (assDir == null) { outputPanel.append("⚠ Assets dir not found.", OutputLevel.WARN); return }
        if (outputField.text.isBlank()) { outputPanel.append("⚠ Output path is empty.", OutputLevel.WARN); return }

        persistence.exportOutputPath = outputField.text
        persistence.exportXmlFiles   = xmlFileBoxes.filter { it.value.isSelected }.keys.toList()
        persistence.exportLocales    = localeBoxes.filter { it.value.isSelected }.keys.toList()
        persistence.exportAssetFields = assetRows.mapValues { it.value.selectedFields }

        exportBtn.isEnabled = false
        outputPanel.clear()

        val selectedXmlFiles = xmlFileBoxes.filter { it.value.isSelected }.keys.toList()
        val selectedLocales = localeBoxes.filter { it.value.isSelected }.keys.toList()
        val selAssets = assetRows.filter { it.value.assetBox.isSelected }
            .map { (_, r) -> r.config.copy(translateFields = r.selectedFields) }

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Exporting to Excel…", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        ExcelExporter(
                            valuesDir          = valDir,
                            assetsDir          = assDir,
                            selectedXmlFiles   = selectedXmlFiles,
                            selectedLocales    = selectedLocales,
                            selectedJsonAssets = selAssets,
                            logger = { msg ->
                                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                    outputPanel.append(msg, OutputLevel.INFO)
                                }
                            }
                        ).export(outPath)
                    } catch (ex: Exception) {
                        javax.swing.SwingUtilities.invokeLater {
                            outputPanel.append("❌ ERROR: ${ex.message}", OutputLevel.ERROR)
                        }
                        ex.printStackTrace()
                    } finally {
                        javax.swing.SwingUtilities.invokeLater { exportBtn.isEnabled = true }
                    }
                }
            }
        )
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun section(text: String) = TitledSeparator(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private fun vgap(h: Int): Component = Box.createRigidArea(Dimension(0, h))
    private fun dirRow(label: String, picker: TextFieldWithBrowseButton) =
        JPanel(BorderLayout(6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 30)
            add(JBLabel(label).apply { preferredSize = Dimension(80, 26) }, BorderLayout.WEST)
            add(picker, BorderLayout.CENTER)
        }

    private fun createDirPicker() = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener("Select Directory", null, project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private fun createFileSavePicker() = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener("Select Output File", null, project,
            FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor())
    }
}
