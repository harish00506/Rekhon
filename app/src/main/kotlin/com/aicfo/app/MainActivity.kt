package com.aicfo.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicfo.app.lock.AppLockGate
import com.aicfo.app.navigation.CfoNavHost
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.app.work.MarketPriceWorker
import com.aicfo.core.designsystem.component.CfoDemoBanner
import com.aicfo.core.designsystem.component.LocalPrivacyBlur
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

/**
 * The single host Activity (ARC-001, ARC-004).
 *
 * Why:  §21.2 — one Activity hosts the whole app and the nav graph routes between features. It
 *       applies [CfoTheme] rather than a bare `MaterialTheme` so every screen inherits the design
 *       tokens from issue 1.8; a screen that themed itself would drift the moment a token changed.
 * What: an `@AndroidEntryPoint` activity hosting [CfoNavHost] inside a themed, edge-to-edge
 *       `Scaffold`.
 * Result: the app the user actually launches.
 * Changelog: 2026-07-19 — Created for issue 1.1 as a placeholder.
 *            2026-07-25 — Issue 1.10: real theme, edge-to-edge, and the typed nav graph.
 *            2026-07-25 — Issue 2.1: waits for the start destination, so a new install opens on
 *            onboarding rather than an empty dashboard.
 *            2026-07-26 — Issue 2.2: became a `FragmentActivity` and the graph moved inside
 *            [AppLockGate].
 *            2026-07-28 — Issue 2.4: the graph moved inside [AppContent], which labels it as a demo.
 *
 * `enableEdgeToEdge()` before `setContent`, with the `Scaffold`'s insets applied to the content —
 * drawing under the system bars without consuming their insets is how content ends up hidden
 * behind the status bar or the keyboard.
 *
 * **A `FragmentActivity`, not a `ComponentActivity` (issue 2.2).** AndroidX `BiometricPrompt`
 * requires one — it hosts itself in a fragment — and SEC-002 requires BiometricPrompt. Nothing else
 * about the activity is fragment-based; `FragmentActivity` extends `ComponentActivity`, so Compose,
 * Hilt and the nav graph are unaffected.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /**
     * Input:  [savedInstanceState] — the saved UI state, if any.
     * Output: none (installs the Compose content).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Issue 11.2: before anything is composed. The first frame of this app is the lock screen,
        // and a flag applied from inside a composition would miss it (§23, FR-PRIV-*).
        SecureWindow.applyTo(this)
        super.onCreate(savedInstanceState)
        setContent {
            // Issue 10.8: the language wraps everything, the lock screen included — a PIN prompt in
            // a language the user does not read is a poor way to start. Its own state holder, so
            // nothing that opens the encrypted database is composed before the PIN (SEC-002).
            val languageViewModel: AppLanguageViewModel = hiltViewModel()
            val language by languageViewModel.language.collectAsStateWithLifecycle()
            LocalisedContent(language) {
                CfoTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            // Everything below is composed only once the session is unlocked (SEC-002).
                            // The gate wraps the graph rather than being a destination inside it, so
                            // there is no navigation — deep link, restored back stack or otherwise —
                            // that reaches a screen without passing it. It covers onboarding too.
                            AppLockGate {
                                // API-002: one market-price refresh per open. Inside the gate, so it is
                                // composed exactly once per unlock and never on a locked device — and
                                // it enqueues work rather than fetching, so the activity never touches
                                // a repository. With no backend configured the job does nothing (6.5).
                                val context = LocalContext.current
                                LaunchedEffect(Unit) { MarketPriceWorker.refreshNow(context) }

                                // The graph is only built once the stored onboarding flag has been read.
                                // Until then the surface stays empty rather than guessing: a returning
                                // user must never see the welcome screen appear and vanish. This is a
                                // disk read of one small file, so it is a frame or two, not a splash.
                                val viewModel: MainViewModel = hiltViewModel()
                                val startDestination by viewModel.startDestination.collectAsStateWithLifecycle()
                                startDestination?.let { AppContent(it, viewModel) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The navigation graph, with the demo label above it (issue 2.4; FR-ONB-004, P-02).
 *
 * Why:  **the banner wraps the graph rather than living inside a screen**, which is the same
 *       argument [AppLockGate] makes one level up: a per-screen label is one forgotten screen away
 *       from showing a user fabricated figures with nothing saying so. Composed here, no destination
 *       — existing, added later, or reached by a deep link — can render without it.
 *
 *       The `NavController` is created here rather than inside [CfoNavHost] because the exit action
 *       has to navigate: leaving the demo returns to onboarding, and a controller owned by the graph
 *       would be out of reach of the banner sitting above it.
 * What: a column of the banner (when active) and the graph.
 * Result: the app is unmistakable as a demo while one is loaded, and one tap from leaving it.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * Changelog: 2026-08-02 — Issue 3.1: the global add-transaction FAB sits here, over the graph.
 *
 * Input:  [startDestination] — decided by [MainViewModel]; [viewModel] — supplies the demo flag and
 *         the wipe. Output: the rendered app.
 */
