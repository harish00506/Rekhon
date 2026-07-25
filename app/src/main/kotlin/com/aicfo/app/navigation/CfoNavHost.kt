package com.aicfo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aicfo.feature.dashboard.DashboardScreen
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
 *
 * Input:  [modifier]; [navController] — hoisted so a test or a preview can supply its own.
 * Output: the navigation host.
 */
@Composable
fun CfoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = CfoRoute.Dashboard,
        modifier = modifier,
    ) {
        composable<CfoRoute.Dashboard> {
            DashboardScreen(onNavigateToTransactions = { navController.navigate(CfoRoute.Transactions) })
        }
        composable<CfoRoute.Transactions> {
            TransactionsScreen()
        }
    }
}
