package com.aicfo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aicfo.feature.accounts.AccountEditorScreen
import com.aicfo.feature.accounts.AccountsScreen
import com.aicfo.feature.dashboard.DashboardScreen
import com.aicfo.feature.onboarding.OnboardingScreen
import com.aicfo.feature.transactions.AddTransactionScreen
import com.aicfo.feature.transactions.TransactionsScreen

/**
 * The app's single navigation graph (ARC-001).
 *
 * Why:  §21.2 puts cross-feature routing here and nowhere else. This is the only file in the
 *       codebase that imports two feature modules, which is what keeps the features independent
 *       of each other — each exposes a plain composable and receives its navigation actions as
 *       lambdas, so no feature holds a `NavController` or knows a sibling exists.
 * What: builds the `NavHost` and connects each destination's actions to [CfoRoute] targets.
 * Result: adding a screen means adding a route and one line here — and forgetting to register a
 *       route is a compile error, not a crash at runtime.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *            2026-07-25 — Issue 2.1: the start destination is now decided at launch, because a new
 *            install must land on onboarding rather than an empty dashboard.
 *            2026-07-28 — Issue 2.4: onboarding gained a second exit — into the demo.
 *            2026-07-28 — Issue 2.5: accounts, and the first destination that takes an argument.
 *            2026-08-02 — Issue 3.1: the add-transaction destination the global FAB opens.
 *
 * Input:  [startDestination] — decided by `MainViewModel` from the stored onboarding flag;
 *         [modifier]; [navController] — hoisted so a test or a preview can supply its own.
 * Output: the navigation host.
 */
@Composable
fun CfoNavHost(
    startDestination: CfoRoute,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        onboardingDestination(navController)
        composable<CfoRoute.Dashboard> {
            DashboardScreen(
                onNavigateToTransactions = { navController.navigate(CfoRoute.Transactions) },
                onNavigateToAccounts = { navController.navigate(CfoRoute.Accounts) },
            )
        }
        composable<CfoRoute.Transactions> {
            TransactionsScreen()
        }
        composable<CfoRoute.AddTransaction> {
            // popBackStack for the same reason the editor below does: the capture screen was pushed
            // on top of whatever the user was looking at, and saving must return them there — the FAB
            // is global, so "wherever they were" is genuinely any destination.
            AddTransactionScreen(onDone = { navController.popBackStack() })
        }
        composable<CfoRoute.Accounts> {
            AccountsScreen(
                onAddAccount = { navController.navigate(CfoRoute.AccountEditor()) },
                onEditAccount = { id -> navController.navigate(CfoRoute.AccountEditor(id)) },
            )
        }
        composable<CfoRoute.AccountEditor> {
            // popBackStack, not navigate: the editor was pushed on top of the list, so returning is
            // a pop. Navigating would push a *second* list above the first, and Back would then walk
            // the user through every account they had edited.
            AccountEditorScreen(onDone = { navController.popBackStack() })
        }
    }
}

/**
 * The onboarding destination and its two exits (issues 2.1, 2.4).
 *
 * Why:  extracted from [CfoNavHost] when issue 2.5's two destinations pushed that function past
 *       detekt's 40-line limit. The seam is a real one: this is the only destination with more than
 *       one way out, and both of its `popUpTo` decisions need explaining, while every other
 *       destination is a single line.
 * What: registers `CfoRoute.Onboarding` with its finished and demo exits.
 * Result: [CfoNavHost] reads as a list of destinations again.
 * Changelog: 2026-07-28 — Extracted for issue 2.5.
 *
 * Input:  [navController] — the graph's controller, passed rather than captured so this stays a
 *         plain extension on the builder. Output: none (registers a destination).
 */
private fun NavGraphBuilder.onboardingDestination(navController: NavHostController) {
    composable<CfoRoute.Onboarding> {
        OnboardingScreen(
            onFinished = {
                // inclusive: onboarding is finished, so Back must not return to it — a user
                // stepping back into a completed first-run flow would be able to overwrite the
                // profile they just made.
                navController.navigate(CfoRoute.Dashboard) {
                    popUpTo<CfoRoute.Onboarding> { inclusive = true }
                }
            },
            // Issue 2.4: also inclusive, and for a different reason. Onboarding was *not*
            // completed — no profile exists — so a Back into it would show a half-answered flow
            // behind the demo banner. The way back is the banner's Exit action, which returns
            // here from the top.
            onDemoStarted = {
                navController.navigate(CfoRoute.Dashboard) {
                    popUpTo<CfoRoute.Onboarding> { inclusive = true }
                }
            },
        )
    }
}
