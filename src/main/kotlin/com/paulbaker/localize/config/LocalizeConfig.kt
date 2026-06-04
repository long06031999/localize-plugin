package com.paulbaker.localize.config

import java.nio.file.Path

enum class GenerateMode {
    /** Keep existing translations for keys not in CSV. Safest for incremental updates. */
    MERGE,
    /** Overwrite entirely from CSV — existing translations not in CSV are lost. */
    FULL_REPLACE
}

data class LocalizeConfig(
    val csvAndroidOnly: Path?,
    val csvOverlap: Path?,
    val csvArrays: Path?,
    val projectDir: Path,
    val selectedLanguages: Map<String, String>,  // csv_column_name → android_locale
    val selectedXmlFiles: List<String>,
    val selectedAssets: List<AssetConfig>,
    val generateMode: GenerateMode = GenerateMode.MERGE,
    // null = use defaults relative to projectDir
    val valuesDir: Path? = null,
    val assetsDir: Path? = null,
    val reportDir: Path? = null,
) {
    fun resolvedValuesDir() = valuesDir ?: projectDir.resolve("app/src/main/res/values")
    fun resolvedAssetsDir() = assetsDir ?: projectDir.resolve("app/src/main/assets")
    fun resolvedReportDir() = reportDir ?: projectDir
}

data class AssetConfig(
    val name: String,
    val dir: Path,
    val baseFile: String,
    val translateFields: List<String>,
    val dataKey: String? = null
)

/**
 * Strategy 1 + 2: CSV column name → Android locale code.
 * Covers English language names, ISO 639-1 codes, common aliases, and native names.
 * Strategy 3 (Java Locale auto-match) handles anything not listed here.
 */
val LANGUAGE_LOCALE_MAP = mapOf(
    // ── English names (Strategy 1) ─────────────────────────────────────────
    "korean"     to "ko", "arabic"     to "ar", "thai"       to "th",
    "japanese"   to "ja", "french"     to "fr", "german"     to "de",
    "spanish"    to "es", "portuguese" to "pt", "chinese"    to "zh",
    "vietnamese" to "vi", "italian"    to "it", "russian"    to "ru",
    "turkish"    to "tr", "dutch"      to "nl", "polish"     to "pl",
    "indonesian" to "in", "hindi"      to "hi", "malay"      to "ms",
    "swedish"    to "sv", "norwegian"  to "no", "danish"     to "da",
    "finnish"    to "fi", "greek"      to "el", "hebrew"     to "he",
    "czech"      to "cs", "hungarian"  to "hu", "romanian"   to "ro",
    "ukrainian"  to "uk",

    // ── ISO 639-1 codes used directly (Strategy 2) ────────────────────────
    "ko" to "ko", "ar" to "ar", "th" to "th", "ja" to "ja",
    "fr" to "fr", "de" to "de", "es" to "es", "pt" to "pt",
    "zh" to "zh", "vi" to "vi", "it" to "it", "ru" to "ru",
    "tr" to "tr", "nl" to "nl", "pl" to "pl", "in" to "in",
    "hi" to "hi", "ms" to "ms", "sv" to "sv", "no" to "no",
    "da" to "da", "fi" to "fi", "el" to "el", "he" to "he",

    // ── Common abbreviations / aliases (Strategy 2) ────────────────────────
    "kr"  to "ko", "jp"  to "ja", "cn"  to "zh", "tw"  to "zh",
    "kor" to "ko", "jpn" to "ja", "ara" to "ar", "tha" to "th",
    "chn" to "zh", "viet" to "vi", "deu" to "de", "fra" to "fr",

    // ── Native language names (Strategy 2) ────────────────────────────────
    "한국어"        to "ko",
    "日本語"        to "ja",
    "ภาษาไทย"      to "th",
    "العربية"      to "ar",
    "中文"          to "zh",
    "繁體中文"       to "zh",
    "简体中文"       to "zh",
    "Tiếng Việt"   to "vi",
    "tiếng việt"   to "vi",
    "Deutsch"      to "de",
    "Français"     to "fr",
    "Español"      to "es",
    "Português"    to "pt",
    "Italiano"     to "it",
    "Русский"      to "ru",
    "Türkçe"       to "tr",
    "Bahasa"       to "in",
    "हिन्दी"       to "hi",
)

// Columns that are NOT language columns
val NON_LANGUAGE_COLS = setOf("android_key", "unified_id", "english", "key", "id")

// Default fields to translate in JSON assets
val DEFAULT_TRANSLATE_FIELDS = listOf("title", "description", "name", "boldText")

// XML files that should NOT be localized (no string elements)
val XML_NON_TRANSLATABLE_NAMES = setOf(
    "colors.xml", "dimens.xml", "attrs.xml", "styles.xml", "themes.xml",
    "ic_launcher_background.xml", "app_configs.xml"
)
