package com.aicfo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single host Activity for the Compose UI (ARC-004: state up, events down).
 *
 * Why:  §21.2 — one Activity hosts the whole app; the nav graph (issue 1.10) will route
 *       between features. This skeleton renders a placeholder to prove the app runs.
 * What: an @AndroidEntryPoint ComponentActivity that sets Compose content.
 * Result: an installable, launchable debug app.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * Input:  [savedInstanceState] — the saved UI state, if any.
     * Output: none (installs the Compose content).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text(text = stringResource(id = R.string.app_name))
                }
            }
        }
    }
}
