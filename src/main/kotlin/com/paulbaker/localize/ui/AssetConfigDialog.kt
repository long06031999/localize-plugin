package com.paulbaker.localize.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.paulbaker.localize.config.AssetConfig
import com.paulbaker.localize.config.DEFAULT_TRANSLATE_FIELDS
import com.paulbaker.localize.core.JsonPreview
import java.awt.*
import java.nio.file.Path
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Field picker for one JSON asset, with a preview of what Generate will actually write.
 *
 * The preview resolves every selected field the same way [JsonLocalizer] does — spreadsheet
 * first, then the locale file already on disk, then English — so what you see here is what you
 * get. An earlier version consulted only the locale file, which meant any locale that hadn't
 * been generated yet previewed as English even though the spreadsheet had translations, with
 * nothing on screen to explain why.
 */
class AssetConfigDialog(
    project: Project,
    private val asset: AssetConfig,
    initialSelected: List<String>,
    csvLocales: List<String> = emptyList(),
    /**
     * (english, locale) → translation from the spreadsheet. Supplied by the Localize panel.
     * Null when no spreadsheet is in play (the Export panel), leaving the preview to rely on
     * the locale file alone.
     */
    private val translate: ((String, String) -> String?)? = null
) : DialogWrapper(project) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val fieldBoxes = linkedMapOf<String, JBCheckBox>()

    private val previewLocales: List<String> = csvLocales.ifEmpty { listOf("ko") }
    private var currentLocale: String = previewLocales.first()

    private val leftPane  = makeJsonPane()
    private val rightPane = makeJsonPane()

    /** Held so the previewed locale can be kept in sync — it used to be baked in once. */
    private lateinit var previewBorder: TitledBorder
    private lateinit var rightScroll: JBScrollPane

    private val statusLabel = JBLabel(" ").apply {
        font = font.deriveFont(11f)
        border = JBUI.Borders.empty(2, 2, 4, 2)
    }

    /** Only the latest refresh may paint; earlier ones are stale by definition. */
    private var refreshGeneration = 0
    private var pendingRefresh: Timer? = null

    init {
        title = "Configure: ${asset.name}"
        setOKButtonText("Save")

        // Detect fields BEFORE init()
        val baseText = runCatching {
            asset.dir.resolve(asset.baseFile).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: ""
        }.getOrElse { "" }

        // Only fields that EXIST in the JSON — never add phantom fields from stale initialSelected
        val detected = detectAllStringFields(baseText, asset.dataKey)
        val selectedSet = initialSelected.map { it.trim() }.toSet()

        detected.forEach { field ->
            fieldBoxes[field] = JBCheckBox(field).apply {
                isSelected = field in selectedSet
                font = font.deriveFont(Font.BOLD, 12f)
                addItemListener { schedulePreviewRefresh() }
            }
        }

        init()
        refresh(showLoading = true)
    }

    fun selectedFields(): List<String> =
        fieldBoxes.filter { it.value.isSelected }.keys.toList()

    // ── Panel layout ──────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 4))
        root.border = JBUI.Borders.empty(8, 12)

        // Top: fields + locale selector
        val topRow = JPanel(BorderLayout(12, 0))

        val fieldsWrap = JPanel(WrapLayout(FlowLayout.LEFT, 8, 2)).apply {
            border = BorderFactory.createTitledBorder(
                JBUI.Borders.empty(), "Fields to translate",
                0, 0, UIManager.getFont("Label.font")?.deriveFont(Font.BOLD)
            )
        }
        if (fieldBoxes.isEmpty()) {
            fieldsWrap.add(JBLabel("No string fields found in ${asset.baseFile}").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        } else {
            fieldBoxes.values.forEach { fieldsWrap.add(it) }
        }
        topRow.add(fieldsWrap, BorderLayout.CENTER)

        if (previewLocales.size > 1) {
            val localeBox = ComboBox(previewLocales.toTypedArray()).apply {
                selectedItem = currentLocale
                addActionListener {
                    val picked = selectedItem as? String ?: return@addActionListener
                    if (picked == currentLocale) return@addActionListener
                    currentLocale = picked
                    refresh(showLoading = true)
                }
                preferredSize = Dimension(120, 28)
            }
            topRow.add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(JBLabel("Preview:"))
                add(localeBox)
            }, BorderLayout.EAST)
        } else {
            topRow.add(JBLabel("  Preview: ${previewLocales.first()}").apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.EAST)
        }

        val header = JPanel(BorderLayout()).apply {
            add(topRow, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }
        root.add(header, BorderLayout.NORTH)

        // Side-by-side JSON panels
        previewBorder = BorderFactory.createTitledBorder("Preview  ($currentLocale)")
        rightScroll = JBScrollPane(rightPane).apply {
            border = previewBorder
            preferredSize = Dimension(420, 420)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true).apply {
            leftComponent  = JBScrollPane(leftPane).apply {
                border = BorderFactory.createTitledBorder("Original  (EN)")
                preferredSize = Dimension(420, 420)
            }
            rightComponent = rightScroll
            resizeWeight = 0.5
            isContinuousLayout = true
        }
        root.add(split, BorderLayout.CENTER)

        root.preferredSize = Dimension(980, 620)
        return root
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /** Debounce field toggles; the locale dropdown refreshes immediately. */
    private fun schedulePreviewRefresh() {
        pendingRefresh?.stop()
        pendingRefresh = Timer(120) { refresh(showLoading = false) }
            .also { it.isRepeats = false; it.start() }
    }

    /**
     * Everything expensive — file I/O, the spreadsheet lookup (which may build the translation
     * DB on first use) and pretty-printing — runs on a pooled thread. Only the text insertion
     * happens on the EDT.
     */
    private fun refresh(showLoading: Boolean) {
        val gen      = ++refreshGeneration
        val locale   = currentLocale
        val selected = fieldBoxes.filter { it.value.isSelected }.keys.toSet()
        if (showLoading) setLoadingText()

        ApplicationManager.getApplication().executeOnPooledThread {
            val baseLoad = load(asset.dir.resolve(asset.baseFile), asset.baseFile)
            val refName  = refFileName(locale)
            val refLoad  = load(asset.dir.resolve(refName), refName)

            val base = baseLoad.element
            val stats = JsonPreview.Stats()
            val baseLines: List<String>
            val prevLines: List<String>
            if (base == null) {
                baseLines = listOf(baseLoad.note ?: "(no content)")
                prevLines = baseLines
            } else {
                baseLines = prettyPrint(base).lines()
                prevLines = prettyPrint(JsonPreview.build(base, refLoad.element, selected, locale, translate, stats)).lines()
            }

            SwingUtilities.invokeLater {
                if (gen != refreshGeneration) return@invokeLater
                renderPane(leftPane,  baseLines, selected, compareWith = null)
                renderPane(rightPane, prevLines, selected, compareWith = baseLines)
                previewBorder.title = "Preview  ($locale)"
                rightScroll.repaint()
                statusLabel.text = statusHtml(stats, baseLoad.note, refLoad.note, refName)
            }
        }
    }

    // ── Preview construction ──────────────────────────────────────────────────

    // ── Loading ───────────────────────────────────────────────────────────────

    private class Loaded(val element: JsonElement?, val note: String?)

    /** [note] is non-null whenever the file could not be used, so nothing fails silently. */
    private fun load(path: Path, label: String): Loaded {
        if (!path.exists()) return Loaded(null, "$label not found")
        val size = path.toFile().length()
        if (size > MAX_PREVIEW_BYTES)
            return Loaded(null, "$label is ${size / 1024} KB — too large to preview (it is still processed on Generate)")
        return runCatching { Loaded(JsonParser.parseString(path.readText(Charsets.UTF_8)), null) }
            .getOrElse { Loaded(null, "$label could not be parsed") }
    }

    private fun refFileName(locale: String): String =
        "${asset.baseFile.substringBeforeLast('.')}_$locale.json"

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * [compareWith] turns colour into information: a line is green only when its value actually
     * differs from English. Previously every selected field was green whether it had been
     * translated or not, which made an untranslated preview look finished.
     */
    private fun renderPane(
        pane: JTextPane,
        lines: List<String>,
        selected: Set<String>,
        compareWith: List<String>?
    ) {
        val doc = pane.styledDocument
        doc.remove(0, doc.length)

        val normalFg     = UIManager.getColor("EditorPane.foreground") ?: JBColor.foreground()
        val dimFg        = UIUtil.getContextHelpForeground()
        val translatedFg = JBColor(Color(0, 130, 0), Color(80, 230, 80))     // green
        val pendingFg    = JBColor(Color(176, 94, 0), Color(255, 176, 84))   // amber
        val selectedFg   = JBColor(Color(90, 40, 180), Color(170, 140, 255)) // purple

        lines.forEachIndexed { i, line ->
            val isSelectedField = selected.any { line.contains("\"$it\"") }
            val changed = compareWith != null && compareWith.getOrNull(i) != line
            val style = SimpleAttributeSet()
            when {
                changed -> {
                    StyleConstants.setForeground(style, translatedFg)
                    StyleConstants.setBold(style, true)
                }
                compareWith != null && isSelectedField -> {
                    StyleConstants.setForeground(style, pendingFg)
                    StyleConstants.setBold(style, true)
                }
                compareWith == null && isSelectedField -> {
                    StyleConstants.setForeground(style, selectedFg)
                    StyleConstants.setBold(style, true)
                }
                line.trimStart().startsWith("\"") && line.contains("\":") ->
                    StyleConstants.setForeground(style, dimFg)
                else -> StyleConstants.setForeground(style, normalFg)
            }
            doc.insertString(doc.length, line + "\n", style)
        }
        pane.caretPosition = 0
    }

    private fun statusHtml(stats: JsonPreview.Stats, baseNote: String?, refNote: String?, refName: String): String {
        val counts = buildList {
            if (stats.fromCsv > 0)  add("<b>${stats.fromCsv}</b> from CSV")
            if (stats.fromFile > 0) add("<b>${stats.fromFile}</b> kept from $refName")
            if (stats.english > 0)  add("<b>${stats.english}</b> still English")
        }
        val head = when {
            counts.isNotEmpty() -> counts.joinToString("  ·  ")
            fieldBoxes.isEmpty() -> "No string fields in this asset"
            else -> "No field selected"
        }
        val warnings = listOfNotNull(baseNote, refNote).joinToString("<br>") { "⚠ $it" }
        val legend = if (stats.total > 0)
            "<br><i>Right pane: green = translated · amber = still English</i>" else ""
        val warnBlock = if (warnings.isEmpty()) "" else "<br>$warnings"
        return "<html>$head$warnBlock$legend</html>"
    }

    private fun prettyPrint(el: JsonElement?): String {
        if (el == null) return "(no file yet)"
        return runCatching { gson.toJson(el) }.getOrElse { "(parse error)" }
    }

    private fun setLoadingText() {
        listOf(leftPane, rightPane).forEach { pane ->
            pane.document.remove(0, pane.document.length)
            val style = SimpleAttributeSet()
            StyleConstants.setForeground(style, UIUtil.getContextHelpForeground())
            StyleConstants.setItalic(style, true)
            pane.styledDocument.insertString(0, "Loading…", style)
        }
    }

    override fun doCancelAction() {
        refreshGeneration++      // invalidate anything in flight
        pendingRefresh?.stop()
        super.doCancelAction()
    }

    // ── Field detection ───────────────────────────────────────────────────────

    /** Recursively collect all unique string field names at any depth in the JSON. */
    private fun detectAllStringFields(json: String, dataKey: String?): List<String> {
        if (json.isBlank()) return DEFAULT_TRANSLATE_FIELDS
        return runCatching {
            val root = JsonParser.parseString(json)
            val found = linkedSetOf<String>()
            collectStringFieldsRecursive(root, found, depth = 0)
            found.toList().ifEmpty { DEFAULT_TRANSLATE_FIELDS }
        }.getOrElse { DEFAULT_TRANSLATE_FIELDS }
    }

    private fun collectStringFieldsRecursive(el: JsonElement, found: MutableSet<String>, depth: Int) {
        if (depth > 8) return
        when {
            el.isJsonObject -> el.asJsonObject.keySet().forEach { key ->
                val v = el.asJsonObject.get(key)
                if (v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotEmpty())
                    found += key
                else
                    collectStringFieldsRecursive(v, found, depth + 1)
            }
            el.isJsonArray -> el.asJsonArray.forEach { item ->
                collectStringFieldsRecursive(item, found, depth + 1)
            }
        }
    }

    private fun makeJsonPane() = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = UIManager.getColor("EditorPane.background") ?: UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 300L * 1024   // 300 KB cap
    }
}
