# Chatsmith Localize Plugin

An Android Studio plugin that generates localized `strings.xml` and JSON asset files from CSV translation spreadsheets. Built for the **Chatsmith** Android project.

---

## Features

- **Import CSV translations** — supports 3 CSV input files with smart merging
- **Language selection** — auto-detected from CSV column headers; select which locales to generate
- **XML generation** — generates `values-{locale}/strings.xml` for each language, with full support for `<string-array>`, `<plurals>`, CDATA, HTML tags, and format strings (`%s`, `%d`, `%1$s`)
- **JSON asset generation** — generates localized JSON files for all asset directories (whats_new, highlight_features, task, topic, etc.), including deeply **nested JSON structures**
- **Smart asset detection** — automatically filters out Lottie animation files and non-translatable config files; keeps them accessible in a collapsible "Ignored" group
- **Field configuration** — per-asset field picker with a side-by-side JSON preview showing original vs translated content
- **Exception report** — generates `localize_report_{locale}.md` listing skipped strings, unmatched fields, and CSV conflicts

---

## Installation

### Build from source

```bash
cd chatsmith-localize-plugin
./gradlew buildPlugin
# Output: build/distributions/chatsmith-localize-plugin-1.0.0.zip
```

### Install in Android Studio

1. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the `.zip` file
3. Restart Android Studio

The **Localize** panel appears in the right sidebar.

---

## Usage

### 1. Prepare CSV files

Three CSV files are supported. Place them in the project root or browse to them manually.

| File | Key column | Purpose |
|------|-----------|---------|
| `ChatSmith - … - android_only_strings.csv` | `android_key` | Main Android string resources |
| `ChatSmith - … - overlap.csv` | `unified_id` | Cross-platform shared strings |
| `ChatSmith - … - android_array_strings.csv` | `android_key` (sparse) | String-array items |

**CSV format** — all files share the same column structure:

```
android_key,english,korean,arabic,thai
edit_message_hint,Talk with Chat Smith,Chat Smith와 대화하기,...
```

The `android_array_strings.csv` uses a **sparse** format — the array name appears only on the first row of each group:

```
android_key,english,korean
label_length_array,Short,짧게
,Medium,보통
,Long,길게
```

### 2. Open the Localize panel

Click the **Localize** icon in the right sidebar, or go to **Tools → Localize...**.

### 3. Configure

```
┌─ Generate ──────────────────────────────────────────────────┐
│                                                  [Generate] │
├─ CSV Files ─────────────────────────────────────────────────┤
│  android_only_strings.csv  [path]  [Browse]                 │
│  overlap.csv (optional)    [path]  [Browse]                 │
│  array_strings.csv (opt.)  [path]  [Browse]                 │
├─ Languages  (detected from CSV) ────────────────────────────┤
│  ☑ Korean (ko)   ☑ Thai (th)   ☐ Arabic (ar)               │
├─ XML Files  (detected from values/) ────────────────────────┤
│  ☑ strings.xml   ☑ ds_ob_text.xml                          │
├─ JSON Assets ───────────────────────────────────────────────┤
│  ☑ whats_new        title, description    [⚙]              │
│  ☑ task             category, title       [⚙]              │
│  ▶ Ignored (3)                                              │
│      benefit_popup  ⚠ animation (Lottie)  [+ Add]          │
└─────────────────────────────────────────────────────────────┘
```

- **Languages** are auto-detected from the CSV column headers
- **XML Files** are scanned from `app/src/main/res/values/`
- **JSON Assets** are scanned from `app/src/main/assets/`, with animation/config files hidden in the **Ignored** group

### 4. Configure JSON asset fields

Click **⚙** next to any asset to open the field configuration dialog:

- **Left panel** — original English JSON
- **Right panel** — translated reference (from existing `_ko.json`)
- **Checkboxes** — tick which fields to translate; the right panel updates live to show the merged result
- Works with **nested JSON** at any depth

### 5. Generate

Click **Generate**. The log panel streams progress:

```
Phase 1: Loading CSVs...
  ✓ android_only: loaded
  ✓ overlap: loaded
  ✓ arrays: 22 arrays, 142 items
  DB: 2111 unique keys, 1773 English phrases

─── Locale: th ───────────────────────────────────
Phase 2: Generating XML for th...
  [th] strings.xml → 1016 written, 0 skipped
Phase 3: Generating JSON for th...
  [th] whats_new_th.json
  ...
Phase 4: Writing reports...
  [th] Report → localize_report_th.md

✅ Done.
```

### 6. Review the exception report

`localize_report_{locale}.md` is generated in the project root. It contains:

| Section | Description |
|---------|-------------|
| Summary | Counts of written/skipped strings and JSON fields |
| CSV Conflicts | Keys present in multiple CSVs with different values |
| XML Keys Not Found | Keys in template that had no CSV translation |
| JSON Fields Not Matched | JSON field values not found in translation DB |
| Skipped String-Arrays | Arrays where some items were missing |

---

## Output files

| Template | Generated (example for `th`) |
|----------|------------------------------|
| `app/src/main/res/values/strings.xml` | `values-th/strings.xml` |
| `app/src/main/res/values/ds_ob_text.xml` | `values-th/ds_ob_text.xml` |
| `app/src/main/assets/whats_new/whats_new.json` | `whats_new/whats_new_th.json` |
| `app/src/main/assets/task/tasks.json` | `task/tasks_th.json` |
| *(any selected asset)* | `{dir}/{base}_{locale}.json` |

---

## Configuration persistence

Settings are saved to `.idea/localize-plugin.json` in the project root. This file can be committed to share settings with the team, or added to `.gitignore` to keep it local.

---

## Project structure

```
src/main/kotlin/com/vulcanlabs/localize/
├── action/
│   └── LocalizeAction.kt          # Tools menu entry — opens the panel
├── config/
│   ├── LocalizeConfig.kt          # Data models: LocalizeConfig, AssetConfig
│   └── ConfigPersistence.kt       # File-based settings (.idea/localize-plugin.json)
├── core/
│   ├── TranslationDb.kt           # CSV loading → byKey / byEn / byArray lookup maps
│   ├── XmlGenerator.kt            # strings.xml generation with dynamic path detection
│   └── JsonLocalizer.kt           # Recursive JSON translation + asset localizability detection
├── ui/
│   ├── LocalizePanel.kt           # Main embedded panel (config + generate + log)
│   ├── AssetConfigDialog.kt       # Per-asset field picker with live JSON preview
│   └── LocalizeToolWindowFactory.kt  # Registers the right-panel tool window
└── LocalizeRunner.kt              # Orchestrates the full pipeline and writes reports
```

---

## Adding a new language

1. Add a new column to the CSV files (e.g., `vietnamese`)
2. The column will auto-appear as a checkbox in the Languages section
3. Add the locale mapping in `LocalizeConfig.kt`:
   ```kotlin
   val LANGUAGE_LOCALE_MAP = mapOf(
       ...
       "vietnamese" to "vi",
   )
   ```
4. The plugin generates `values-vi/strings.xml` and `*_vi.json` files automatically

---

## Requirements

- Android Studio 2024.1+ (IntelliJ Platform 241+)
- Python not required — all logic is implemented in Kotlin
- No external dependencies beyond the IntelliJ Platform SDK
