package com.aicfo.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aicfo.core.datastore.LanguageSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That choosing a language actually changes the words (issue 10.8; SRS §3.5, NFR-011).
 *
 * Why:  every other test in this issue checks the *files*. This one checks the mechanism: that
 *       `LocalisedContent` puts the chosen locale in front of the composition, so a
 *       `stringResource` below it resolves in Hindi while the device stays in English. That is the
 *       whole feature, and it is the part that would silently do nothing — the app would keep
 *       working, in English, with a picker that appeared to have taken the choice.
 * What: the chosen language wins over the device's, and "follow the phone" leaves it alone.
 * Result: the picker is connected to the words on screen.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the chosen language is what the screen speaks, whatever the device is set to`() {
        // The guard first: without it this test passes while nothing is translated, because both
        // sides of the comparison fall back to English. That is the shape of a check that never
        // ran — this project has shipped two, and neither was noticed by reading it.
        assertNotEquals(
            "there is no Hindi to fall back from",
            compose.activity.getString(R.string.demo_banner_exit),
            hindi(R.string.demo_banner_exit),
        )

        compose.setContent {
            LocalisedContent(LanguageSetting.HINDI) { Text(stringResource(R.string.demo_banner_exit)) }
        }

        compose.onNodeWithText(hindi(R.string.demo_banner_exit)).assertIsDisplayed()
    }

    @Test
    fun `following the phone leaves the composition exactly as it was`() {
        // The default has to be a no-op, not "English": a phone already in Kannada must stay there.
        var locale: String? = null
        compose.setContent {
            LocalisedContent(LanguageSetting.SYSTEM) {
                locale = LocalConfiguration.current.locales[0].language
            }
        }

        assertEquals("en", locale)
    }

    @Test
    fun `a screen under the chosen language reads its plurals from that language too`() {
        // Plurals resolve through a different resource path than strings, and a locale applied to
        // one but not the other is a screen half in Hindi.
        compose.setContent {
            LocalisedContent(LanguageSetting.HINDI) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.lock_attempts_remaining, 2, 2))
            }
        }

        val inHindi =
            compose.activity.localised(
                "hi",
            ).resources.getQuantityString(R.plurals.lock_attempts_remaining, 2, 2)
        assertNotEquals(
            "there is no Hindi plural to fall back from",
            compose.activity.resources.getQuantityString(R.plurals.lock_attempts_remaining, 2, 2),
            inHindi,
        )
        compose.onNodeWithText(inHindi).assertIsDisplayed()
    }

    @Test
    fun `a screen below the chosen language can still find its activity`() {
        // The one this issue's device run found, and the reason it is a test now. The obvious
        // implementation — handing `createConfigurationContext(...)` straight to `LocalContext` —
        // returns a context whose base is the *application*, and the first `hiltViewModel()` below
        // it dies with "Expected an activity context for creating a HiltViewModelFactory". Every
        // real screen in this app calls that; a `Text` does not, which is why the first version of
        // this suite passed while the app crashed the moment a language was chosen.
        var found: android.content.Context? = null
        compose.setContent {
            LocalisedContent(LanguageSetting.HINDI) { found = LocalContext.current.activityOrNull() }
        }

        assertEquals(compose.activity, found)
    }

    /** Result: what [id] says in Hindi. Input: [id]. Output: [String]. */
    private fun hindi(id: Int): String = compose.activity.localised("hi").getString(id)

    /**
     * Result: the Activity this context unwraps to, or `null` — the same walk Hilt does.
     * Input:  the receiver. Output: the activity, or `null`.
     */
    private tailrec fun android.content.Context.activityOrNull(): android.app.Activity? =
        when (this) {
            is android.app.Activity -> this
            is android.content.ContextWrapper -> baseContext.activityOrNull()
            else -> null
        }

    /** Result: a context whose resources speak [tag]. Input: [tag]. Output: the context. */
    private fun android.content.Context.localised(tag: String): android.content.Context =
        createConfigurationContext(
            android.content.res.Configuration(resources.configuration).apply {
                setLocales(android.os.LocaleList.forLanguageTags(tag))
            },
        )
}
