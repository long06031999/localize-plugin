package com.paulbaker.localize.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.paulbaker.localize.LocalizeRunner
import com.paulbaker.localize.config.*
import com.paulbaker.localize.config.GenerateMode
import com.paulbaker.localize.core.JsonLocalizer
import com.paulbaker.localize.core.TranslationDb
import java.awt.*
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.*
import kotlin.io.path.exists

class LocalizePanel(val project: Project) : JPanel(BorderLayout()) {

    private val persistence = ConfigPersistence(project)
    private val projectDir: Path = Paths.get(project.basePath ?: ".")

    // CSV pickers
    private val csvAndroidField = createFilePicker()
    private val csvOverlapField  = createFilePicker()
    private val csvArraysField   = createFilePicker()

    // Language checkboxes — keyed by locale code (e.g. "ko")
    private val languageBoxes = mutableMapOf<String, JBCheckBox>()
    private val languageRow   = JPanel(WrapLayout(FlowLayout.LEFT, 8, 4))
    // Maps locale → original CSV column name (e.g. "ko" → "Korean").
    // Used to build langMap for loadCsv/loadArrayCsv so they can find the right column.
    private val localeToColumnName = mutableMapOf<String, String>()

    // XML checkboxes
    private val xmlBoxes = mutableMapOf<String, JBCheckBox>()
    private val xmlRow   = JPanel(WrapLayout(FlowLayout.LEFT, 8, 4))

    // JSON asset rows
    data class AssetRow(
        val assetBox: JBCheckBox,
        var selectedFields: MutableList<String>,
        var config: AssetConfig
    )
    private val assetRows  = mutableMapOf<String, AssetRow>()
    private val assetPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    // Ignored assets group — MUST be declared before init {}
    private data class IgnoredAsset(val name: String, val config: AssetConfig, val reason: String)
    private val ignoredList         = mutableListOf<IgnoredAsset>()
    private val ignoredSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private var ignoredExpanded = false

    // Log output
    val outputPanel = LocalizeOutputPanel()

    // Set by MainPanel after construction
    var mainPanel: MainPanel? = null

