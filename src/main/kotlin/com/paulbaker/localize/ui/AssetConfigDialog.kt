package com.paulbaker.localize.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.awt.*
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class AssetConfigDialog(
    project: Project,
    private val asset: AssetConfig,
    initialSelected: List<String>,
    csvLocales: List<String> = emptyList()
) : DialogWrapper(project) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val fieldBoxes = linkedMapOf<String, JBCheckBox>()

    private val previewLocales: List<String> = csvLocales.ifEmpty { listOf("ko") }
    private var currentLocale: String = previewLocales.first()

    private val leftPane  = makeJsonPane()
    private val rightPane = makeJsonPane()

    // Parsed JSON — set by background worker, read by EDT for merge
    private var parsedBase: JsonElement? = null
    private var parsedTranslated: JsonElement? = null

    // Generation counter: only the latest load's result is accepted.
    // Prevents stale background results from overwriting newer data when switching locales quickly.
    private var loadGeneration = 0
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
        startLoad()
    }

    fun selectedFields(): List<String> =
        fieldBoxes.filter { it.value.isSelected }.keys.toList()

    // ── Panel layout ──────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
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
                    currentLocale = selectedItem as String
                    startLoad()
                }
                preferredSize = Dimension(100, 28)
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
        root.add(topRow, BorderLayout.NORTH)

        // Side-by-side JSON panels
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true).apply {
            leftComponent  = JBScrollPane(leftPane).apply {
                border = BorderFactory.createTitledBorder("Original  (EN)")
                preferredSize = Dimension(420, 420)
            }
            rightComponent = JBScrollPane(rightPane).apply {
                border = BorderFactory.createTitledBorder("Preview  ($currentLocale)")
                preferredSize = Dimension(420, 420)
            }
            resizeWeight = 0.5
            isContinuousLayout = true
        }
        root.add(split, BorderLayout.CENTER)

        root.preferredSize = Dimension(980, 600)
        return root
    }

    // ── Background loading ─────────────────────────────────────────────────────

    private fun startLoad() {
        // Increment generation — any in-flight load with a smaller number is stale
        val gen           = ++loadGeneration
        val localeSnapshot = currentLocale        // capture on EDT before switching to pool thread

        parsedTranslated = null                   // clear stale data immediately
        setLoadingText()

        val basePath       = asset.dir.resolve(asset.baseFile)
        val translatedPath = asset.dir.resolve(
            "${basePath.toFile().nameWithoutExtension}_$localeSnapshot.json"
        )

        // Run file I/O on pool thread, then update UI back on EDT.
        // SwingUtilities.invokeLater is used (not ApplicationManager.invokeLater) because
        // ApplicationManager's version respects ModalityState and won't fire inside modal dialogs.
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val base       = loadAndParse(basePath)
            val translated = loadAndParse(translatedPath)

            javax.swing.SwingUtilities.invokeLater {
                if (gen == loadGeneration) {
                    parsedBase       = base
                    parsedTranslated = translated
                    renderBoth()
                }
            }
        }
    }

    private fun loadAndParse(path: java.nio.file.Path): JsonElement? {
        if (!path.exists()) return null
        val size = path.toFile().length()
        if (size > MAX_PREVIEW_BYTES) {
            val kb = size / 1024
            return JsonParser.parseString("""{"⚠ preview_unavailable": "File too large to preview (${kb} KB). Content will still be processed during Generate."}""")
        }
        return runCatching { JsonParser.parseString(path.readText(Charsets.UTF_8)) }.getOrNull()
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 300L * 1024   // 300 KB cap
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Debounce: wait 120ms after last checkbox change. Only renders if load is complete. */
    private fun schedulePreviewRefresh() {
        pendingRefresh?.stop()
        pendingRefresh = Timer(120) {
            // Only render if data is fully loaded (parsedTranslated is null = still loading)
            if (parsedBase != null && parsedTranslated != null) renderBoth()
        }.also { it.isRepeats = false; it.start() }
    }

    private fun renderBoth() {
        val selected = fieldBoxes.filter { it.value.isSelected }.keys.toSet()

        // Left: always full original EN
        renderJson(leftPane, prettyPrint(parsedBase), selected, isTranslated = false)

        // Right: merge — ticked fields use translated value, unticked use EN value
        val merged = buildMergedPreview(parsedBase, parsedTranslated, selected)
        renderJson(rightPane, prettyPrint(merged), selected, isTranslated = true)
    }

    /**
     * Recursive structural merge of base + translated JSON at any depth.
     * - Objects: matched by key name; selected string fields use translated value
     * - Arrays: matched by index position
     * - Primitive selected fields: use translated if non-empty string, else keep base
     * - Non-selected fields: keep base value but RECURSE into nested objects/arrays
     */
    private fun buildMergedPreview(
        base: JsonElement?,
        translated: JsonElement?,
        selectedFields: Set<String>
    ): JsonElement? {
        if (base == null) return null
        if (translated == null || selectedFields.isEmpty()) return base
        return mergeRecursive(base, translated, selectedFields)
    }

    private fun mergeRecursive(
        base: JsonElement,
        trans: JsonElement,
        fields: Set<String>
    ): JsonElement = when {
        base.isJsonObject && trans.isJsonObject -> {
            val bObj = base.asJsonObject
            val tObj = trans.asJsonObject
            val out = JsonObject()
            bObj.keySet().forEach { key ->
                val bv = bObj.get(key)
                val tv = tObj.get(key)
                when {
                    // Selected string field → use translated if non-empty
                    key in fields &&
                    tv != null && tv.isJsonPrimitive && tv.asJsonPrimitive.isString &&
                    bv.isJsonPrimitive && bv.asJsonPrimitive.isString &&
                    tv.asString.isNotEmpty() ->
                        out.add(key, tv)
                    // Recurse into nested objects/arrays regardless of selection
                    tv != null && (bv.isJsonObject || bv.isJsonArray) ->
                        out.add(key, mergeRecursive(bv, tv, fields))
                    // Keep base for everything else
                    else -> out.add(key, bv)
                }
            }
            out
        }
        base.isJsonArray && trans.isJsonArray -> {
            val bArr = base.asJsonArray
            val tArr = trans.asJsonArray
            // Same rule as JsonLocalizer: pair by item identity, fall back to position only for
            // items that have none. Pairing by position would preview a neighbour's translation
            // and make a correct config look broken (or a broken one look fine).
            val transById = HashMap<String, JsonElement>()
            tArr.forEach { e -> com.paulbaker.localize.core.JsonLocalizer.itemIdentity(e)?.let { transById.putIfAbsent(it, e) } }
            JsonArray().also { out ->
                bArr.forEachIndexed { i, bItem ->
                    val identity = com.paulbaker.localize.core.JsonLocalizer.itemIdentity(bItem)
                    val tItem = when {
                        identity != null -> transById[identity]
                        i < tArr.size()  -> tArr[i]
                        else             -> null
                    }
                    out.add(if (tItem != null) mergeRecursive(bItem, tItem, fields) else bItem)
                }
            }
        }
        else -> base  // primitives that don't match the selected-field condition above
    }

    private fun prettyPrint(el: JsonElement?): String {
        if (el == null) return "(no file yet)"
        return runCatching { gson.toJson(el) }.getOrElse { "(parse error)" }
    }

    private fun renderJson(
        pane: JTextPane, json: String,
        highlightFields: Set<String>, isTranslated: Boolean
    ) {
        val doc = pane.styledDocument
        doc.remove(0, doc.length)

        val normalFg      = UIManager.getColor("EditorPane.foreground") ?: JBColor.foreground()
        val dimFg         = UIUtil.getContextHelpForeground()
        val translatedFg  = JBColor(Color(0, 130, 0), Color(80, 230, 80))    // green = translated
        val selectedKeyFg = JBColor(Color(90, 40, 180), Color(170, 140, 255)) // purple = selected field in EN

        json.lines().forEach { line ->
            val matchedField = highlightFields.firstOrNull { f -> line.contains("\"$f\"") }
            val style = SimpleAttributeSet()
            when {
                matchedField != null && isTranslated -> {
                    StyleConstants.setForeground(style, translatedFg)
                    StyleConstants.setBold(style, true)
                }
                matchedField != null -> {
                    StyleConstants.setForeground(style, selectedKeyFg)
                    StyleConstants.setBold(style, true)
                }
                line.trimStart().startsWith("\"") && line.contains("\":") ->
                    StyleConstants.setForeground(style, dimFg)
                else ->
                    StyleConstants.setForeground(style, normalFg)
            }
            doc.insertString(doc.length, line + "\n", style)
        }
        pane.caretPosition = 0
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
        // Invalidate any pending load by incrementing generation
        loadGeneration++
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

}
