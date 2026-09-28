package com.aicfo.app

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.layoutDirection
import com.aicfo.core.datastore.LanguageSetting
import java.util.Locale

/**
 * The app's screens in the language the user chose (issue 10.8; SRS §3.5, NFR-011).
 *
 * Why:  a phone in English owned by someone who reads Hindi is the ordinary case in India, so the
 *       choice has to belong to the app and not only to the device. Two mechanisms, deliberately:
 *
 *       **The composition is re-provided with the chosen locale**, which is what actually changes
 *       the words on screen. It works on every version this app supports, needs no new dependency,
 *       and — unlike overriding the Activity's base context — does not require reading a stored
 *       setting *synchronously* before `onCreate` returns. That read would have to block on
 *       DataStore, and `runBlocking` is banned outside tests (§21.6).
 *
 *       **And, from Android 13, the choice is mirrored into the platform's own per-app language**,
 *       so the system settings entry agrees with the in-app picker, and so the parts of the app
 *       that are not a composition — the home-screen widget and notifications, which build their
 *       own `Context` — speak the same language. Below 13 there is no such store: the screens
 *       follow the choice, and the widget and notifications follow the phone. That is stated in
 *       ADR-0056 rather than hidden, because it is a real limit of the platform, not of the app.
 * What: provides a locale-configured `Context`, `Configuration` and layout direction to [content].
 * Result: every `stringResource` below this point resolves in the chosen language.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 *
 * Input:  [language] — the stored choice; [LanguageSetting.SYSTEM] leaves the composition alone.
 *         [content] — the app.
 * Output: the composition.
 */
@Composable
internal fun LocalisedContent(
    language: LanguageSetting,
    content: @Composable () -> Unit,
) {
    val tag = language.tag
    if (tag == null) {
        SystemLanguageMirror(tag = null)
        content()
        return
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localised = remember(context, tag, configuration) { context.speaking(tag, configuration) }
    SystemLanguageMirror(tag = tag)
    CompositionLocalProvider(
        LocalContext provides localised,
        LocalConfiguration provides localised.resources.configuration,
        LocalLayoutDirection provides layoutDirectionOf(tag),
        content = content,
    )
}

/**
 * The same context, answering in another language.
 *
 * Why:  the obvious implementation — `createConfigurationContext(...)` handed straight to
 *       `LocalContext` — **crashes the app**, and only on a device. That call returns a fresh
 *       `ContextImpl` whose base is the application, so the chain back to the Activity is cut; the
 *       first `hiltViewModel()` below the provider then dies with *"Expected an activity context
 *       for creating a HiltViewModelFactory"*. The Robolectric test did not catch it because it
 *       rendered a `Text`, not a screen — the crash arrived the moment a real language was chosen
 *       on the emulator.
 *
 *       A `ContextWrapper` **around the Activity** keeps that chain intact — `findActivity()`
 *       unwraps wrappers — while answering `getResources()` and `getAssets()` from the configured
 *       context, which is what `stringResource` reads. The theme is deliberately left to the base:
 *       the language changes the words, never the colours.
 * What: a wrapper delegating resources to a configuration context.
 * Result: every string below resolves in [tag], and every ViewModel still finds its Activity.
 * Changelog: 2026-09-28 — Created for issue 10.8, after the device run crashed.
 *
 * Input:  the receiver — the Activity's own context; [tag] — BCP-47; [configuration] — the current
 *         one, so density, font scale and orientation are inherited. Output: the wrapped context.
 */
private fun Context.speaking(
    tag: String,
    configuration: Configuration,
): Context {
    val configured =
        createConfigurationContext(
            Configuration(configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) },
        )
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = configured.resources

        override fun getAssets(): AssetManager = configured.assets
    }
}

/**
 * Keeps Android 13+'s per-app language in step with the in-app choice.
 *
 * Why:    so the system settings entry does not disagree with the picker, and so the widget and
 *         the notification builder — which never see this composition — speak the chosen language
 *         too. **It writes only when the value differs**: setting it is what makes the system
 *         recreate the Activity, and an unconditional write would recreate it forever.
 * Result: on API 33+, the platform's stored per-app locale equals [tag] (or is cleared for the
 *         system default). On older versions, nothing — there is no such store to write to.
 * Input:  [tag] — the BCP-47 tag, or `null` to follow the phone. Output: none.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
@Composable
private fun SystemLanguageMirror(tag: String?) {
    val context = LocalContext.current
    LaunchedEffect(tag) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val manager = context.getSystemService(LocaleManager::class.java) ?: return@LaunchedEffect
        val wanted = tag.orEmpty()
        if (manager.applicationLocales.toLanguageTags().substringBefore('-') != wanted) {
            manager.applicationLocales = LocaleList.forLanguageTags(wanted)
        }
    }
}

/**
 * Which way a language's text runs.
 * Why:    NFR-011 asks for RTL-safe layouts, and the honest way to be RTL-safe is to let the
 *         direction follow the locale rather than assume it. None of Hindi, Kannada and Tamil is
 *         right-to-left, so today this always answers `Ltr` — and the day an RTL language is added
 *         it answers correctly without anyone remembering this function exists.
 * Result: the direction for [tag]. Input: [tag] — BCP-47. Output: [LayoutDirection].
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
private fun layoutDirectionOf(tag: String): LayoutDirection =
    if (Locale.forLanguageTag(tag).layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