    // Fixed top bar buttons
    private val backBtn = JButton(com.intellij.icons.AllIcons.Actions.Back).apply {
        isBorderPainted = false; isContentAreaFilled = false
        preferredSize   = Dimension(30, 30)
        cursor          = Cursor(Cursor.HAND_CURSOR)
        toolTipText     = "Back to dashboard"
        addActionListener { mainPanel?.navigateTo("dashboard") }
    }
    private val generateBtn = JButton("Generate").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        preferredSize = Dimension(120, 30)
        addActionListener { onGenerate() }
    }
    private val settingsBtn = JButton(com.intellij.icons.AllIcons.General.Settings).apply {
        isBorderPainted      = false
        isContentAreaFilled  = false
        preferredSize        = Dimension(30, 30)
        cursor               = Cursor(Cursor.HAND_CURSOR)
        toolTipText          = "Settings"
        addActionListener {
            // Snapshot dir settings BEFORE dialog opens
            val prevValuesDir = persistence.valuesDir
            val prevAssetsDir = persistence.assetsDir
            // Pass the SAME persistence instance so SettingsDialog writes to the same in-memory data
            val dialog = SettingsDialog(project, projectDir, persistence)
            dialog.showAndGet()
            // Only re-scan if the relevant directory actually changed — avoids resetting asset tick state
            if (persistence.valuesDir != prevValuesDir) scanXmlFiles()
            if (persistence.assetsDir != prevAssetsDir) scanAssets()
        }
    }

    init {
        buildUI()
        restoreAndScan()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildUI() {
        // ── Fixed top bar (Generate + Settings) — not inside scroll ────────────
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 10, 4, 10)
            val leftBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false; add(backBtn); add(generateBtn)
            }
            add(leftBar,    BorderLayout.WEST)
            add(settingsBtn, BorderLayout.EAST)
        }
        add(topBar, BorderLayout.NORTH)

        // ── Scrollable config panel ────────────────────────────────────────────
        val config = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        config.border = JBUI.Borders.empty(4, 10, 8, 10)

        config.add(section("CSV Files"))
        config.add(vgap(4))
        config.add(csvRow("android_only_strings.csv", csvAndroidField, showMapBtn = true))
        config.add(vgap(3))
        config.add(csvRow("overlap.csv  (optional)",  csvOverlapField,  showMapBtn = true))
        config.add(vgap(3))
        config.add(csvRow("array_strings.csv  (optional)", csvArraysField,  showMapBtn = true))
        config.add(vgap(10))

        config.add(section("Languages"))
        config.add(vgap(4))
        languageRow.alignmentX = Component.LEFT_ALIGNMENT
        config.add(languageRow)
        config.add(vgap(10))

        config.add(section("XML Files"))
        config.add(vgap(4))
        xmlRow.alignmentX = Component.LEFT_ALIGNMENT
        config.add(xmlRow)
        config.add(vgap(10))

        config.add(section("JSON Assets"))
        config.add(vgap(4))
        assetPanel.alignmentX = Component.LEFT_ALIGNMENT
        config.add(assetPanel)
        config.add(vgap(10))

        val configScroll = JBScrollPane(config).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // ── Log panel (bottom) ─────────────────────────────────────────────────
        val logHeader = JBLabel("  Log").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(4, 6, 4, 0)
        }
        val logWrapper = JPanel(BorderLayout()).apply {
            add(logHeader, BorderLayout.NORTH)
            add(JBScrollPane(outputPanel.textPane).apply {
                border = BorderFactory.createEmptyBorder()
            }, BorderLayout.CENTER)
        }

        // ── OnePixelSplitter: clean 1px divider, no ugly JSplitPane handle ────
        val split = OnePixelSplitter(true, 0.65f).apply {
            firstComponent  = configScroll
            secondComponent = logWrapper
        }
        add(split, BorderLayout.CENTER)
    }

    // ── Restore & detect ──────────────────────────────────────────────────────

    fun restoreAndScan() {
        if (persistence.csvAndroidOnly.isNotEmpty()) csvAndroidField.text = persistence.csvAndroidOnly
        if (persistence.csvOverlap.isNotEmpty())     csvOverlapField.text  = persistence.csvOverlap
        if (persistence.csvArrays.isNotEmpty())      csvArraysField.text   = persistence.csvArrays

        if (persistence.csvAndroidOnly.isEmpty()) {
            projectDir.toFile().listFiles { f -> f.extension == "csv" }?.forEach { csv ->
                val n = csv.name.lowercase()
                when {
                    "android_only" in n               -> csvAndroidField.text = csv.absolutePath
                    "overlap" in n && "array" !in n   -> csvOverlapField.text  = csv.absolutePath
                    "array"   in n                    -> csvArraysField.text   = csv.absolutePath
                }
            }
        }

        refreshLanguages()
        scanXmlFiles()
        scanAssets()

        // Re-detect languages whenever ANY csv field changes
        listOf(csvAndroidField, csvOverlapField, csvArraysField).forEach { field ->
            field.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent)  = refreshLanguages()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent)  = refreshLanguages()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshLanguages()
            })
        }
    }

    /**
     * Merge languages from ALL three CSV fields into one unified Languages section.
     * Each field may have its own saved column mapping.
     */
    private fun refreshLanguages() {
        // Deduplicate by LOCALE CODE: locale → best_display_key
        // Prefer descriptive names (e.g. "Korean") over raw ISO codes (e.g. "ko")
        val byLocale = linkedMapOf<String, String>()  // locale → display_key
        val unresolved = mutableListOf<String>()

        listOf(csvAndroidField, csvOverlapField, csvArraysField).forEach { field ->
            val path = Paths.get(field.text.trim()).takeIf { it.toFile().isFile } ?: return@forEach
            val mapping = persistence.getCsvMapping(path.toString())
            if (mapping != null) {
                // Saved mapping: locale code as both key and display
                mapping.languageColumns.forEach { (_, locale) ->
                    if (!byLocale.containsKey(locale)) byLocale[locale] = locale
                    // Don't overwrite if already have a descriptive name for this locale
                }
            } else {
                // Standard header detection
                TranslationDb().readHeaders(path)
                    .filter { it.isNotEmpty() && it.lowercase() !in NON_LANGUAGE_COLS }
                    .forEach { col ->
                        val locale = resolveLocale(col) ?: persistence.customLocaleMap[col.lowercase()]
                        if (locale != null) {
                            // Prefer column name (e.g. "Korean") over plain locale code (e.g. "ko")
                            val existing = byLocale[locale]
                            if (existing == null || existing == locale) {
                                byLocale[locale] = col   // descriptive name wins
                            }
                        } else if (col !in unresolved) {
                            unresolved += col
                        }
                    }
            }
        }

        languageRow.removeAll(); languageBoxes.clear()
        localeToColumnName.clear()
        val saved = persistence.checkedLanguages.map { it.lowercase() }

        // languageBoxes key = locale code (ensures uniqueness)
        // localeToColumnName: locale → CSV column name (needed for loadCsv/loadArrayCsv to find right column)
        byLocale.forEach { (locale, displayKey) ->
            // displayKey is the CSV column name (e.g. "Korean") when from standard CSV,
            // or the locale code itself (e.g. "ko") when from a mapped CSV.
            localeToColumnName[locale] = displayKey

            val label = if (displayKey == locale) locale
                        else "${displayKey.replaceFirstChar { it.uppercase() }} ($locale)"
            languageBoxes[locale] = JBCheckBox(label).apply {
                isSelected = saved.isEmpty() || locale in saved || displayKey.lowercase() in saved
                font = font.deriveFont(13f)
            }
            languageRow.add(languageBoxes[locale])
        }

        // Show unresolved hint only if there are many (not inline inputs)
        if (unresolved.isNotEmpty() && byLocale.isEmpty()) {
            languageRow.add(JBLabel("  ${unresolved.size} unrecognized columns — click ✏ to map").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = font.deriveFont(Font.ITALIC, 11f)
            })
        } else if (unresolved.isNotEmpty() && unresolved.size <= 3) {
            languageRow.add(JBLabel("  |  Unknown:").apply {
                foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(Font.ITALIC, 11f)
            })
            unresolved.forEach { col ->
                val field = javax.swing.JTextField(6).apply { font = font.deriveFont(12f) }
                val confirm: () -> Unit = confirm@{
                    val code = field.text.trim().lowercase()
                    if (code.isEmpty()) return@confirm
                    persistence.customLocaleMap = persistence.customLocaleMap.toMutableMap().also { it[col.lowercase()] = code }
                    refreshLanguages()
                }
                field.addActionListener { confirm() }
                field.addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent) = confirm()
                })
                languageRow.add(JBLabel("\"$col\" →").apply {
                    foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(Font.ITALIC, 11f)
                })
                languageRow.add(field)
            }
        }

        languageRow.revalidate(); languageRow.repaint()
    }

    /**
     * Resolves a CSV column header to an Android locale code using 3 strategies:
     * 1. Direct match in LANGUAGE_LOCALE_MAP (English names, ISO codes, aliases, native names)
     * 2. Java Locale display language matching (handles any language Java knows about)
     * Returns null if unresolved — caller should check customLocaleMap or show fallback UI.
     */
    private fun resolveLocale(header: String): String? {
        val h = header.trim().lowercase()
        // Strategy 1+2: check expanded map
        LANGUAGE_LOCALE_MAP[h]?.let { return it }
        // Strategy 3: Java Locale display name matching
        for (locale in java.util.Locale.getAvailableLocales()) {
            if (locale.language.isEmpty()) continue
            val enName = locale.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase()
            val native = locale.displayLanguage.lowercase()
            if (enName == h || native == h) return locale.language
        }
        return null
    }

    private fun scanXmlFiles() {
        val customValues = persistence.valuesDir.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
        val valuesDir = (customValues ?: projectDir.resolve("app/src/main/res/values")).toFile()
        if (!valuesDir.exists()) return
        val saved = persistence.checkedXmlFiles
        xmlRow.removeAll(); xmlBoxes.clear()
        valuesDir.listFiles { f -> f.extension == "xml" }?.sortedBy { it.name }?.forEach { file ->
            if (file.name in XML_NON_TRANSLATABLE_NAMES) return@forEach
            if (!file.readText().contains("<string")) return@forEach
            xmlBoxes[file.name] = JBCheckBox(file.name).apply {
                isSelected = saved.isEmpty() || file.name in saved
                font = font.deriveFont(13f)
            }
            xmlRow.add(xmlBoxes[file.name])
        }
        xmlRow.revalidate(); xmlRow.repaint()
    }

    // Data class for ignored (hidden) assets
    private fun scanAssets() {
        val customAssets = persistence.assetsDir.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
        val assetsDir = (customAssets ?: projectDir.resolve("app/src/main/assets")).toFile()
        if (!assetsDir.exists()) return
        val savedAssets = persistence.checkedAssets
        val savedFields = persistence.assetFields
        val localizer   = JsonLocalizer(TranslationDb())

        // Clear everything
        assetPanel.removeAll()
        assetRows.clear()
        ignoredList.clear()

        assetsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val baseFile = dir.listFiles { f ->
                f.extension == "json" &&
                !f.nameWithoutExtension.contains(Regex("_[a-z]{2}(-r[A-Z]{2})?$"))
            }?.firstOrNull() ?: return@forEach

            val stub    = AssetConfig(dir.name, dir.toPath(), baseFile.name, DEFAULT_TRANSLATE_FIELDS)
            val dataKey = localizer.detectDataKey(stub)
            val withKey = stub.copy(dataKey = dataKey)

            val reason = localizer.ignoreReason(withKey)
            // If user explicitly added this from ignored group before, show in main list
            if (reason != null && dir.name !in savedAssets) {
                ignoredList += IgnoredAsset(dir.name, withKey, reason)
                return@forEach
            }
            addMainRow(dir.name, withKey, localizer, savedAssets, savedFields)
        }

        // Add the ignored section panel ONCE at the bottom
        assetPanel.add(ignoredSectionPanel)
        rebuildIgnoredSection(localizer, savedFields)

        assetPanel.revalidate(); assetPanel.repaint()
    }

    /**
     * Add one row to the main list (above the ignored section).
     * The row is inserted BEFORE ignoredSectionPanel.
     */
    private fun addMainRow(
        name: String, withKey: AssetConfig,
        localizer: JsonLocalizer,
        savedAssets: List<String>, savedFields: Map<String, List<String>>
    ) {
        val auto          = localizer.detectTranslateFields(withKey)
        val allJsonFields = localizer.detectAllStringFields(withKey)
        val saved         = savedFields[name]?.filter { it in allJsonFields }
        val initial       = (saved ?: auto).toMutableList()

        val row = AssetRow(
            JBCheckBox(name).apply {
                isSelected = savedAssets.isEmpty() || name in savedAssets
                font = font.deriveFont(13f)
            },
            initial, withKey.copy(translateFields = initial)
        )
        assetRows[name] = row

        val summaryLabel = JBLabel(fieldSummary(initial)).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(11f)
        }
        val gearBtn = JButton("⚙").apply {
            toolTipText = "Configure fields to translate"
            isBorderPainted = false; isContentAreaFilled = false
            font = font.deriveFont(14f)
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 24)
            isVisible = row.assetBox.isSelected
            addActionListener {
                // languageBoxes key is now the locale code directly
                val locales = languageBoxes.filter { it.value.isSelected }.keys.toList()
                val dialog = AssetConfigDialog(project, withKey, row.selectedFields, locales)
                if (dialog.showAndGet()) {
                    row.selectedFields = dialog.selectedFields().toMutableList()
                    row.config = withKey.copy(translateFields = row.selectedFields)
                    summaryLabel.text = fieldSummary(row.selectedFields)
                }
            }
        }
        row.assetBox.addItemListener { gearBtn.isVisible = row.assetBox.isSelected }

        val rowPanel = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            border = JBUI.Borders.empty(2, 0)
            add(row.assetBox, BorderLayout.WEST)
            add(summaryLabel, BorderLayout.CENTER)
            add(gearBtn,      BorderLayout.EAST)
        }

        // Insert BEFORE ignoredSectionPanel (last component) if it's already added
        val idx = assetPanel.componentCount
        val lastIdx = if (idx > 0 && assetPanel.getComponent(idx - 1) === ignoredSectionPanel) idx - 1 else idx
        assetPanel.add(rowPanel, lastIdx)
    }

    /**
     * Fully rebuild ignoredSectionPanel from scratch.
     * No nested sub-panels — flat structure, no Swing tracking issues.
     */
    private fun rebuildIgnoredSection(
        localizer: JsonLocalizer,
        savedFields: Map<String, List<String>>
    ) {
        ignoredSectionPanel.removeAll()

        if (ignoredList.isEmpty()) {
            ignoredSectionPanel.isVisible = false
            ignoredSectionPanel.revalidate()
            ignoredSectionPanel.repaint()
            return
        }

        ignoredSectionPanel.isVisible = true

        // ── Header ─────────────────────────────────────────────────────────────
        val arrow     = if (ignoredExpanded) "▼" else "▶"
        val headerLbl = JLabel("$arrow  Ignored  (${ignoredList.size})").apply {
            font       = font.deriveFont(Font.ITALIC, 11f)
            foreground = UIUtil.getContextHelpForeground()
            cursor     = Cursor(Cursor.HAND_CURSOR)
        }
        headerLbl.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                ignoredExpanded = !ignoredExpanded
                rebuildIgnoredSection(localizer, savedFields)
                ignoredSectionPanel.revalidate()
                ignoredSectionPanel.repaint()
            }
        })
        ignoredSectionPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 26)
            border = JBUI.Borders.empty(4, 0, 2, 0)
            add(headerLbl)
        })

        if (!ignoredExpanded) {
            ignoredSectionPanel.revalidate()
            ignoredSectionPanel.repaint()
            return
        }

        // ── Rows (only when expanded) ──────────────────────────────────────────
        ignoredList.toList().forEach { ignored ->
            val addBtn = JButton("+ Add").apply {
                font = font.deriveFont(11f)
                isBorderPainted = true; isContentAreaFilled = false
                cursor = Cursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(60, 22)
                addActionListener {
                    ignoredList.remove(ignored)
                    addMainRow(ignored.name, ignored.config, localizer, emptyList(), savedFields)
                    assetRows[ignored.name]?.assetBox?.isSelected = true
                    rebuildIgnoredSection(localizer, savedFields)
                    assetPanel.revalidate()
                    assetPanel.repaint()
                }
            }
            ignoredSectionPanel.add(JPanel(BorderLayout(6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 26)
                border = JBUI.Borders.empty(1, 8)
                add(JBLabel("  ${ignored.name}").apply {
                    font = font.deriveFont(12f); preferredSize = Dimension(160, 24)
                }, BorderLayout.WEST)
                add(JBLabel("⚠ ${ignored.reason}").apply {
                    font = font.deriveFont(Font.ITALIC, 11f)
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.CENTER)
                add(addBtn, BorderLayout.EAST)
            })
        }

        ignoredSectionPanel.revalidate()
        ignoredSectionPanel.repaint()
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private fun onGenerate() {
        // Build langMap using ORIGINAL CSV column names (not locale codes).
        // e.g. {"Korean" → "ko", "Thai" → "th"} so loadCsv/loadArrayCsv can find the right columns.
        // For mapped CSVs the column name equals the locale code, which is fine since those
        // CSVs go through loadCsvFromNormalized (which ignores langMap anyway).
        val selectedLangs = languageBoxes
            .filter { it.value.isSelected }
            .mapNotNull { (locale, _) ->
                val colName = localeToColumnName[locale] ?: locale
                colName to locale   // colName → locale (e.g. "Korean" → "ko")
            }
            .toMap()

        if (selectedLangs.isEmpty()) {
            outputPanel.append("⚠ No languages selected.", OutputLevel.WARN)
            return
        }

        val config = LocalizeConfig(
            csvAndroidOnly    = csvPath(csvAndroidField),
            csvOverlap        = csvPath(csvOverlapField),
            csvArrays         = csvPath(csvArraysField),
            projectDir        = projectDir,
            selectedLanguages = selectedLangs,
            selectedXmlFiles  = xmlBoxes.filter { it.value.isSelected }.keys.toList(),
            selectedAssets    = assetRows
                .filter { it.value.assetBox.isSelected }
                .map { (_, r) -> r.config.copy(translateFields = r.selectedFields) },
            generateMode     = persistence.generateMode,
            preserveKeyOrder = persistence.preserveKeyOrder,
            overrideNonTranslatable = persistence.overrideNonTranslatable,
            keepExistingJsonFields  = persistence.keepExistingJsonFields,
            csvMappings  = buildMap {
                listOf(csvAndroidField, csvOverlapField, csvArraysField).forEach { f ->
                    val path = f.text.trim().takeIf { it.isNotEmpty() } ?: return@forEach
                    persistence.getCsvMapping(path)?.let { put(path, it) }
                }
            },
            valuesDir    = persistence.valuesDir.takeIf { it.isNotEmpty() }?.let { Paths.get(it) },
            assetsDir    = persistence.assetsDir.takeIf { it.isNotEmpty() }?.let { Paths.get(it) },
            reportDir    = persistence.reportDir.takeIf { it.isNotEmpty() }?.let { Paths.get(it) },
        )

        persistence.saveAll(
            androidOnly = csvAndroidField.text,
            overlap     = csvOverlapField.text,
            arrays      = csvArraysField.text,
            languages   = languageBoxes.filter { it.value.isSelected }.keys.toList(),
            xmlFiles    = xmlBoxes.filter { it.value.isSelected }.keys.toList(),
            assets      = assetRows.filter { it.value.assetBox.isSelected }.keys.toList(),
            fields      = assetRows.mapValues { it.value.selectedFields }
        )

        generateBtn.isEnabled = false
        outputPanel.clear()

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Generating localizations…", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    try {
                        LocalizeRunner().run(config) { message, level ->
                            indicator.text = message.trimStart()
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                outputPanel.append(message, level)
                            }
                        }
                    } catch (ex: Exception) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            outputPanel.append("❌ ERROR: ${ex.message}", OutputLevel.ERROR)
                        }
                    } finally {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            generateBtn.isEnabled = true
                        }
                    }
                }
            }
        )
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun section(text: String) = TitledSeparator(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun csvRow(
        label: String,
        picker: com.intellij.openapi.ui.TextFieldWithBrowseButton,
        showMapBtn: Boolean = false
    ): JPanel {
        fun hasFile() = picker.text.trim().let { Paths.get(it).toFile().isFile }

        val mapBtn = if (showMapBtn) JButton(com.intellij.icons.AllIcons.Actions.Edit).apply {
            isBorderPainted = false; isContentAreaFilled = false
            preferredSize = Dimension(26, 26)
            // Always visible; enabled/disabled based on whether a file is selected
            isEnabled = hasFile()
            cursor = Cursor(if (hasFile()) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            toolTipText = if (hasFile()) "Configure column mapping" else "Select a CSV file first"
            addActionListener {
                val path = Paths.get(picker.text.trim()).takeIf { it.toFile().isFile }
                    ?: return@addActionListener
                val existing = persistence.getCsvMapping(path.toString())
                val dialog = CsvMappingDialog(project, path, existing)
                if (dialog.showAndGet()) {
                    val mapping = dialog.buildMapping()
                    if (dialog.shouldRemember()) persistence.saveCsvMapping(path.toString(), mapping)
                    refreshLanguages()  // merge all 3 CSV fields
                }
            }
        } else null

        // Sync enabled state when text field changes
        if (mapBtn != null) {
            picker.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent)  = sync()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent)  = sync()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
                private fun sync() {
                    val has = hasFile()
                    mapBtn.isEnabled  = has
                    mapBtn.cursor     = Cursor(if (has) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
                    mapBtn.toolTipText = if (has) "Configure column mapping" else "Select a CSV file first"
                }
            })
        }

        return JPanel(BorderLayout(4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            add(JBLabel(label).apply { preferredSize = Dimension(200, 26) }, BorderLayout.WEST)
            add(picker, BorderLayout.CENTER)
            if (mapBtn != null) add(mapBtn, BorderLayout.EAST)
        }
    }

    private fun vgap(h: Int): Component = Box.createRigidArea(Dimension(0, h))

    private fun fieldSummary(fields: List<String>) =
        if (fields.isEmpty()) "  no fields" else "  ${fields.joinToString(", ")}"

    private fun createFilePicker() = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener("Select CSV File", null, project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("csv"))
    }

    private fun csvPath(f: TextFieldWithBrowseButton): Path? {
        val t = f.text.trim()
        return if (t.isNotEmpty()) Paths.get(t).takeIf { it.toFile().isFile } else null
    }
}

// FlowLayout that wraps to next line when container width is exceeded.
class WrapLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container) = layoutSize(target, true)
    override fun minimumLayoutSize(target: Container)   = layoutSize(target, false)
    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val maxW = (target.size.width.takeIf { it > 0 } ?: Int.MAX_VALUE) -
                       target.insets.left - target.insets.right
            var w = 0; var h = 0; var rowW = 0; var rowH = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i).takeIf { it.isVisible } ?: continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowW + d.width > maxW && rowW > 0) {
                    w = maxOf(w, rowW); h += rowH + vgap; rowW = 0; rowH = 0
                }
                rowW += d.width + hgap; rowH = maxOf(rowH, d.height)
            }
            w = maxOf(w, rowW); h += rowH
            return Dimension(w + target.insets.left + target.insets.right,
                             h + target.insets.top  + target.insets.bottom + vgap * 2)
        }
    }
}
