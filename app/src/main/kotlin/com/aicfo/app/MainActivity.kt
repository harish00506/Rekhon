package com.aicfo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
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
 *
 * `enableEdgeToEdge()` before `setContent`, with the `Scaffold`'s insets applied to the content —
 * drawing under the system bars without consuming their insets is how content ends up hidden
 * behind the status bar or the keyboard.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                        CfoNavHost()
                    }
                }
            }
        }
    }
}
