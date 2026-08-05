package com.aicfo.app

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicfo.app.lock.AppLockGate
import com.aicfo.app.navigation.CfoNavHost
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.core.designsystem.component.CfoDemoBanner
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import dagger.hilt.android.AndroidEntryPoint

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
        super.onCreate(savedInstanceState)
        setContent {
            CfoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        // Everything below is composed only once the session is unlocked (SEC-002).
                        // The gate wraps the graph rather than being a destination inside it, so
                        // there is no navigation — deep link, restored back stack or otherwise —
                        // that reaches a screen without passing it. It covers onboarding too.
                        AppLockGate {
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
    val isDemoActive by viewModel.isDemoActive.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (isDemoActive) {
            CfoDemoBanner(
                message = stringResource(R.string.demo_banner_message),
                actionText = stringResource(R.string.demo_banner_exit),
                onExit = {
                    viewModel.exitDemo()
                    // inclusive, so the dashboard the demo filled cannot be reached with Back. The
                    // user is returning to a flow they never completed, and the app must look to
                    // them exactly as it did before they tapped into the demo.
                    navController.navigate(CfoRoute.Onboarding) {
                        popUpTo<CfoRoute.Dashboard> { inclusive = true }
                    }
                },
            )
        }
        // A Box rather than a second Scaffold: MainActivity's already owns the window insets, and
        // nesting one inside it would apply them twice and push the FAB above the gesture bar.
        Box(modifier = Modifier.weight(1f)) {
            CfoNavHost(startDestination = startDestination, navController = navController)
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
