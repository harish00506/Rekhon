package com.aicfo.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.app.lock.AppLockGate
import com.aicfo.app.navigation.CfoNavHost
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
                            startDestination?.let { CfoNavHost(startDestination = it) }
                        }
                    }
                }
            }
        }
    }
}
