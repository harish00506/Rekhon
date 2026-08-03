package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * The booked-date field, its picker, and its reducer (issue 3.4; FR-TXN-010).
 *
 * A separate file from `AddTransactionScreen.kt` for the reason `SplitEditor.kt` is one — that file
 * sits against detekt's eleven-function ceiling. The seam is real: everything here answers one
 * question the rest of the form does not care about, *when*, and it renders a dialog nothing else
 * on the screen shares.
 *
 * **[applySchedule] lives here rather than beside `applySplit` in `AddTransactionViewModel.kt`**,
 * which is a deviation from issue 3.3's arrangement and was forced by the same ceiling — that file
 * reached 11/11 functions the moment the reducer was added. Keeping the mode's state transitions
 * next to the mode's UI is the more honest of the two shapes anyway: a change to what the date
 * button offers and a change to what picking a date does are the same change.
 */

/**
 * The row showing which day the transaction will be booked on, and the button that changes it.
 *
 * Why:    FR-TXN-010 requires future dating to be *supported*, and this is where a user reaches it.
 *         **It costs the common path nothing** — the field is pre-filled with today, so FR-TXN-002's
 *         two-tap expense is unchanged and its tap-count test still passes. A user who wants next
 *         Tuesday pays two taps for it and nobody else pays anything, which is the same bargain the
 *         split toggle strikes.
 *
 *         The label is rendered by [AddTransactionUiState.bookedOn] being `null` or not rather than
 *         by comparing dates here: "today" is a profile-zone question, and the ViewModel's injected
 *         `Clock` is the only sanctioned source of the answer (TIM-001).
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 */
@Composable
internal fun ScheduleField(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(R.string.add_txn_date), style = MaterialTheme.typography.bodyMedium)
        CfoSecondaryButton(
            // `toString()` gives ISO `yyyy-MM-dd`, which is the format `dayHeader` localises — the
            // same one the list headers go through, so the button and the header agree.
            text =
                uiState.bookedOn
                    ?.let { TransactionLabels.dayHeader(it.toString()) }
                    ?: stringResource(R.string.add_txn_date_today),
            onClick = { onEvent(ScheduleEvent.DatePickerOpened) },
        )
    }
    ScheduleDatePicker(uiState = uiState, onEvent = onEvent)
}

/**
 * The Material 3 date picker, shown only while the user has asked for it.
 *
 * Why:    **the past is not selectable**, and that is a real constraint rather than a nicety: a
 *         back-dated row would change days for which `net_worth_snapshot` already holds a written
 *         figure that nothing recomputes, so the sparkline and today's number would disagree. The
 *         repository refuses a past date independently — this only stops the user reaching for one.
 *
 *         `SelectableDates` works in UTC millis, which is why [minSelectableUtcMillis] converts
 *         through [ZoneOffset.UTC] rather than the profile zone: the picker's own grid is drawn in
 *         UTC, so comparing its values against a profile-zone midnight would make the boundary day
 *         selectable or not depending on the user's offset. The date the user picks is converted
 *         back the same way, and it is a `LocalDate` from there down (TIM-002).
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePicker(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.isDatePickerOpen) return

    val earliest = uiState.earliestBookableDate
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = (uiState.bookedOn ?: earliest).utcMillis(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= earliest.utcMillis()

                    override fun isSelectableYear(year: Int) = year >= earliest.year
                },
        )

    DatePickerDialog(
        onDismissRequest = { onEvent(ScheduleEvent.DatePickerDismissed) },
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onEvent(ScheduleEvent.DateSelected(it.toUtcLocalDate())) }
                    onEvent(ScheduleEvent.DatePickerDismissed)
                },
            ) {
                Text(stringResource(R.string.add_txn_date_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ScheduleEvent.DatePickerDismissed) }) {
                Text(stringResource(R.string.add_txn_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/**
 * Applies one date interaction to the state (issue 3.4; FR-TXN-010).
 * Why:    the schedule reducer, the same shape `applySplit` takes and for the same reason — three
 *         pure transitions handled in one exhaustive `when` rather than three more branches of the
 *         screen's main `onEvent`, which detekt's complexity ceiling already objects to.
 * Result: the state with [event] applied. Input: the receiver; [event]. Output: [AddTransactionUiState].
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
internal fun AddTransactionUiState.applySchedule(event: ScheduleEvent): AddTransactionUiState =
    when (event) {
        ScheduleEvent.DatePickerOpened -> copy(isDatePickerOpen = true)
        ScheduleEvent.DatePickerDismissed -> copy(isDatePickerOpen = false)
        // Today collapses back to `null` rather than being stored as a date: `null` is what every
        // path before issue 3.4 meant, and it is what makes the button read "Today" again if the
        // user opens the picker and changes their mind back.
        is ScheduleEvent.DateSelected ->
            copy(bookedOn = event.date.takeIf { it != earliestBookableDate })
    }

/**
 * The date as the picker counts time — UTC midnight millis.
 * Why:    `DatePickerState` and `SelectableDates` both speak UTC epoch millis, so a date crossing
 *         that boundary has to be converted in exactly one place rather than at each call site.
 * Result: midnight UTC on this date. Input: the receiver. Output: [Long] epoch millis.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * The inverse of [utcMillis].
 * Result: the calendar date the picker's value names. Input: the receiver — UTC epoch millis.
 * Output: [LocalDate].
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
