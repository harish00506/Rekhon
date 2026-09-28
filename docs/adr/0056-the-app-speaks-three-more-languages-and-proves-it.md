# ADR-0056 — The app speaks Hindi, Kannada and Tamil, and the build proves it

- **Status:** accepted
- **Date:** 2026-09-28
- **Deciders:** Harish G (solo)
- **SRS refs:** §3.5 (accessibility & inclusivity — *"English at launch; all strings externalised and
  ICU-plural-ready for Hindi/Kannada/Tamil in Phase 4"*, and *"number formatting follows the Indian
  system"*), NFR-011 (no hardcoded strings; ICU plurals; RTL-safe layouts), §21.6, ACC-*; issue 10.8.

## Context

The app has externalised its strings from the first screen, so this issue is not *"make the app
translatable"* — it is **"translate it, and make the next screen unable to ship untranslated"**.
1,159 resources across 17 modules, into three languages the SRS names.

Three questions had to be decided that the SRS does not: **who the translations are and how honest
the app is about that**, **how a user picks a language when their phone is in another one**, and
**what number formatting must survive the change**.

## Decision

**1. Hindi, Kannada and Tamil — the three §3.5 names, and no more.**
Not a guess: `hi`, `kn`, `ta`. `LanguageSetting`, `res/xml/locales_config.xml` and the `values-*`
folders are held to each other by a test, so a language cannot appear in the picker without strings
behind it, and translations cannot ship that nobody can select.

**2. The translations are machine-authored and unreviewed, and this file is where that is written
down.** They were written in this repository by the assistant working the issue, from the English
originals. Nobody who reads Kannada or Tamil as a first language has reviewed them. That is a real
limitation and the honest thing to do with it is to state it rather than to imply a review that did
not happen — the same standard the app applies to its own figures (P-02). What is *machine-checked*
is everything mechanical: coverage, format arguments, plural categories, and that no sentence was
left in English. What is not checked is register, idiom and whether a financial term is the one a
speaker would use. A native review before a public release is the outstanding work, and it is
cheaper now than it was: the files exist and the harness will hold any correction in place.

**3. The language is the app's choice, not only the device's.** A phone in English owned by someone
who reads Hindi is the ordinary case here. The choice is stored (proto field 18, a BCP-47 tag) and
applied by re-providing the composition's `Context`, `Configuration` and layout direction —
**not** by overriding the Activity's base context, which would mean reading DataStore
*synchronously* before `onCreate` returns, and `runBlocking` is banned outside tests (§21.6). From
Android 13 the choice is mirrored into the platform's own per-app language, so the system settings
entry agrees and the parts of the app that are not a composition speak it too.

- **Below Android 13, the widget and notifications follow the phone**, not the in-app choice: there
  is no platform store to write to, and the alternative — a second locale source of truth consulted
  by every `Context` the app builds — is a bug factory. Stated here rather than hidden.
- **`android:localeConfig` is API 33+**, so lint's `UnusedAttribute` is suppressed on the
  application tag with a comment, rather than the attribute being dropped.
- **Language splits are disabled in the bundle** (`bundle { language { enableSplit = false } }`).
  With them on, Play installs only the device's language and a user choosing Kannada on an English
  phone would get English with no way to tell why. Lint's `AppBundleLocaleChanges` asked for this,
  and it was asking for the right thing.

**4. The percentage in an amount is not the currency's.** `MoneyFormatter` already refused the
platform's number formatting and writes the 2,2,3 grouping itself; issue 10.8 adds the test that
says why it must keep doing so. A locale can carry a numbering system, and a Hindi number can come
back in Devanagari digits — `१,२३,४५६` is not wrong to read, but **this app's own parser rejects
it** (`MoneyFormatter.parse` accepts ASCII digits only, and its doc comment says why). A formatter
that followed the locale would write amounts the app could not read back. Dates, by contrast, *do*
follow the locale — that is the half of §3.5 that should change, and the two halves are easy to get
backwards, so both are pinned by tests.

**5. The build refuses an untranslated screen.** `TranslationCoverageTest` reads every
`strings.xml` in the repository — discovered, not listed, so a module added next year is covered
without anyone remembering — and checks: every module has a file per locale; every key is
translated; no locale carries a key the English file does not; **every translation takes exactly
the format arguments its original does**; plurals declare the categories their language needs; a
`<string>` stays a string; and no sentence is still English. Seven mutations were run against it and
each one failed the build.

  The argument check is the one that stops a crash rather than an embarrassment: a translation that
  kept `%1$s` and dropped `%2$s` throws `MissingFormatArgumentException` on the user's device and
  nowhere else. Android lint's `MissingTranslation` and `ExtraTranslation` are errors now too — the
  same claim, made later and in less detail, as the build's own backstop.

**6. "Nothing to translate" is a rule, not a list.** Three kinds of value are identical in all four
languages by construction: a pure format string (`%1$s%%`), an acronym the languages borrow as it
stands (`PIN`), and a rule id quoted as evidence (`RULE-PAY-FIRST v1.0`). The test strips format
specifiers and version markers and asks whether a lowercase letter is left — a real sentence always
has one. Only three keys are exempt by name: the app's own name, the language endonyms (written in
their own script in every file, which is how a person finds their language in a list they cannot
otherwise read), and the ISO date mask, whose field the app parses as ISO.