@Composable
private fun AppContent(
    startDestination: CfoRoute,
    viewModel: MainViewModel,
) {
    val navController = rememberNavController()
    // Walked back from the context rather than taken from `LocalActivity`, which this project's
    // Compose version does not have. Issue 10.8 established the walk: `LocalContext` here may be a
    // wrapper rather than the Activity itself.
    val activity = LocalContext.current.findActivity()
    val isDemoActive by viewModel.isDemoActive.collectAsStateWithLifecycle()
    val isBlurred by viewModel.isPrivacyBlurred.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()

    // The capture guard is not here any more. Issue 5.3 tied FLAG_SECURE to this toggle, which
    // meant the window was capturable whenever the blur was off — the default. Issue 11.2 made the
    // policy always-on and moved it to `onCreate` (`SecureWindow`); the blur still masks the text.

    Column(modifier = Modifier.fillMaxSize()) {
        if (isDemoActive) DemoBanner(viewModel, navController)
        // A Box rather than a second Scaffold: MainActivity's already owns the window insets, and
        // nesting one inside it would apply them twice and push the FAB above the gesture bar.
        Box(modifier = Modifier.weight(1f)) {
            // Issue 5.3: provided around the graph rather than inside a screen — the argument the
            // demo banner above makes, for the same reason. No destination, existing or added
            // later, can render an amount outside this provider.
            CompositionLocalProvider(LocalPrivacyBlur provides isBlurred) {
                CfoNavHost(
                    startDestination = startDestination,
                    navController = navController,
                    onEraseComplete = { endProcessAfterErase(activity) },
                )
            }
            CfoPrivacyBlurToggle(
                blurred = isBlurred,
                onToggle = viewModel::setPrivacyBlur,
                modifier = Modifier.align(Alignment.TopEnd).padding(CfoDimens.spaceMd),
            )
            if (currentEntry.showsAddTransactionFab()) {
                CfoAddTransactionFab(
                    onClick = { navController.navigate(CfoRoute.AddTransaction) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(CfoDimens.spaceMd),
                )
            }
        }
    }
}

/**
 * Whether the add-transaction FAB belongs over the current destination (issue 3.1; FR-TXN-002).
 *
 * Why:    FR-TXN-002 says add-transaction is reachable in **one tap**, and the honest reading of
 *         that is a FAB the user does not first have to navigate to — so it lives above the graph
 *         rather than inside one screen. Two destinations have to opt out. **Onboarding**, because a
 *         user who has no profile yet has no account to spend from and the flow must not be
 *         escapable sideways. **The capture screen itself**, because a FAB that reopens the screen
 *         it is already on is a button that does nothing, and it would sit over the Save button.
 *
 *         It needs no check for the lock screen: this whole composable is inside `AppLockGate`, so a
 *         locked session never composes it at all.
 * Result: `true` on the dashboard, the transaction list, accounts and the account editor; `false` on
 *         onboarding, on the capture screen, and before the first destination has resolved.
 * Input:  the receiver — the current back-stack entry, `null` until the graph settles.
 * Output: [Boolean].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
private fun NavBackStackEntry?.showsAddTransactionFab(): Boolean {
    val destination = this?.destination ?: return false
    // `NavDestination.Companion.hasRoute` — the typed overload. Without that import it resolves to
    // the String one and the check silently compares a route object against a path.
    return !destination.hasRoute(CfoRoute.Onboarding::class) &&
        !destination.hasRoute(CfoRoute.AddTransaction::class)
}

/**
 * The one-tap entry to capture (issue 3.1; FR-TXN-002).
 *
 * Why:    a named composable rather than a `FloatingActionButton` inline in [AppContent], so a test
 *         can render and click it without standing up the whole app — half of FR-TXN-002's tap
 *         budget is asserted against this function, and the other half against
 *         `AddTransactionContent`.
 *
 *         The content description is what a screen reader announces; an icon-only button without one
 *         is announced as "button" and is unusable (§21.6's accessibility line).
 * Result: the composition. Input: [onClick]; [modifier]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
internal fun CfoAddTransactionFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.add_transaction))
    }
}

/**
 * The label that says the figures on screen are invented (issue 2.4; FR-ONB-004, P-02).
 * Why:    lifted out of [AppContent] by issue 11.4, which pushed that function past detekt's length
 *         limit. It is one unit of behaviour — the banner and the only thing it does — so it reads
 *         better named than inline.
 * Result: the banner, and on exit a return to onboarding with the demo's dashboard unreachable by
 *         Back: the user is going back to a flow they never completed, and the app must look to them
 *         exactly as it did before they tapped into the demo.
 * Input:  [viewModel] — clears the demo; [navController] — navigates. Output: the composition.
 * Changelog: 2026-10-01 — Extracted from [AppContent] for issue 11.4.
 */
@Composable
private fun DemoBanner(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    CfoDemoBanner(
        message = stringResource(R.string.demo_banner_message),
        actionText = stringResource(R.string.demo_banner_exit),
        onExit = {
            viewModel.exitDemo()
            navController.navigate(CfoRoute.Onboarding) {
                popUpTo<CfoRoute.Dashboard> { inclusive = true }
            }
        },
    )
}

/**
 * Closes the app once an erase has destroyed this installation's keys (issue 11.4).
 * Why:    `finishAndRemoveTask` clears the task so the recents entry cannot reopen into a dead
 *         graph, and the process is ended because every handle it holds — the Room database, the
 *         Tink primitives, the DataStore — points at a key that no longer exists. Navigating back
 *         to onboarding instead would mean each of those failing one at a time in front of a user
 *         who has just been told the operation succeeded. The next launch is a fresh install, which
 *         is what they asked for.
 * Result: the task is gone and the process exits; this function does not return.
 * Input:  [activity] — the host, or `null` if it could not be resolved, in which case the process
 *         still ends: that is the half that matters.
 * Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private fun endProcessAfterErase(activity: Activity?) {
    activity?.finishAndRemoveTask()
    exitProcess(0)
}

/**
 * Walks a context chain back to the Activity that owns it (issue 11.4).
 * Why:    `LocalContext` is not necessarily the Activity — issue 10.8 wrapped it in a
 *         `ContextWrapper` so the app could change language without recreating, and a `as?` cast
 *         against that wrapper returns null. The erase needs the real Activity to clear its task.
 * Result: the Activity, or `null` if this context has none — in which case the erase still ends
 *         the process, which is the part that matters.
 * Input:  the receiver. Output: [Activity]`?`.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
