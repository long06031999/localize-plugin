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
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

/**
 * Lets the user map CSV columns to roles (android_key, english, language, skip)
 * before the tool processes the file. Auto-detection provides initial suggestions.
 */
class CsvMappingDialog(
    project: Project,
    private val csvPath: Path,
    existingMapping: CsvMapping?
) : DialogWrapper(project) {

    companion object {
        val ROLE_SKIP       = "Skip"
        val ROLE_ANDROID_KEY = "android_key"
        val ROLE_ENGLISH    = "english"
        val ROLE_LANGUAGE   = "Language"
        val ROLES = arrayOf(ROLE_SKIP, ROLE_ANDROID_KEY, ROLE_ENGLISH, ROLE_LANGUAGE)
    }

    // ── Raw CSV data ───────────────────────────────────────────────────────────

    private val rawRows: List<List<String>>
    private val headers: List<String>
    private val sampleRows: List<List<String>>

    init {
        val raw = csvPath.toFile().readText(Charsets.UTF_8)
        rawRows = TranslationDb.parseCsvFull(raw)
        headers = rawRows.getOrNull(0)?.map { TranslationDb.stripBom(it) } ?: emptyList()
        sampleRows = rawRows.drop(1).filter { r -> r.any { it.isNotBlank() } }.take(5)
    }

    // ── Table model ────────────────────────────────────────────────────────────

    // For each header column: [role, locale]
    private val rowRoles   = Array(headers.size) { ROLE_SKIP }
    private val rowLocales = Array(headers.size) { "" }

    private val auto: CsvMapping = CsvPreprocessor.autoDetect(headers, sampleRows)

    init {
        // Apply existing mapping or auto-detection
        val mapping = existingMapping ?: auto
        applyMappingToModel(mapping)
    }

    private fun applyMappingToModel(mapping: CsvMapping) {
        headers.forEachIndexed { i, h ->
            val trimmed = h.trim()
            when {
                trimmed == mapping.keyColumn     -> { rowRoles[i] = ROLE_ANDROID_KEY }
                trimmed == mapping.englishColumn -> { rowRoles[i] = ROLE_ENGLISH }
                mapping.languageColumns.containsKey(trimmed) -> {
                    rowRoles[i]   = ROLE_LANGUAGE
                    rowLocales[i] = mapping.languageColumns[trimmed] ?: ""
                }
                else -> rowRoles[i] = ROLE_SKIP
            }
        }
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private val tableModel = object : AbstractTableModel() {
        val COLS = arrayOf("CSV Column", "Role", "Locale", "Sample")
        override fun getRowCount() = headers.size
        override fun getColumnCount() = 4
        override fun getColumnName(col: Int) = COLS[col]
        override fun isCellEditable(row: Int, col: Int) = col == 1 || (col == 2 && rowRoles[row] == ROLE_LANGUAGE)
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> headers[row].ifEmpty { "(empty)" }
            1 -> rowRoles[row]
            2 -> if (rowRoles[row] == ROLE_LANGUAGE) rowLocales[row] else ""
            3 -> sampleRows.firstOrNull { it.getOrNull(row)?.isNotBlank() == true }
                     ?.getOrNull(row)?.take(24) ?: ""
            else -> ""
        }
        override fun setValueAt(value: Any?, row: Int, col: Int) {
            when (col) {
                1 -> { rowRoles[row] = value as String; fireTableRowsUpdated(row, row) }
                2 -> rowLocales[row] = value as String
            }
            updatePreview()
        }
    }

    private val table = JBTable(tableModel).apply {
        columnModel.getColumn(0).preferredWidth = 200
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).preferredWidth = 70
        columnModel.getColumn(3).preferredWidth = 160
        rowHeight = 26

        // Role dropdown
        columnModel.getColumn(1).cellEditor = DefaultCellEditor(JComboBox(ROLES)).apply {
            (component as JComboBox<*>).addActionListener { updatePreview() }
        }
        // Locale text field
        columnModel.getColumn(2).cellEditor = DefaultCellEditor(JTextField())

        // Color non-skip rows
        columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, s: Boolean, f: Boolean, r: Int, c: Int): Component {
                val comp = super.getTableCellRendererComponent(t, v, s, f, r, c)
                foreground = when (rowRoles[r]) {
                    ROLE_ANDROID_KEY -> JBColor(Color(0, 100, 180), Color(100, 170, 255))
                    ROLE_ENGLISH     -> JBColor(Color(0, 130, 0),   Color(80, 220, 80))
                    ROLE_LANGUAGE    -> JBColor(Color(140, 60, 0),  Color(255, 180, 80))
                    else             -> UIUtil.getContextHelpForeground()
                }
                return comp
            }
        }
    }

    private val skipEmptyBox   = JBCheckBox("Skip empty rows", true)
    private val skipSectionBox = JBCheckBox("Skip section-only rows (≤1 non-empty cell)", true)
    private val rememberBox    = JBCheckBox("Remember this mapping for this file", true)

    private val previewModel = object : AbstractTableModel() {
        var previewHeaders = listOf<String>()
        var previewRows    = listOf<List<String>>()
        override fun getRowCount()        = previewRows.size
        override fun getColumnCount()     = previewHeaders.size
        override fun getColumnName(c: Int) = previewHeaders.getOrNull(c) ?: ""
        override fun getValueAt(r: Int, c: Int) = previewRows.getOrNull(r)?.getOrNull(c) ?: ""
    }
    private val previewTable = JBTable(previewModel).apply { rowHeight = 22; isEnabled = false }

    private fun updatePreview() {
        // Commit any active edits
        if (table.isEditing) table.cellEditor?.stopCellEditing()

        val mapping = buildMapping()
        val normalized = CsvPreprocessor.applyMapping(csvPath, mapping)

        previewModel.previewHeaders = buildList {
            add("android_key"); add("english")
            normalized.localeOrder.forEach { add(it) }
        }
        previewModel.previewRows = normalized.rows.take(5)
        previewModel.fireTableStructureChanged()
    }

    override fun createCenterPanel(): JComponent {
        title = "Configure CSV: ${csvPath.fileName}"
        setOKButtonText("Apply")

        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(8, 12)

        // File info
        val fileInfo = JBLabel("${headers.size} columns · ${rawRows.size - 1} rows · ${csvPath.fileName}").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(11f)
        }
        root.add(fileInfo, BorderLayout.NORTH)

        val center = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        // Column table
        center.add(JBLabel("Column Roles  (auto-detected — change any role by clicking the dropdown)").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.emptyBottom(4)
        })
        val tableScroll = JBScrollPane(table).apply {
            preferredSize = Dimension(660, 260)
            border = BorderFactory.createLineBorder(JBColor.border())
        }
        center.add(tableScroll)
        center.add(Box.createRigidArea(Dimension(0, 8)))

        // Filters
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Filters:").apply { font = font.deriveFont(Font.BOLD, 11f) })
            add(skipEmptyBox)
            add(skipSectionBox)
        }
        center.add(filterPanel)
        center.add(Box.createRigidArea(Dimension(0, 8)))

        // Preview
        center.add(JBLabel("Preview — first 5 rows after mapping:").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.emptyBottom(4)
        })
        val previewScroll = JBScrollPane(previewTable).apply {
            preferredSize = Dimension(660, 100)
            border = BorderFactory.createLineBorder(JBColor.border())
        }
        center.add(previewScroll)
        center.add(Box.createRigidArea(Dimension(0, 8)))
        center.add(rememberBox)

        root.add(center, BorderLayout.CENTER)

        // Reset button
        val resetBtn = JButton("Reset to auto-detect").apply {
            isBorderPainted = false; isContentAreaFilled = false
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(Font.ITALIC, 11f)
            cursor = Cursor(Cursor.HAND_CURSOR)
            addActionListener {
                applyMappingToModel(auto)
                tableModel.fireTableDataChanged()
                updatePreview()
            }
        }
        root.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(resetBtn) }, BorderLayout.SOUTH)

        root.preferredSize = Dimension(700, 560)

        updatePreview()
        return root
    }

    // ── Result ─────────────────────────────────────────────────────────────────

    fun buildMapping(): CsvMapping {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        var keyCol = ""
        var enCol  = ""
        val langCols = mutableMapOf<String, String>()
        headers.forEachIndexed { i, h ->
            when (rowRoles[i]) {
                ROLE_ANDROID_KEY -> keyCol = h
                ROLE_ENGLISH     -> enCol  = h
                ROLE_LANGUAGE    -> if (rowLocales[i].isNotEmpty()) langCols[h] = rowLocales[i]
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

    fun shouldRemember(): Boolean = rememberBox.isSelected
}