**7. `:app` joined the hardcoded-string rule.** Its own `strings.xml` had been noting since issue
1.5 that nothing enforced this there. For a localisation issue that is the gap that matters: a
literal in the lock screen is a sentence no translation can reach. It is matched by package, not by
path — lint's own test harness builds fixtures under a directory called `app`.

## What this found

- **A third "a number in a sentence is a claim".** `budgets_band_warn` read *"80% used"* for every
  warned category, whatever they had spent. 80 is `RULE-BUD-ALERT.warn_pct` — the rulebook's, not
  the screen's — and it would have gone on saying 80 after someone moved the band. It states the
  category's own measured percentage now. The health score's `/ 1000` (10.5) and the forecast's
  hardcoded "90 days" (10.6) were the first two; this one was found by a *translation* disagreeing
  with the original about how many format arguments the sentence had.
- **Two lint tests had been silently red.** `:lint:test` was held up to date by Gradle, so the two
  PII-logging tests only went red when something in that module finally changed. They were failing
  inside the harness — `android.util.Log` does not resolve without a stub — not on their
  assertions. Fixed with the stub the harness asks for. Third vacuous gate this project has found,
  after the drift test that never ran and the unreachable horizon filter.

## Deferred, and why

- **A native review of all three languages.** The outstanding work, and named as such above.
- **The international number-format toggle** (§3.5's second sentence). It changes every amount on
  every screen and every screenshot baseline, and it belongs with a currency setting rather than
  with a language one. The grouping is a deliberate, tested choice today; making it switchable is
  its own issue.
- **RTL in practice.** Layout direction follows the locale rather than being assumed, so the day an
  RTL language is added it is answered correctly — but none of the three shipped languages is RTL,
  so nothing here has been *seen* in mirrored layout. `android:supportsRtl` has been true since 1.1.
- **Translating the AI's knowledge files.** `ai/` holds rule names and evidence strings that reach
  the screen through ids, not through prose; the sentences the assistant speaks are in
  `:ml:llm`'s `strings.xml` and are translated. A rulebook in three languages is a different
  problem, and a bigger one.
- **Per-locale screenshots of every screen.** The dashboard is covered in all three, which is where
  the longest sentences and the tightest rows are. The rest would be baselines nobody reads.

## Consequences

- Someone who reads Hindi, Kannada or Tamil can use the whole app in that language, including the
  lock screen, and can choose it on a phone that is in English.
- Amounts keep Indian grouping and Latin digits in every language, and dates follow the language.
- A new screen cannot ship with `values/` alone, and a translated sentence cannot crash on a
  format argument its original had.
- Three languages' wording rests on an unreviewed machine translation until a speaker reads it.
