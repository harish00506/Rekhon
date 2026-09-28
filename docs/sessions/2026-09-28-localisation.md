<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.8 — Hindi, Kannada and Tamil, and the harness that keeps them honest.
  Result: a reader can see what was translated, what was deliberately not, what the build now
          refuses, and the two defects the translations exposed in code that was already shipped.
  Changelog: 2026-09-28 — Created.
-->

# 2026-09-28 — Three more languages (issue 10.8, ADR-0056)

**Branch:** `feature/10-8-localisation-hindi-2` off `dev` (`50bc0ff`)
**Versions:**
- **VERSION** 0.10.6 → **0.10.7**
- **versionCode** 50 → 51
- **Schema** 29 → **29 (unchanged — the language is a settings field, not a table)**
- **`cfo_settings.proto`** field 18, `language_tag`

---

## 1 · Decisions this session

The full argument for each is in [ADR-0056](../adr/0056-the-app-speaks-three-more-languages-and-proves-it.md).

- **The three languages are §3.5's own: Hindi, Kannada and Tamil.** Not a guess — the SRS names
  them. `LanguageSetting`, `res/xml/locales_config.xml` and the `values-*` folders are held to each
  other by a test, so a language cannot be offered without strings behind it, and translations
  cannot ship that nobody can select.
- **The translations are machine-authored and unreviewed, and the ADR says so.** They were written
  here, from the English. Nobody who reads Kannada or Tamil first has read them. Everything
  mechanical is checked by the build; register and idiom are not. A native review before a public
  release is the outstanding work, and the harness will hold any correction in place.
- **The language belongs to the app, not only to the phone.** A phone in English owned by someone
  who reads Hindi is the ordinary case here. The choice is stored and applied by re-providing the
  composition's context — **not** by overriding the Activity's base context, which would mean
  reading DataStore synchronously before `onCreate` returns, and `runBlocking` is banned outside
  tests. It wraps the **lock screen** too, and has its own state holder so that nothing which opens
  the encrypted database is composed before the PIN.
- **From Android 13 the choice is mirrored into the platform's per-app locale**, so system settings
  agree and the widget and notifications follow. Below 13 they follow the phone — a real limit of
  the platform, written down rather than hidden.
- **Language splits are off in the bundle.** With them on, Play installs only the device's language
  and a user choosing Kannada on an English phone would get English with no way to tell why.
- **Money does not follow the locale; dates do.** `MoneyFormatter` writes 2,2,3 grouping and ASCII
  digits itself — a locale-aware formatter can return Devanagari digits, which **this app's own
  parser rejects**, so it would write amounts the app could not read back. `DateFormatter` asks the
  platform, which is the half of §3.5 that is meant to change. Both halves are now pinned, because
  they are easy to get backwards.
- **"Nothing to translate" is a rule, not a list of keys.** Strip the format specifiers and the
  version markers; if no lowercase letter is left it is a format string, an acronym or a rule id.
  Three keys are exempt by name: the app's name, the language endonyms, and the ISO date mask.
- **Deferred** (ADR-0056): the native review; §3.5's international number-format toggle; RTL seen in
  practice; a rulebook in three languages; per-locale screenshots beyond the dashboard.

**What the translations found in code that had already shipped.**

1. **A third "a number in a sentence is a claim."** Lint flagged that a translated sentence took
   fewer format arguments than its original — and the reason was that the English
   `budgets_band_warn_description` contained an accidental `%o`, inside *"80% of its budget"*. The
   80 was `RULE-BUD-ALERT.warn_pct`: the rulebook's threshold written into a string, shown for every
   warned category whatever it had spent, and it would have gone on saying 80 after someone moved
   the band. The chip states the category's own measured percentage now. The health score's
   `/ 1000` (10.5) and the forecast's hardcoded "90 days" (10.6) were the first two.
2. **The device run found a crash no unit test could.** Choosing a language killed the app:
   `createConfigurationContext(...)` handed straight to `LocalContext` returns a context whose base
   is the *application*, so the chain back to the Activity was cut and the first `hiltViewModel()`
   below the provider died — *"Expected an activity context for creating a HiltViewModelFactory"*.
   The Robolectric test had passed because it rendered a `Text` rather than a screen. A
   `ContextWrapper` **around the Activity**, answering `getResources()` from the configured context,
   fixes it; the regression test walks `LocalContext` back to the Activity and goes red against the
   implementation that crashed.
3. **Two lint tests had been silently red.** Gradle held `:lint:test` up to date, so they only went
   red when this issue finally changed that module. They were failing *inside* the harness —
   `android.util.Log` does not resolve without a stub — rather than on their assertions. Third
   vacuous gate this project has found, after the drift test that never ran and the unreachable
   horizon filter.

## 2 · Flow changed this session

One new path, above everything else in the app — `FLOW.md` §2.19:

```
MainActivity.setContent
└─ AppLanguageViewModel.language     (SettingsStore, proto field 18 — settings only, never the DB)
   └─ LocalisedContent(tag)
      ├─ API 33+ → LocaleManager.applicationLocales = tag
      └─ CompositionLocalProvider(LocalContext, LocalConfiguration, LocalLayoutDirection)
         └─ CfoTheme → AppLockGate → CfoNavHost

SettingsScreen → "Language" → LanguageChosen → SettingsStore.setLanguage → read back from disk
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `*/src/main/res/values-{hi,kn,ta}/strings.xml` (51 new files) | 1,159 resources in three languages, across every module that owns user-visible text |
| `app/src/test/**/i18n/{StringCatalogue,TranslationCoverageTest}.kt` (new) | the harness: discovers every `strings.xml` and checks coverage both ways, format-argument parity, plural categories, shape, and that nothing is still English |
| `app/build.gradle.kts` | the string resources as declared test inputs; language splits off |
| `app/src/main/res/xml/locales_config.xml` (new) + manifest | the languages Android 13's picker offers |
| `app/src/main/kotlin/com/aicfo/app/{AppLanguage,AppLanguageViewModel}.kt` (new) | the chosen language, applied above the lock screen |
| `core/datastore/**` | `language_tag` (proto 18), `LanguageSetting`, `setLanguage`, and an unknown tag reading back as "follow the phone" |
| `feature/settings/**` | the picker: every language in its own script, read back from disk |
| `core/model/**/LocaleFormattingTest.kt` (new) | Indian grouping and ASCII digits in every locale; dates that do follow it |
| `feature/dashboard/**/DashboardScreenshotTest.kt` + 3 baselines | the dashboard rendered in Hindi, Kannada and Tamil |
| `feature/budgets/**` | the band chip states the measured percentage, not the rulebook's threshold |
| `lint/**` | `:app` joined `CfoHardcodedUiString`; the `android.util.Log` stub that unblocked two silently-failing tests |
| `core/datastore/SettingsStore.kt`, `feature/settings/LanguageSection.kt` | `update` lifted to a `DataStore` extension and the language section split into its own file — both were one member over detekt's limit once the language joined them |
| `build-logic/**/KotlinConfig.kt` | `MissingTranslation` and `ExtraTranslation` are errors |
| `docs/adr/0056-…`, `DECISIONS.md`, `FLOW.md` §2.19, `CHANGELOG.md`, `docs/memory.md` | the records |
