package com.aicfo.feature.transactions

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aicfo.core.common.Clock
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
        // FR-TXN-001 says "date-time". The time sits beside the date rather than on a row of its
        // own, so the two read as one answer to "when?" and neither costs vertical space the
        // ≤ 3-tap form does not have.
        CfoSecondaryButton(
            text =
                when {
                    uiState.bookedAt != null -> TransactionLabels.timeOfDay(uiState.bookedAt)
                    // Today with nothing picked: the instant will be *now*, and a clock time printed
                    // here would be stale by the second the user read it.
                    uiState.bookedAtLabelIsNow -> stringResource(R.string.add_txn_time_now)
                    // A future day with nothing picked starts at midnight — a fixed, real value, so
                    // it is shown rather than hidden behind a second meaning of the word "now".
                    else -> TransactionLabels.timeOfDay(LocalTime.MIDNIGHT)
                },
            onClick = { onEvent(ScheduleEvent.TimePickerOpened) },
        )
    }
    ScheduleDatePicker(uiState = uiState, onEvent = onEvent)
    ScheduleTimePicker(uiState = uiState, onEvent = onEvent)
}

/**
 * The Material 3 time picker, shown only while the user has asked for it (FR-TXN-001).
 *
 * Why:    **no bound on it, unlike the date picker.** A past *date* is refused because it would
 *         stale a written `net_worth_snapshot` row; a past *time today* is the ordinary case — most
 *         people record this morning's coffee in the evening — and it changes nothing but the order
 *         of rows within a day. A time later today is equally harmless: the row is booked today
 *         either way, because balances bound on the date (ADR-0010).
 *
 *         Seeded from [AddTransactionUiState.bookedAt] when there is one, and otherwise from the
 *         profile's current time, so opening the picker on a fresh form starts where the user is
 *         rather than at midnight.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for FR-TXN-001's "date-time".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePicker(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.isTimePickerOpen) return

    val initial = uiState.bookedAt ?: uiState.nowInProfileZone
    val state =
        rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            // Follows the device's 12/24-hour setting rather than forcing one: the same reason the
            // date is formatted through the locale instead of a hardcoded pattern (§21.6).
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )

    AlertDialog(
        onDismissRequest = { onEvent(ScheduleEvent.TimePickerDismissed) },
        confirmButton = {
            TextButton(
                onClick = {
                    onEvent(ScheduleEvent.TimeSelected(LocalTime.of(state.hour, state.minute)))
                    onEvent(ScheduleEvent.TimePickerDismissed)
                },
            ) {
                Text(stringResource(R.string.add_txn_time_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ScheduleEvent.TimePickerDismissed) }) {
                Text(stringResource(R.string.add_txn_cancel))
            }
        },
        // `AlertDialog` rather than `DatePickerDialog`'s time equivalent, which Material 3 does not
        // ship: the time picker is a bare composable and the dialog around it is the caller's.
        text = { TimePicker(state = state) },
    )
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

    // **No `selectableDates` bound, and that is the change ADR-0012 made.** Until then the picker
    // refused every day before today, because a back-dated row left the frozen net-worth series
    // behind it. `NetWorthRepository.repairStaleHistory` corrects exactly those days now, so the
    // only thing the bound was still doing was stopping a user recording a purchase they actually
    // made — and the picker opens on today, so the common case costs no extra scrolling.
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = (uiState.bookedOn ?: uiState.todayInProfileZone).utcMillis(),
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
            copy(bookedOn = event.date.takeIf { it != todayInProfileZone })

        ScheduleEvent.TimePickerOpened -> copy(isTimePickerOpen = true)
        ScheduleEvent.TimePickerDismissed -> copy(isTimePickerOpen = false)
        // Kept as picked, with no collapse-to-null counterpart to `DateSelected`'s: there is no time
        // the user could choose that means "now", because "now" moves and a chosen time does not.
        is ScheduleEvent.TimeSelected -> copy(bookedAt = event.time)
    }

/**
 * The current time of day in the profile zone.
 * Why:    the time picker has to open somewhere, and "wherever the user is" beats midnight. The
 *         profile zone rather than the device's, for the reason TIM-001 gives about every other
 *         calendar answer — and computed outside the composable, which may not read a clock at all.
 *
 *         Lives here rather than beside the ViewModel it is called from, for the reason
 *         [applySchedule] does: `AddTransactionViewModel.kt` reached detekt's eleven-function
 *         ceiling the moment this was added, and everything about *when* belongs together anyway.
 * Result: the wall-clock time a user in the profile zone would see now.
 * Input:  the receiver — the injected [Clock]. Output: [LocalTime].
 * Changelog: 2026-08-03 — Created for FR-TXN-001's "date-time".
 */
internal fun Clock.timeOfDay(): LocalTime = Instant.ofEpochMilli(nowUtcMillis()).atZone(zone()).toLocalTime()

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
