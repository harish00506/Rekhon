package com.aicfo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aicfo.feature.dashboard.DashboardScreen
import com.aicfo.feature.onboarding.OnboardingScreen
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
            )
        }
        composable<CfoRoute.Dashboard> {
            DashboardScreen(onNavigateToTransactions = { navController.navigate(CfoRoute.Transactions) })
        }
        composable<CfoRoute.Transactions> {
            TransactionsScreen()
        }
    }
}
