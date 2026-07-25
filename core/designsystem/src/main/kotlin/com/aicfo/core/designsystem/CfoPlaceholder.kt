package com.aicfo.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Placeholder design-system composable that exercises the Compose compiler.
 *
 * Why:  proves :core:designsystem's Compose toolchain (compiler plugin + M3) compiles
 *       under the skeleton (issue 1.1); real tokens/components arrive in issue 1.8.
 * What: renders the given label with Material 3 [Text].
 * Result: the design-system module produces valid Compose UI.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 *
 * Input:  [label] — the text to render.
 * Output: none (emits UI into the current composition).
 */
@Composable
fun CfoPlaceholder(label: String) {
    Text(text = label)
}
