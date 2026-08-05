package com.aicfo.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign

/**
 * The PIN entry field (issue 2.2; SEC-002, ACC-*).
 *
 * Why:  two screens need exactly this control — the lock screen in `:app` and onboarding's security
 *       step in `:feature:onboarding` — and ARC-001 forbids one feature module depending on the
 *       other. The design system is where a component both of them need belongs, and it is also
 *       what stops the two drifting into different PIN keyboards and different masking.
 * What: a masked, numeric, length-capped text field.
 * Result: one PIN control, with the security-relevant details decided once.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **Three details here are security decisions, not styling.**
 * - `PasswordVisualTransformation` — a PIN visible on screen defeats the shoulder-surfing threat
 *   §23.1 names, and this is the one field in the app where that threat is the whole point.
 * - `KeyboardType.NumberPassword` — a numeric keypad that, unlike `KeyboardType.Number`, does not
 *   offer the autofill and prediction strip. A PIN must never reach a keyboard's learned dictionary.
 * - **Filtering happens here, not at the call site.** Only ASCII digits pass, and input stops at
 *   [MAX_PIN_LENGTH]; a caller that forgot either would let a PIN be set that its own keypad cannot
 *   re-enter.
 *
 * **Deliberately five parameters.** The length cap, the IME action, a separate content description
 * and an `isError` flag were all dropped rather than suppressing detekt's limit: the cap has
 * exactly one correct value, `ImeAction.Done` is right for every current caller,
 * `OutlinedTextField` already announces its [label] to a screen reader, and a caller that needs to
 * report "those two PINs do not match" has to render the message anyway — a red outline as well
 * would be a second way of saying it, not a necessary one.
 *
 * Input:  [value] — the digits typed so far; [onValueChange] — receives the **filtered** value;
 *         [label] — from the caller's `strings.xml` (§21.6), and also what a screen reader
 *         announces; [modifier]; [enabled] — false during a lockout.
 * Output: the rendered field.
 */
@Composable
fun CfoPinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            // Filter rather than reject: a user pasting "1234 " should end up with "1234", not with
            // nothing and no explanation. `'0'..'9'` rather than Char.isDigit, which also accepts
            // Arabic-Indic and Devanagari digits — those would pass validation here and then be
            // impossible to type again on this keypad.
            onValueChange(typed.filter { it in '0'..'9' }.take(MAX_PIN_LENGTH))
        },
        label = { Text(text = label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Six digits — `TinkPinVerifier`'s ceiling, so the field cannot accept a PIN the verifier rejects. */
const val MAX_PIN_LENGTH: Int = 6
