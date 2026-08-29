package com.aicfo.widget

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.layout.fillMaxSize
import com.aicfo.core.model.Money

/**
 * The AI Personal CFO home-screen widget (issue 5.5; §5.2, §35, P-01/P-03/P-04).
 *
 * Why:  the app's daily surfaces end at the launcher — a user checks whether they can afford lunch
 *       without unlocking anything. That is only safe if the widget can answer from a cache,
 *       because the encrypted database is unavailable while the app is locked (SEC-002).
 *
 *       **Glance's own state *is* the cache, and that is the central decision (ADR-0024).**
 *       Safe-to-Spend has no snapshot table — `SafeToSpendRepository` recomputes it live from five
 *       Room reads — so a cache had to be created either way. Using Glance's preference store
 *       rather than a new proto field or a new table means the value the widget reads is the value
 *       the widget was redrawn for: there is no second store to fall out of step, and
 *       [provideGlance] needs no dependency injection at all.
 * What: a [GlanceAppWidget] whose content is [CfoWidgetContent] over the current state, plus the
 *       two writers `:app` uses to fill that state.
 * Result: a widget that renders offline, renders locked, and computes nothing itself.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * **No network on any path** (P-04): the state is local preferences and the figures were computed
 * from local rows, so this behaves identically in airplane mode.
 */
class CfoWidget : GlanceAppWidget() {
    /**
     * One layout for every size.
     *
     * Two lines of text with a label each do not want a different arrangement at 4×1 than at 2×2 —
     * [SizeMode.Exact] would mean maintaining variants that all say the same thing. The launcher
     * scales the single layout instead.
     */
    override val sizeMode: SizeMode = SizeMode.Single

    /**
     * Draws the widget from whatever is in its state.
     *
     * Why:    reads [currentState] and nothing else. Every alternative — injecting a repository via
     *         a Hilt entry point, opening the database, calling an engine — would put work that can
     *         throw (SEC-002) or block onto the launcher's draw path.
     * Result: the composed content. Before any refresh has run the state is empty, which
     *         [toWidgetSnapshot] turns into the all-absent snapshot and the content renders as
     *         pending — not as ₹0.00 (P-03).
     * Input:  [context] — supplied by Glance; [id] — the placed instance, unused because every
     *         instance shows the same profile's figures.
     * Output: none; emits the content.
     * Changelog: 2026-08-17 — Created for issue 5.5.
     */
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            GlanceTheme {
                CfoWidgetContent(
                    snapshot = currentState<Preferences>().toWidgetSnapshot(),
                    context = LocalContext.current,
                    modifier = GlanceModifier.fillMaxSize().clickable(launchApp(LocalContext.current)),
                )
            }
        }
    }

    companion object {
        /**
         * Writes the two figures into every placed widget and redraws them (issue 5.5).
         *
         * Why:    called from `WidgetRefreshWorker`, the only thing in the app allowed to *compute*
         *         these. Splitting it from [writeBlurred] is the point of the pair: this one can
         *         only run when the database is readable, and the other must run whether or not it
         *         is.
         *
         *         **Idempotent by construction.** The state is a set of keys, not a log, so a
         *         second run with the same inputs writes the same bytes — there is no timestamp to
         *         make two identical refreshes differ. Running the worker twice costs a redraw.
         * What:   sets or removes each key, then redraws.
         * Result: every widget on the home screen shows the new figures. A `null` figure **removes**
         *         its key rather than writing a zero, so "uncomputed" survives a refresh that could
         *         not compute it (P-03).
         * Input:  [context]; [safeToSpend] — paise or `null`; [netWorth] — paise or `null`.
         * Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        suspend fun writeFigures(
            context: Context,
            safeToSpend: Money?,
            netWorth: Money?,
        ) = update(context) { preferences ->
            preferences.putOrRemove(WidgetKeys.SafeToSpendMinor, safeToSpend?.minor)
            preferences.putOrRemove(WidgetKeys.NetWorthMinor, netWorth?.minor)
        }

        /**
         * Writes the privacy-blur flag into every placed widget and redraws them (issue 5.5; P-01).
         *
         * Why:    **this must work while the app is locked, which is why it is separate.** The user
         *         taps the blur toggle to hide amounts *now* — often with someone looking. If
         *         hiding the widget required recomputing its figures it would depend on the
         *         database, which throws while locked (SEC-002), and the amounts would stay on the
         *         home screen. This touches nothing but preferences, so it always succeeds.
         * What:   sets the one key and redraws.
         * Result: the amounts mask or unmask on the next frame. The figures are left alone — the
         *         mask is a rendering decision, and re-blurring must not be able to lose a figure
         *         the app cannot currently recompute.
         * Input:  [context]; [blurred] — the flag as `SettingsStore` reports it.
         * Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        suspend fun writeBlurred(
            context: Context,
            blurred: Boolean,
        ) = update(context) { preferences -> preferences[WidgetKeys.Blurred] = blurred }

        /**
         * Applies a state change to every placed instance, then redraws (issue 5.5).
         *
         * Why:    the two writers differ only in what they set; the "for each GlanceId, update, then
         *         updateAll" ceremony belongs in one place. The redraw comes after the writes, not
         *         between them, so a widget never renders a half-applied change.
         * Result: the change is visible. **A widget that has never been placed is not an error** —
         *         `getGlanceIds` returns empty and this does nothing, which is the normal case for
         *         most users and must not fail the worker that called it.
         * Input:  [context]; [transform] — mutates the preferences.
         * Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        private suspend fun update(
            context: Context,
            transform: (MutablePreferences) -> Unit,
        ) {
            GlanceAppWidgetManager(context).getGlanceIds(CfoWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { preferences -> transform(preferences) }
            }
            CfoWidget().updateAll(context)
        }

        /**
         * Puts a nullable paise value, removing the key when there is nothing to say (issue 5.5).
         * Why:    absence is how [WidgetSnapshot] carries "not computed"; writing `0` instead would
         *         put a number on the home screen that no engine produced (P-03).
         * Result: the key is set, or gone.
         * Input:  [key] — the paise key; [value] — paise or `null`. Output: none.
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        private fun MutablePreferences.putOrRemove(
            key: Preferences.Key<Long>,
            value: Long?,
        ) {
            if (value == null) remove(key) else this[key] = value
        }
    }
}

/**
 * The action that opens the app when the widget is tapped (issue 5.5; P-07).
 *
 * Why:  the widget states a position; acting on it happens in the app, where the breakdown and the
 *       controls are (P-02/P-07). The launch intent is resolved from the package manager rather
 *       than by naming `MainActivity`, because `:widget` must not depend on `:app` — that edge
 *       would invert the module graph (ARC-001) — and the launcher entry point is the destination
 *       wanted anyway: the dashboard is the app's start destination, so a deep link would add a
 *       route to arrive at the same screen.
 * Result: the app opens on the dashboard. A device with no resolvable launcher intent, which
 *       should not exist, gets a no-op rather than a crash.
 * Input:  [context]. Output: the Glance action to run on tap.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
private fun launchApp(context: Context) =
    actionStartActivity(
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(),
    )
