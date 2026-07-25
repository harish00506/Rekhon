package com.aicfo.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contrast checks for every token pair (ACC-*, WCAG 2.2 AA).
 *
 * Why:  "accessibility scan passes" appears in the Definition of Done for every issue, and until
 *       now nothing computed anything — it was a claim. Contrast is the one accessibility property
 *       that is pure arithmetic over the tokens, so it can be checked in a unit test with no
 *       device, no emulator and no scanning tool. If a future palette change makes a colour pair
 *       unreadable, this fails in seconds rather than in a user's hands.
 * What: the WCAG relative-luminance formula over each foreground/background pair the theme
 *       defines, in both light and dark.
 * Result: the contrast half of the accessibility promise is enforced, not asserted.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * The threshold is **4.5:1**, WCAG AA for normal-size text. Large text is allowed 3:1, but these
 * pairs are used for body copy and amounts, so the stricter bar is the right one — and holding
 * everything to it means no future caller has to check which rule applies.
 */
class ColorContrastTest {
    /** Input: the light scheme's core pairs. Output: asserts each meets AA. */
    @Test
    fun `light scheme text pairs meet WCAG AA`() {
        assertContrast("onPrimary on primary", CfoLightColorScheme.onPrimary, CfoLightColorScheme.primary)
        assertContrast("onBackground on background", CfoLightColorScheme.onBackground, CfoLightColorScheme.background)
        assertContrast("onSurface on surface", CfoLightColorScheme.onSurface, CfoLightColorScheme.surface)
        assertContrast(
            "onSurfaceVariant on surfaceVariant",
            CfoLightColorScheme.onSurfaceVariant,
            CfoLightColorScheme.surfaceVariant,
        )
        assertContrast("onError on error", CfoLightColorScheme.onError, CfoLightColorScheme.error)
    }

    /** Input: the dark scheme's core pairs. Output: asserts each meets AA. */
    @Test
    fun `dark scheme text pairs meet WCAG AA`() {
        assertContrast("onPrimary on primary", CfoDarkColorScheme.onPrimary, CfoDarkColorScheme.primary)
        assertContrast("onBackground on background", CfoDarkColorScheme.onBackground, CfoDarkColorScheme.background)
        assertContrast("onSurface on surface", CfoDarkColorScheme.onSurface, CfoDarkColorScheme.surface)
        assertContrast(
            "onSurfaceVariant on surfaceVariant",
            CfoDarkColorScheme.onSurfaceVariant,
            CfoDarkColorScheme.surfaceVariant,
        )
        assertContrast("onError on error", CfoDarkColorScheme.onError, CfoDarkColorScheme.error)
    }

    /**
     * Input:  the finance roles used as fills, with their `on…` pairs.
     * Output: asserts a chip or badge in positive/negative/warning is readable in both themes.
     */
    @Test
    fun `finance role fills are readable in both themes`() {
        listOf(
            "light" to CfoLightExtendedColors,
            "dark" to CfoDarkExtendedColors,
        ).forEach { (name, colors) ->
            assertContrast("$name onPositive on positive", colors.onPositive, colors.positive)
            assertContrast("$name onNegative on negative", colors.onNegative, colors.negative)
            assertContrast("$name onWarning on warning", colors.onWarning, colors.warning)
        }
    }

    /**
     * Input:  the finance roles used as **text** on the app's surfaces — how `CfoAmountText`
     *         actually renders them.
     * Output: asserts a green or red amount is legible on the background it is drawn on. This is
     *         the pair that matters most: every figure in the app is one of these.
     */
    @Test
    fun `amount colours are readable on their own surfaces`() {
        assertContrast(
            "light positive amount on surface",
            CfoLightExtendedColors.positive,
            CfoLightColorScheme.surface,
        )
        assertContrast(
            "light negative amount on surface",
            CfoLightExtendedColors.negative,
            CfoLightColorScheme.surface,
        )
        assertContrast(
            "dark positive amount on surface",
            CfoDarkExtendedColors.positive,
            CfoDarkColorScheme.surface,
        )
        assertContrast(
            "dark negative amount on surface",
            CfoDarkExtendedColors.negative,
            CfoDarkColorScheme.surface,
        )
    }

    /**
     * Input:  a pair known to be unreadable (mid-grey on white).
     * Output: asserts the check itself can fail. Without this the suite above could be passing
     *         because the formula is wrong rather than because the palette is good — the vacuous
     *         gate this project keeps finding.
     */
    @Test
    fun `the contrast check rejects an unreadable pair`() {
        val ratio = contrastRatio(Color(0xFF999999), Color.White)
        assertTrue("mid-grey on white must fail AA, got $ratio", ratio < WCAG_AA_NORMAL)
    }

    /**
     * Asserts one pair meets the AA threshold.
     * Input:  [what] — a label for the failure message; [foreground], [background].
     * Output: fails the test when the ratio is below [WCAG_AA_NORMAL].
     */
    private fun assertContrast(
        what: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what has contrast %.2f:1, below the WCAG AA minimum of %.1f:1".format(ratio, WCAG_AA_NORMAL),
            ratio >= WCAG_AA_NORMAL,
        )
    }

    private companion object {
        /** WCAG 2.2 AA for normal-size text. */
        const val WCAG_AA_NORMAL = 4.5

        /** The sRGB channel value below which the transfer function is linear. */
        const val SRGB_LINEAR_THRESHOLD = 0.03928
        const val SRGB_LINEAR_DIVISOR = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_SCALE = 1.055
        const val SRGB_EXPONENT = 2.4

        const val LUMA_RED = 0.2126
        const val LUMA_GREEN = 0.7152
        const val LUMA_BLUE = 0.0722

        /** WCAG's 0.05 flare constant, which keeps the ratio finite for pure black. */
        const val FLARE = 0.05

        /**
         * The WCAG contrast ratio between two colours.
         * Why:    written out rather than pulled from a library — it is eight lines, and a
         *         dependency for eight lines of arithmetic would need an ADR to justify.
         * Result: a ratio from 1.0 (identical) to 21.0 (black on white).
         * Input:  [foreground], [background] — opaque colours. Output: [Double] ratio.
         */
        fun contrastRatio(
            foreground: Color,
            background: Color,
        ): Double {
            val first = relativeLuminance(foreground)
            val second = relativeLuminance(background)
            return (max(first, second) + FLARE) / (min(first, second) + FLARE)
        }

        /**
         * WCAG relative luminance.
         * Result: 0.0 for black, 1.0 for white.
         * Input:  [color]. Output: [Double].
         */
        fun relativeLuminance(color: Color): Double =
            LUMA_RED * linearise(color.red) +
                LUMA_GREEN * linearise(color.green) +
                LUMA_BLUE * linearise(color.blue)

        /**
         * Undoes the sRGB transfer curve for one channel.
         * Result: the linear-light value. Input: [channel] in 0..1. Output: [Double].
         */
        fun linearise(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= SRGB_LINEAR_THRESHOLD) {
                value / SRGB_LINEAR_DIVISOR
            } else {
                ((value + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_EXPONENT)
            }
        }
    }
}
