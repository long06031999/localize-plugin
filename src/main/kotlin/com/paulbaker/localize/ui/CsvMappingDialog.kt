package com.paulbaker.localize.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.paulbaker.localize.config.CsvMapping
import com.paulbaker.localize.core.CsvPreprocessor
import com.paulbaker.localize.core.TranslationDb
import java.awt.*
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.AbstractTableModel

class CsvMappingDialog(
    project: Project,
    private val csvPath: Path,
    existingMapping: CsvMapping?
) : DialogWrapper(project) {

    companion object {
        const val ROLE_SKIP         = "Skip"
        const val ROLE_STRING_KEY  = "String Key"
        const val ROLE_STRING_VALUE      = "String Value"
        const val ROLE_LANGUAGE     = "Language"
        val ROLES = arrayOf(ROLE_SKIP, ROLE_STRING_KEY, ROLE_STRING_VALUE, ROLE_LANGUAGE)
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private val rawRows: List<List<String>> = run {
        val raw = runCatching { csvPath.toFile().readText(Charsets.UTF_8) }.getOrElse { "" }
        if (raw.isNotEmpty()) TranslationDb.parseCsvFull(raw) else emptyList()
    }
    private val headers: List<String>          = rawRows.getOrNull(0)?.map { TranslationDb.stripBom(it) } ?: emptyList()
    private val sampleRows: List<List<String>> = rawRows.drop(1).filter { r -> r.any { it.isNotBlank() } }.take(5)
    private val auto: CsvMapping               = CsvPreprocessor.autoDetect(headers, sampleRows)

    // ── Per-row widgets (real JComboBox — no cell editor complications) ────────

    private data class RowWidgets(
        val combo: JComboBox<String>,
        val localeField: JTextField,
        val sampleLabel: JLabel
    )

    private val rowWidgets: List<RowWidgets> = headers.mapIndexed { i, _ ->
        val combo = JComboBox(ROLES)
        val localeField = JTextField(6).apply { font = font.deriveFont(12f) }
        val sample = sampleRows.mapNotNull { it.getOrNull(i)?.takeIf { s -> s.isNotBlank() } }
                         .firstOrNull()?.take(28) ?: ""
        val sampleLabel = JLabel(sample).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(11f)
        }
        // Show/hide locale field based on role selection
        combo.addActionListener {
            localeField.isVisible = combo.selectedItem == ROLE_LANGUAGE
            schedulePreviewRefresh()
        }
        localeField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent)  = schedulePreviewRefresh()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent)  = schedulePreviewRefresh()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = schedulePreviewRefresh()
        })
        RowWidgets(combo, localeField, sampleLabel)
    }

    // ── Filter checkboxes + preview + remember ─────────────────────────────────

    private val skipEmptyBox   = JBCheckBox("Skip empty rows", true)
    private val skipSectionBox = JBCheckBox("Skip section-only rows  (≤1 non-empty cell)", true)
    private val rememberBox    = JBCheckBox("Remember this mapping for this file", true)

    private val previewModel = object : AbstractTableModel() {
        var cols = listOf<String>()
        var rows = listOf<List<String>>()
        override fun getRowCount()    = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int)      = cols.getOrNull(c) ?: ""
        override fun getValueAt(r: Int, c: Int) = rows.getOrNull(r)?.getOrNull(c) ?: ""
    }
    private val previewTable = JBTable(previewModel).apply { rowHeight = 22; isEnabled = false }

    private var previewTimer: Timer? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        title = "Configure CSV: ${csvPath.fileName}"
        setOKButtonText("Apply")
        applyMappingToWidgets(existingMapping ?: auto)
        init()
        schedulePreviewRefresh()
    }

    // ── Layout ─────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(8, 12)

        root.add(JBLabel(
            "${headers.size} columns  ·  ${rawRows.size - 1} rows  ·  ${csvPath.fileName}"
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(11f)
        }, BorderLayout.NORTH)

        val center = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        // ── Column header row ──────────────────────────────────────────────────
        center.add(headerRow())
        center.add(Box.createRigidArea(Dimension(0, 2)))

        // ── Mapping rows (real JComboBoxes — no JTable cell editor) ───────────
        val mappingPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        headers.forEachIndexed { i, header ->
            mappingPanel.add(mappingRow(i, header))
        }
        center.add(JBScrollPane(mappingPanel).apply {
            preferredSize = Dimension(680, 260)
            border = BorderFactory.createLineBorder(JBColor.border())
        })
        center.add(Box.createRigidArea(Dimension(0, 8)))

        // ── Filters ────────────────────────────────────────────────────────────
        center.add(JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Filters:").apply { font = font.deriveFont(Font.BOLD, 11f) })
            add(skipEmptyBox)
            add(skipSectionBox)
        })
        center.add(Box.createRigidArea(Dimension(0, 8)))

        // ── Preview ────────────────────────────────────────────────────────────
        center.add(JBLabel("Preview — first 5 rows after mapping:").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.emptyBottom(4)
        })
        center.add(JBScrollPane(previewTable).apply {
            preferredSize = Dimension(680, 95)
            border = BorderFactory.createLineBorder(JBColor.border())
        })
        center.add(Box.createRigidArea(Dimension(0, 8)))
        center.add(rememberBox)

        root.add(center, BorderLayout.CENTER)
        root.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Reset to auto-detect").apply {
                isBorderPainted = false; isContentAreaFilled = false
                foreground = UIUtil.getContextHelpForeground()
                font = font.deriveFont(Font.ITALIC, 11f)
                cursor = Cursor(Cursor.HAND_CURSOR)
                addActionListener { applyMappingToWidgets(auto); schedulePreviewRefresh() }
            })
        }, BorderLayout.SOUTH)

        root.preferredSize = Dimension(720, 580)
        return root
    }

    private fun headerRow(): JPanel = JPanel(GridLayout(1, 4, 6, 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 22)
        border = JBUI.Borders.empty(0, 4)
        listOf("CSV Column", "Role", "Locale", "Sample").forEach { text ->
            add(JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = UIUtil.getContextHelpForeground()
            })
        }
    }

    private fun mappingRow(i: Int, header: String): JPanel {
        val w = rowWidgets[i]
        return JPanel(GridLayout(1, 4, 6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            border = JBUI.Borders.empty(2, 4)
            // Col 1: CSV column name
            add(JLabel(header.ifEmpty { "(empty)" }).apply {
                foreground = if (header.isEmpty()) UIUtil.getContextHelpForeground() else UIManager.getColor("Label.foreground")
                font = font.deriveFont(12f)
                toolTipText = header
            })
            // Col 2: Role JComboBox (always editable — no cell editor issues)
            add(w.combo)
            // Col 3: Locale text field (only visible for Language role)
            add(w.localeField)
            // Col 4: Sample value
            add(w.sampleLabel)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun applyMappingToWidgets(mapping: CsvMapping) {
        headers.forEachIndexed { i, h ->
            val t = h.trim()
            val w = rowWidgets[i]
            when {
                t == mapping.keyColumn     -> { w.combo.selectedItem = ROLE_STRING_KEY; w.localeField.isVisible = false }
                t == mapping.englishColumn -> { w.combo.selectedItem = ROLE_STRING_VALUE;     w.localeField.isVisible = false }
                mapping.languageColumns.containsKey(t) -> {
                    w.combo.selectedItem = ROLE_LANGUAGE
                    w.localeField.text   = mapping.languageColumns[t] ?: ""
                    w.localeField.isVisible = true
                }
                else -> { w.combo.selectedItem = ROLE_SKIP; w.localeField.isVisible = false }
            }
        }
    }

    private fun schedulePreviewRefresh() {
        previewTimer?.stop()
        previewTimer = Timer(200) {
            val mapping = buildMapping()
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread {
                    val norm = runCatching { CsvPreprocessor.applyMapping(csvPath, mapping) }.getOrNull()
                        ?: return@executeOnPooledThread
                    javax.swing.SwingUtilities.invokeLater {
                        previewModel.cols = buildList { add("Key"); add("Value"); addAll(norm.localeOrder) }
                        previewModel.rows = norm.rows.take(5)
                        previewModel.fireTableStructureChanged()
                    }
                }
        }.also { it.isRepeats = false; it.start() }
    }

    // ── Result ─────────────────────────────────────────────────────────────────

    fun buildMapping(): CsvMapping {
        var keyCol = ""
        var enCol  = ""
        val langCols = mutableMapOf<String, String>()
        headers.forEachIndexed { i, h ->
            val w = rowWidgets[i]
            when (w.combo.selectedItem) {
                ROLE_STRING_KEY -> keyCol = h
                ROLE_STRING_VALUE     -> enCol  = h
                ROLE_LANGUAGE    -> { val loc = w.localeField.text.trim(); if (loc.isNotEmpty()) langCols[h] = loc }
            }
        }
        return CsvMapping(
            keyColumn       = keyCol,
            englishColumn   = enCol,
            languageColumns = langCols,
            skipEmptyRows   = skipEmptyBox.isSelected,
            skipSectionRows = skipSectionBox.isSelected,
        )
    }

    fun shouldRemember() = rememberBox.isSelected
}
